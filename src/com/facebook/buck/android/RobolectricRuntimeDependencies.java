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
import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.CustomFieldBehavior;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.modern.BuildCellRelativePathFactory;
import com.facebook.buck.rules.modern.Buildable;
import com.facebook.buck.rules.modern.ModernBuildRule;
import com.facebook.buck.rules.modern.OutputPath;
import com.facebook.buck.rules.modern.OutputPathResolver;
import com.facebook.buck.rules.modern.RemoteExecutionEnabled;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.isolatedsteps.common.MakeCleanDirectoryIsolatedStep;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Creates a symlink to the real Robolectric jars, so there's a way to only specify required jars
 * instead of having to use a genrule that contains everything.
 */
public class RobolectricRuntimeDependencies
    extends ModernBuildRule<RobolectricRuntimeDependencies.Impl> {

  public RobolectricRuntimeDependencies(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      ImmutableList<SourcePath> properties) {
    super(
        buildTarget,
        projectFilesystem,
        ruleFinder,
        new RobolectricRuntimeDependencies.Impl(properties));
  }

  /** Creates a symlink in buck-out that points to the actual jar. */
  static class Impl implements Buildable {
    @AddToRuleKey private final ImmutableList<SourcePath> robolectricJars;

    @AddToRuleKey private final OutputPath outputPath = new OutputPath("runtime_jars");

    @CustomFieldBehavior(RemoteExecutionEnabled.class)
    private final boolean remoteExecutionEnabled = false;

    private Impl(ImmutableList<SourcePath> robolectricJars) {
      this.robolectricJars = robolectricJars;
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext buildContext,
        ProjectFilesystem filesystem,
        OutputPathResolver outputPathResolver,
        BuildCellRelativePathFactory buildCellPathFactory) {
      RelPath outputDir = outputPathResolver.resolvePath(outputPath);

      ImmutableList.Builder<Step> steps = ImmutableList.builder();
      steps.addAll(MakeCleanDirectoryIsolatedStep.of(outputDir));
      steps.add(
          new AbstractExecutionStep("create_symlinks") {
            @Override
            public StepExecutionResult execute(StepExecutionContext context) throws IOException {
              for (SourcePath sourcePath : robolectricJars) {
                Path path =
                    buildContext.getSourcePathResolver().getAbsolutePath(sourcePath).getPath();
                filesystem.createSymLink(
                    outputPathResolver.resolvePath(outputPath).resolve(path.getFileName()),
                    path,
                    false);
              }

              return StepExecutionResults.SUCCESS;
            }
          });

      return steps.build();
    }
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return getSourcePath(getBuildable().outputPath);
  }

  @Override
  public boolean isCacheable() {
    return false;
  }
}
