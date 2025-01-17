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

package com.facebook.buck.android;

import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.execution.context.IsolatedExecutionContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.ProjectFilesystemUtils;
import com.facebook.buck.rules.modern.BuildCellRelativePathFactory;
import com.facebook.buck.rules.modern.Buildable;
import com.facebook.buck.rules.modern.HasBrokenInputBasedRuleKey;
import com.facebook.buck.rules.modern.ModernBuildRule;
import com.facebook.buck.rules.modern.OutputPath;
import com.facebook.buck.rules.modern.OutputPathResolver;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.isolatedsteps.shell.IsolatedShellStep;
import com.facebook.buck.zip.ZipScrubberStep;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import javax.annotation.Nullable;

/** Perform the "aapt2 compile" step of a single Android resource. */
public class Aapt2Compile extends ModernBuildRule<Aapt2Compile.Impl> {

  public Aapt2Compile(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      Tool aapt2ExecutableTool,
      SourcePath resDir,
      boolean skipCrunchPngs,
      boolean withDownwardApi) {
    super(
        buildTarget,
        projectFilesystem,
        ruleFinder,
        new Impl(aapt2ExecutableTool, resDir, skipCrunchPngs, withDownwardApi));
  }

  /** internal buildable implementation */
  static class Impl
      implements Buildable,
          // more details in the task: T47360608
          HasBrokenInputBasedRuleKey {

    @AddToRuleKey private final Tool aapt2ExecutableTool;
    @AddToRuleKey private final SourcePath resDir;
    @AddToRuleKey private final OutputPath output = new OutputPath("resources.flata");
    @AddToRuleKey private final boolean skipCrunchPngs;
    @AddToRuleKey private final boolean withDownwardApi;

    private Impl(
        Tool aapt2ExecutableTool,
        SourcePath resDir,
        boolean skipCrunchPngs,
        boolean withDownwardApi) {
      this.aapt2ExecutableTool = aapt2ExecutableTool;
      this.resDir = resDir;
      this.skipCrunchPngs = skipCrunchPngs;
      this.withDownwardApi = withDownwardApi;
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext buildContext,
        ProjectFilesystem filesystem,
        OutputPathResolver outputPathResolver,
        BuildCellRelativePathFactory buildCellPathFactory) {

      RelPath outputPath = outputPathResolver.resolvePath(output);
      SourcePathResolverAdapter sourcePathResolverAdapter = buildContext.getSourcePathResolver();

      Aapt2CompileStep aapt2CompileStep =
          new Aapt2CompileStep(
              filesystem.getRootPath(),
              aapt2ExecutableTool.getCommandPrefix(sourcePathResolverAdapter),
              sourcePathResolverAdapter.getCellUnsafeRelPath(resDir).getPath(),
              outputPath.getPath(),
              ProjectFilesystemUtils.relativize(
                  filesystem.getRootPath(), buildContext.getBuildCellRootPath()),
              skipCrunchPngs,
              withDownwardApi);
      ZipScrubberStep zipScrubberStep = ZipScrubberStep.of(filesystem.resolve(outputPath));
      return ImmutableList.of(aapt2CompileStep, zipScrubberStep);
    }
  }

  private static class Aapt2CompileStep extends IsolatedShellStep {
    private final ImmutableList<String> commandPrefix;
    private final Path resDirPath;
    private final Path outputPath;
    private final boolean skipCrunchPngs;

    Aapt2CompileStep(
        AbsPath workingDirectory,
        ImmutableList<String> commandPrefix,
        Path resDirPath,
        Path outputPath,
        RelPath cellPath,
        boolean skipCrunchPngs,
        boolean withDownwardApi) {
      super(workingDirectory, cellPath, withDownwardApi);
      this.commandPrefix = commandPrefix;
      this.resDirPath = resDirPath;
      this.outputPath = outputPath;
      this.skipCrunchPngs = skipCrunchPngs;
    }

    @Override
    public String getShortName() {
      return "aapt2_compile";
    }

    @Override
    protected ImmutableList<String> getShellCommandInternal(IsolatedExecutionContext context) {
      ImmutableList.Builder<String> builder = ImmutableList.builder();
      builder.addAll(commandPrefix);
      builder.add("compile");
      builder.add("--legacy");
      if (skipCrunchPngs) {
        builder.add("--no-crunch");
      }
      builder.add("-o");
      builder.add(outputPath.toString());
      builder.add("--dir");
      builder.add(resDirPath.toString());
      return builder.build();
    }
  }

  @Nullable
  @Override
  public SourcePath getSourcePathToOutput() {
    return getSourcePath(getBuildable().output);
  }
}
