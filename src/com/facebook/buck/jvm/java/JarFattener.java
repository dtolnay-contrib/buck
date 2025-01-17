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

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.cell.impl.CellPathResolverUtils;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.OutputLabel;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.attr.HasRuntimeDeps;
import com.facebook.buck.core.rules.impl.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.rules.tool.BinaryBuildRule;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.tool.impl.CommandTool;
import com.facebook.buck.io.filesystem.BuckPaths;
import com.facebook.buck.io.filesystem.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.BuildTargetValue;
import com.facebook.buck.jvm.core.FilesystemParamsUtils;
import com.facebook.buck.jvm.core.HasClasspathEntries;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.SymlinkFileStep;
import com.facebook.buck.step.fs.ZipStep;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import com.facebook.buck.step.isolatedsteps.common.TouchStep;
import com.facebook.buck.step.isolatedsteps.common.WriteFileIsolatedStep;
import com.facebook.buck.step.isolatedsteps.java.JarDirectoryStep;
import com.facebook.buck.util.zip.ZipCompressionLevel;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.io.Resources;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/** Build a fat JAR that packages an inner JAR along with any required native libraries. */
public class JarFattener extends AbstractBuildRuleWithDeclaredAndExtraDeps
    implements BinaryBuildRule, HasClasspathEntries, HasRuntimeDeps {

  public static final ImmutableList<String> FAT_JAR_SRC_RESOURCES =
      ImmutableList.of("com/facebook/buck/jvm/java/WindowsCreateProcessEscape.java");
  public static final String FAT_JAR_MAIN_SRC_RESOURCE =
      "com/facebook/buck/jvm/java/FatJarMain.java";

  @AddToRuleKey private final Javac javac;
  @AddToRuleKey private final JavacOptions javacOptions;
  @AddToRuleKey private final SourcePath innerJar;
  @AddToRuleKey private final ImmutableMap<String, SourcePath> nativeLibraries;
  // We're just propagating the runtime launcher through `getExecutableCommand`, so don't add it to
  // the rule key.
  private final Tool javaRuntimeLauncher;
  private final RelPath output;
  private final JavaBinary innerJarRule;
  @AddToRuleKey private final boolean withDownwardApi;
  @AddToRuleKey private final boolean prepareWrapperScript;

  public JarFattener(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      Javac javac,
      JavacOptions javacOptions,
      SourcePath innerJar,
      JavaBinary innerJarRule,
      ImmutableMap<String, SourcePath> nativeLibraries,
      Tool javaRuntimeLauncher,
      boolean withDownwardApi,
      boolean prepareWrapperScript) {
    super(buildTarget, projectFilesystem, params);
    this.javac = javac;
    this.javacOptions = javacOptions;
    this.innerJar = innerJar;
    this.innerJarRule = innerJarRule;
    this.nativeLibraries = nativeLibraries;
    this.javaRuntimeLauncher = javaRuntimeLauncher;
    this.withDownwardApi = withDownwardApi;
    this.prepareWrapperScript = prepareWrapperScript;
    this.output =
        BuildTargetPaths.getGenPath(projectFilesystem.getBuckPaths(), buildTarget, "%s")
            .resolveRel(buildTarget.getShortName() + ".jar");
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    AbsPath buildCellRootPath = context.getBuildCellRootPath();
    SourcePathResolverAdapter sourcePathResolver = context.getSourcePathResolver();

    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    RelPath outputDir = getOutputDirectory();
    ProjectFilesystem filesystem = getProjectFilesystem();
    BuildTarget buildTarget = getBuildTarget();
    BuckPaths buckPaths = filesystem.getBuckPaths();
    CompilerOutputPaths compilerOutputPaths = CompilerOutputPaths.of(buildTarget, buckPaths);
    RelPath fatJarDir = compilerOutputPaths.getClassesDir();
    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(buildCellRootPath, filesystem, outputDir)));

    // Map of the system-specific shared library name to it's resource name as a string.
    for (Map.Entry<String, SourcePath> entry : nativeLibraries.entrySet()) {
      String resource = FatJarMain.FAT_JAR_NATIVE_LIBRARIES_DIR + "/" + entry.getKey();
      Path resourcePath = fatJarDir.resolve(resource);
      steps.add(
          MkdirStep.of(
              BuildCellRelativePath.fromCellRelativePath(
                  buildCellRootPath, filesystem, resourcePath.getParent())));
      steps.add(
          SymlinkFileStep.of(
              filesystem,
              sourcePathResolver.getAbsolutePath(entry.getValue()).getPath(),
              resourcePath));
    }

    // Build up the resource and src collections.
    ImmutableSortedSet.Builder<RelPath> javaSourceFilePaths =
        new ImmutableSortedSet.Builder<>(RelPath.comparator());
    for (String srcResource : FAT_JAR_SRC_RESOURCES) {
      RelPath fatJarSource = outputDir.resolveRel(Paths.get(srcResource).getFileName().toString());
      javaSourceFilePaths.add(fatJarSource);
      steps.add(writeFromResource(fatJarSource, srcResource));
    }
    RelPath fatJarMainSource =
        outputDir.resolveRel(Paths.get(FAT_JAR_MAIN_SRC_RESOURCE).getFileName().toString());
    javaSourceFilePaths.add(fatJarMainSource);
    steps.add(writeFromResource(fatJarMainSource, FAT_JAR_MAIN_SRC_RESOURCE));

    // Symlink the inner JAR into it's place in the fat JAR.
    steps.add(
        MkdirStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                buildCellRootPath,
                filesystem,
                fatJarDir.resolve(FatJarMain.FAT_JAR_INNER_JAR).getParent())));
    steps.add(
        SymlinkFileStep.of(
            filesystem,
            sourcePathResolver.getAbsolutePath(innerJar).getPath(),
            fatJarDir.resolve(FatJarMain.FAT_JAR_INNER_JAR)));

    if (prepareWrapperScript) {
      steps.add(new TouchStep(fatJarDir.resolve(FatJarMain.WRAPPER_SCRIPT_MARKER_FILE)));
    }

    BuildTargetValue buildTargetValue = BuildTargetValue.of(buildTarget);

    CompilerParameters compilerParameters =
        CompilerParameters.builder()
            .setClasspathEntries(ImmutableSortedSet.of())
            .setSourceFilePaths(javaSourceFilePaths.build())
            .setOutputPaths(compilerOutputPaths)
            .build();
    CompilerOutputPaths outputPaths = compilerParameters.getOutputPaths();
    Preconditions.checkState(outputPaths.getClassesDir().equals(fatJarDir));

    steps.add(
        MkdirStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                buildCellRootPath, filesystem, outputPaths.getPathToSourcesList().getParent())));

    JavacToJarStepFactory compileStepFactory =
        new JavacToJarStepFactory(javacOptions, ExtraClasspathProvider.EMPTY, withDownwardApi);

    AbsPath rootPath = filesystem.getRootPath();
    ImmutableList.Builder<IsolatedStep> isolatedSteps = ImmutableList.builder();
    compileStepFactory.createCompileStep(
        FilesystemParamsUtils.of(filesystem),
        CellPathResolverUtils.getCellToPathMappings(rootPath, context.getCellPathResolver()),
        buildTargetValue,
        CompilerOutputPathsValue.of(buckPaths, buildTarget),
        compilerParameters,
        isolatedSteps,
        buildableContext,
        javac.resolve(sourcePathResolver, rootPath),
        compileStepFactory.createExtraParams(context, rootPath));
    steps.addAll(isolatedSteps.build());

    // Build the final fat JAR from the structure we've laid out above.  We first package the
    // fat jar resources (e.g. native libs) using the "stored" compression level, to avoid
    // expensive compression on builds and decompression on startup.
    RelPath zipped = outputDir.resolveRel("contents.zip");
    steps.add(
        ZipStep.of(
            filesystem,
            zipped.getPath(),
            ImmutableSet.of(),
            false,
            ZipCompressionLevel.NONE,
            fatJarDir.getPath()));

    JarParameters jarParameters =
        JarParameters.builder()
            .setJarPath(output)
            .setEntriesToJar(ImmutableSortedSet.orderedBy(RelPath.comparator()).add(zipped).build())
            .setMainClass(Optional.of(FatJarMain.class.getName()))
            .setMergeManifests(true)
            .build();
    steps.add(new JarDirectoryStep(jarParameters));

    buildableContext.recordArtifact(output.getPath());

    return steps.build();
  }

  /** @return a {@link Step} that writes the final from the resource named {@code name}. */
  private Step writeFromResource(RelPath destination, String name) {
    return WriteFileIsolatedStep.of(
        Resources.asByteSource(Resources.getResource(name)), destination, /* executable */ false);
  }

  private RelPath getOutputDirectory() {
    return output.getParent();
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), output);
  }

  @Override
  public Tool getExecutableCommand(OutputLabel outputLabel) {
    return new CommandTool.Builder(javaRuntimeLauncher)
        // Directs the VM to refrain from setting the file descriptor limit to the default maximum.
        // https://stackoverflow.com/a/16535804/5208808
        .addArg("-XX:-MaxFDLimit")
        .addArg("-jar")
        .addArg(SourcePathArg.of(getSourcePathToOutput()))
        .build();
  }

  public ImmutableMap<String, SourcePath> getNativeLibraries() {
    return nativeLibraries;
  }

  @Override
  public void updateBuildRuleResolver(BuildRuleResolver ruleResolver) {}

  @Override
  public ImmutableSet<SourcePath> getTransitiveClasspaths() {
    return innerJarRule.getTransitiveClasspaths();
  }

  @Override
  public ImmutableSet<JavaLibrary> getTransitiveClasspathDeps() {
    return innerJarRule.getTransitiveClasspathDeps();
  }

  @Override
  public ImmutableSet<SourcePath> getImmediateClasspaths() {
    return innerJarRule.getImmediateClasspaths();
  }

  @Override
  public ImmutableSet<SourcePath> getOutputClasspaths() {
    return innerJarRule.getOutputClasspaths();
  }

  @Override
  public ImmutableSet<SourcePath> getCompileTimeClasspathSourcePaths() {
    return innerJarRule.getCompileTimeClasspathSourcePaths();
  }

  @Override
  public Stream<BuildTarget> getRuntimeDeps(BuildRuleResolver buildRuleResolver) {
    return innerJarRule.getRuntimeDeps(buildRuleResolver);
  }
}
