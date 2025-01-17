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

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.common.BuildableSupport;
import com.facebook.buck.core.rules.impl.AbstractBuildRule;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.io.filesystem.BuildCellRelativePath;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.util.MoreSuppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import java.util.SortedSet;
import java.util.function.Supplier;

/** Rule that collects text files output by ProGuard. */
class ProguardTextOutput extends AbstractBuildRule {
  @AddToRuleKey private final SourcePath proguardConfigPath;
  private final Supplier<ImmutableSortedSet<BuildRule>> buildDepsSupplier;

  ProguardTextOutput(
      BuildTarget buildTarget,
      NonPreDexedDexBuildable nonPreDexedDexBuildable,
      SourcePathRuleFinder ruleFinder) {
    super(buildTarget, nonPreDexedDexBuildable.getProjectFilesystem());
    this.proguardConfigPath = nonPreDexedDexBuildable.getProguardConfigSourcePath();
    this.buildDepsSupplier =
        MoreSuppliers.memoize(
            () ->
                BuildableSupport.deriveDeps(this, ruleFinder)
                    .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural())));
  }

  @Override
  public SortedSet<BuildRule> getBuildDeps() {
    return buildDepsSupplier.get();
  }

  @Override
  public boolean isCacheable() {
    // Do not cache since command-line.txt contains absolute paths
    return false;
  }

  @Override
  public ImmutableList<? extends Step> getBuildSteps(
      BuildContext buildContext, BuildableContext buildableContext) {
    SourcePathResolverAdapter resolver = buildContext.getSourcePathResolver();
    AbsPath configPath = resolver.getAbsolutePath(proguardConfigPath);
    ImmutableList.Builder<Step> builder = ImmutableList.builder();
    builder.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                buildContext.getBuildCellRootPath(), getProjectFilesystem(), getOutputPath())));

    builder.add(
        CopyStep.forDirectory(
            configPath.getPath(), getOutputPath().getPath(), CopyStep.DirectoryMode.CONTENTS_ONLY));
    buildableContext.recordArtifact(getOutputPath().getPath());
    return builder.build();
  }

  private RelPath getOutputPath() {
    return BuildTargetPaths.getGenPath(
        getProjectFilesystem().getBuckPaths(), getBuildTarget(), "%s");
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), getOutputPath());
  }
}
