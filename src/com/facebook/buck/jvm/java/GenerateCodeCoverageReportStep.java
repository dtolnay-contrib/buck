/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.jvm.java;

import static com.facebook.buck.jvm.java.JacocoConstants.JACOCO_EXEC_COVERAGE_FILE;
import static java.util.stream.Collectors.joining;

import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.io.file.MostFiles;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.ProjectFilesystemFactory;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.test.CoverageReportFormat;
import com.facebook.buck.util.unarchive.ArchiveFormat;
import com.facebook.buck.util.unarchive.ExistingFileMode;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

public class GenerateCodeCoverageReportStep extends ShellStep {

  private final ImmutableList<String> javaRuntimeLauncher;
  private final ProjectFilesystem filesystem;
  private final Set<String> sourceDirectories;
  private final Set<Path> jarFiles;
  private final Path outputDirectory;
  private final Set<CoverageReportFormat> formats;
  private final String title;
  private final Path propertyFile;
  private final Optional<String> coverageIncludes;
  private final Optional<String> coverageExcludes;

  public GenerateCodeCoverageReportStep(
      ImmutableList<String> javaRuntimeLauncher,
      ProjectFilesystem filesystem,
      Set<String> sourceDirectories,
      Set<Path> jarFiles,
      Path outputDirectory,
      Set<CoverageReportFormat> formats,
      String title,
      Optional<String> coverageIncludes,
      Optional<String> coverageExcludes,
      boolean withDownwardApi) {
    super(filesystem.getRootPath(), withDownwardApi);
    this.javaRuntimeLauncher = javaRuntimeLauncher;
    this.filesystem = filesystem;
    this.sourceDirectories = ImmutableSet.copyOf(sourceDirectories);
    this.jarFiles = ImmutableSet.copyOf(jarFiles);
    this.outputDirectory = outputDirectory;
    this.formats = formats;
    this.title = title;
    this.propertyFile = outputDirectory.resolve("parameters.properties");
    this.coverageIncludes = coverageIncludes;
    this.coverageExcludes = coverageExcludes;
  }

  @Override
  public String getShortName() {
    return "emma_report";
  }

  @Override
  public StepExecutionResult execute(StepExecutionContext context)
      throws IOException, InterruptedException {
    Set<Path> tempDirs = new HashSet<>();
    Set<Path> extractedClassesDirectories = new HashSet<>();
    for (Path jarFile : jarFiles) {
      if (filesystem.isDirectory(jarFile)) {
        extractedClassesDirectories.add(jarFile);
      } else {
        Path extractClassDir = Files.createTempDirectory("extractedClasses");
        populateClassesDir(context.getProjectFilesystemFactory(), jarFile, extractClassDir);
        extractedClassesDirectories.add(extractClassDir);
        tempDirs.add(extractClassDir);
      }
    }

    try {
      return executeInternal(context, extractedClassesDirectories);
    } finally {
      for (Path tempDir : tempDirs) {
        MostFiles.deleteRecursively(tempDir);
      }
    }
  }

  @VisibleForTesting
  StepExecutionResult executeInternal(
      StepExecutionContext context, Set<Path> extractedClassesDirectories)
      throws IOException, InterruptedException {
    try (OutputStream propertyFileStream =
        new FileOutputStream(filesystem.resolve(propertyFile).toFile())) {
      saveParametersToPropertyStream(filesystem, extractedClassesDirectories, propertyFileStream);
    }

    return super.execute(context);
  }

  @VisibleForTesting
  void saveParametersToPropertyStream(
      ProjectFilesystem filesystem,
      Set<Path> extractedClassesDirectories,
      OutputStream outputStream)
      throws IOException {
    Properties properties = new Properties();

    properties.setProperty("jacoco.output.dir", filesystem.resolve(outputDirectory).toString());
    properties.setProperty("jacoco.exec.data.file", JACOCO_EXEC_COVERAGE_FILE);
    properties.setProperty(
        "jacoco.format",
        formats.stream().map(format -> format.name().toLowerCase()).collect(joining(",")));
    properties.setProperty("jacoco.title", title);

    properties.setProperty("classes.jars", formatPathSet(jarFiles));

    properties.setProperty("classes.dir", formatPathSet(extractedClassesDirectories));
    properties.setProperty("src.dir", Joiner.on(":").join(sourceDirectories));

    coverageIncludes.ifPresent(s -> properties.setProperty("jacoco.includes", s));
    coverageExcludes.ifPresent(s -> properties.setProperty("jacoco.excludes", s));

    try (Writer writer = new OutputStreamWriter(outputStream, StandardCharsets.UTF_8)) {
      properties.store(writer, "Parameters for Jacoco report generator.");
    }
  }

  private String formatPathSet(Set<Path> paths) {
    return Joiner.on(":").join(Iterables.transform(paths, filesystem::resolve));
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(StepExecutionContext context) {
    ImmutableList.Builder<String> args = ImmutableList.builder();
    args.addAll(javaRuntimeLauncher);
    // Directs the VM to refrain from setting the file descriptor limit to the default maximum.
    // https://stackoverflow.com/a/16535804/5208808
    args.add("-XX:-MaxFDLimit");

    // Generate report from JaCoCo exec file using 'ReportGenerator.java'
    args.add("-jar", System.getProperty("buck.report_generator_jar"));
    args.add(filesystem.resolve(propertyFile).toString());

    return args.build();
  }

  /**
   * ReportGenerator.java needs a class-directory to work with, so if we instead have a jar file we
   * extract it first.
   */
  private void populateClassesDir(
      ProjectFilesystemFactory projectFilesystemFactory, Path outputJar, Path classesDir) {
    try {
      Preconditions.checkState(filesystem.exists(outputJar), outputJar + " does not exist");
      ArchiveFormat.ZIP
          .getUnarchiver()
          .extractArchive(
              projectFilesystemFactory,
              filesystem.getPathForRelativePath(outputJar),
              classesDir,
              ExistingFileMode.OVERWRITE_AND_CLEAN_DIRECTORIES);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
