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

import com.facebook.buck.android.apkmodule.APKModule;
import com.facebook.buck.android.apkmodule.APKModuleGraph;
import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.impl.AbstractBuildRule;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.filesystem.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.SortedSet;

public class AndroidAppModularity extends AbstractBuildRule {

  @AddToRuleKey private final AndroidAppModularityGraphEnhancementResult result;
  @AddToRuleKey private final boolean shouldIncludeClasses;
  @AddToRuleKey private final APKModuleGraph<BuildTarget> apkModuleGraph;

  AndroidAppModularity(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      AndroidAppModularityGraphEnhancementResult result,
      boolean shouldIncludeClasses,
      APKModuleGraph<BuildTarget> apkModuleGraph) {
    super(buildTarget, projectFilesystem);
    this.result = result;
    this.shouldIncludeClasses = shouldIncludeClasses;
    this.apkModuleGraph = apkModuleGraph;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext buildContext, BuildableContext buildableContext) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    RelPath metadataFile =
        buildContext.getSourcePathResolver().getCellUnsafeRelPath(getSourcePathToOutput());

    steps.add(
        MkdirStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                buildContext.getBuildCellRootPath(),
                getProjectFilesystem(),
                metadataFile.getParent())));

    ImmutableMultimap.Builder<APKModule, Path> additionalDexStoreToJarPathMapBuilder =
        ImmutableMultimap.builder();
    if (shouldIncludeClasses) {
      additionalDexStoreToJarPathMapBuilder.putAll(
          result.getPackageableCollection().getModuleMappedClasspathEntriesToDex().entries()
              .stream()
              .map(
                  input ->
                      new AbstractMap.SimpleEntry<>(
                          input.getKey(),
                          getProjectFilesystem()
                              .relativize(
                                  buildContext
                                      .getSourcePathResolver()
                                      .getAbsolutePath(input.getValue()))
                              .getPath()))
              .collect(ImmutableSet.toImmutableSet()));
    }

    steps.add(
        new WriteAppModuleMetadataStep(
            metadataFile.getPath(),
            additionalDexStoreToJarPathMapBuilder.build(),
            result.getModulesToSharedLibraries(),
            apkModuleGraph,
            getProjectFilesystem(),
            shouldIncludeClasses));

    buildableContext.recordArtifact(metadataFile.getPath());

    return steps.build();
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(
        getBuildTarget(),
        BuildTargetPaths.getGenPath(
            getProjectFilesystem().getBuckPaths(), getBuildTarget(), "%s/modulemetadata.txt"));
  }

  @Override
  public SortedSet<BuildRule> getBuildDeps() {
    if (shouldIncludeClasses) {
      return result.getFinalDeps();
    } else {
      return ImmutableSortedSet.of();
    }
  }
}
