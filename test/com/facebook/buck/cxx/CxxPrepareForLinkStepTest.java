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

package com.facebook.buck.cxx;

import static org.hamcrest.MatcherAssert.assertThat;

import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.UnconfiguredTargetConfiguration;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.io.pathformat.PathFormatter;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.FileListableLinkerInputArg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.Test;

public class CxxPrepareForLinkStepTest {

  @Test
  public void testCreateCxxPrepareForLinkStep() {
    Path dummyPath = Paths.get("dummy");
    BuildRuleResolver buildRuleResolver = new TestActionGraphBuilder();

    // Setup some dummy values for inputs to the CxxLinkStep
    ImmutableList<Arg> dummyArgs =
        ImmutableList.of(
            FileListableLinkerInputArg.withSourcePathArg(
                SourcePathArg.of(FakeSourcePath.of("libb.a"))));

    ImmutableList<Step> cxxPrepareForLinkStepSupportFileList =
        CxxPrepareForLinkStep.create(
            dummyPath,
            dummyPath,
            ImmutableList.of(StringArg.of("-filelist"), StringArg.of(dummyPath.toString())),
            dummyPath,
            dummyArgs,
            CxxPlatformUtils.DEFAULT_PLATFORM
                .getLd()
                .resolve(buildRuleResolver, UnconfiguredTargetConfiguration.INSTANCE),
            CanonicalCellName.rootCell(),
            dummyPath,
            buildRuleResolver.getSourcePathResolver(),
            ImmutableMap.of(),
            ImmutableList.of());

    assertThat(cxxPrepareForLinkStepSupportFileList.size(), Matchers.equalTo(2));
    Step firstStep = cxxPrepareForLinkStepSupportFileList.get(0);
    Step secondStep = cxxPrepareForLinkStepSupportFileList.get(1);
    assertThat(firstStep, Matchers.instanceOf(CxxWriteArgsToFileStep.class));
    assertThat(secondStep, Matchers.instanceOf(CxxWriteArgsToFileStep.class));
    assertThat(firstStep, Matchers.not(secondStep));

    ImmutableList<Step> cxxPrepareForLinkStepNoSupportFileList =
        CxxPrepareForLinkStep.create(
            dummyPath,
            dummyPath,
            ImmutableList.of(),
            dummyPath,
            dummyArgs,
            CxxPlatformUtils.DEFAULT_PLATFORM
                .getLd()
                .resolve(buildRuleResolver, UnconfiguredTargetConfiguration.INSTANCE),
            CanonicalCellName.rootCell(),
            dummyPath,
            buildRuleResolver.getSourcePathResolver(),
            ImmutableMap.of(),
            ImmutableList.of());

    assertThat(cxxPrepareForLinkStepNoSupportFileList.size(), Matchers.equalTo(1));
    assertThat(
        cxxPrepareForLinkStepNoSupportFileList.get(0),
        Matchers.instanceOf(CxxWriteArgsToFileStep.class));
  }

  @Test
  public void cxxLinkStepPassesLinkerOptionsViaArgFile() throws IOException, InterruptedException {
    ProjectFilesystem projectFilesystem = FakeProjectFilesystem.createRealTempFilesystem();
    AbsPath argFilePath =
        projectFilesystem.getRootPath().resolve("cxxLinkStepPassesLinkerOptionsViaArgFile.txt");
    AbsPath fileListPath =
        projectFilesystem.getRootPath().resolve("cxxLinkStepPassesLinkerOptionsViaFileList.txt");
    AbsPath output = projectFilesystem.getRootPath().resolve("output");

    runTestForArgFilePathAndOutputPath(
        argFilePath, fileListPath, output, projectFilesystem.getRootPath());
  }

  @Test
  public void cxxLinkStepCreatesDirectoriesIfNeeded() throws IOException, InterruptedException {
    ProjectFilesystem projectFilesystem = FakeProjectFilesystem.createRealTempFilesystem();
    AbsPath argFilePath =
        projectFilesystem.getRootPath().resolve("unexisting_parent_folder/argfile.txt");
    AbsPath fileListPath =
        projectFilesystem.getRootPath().resolve("unexisting_parent_folder/filelist.txt");
    AbsPath output = projectFilesystem.getRootPath().resolve("output");

    Files.deleteIfExists(argFilePath.getPath());
    Files.deleteIfExists(fileListPath.getPath());
    Files.deleteIfExists(argFilePath.getParent().getPath());
    Files.deleteIfExists(fileListPath.getParent().getPath());

    runTestForArgFilePathAndOutputPath(
        argFilePath, fileListPath, output, projectFilesystem.getRootPath());

    // cleanup after test
    Files.deleteIfExists(argFilePath.getPath());
    Files.deleteIfExists(argFilePath.getParent().getPath());
    Files.deleteIfExists(fileListPath.getPath());
    Files.deleteIfExists(fileListPath.getParent().getPath());
  }

  @Test
  public void cxxLinkStepEscapesOptionsForArgFile() throws IOException, InterruptedException {
    ProjectFilesystem projectFilesystem = FakeProjectFilesystem.createRealTempFilesystem();
    AbsPath argFilePath =
        projectFilesystem.getRootPath().resolve("cxxLinkStepEscapesOptionsForArgFile.txt");
    AbsPath fileListPath =
        projectFilesystem.getRootPath().resolve("cxxLinkStepEscapesOptionsForFileList.txt");
    AbsPath output = projectFilesystem.getRootPath().resolve("output");

    runTestForArgFilePathAndOutputPathWithoutFileList(
        argFilePath, fileListPath, output, projectFilesystem.getRootPath());
  }

  private void runTestForArgFilePathAndOutputPath(
      AbsPath argFilePath, AbsPath fileListPath, AbsPath output, AbsPath currentCellPath)
      throws IOException, InterruptedException {
    StepExecutionContext context = TestExecutionContext.newInstance();

    BuildRuleResolver buildRuleResolver = new TestActionGraphBuilder();

    // Setup some dummy values for inputs to the CxxLinkStep
    ImmutableList<Arg> args =
        ImmutableList.of(
            StringArg.of("-rpath"),
            StringArg.of("hello"),
            StringArg.of("a.o"),
            FileListableLinkerInputArg.withSourcePathArg(
                SourcePathArg.of(FakeSourcePath.of("libb.a"))),
            StringArg.of("-F/System/Frameworks"),
            StringArg.of("-L/System/libraries"),
            StringArg.of("-lz"));

    // Create our CxxLinkStep to test.
    ImmutableList<Step> steps =
        CxxPrepareForLinkStep.create(
            argFilePath.getPath(),
            fileListPath.getPath(),
            ImmutableList.of(StringArg.of("-filelist"), StringArg.of(fileListPath.toString())),
            output.getPath(),
            args,
            CxxPlatformUtils.DEFAULT_PLATFORM
                .getLd()
                .resolve(buildRuleResolver, UnconfiguredTargetConfiguration.INSTANCE),
            CanonicalCellName.rootCell(),
            currentCellPath.getPath(),
            buildRuleResolver.getSourcePathResolver(),
            ImmutableMap.of(),
            ImmutableList.of());

    for (Step step : steps) {
      step.execute(context);
    }

    assertThat(Files.exists(argFilePath.getPath()), Matchers.equalTo(true));
    assertThat(Files.exists(fileListPath.getPath()), Matchers.equalTo(true));

    ImmutableList<String> expectedArgFileContents =
        ImmutableList.<String>builder()
            .add("-o", PathFormatter.pathWithUnixSeparators(output))
            .add("-rpath")
            .add("hello")
            .add("a.o")
            .add("-F/System/Frameworks")
            .add("-L/System/libraries")
            .add("-lz")
            .add("-filelist")
            .add(fileListPath.toString())
            .build();

    ImmutableList<String> expectedFileListContents =
        ImmutableList.of(
            PathFormatter.pathWithUnixSeparators(Paths.get("libb.a").toAbsolutePath()));

    checkContentsOfFile(argFilePath, expectedArgFileContents);
    checkContentsOfFile(fileListPath, expectedFileListContents);

    Files.deleteIfExists(argFilePath.getPath());
    Files.deleteIfExists(fileListPath.getPath());
  }

  private void runTestForArgFilePathAndOutputPathWithoutFileList(
      AbsPath argFilePath, AbsPath fileListPath, AbsPath output, AbsPath currentCellPath)
      throws IOException, InterruptedException {
    StepExecutionContext context = TestExecutionContext.newInstance();

    BuildRuleResolver buildRuleResolver = new TestActionGraphBuilder();

    // Setup some dummy values for inputs to the CxxLinkStep
    ImmutableList<Arg> args =
        ImmutableList.of(
            StringArg.of("-rpath"),
            StringArg.of("\"hello\""),
            StringArg.of("'a.o'"),
            StringArg.of("-fuse-ld=gold"),
            StringArg.of("-lsysroot"),
            StringArg.of("/Library/Application Support/blabla"),
            FileListableLinkerInputArg.withSourcePathArg(
                SourcePathArg.of(FakeSourcePath.of("libb.a"))),
            FileListableLinkerInputArg.withSourcePathArg(
                SourcePathArg.of(
                    FakeSourcePath.of("buck-out/gen/mylib#default,static/libmylib.lib"))));

    // Create our CxxLinkStep to test.
    ImmutableList<Step> steps =
        CxxPrepareForLinkStep.create(
            argFilePath.getPath(),
            fileListPath.getPath(),
            ImmutableList.of(),
            output.getPath(),
            args,
            CxxPlatformUtils.DEFAULT_PLATFORM
                .getLd()
                .resolve(buildRuleResolver, UnconfiguredTargetConfiguration.INSTANCE),
            CanonicalCellName.rootCell(),
            currentCellPath.getPath(),
            buildRuleResolver.getSourcePathResolver(),
            ImmutableMap.of(),
            ImmutableList.of());

    for (Step step : steps) {
      step.execute(context);
    }

    assertThat(Files.exists(argFilePath.getPath()), Matchers.equalTo(true));
    assertThat(Files.exists(fileListPath.getPath()), Matchers.equalTo(false));

    boolean isWindows = Platform.detect() == Platform.WINDOWS;

    ImmutableList<String> expectedArgFileContents =
        ImmutableList.<String>builder()
            .add("-o", PathFormatter.pathWithUnixSeparators(output))
            .add("-rpath")
            .add(isWindows ? "\"\\\"hello\\\"\"" : "'\"hello\"'")
            .add(isWindows ? "'a.o'" : "''\\''a.o'\\'''")
            .add(isWindows ? "-fuse-ld=gold" : "'-fuse-ld=gold'")
            .add("-lsysroot")
            .add(
                isWindows
                    ? "\"/Library/Application Support/blabla\""
                    : "'/Library/Application Support/blabla'")
            .add(PathFormatter.pathWithUnixSeparators(FakeSourcePath.of("libb.a").toString()))
            .add(
                PathFormatter.pathWithUnixSeparators(
                    FakeSourcePath.of("buck-out/gen/mylib#default,static/libmylib.lib").toString()))
            .build();

    checkContentsOfFile(argFilePath, expectedArgFileContents);

    Files.deleteIfExists(argFilePath.getPath());
  }

  private void checkContentsOfFile(AbsPath file, ImmutableList<String> contents)
      throws IOException {
    List<String> fileContents = Files.readAllLines(file.getPath(), StandardCharsets.UTF_8);
    assertThat(fileContents, Matchers.equalTo(contents));
  }
}
