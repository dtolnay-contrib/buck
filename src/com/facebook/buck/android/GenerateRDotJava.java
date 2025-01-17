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

import com.facebook.buck.android.aapt.RDotTxtEntry;
import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.impl.AbstractBuildRule;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.io.filesystem.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.ProjectFilesystemUtils;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.RmStep;
import com.facebook.buck.step.fs.ZipStep;
import com.facebook.buck.util.zip.ZipCompressionLevel;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.util.EnumSet;
import java.util.Optional;
import java.util.SortedSet;
import javax.annotation.Nullable;

public class GenerateRDotJava extends AbstractBuildRule {
  @AddToRuleKey private final EnumSet<RDotTxtEntry.RType> bannedDuplicateResourceTypes;
  @AddToRuleKey private final ImmutableCollection<SourcePath> pathToRDotTxtFiles;
  @AddToRuleKey private final ImmutableCollection<SourcePath> pathToOverrideSymbolsFile;
  @AddToRuleKey private final Optional<SourcePath> duplicateResourceWhitelistPath;
  @AddToRuleKey private final Optional<String> resourceUnionPackage;

  private final ImmutableList<HasAndroidResourceDeps> resourceDeps;
  private final ImmutableCollection<FilteredResourcesProvider> resourcesProviders;
  // TODO(cjhopman): allResourceDeps is used for getBuildDeps(), can that just use resourceDeps?
  private final ImmutableSortedSet<BuildRule> allResourceDeps;
  private final SourcePathRuleFinder ruleFinder;

  GenerateRDotJava(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      EnumSet<RDotTxtEntry.RType> bannedDuplicateResourceTypes,
      Optional<SourcePath> duplicateResourceWhitelistPath,
      ImmutableCollection<SourcePath> pathToRDotTxtFiles,
      Optional<String> resourceUnionPackage,
      ImmutableSortedSet<BuildRule> resourceDeps,
      ImmutableCollection<FilteredResourcesProvider> resourcesProviders) {
    super(buildTarget, projectFilesystem);
    this.ruleFinder = ruleFinder;
    this.bannedDuplicateResourceTypes = bannedDuplicateResourceTypes;
    this.duplicateResourceWhitelistPath = duplicateResourceWhitelistPath;
    this.pathToRDotTxtFiles = pathToRDotTxtFiles;
    this.resourceUnionPackage = resourceUnionPackage;
    this.allResourceDeps = resourceDeps;
    this.resourceDeps =
        resourceDeps.stream()
            .map(HasAndroidResourceDeps.class::cast)
            .collect(ImmutableList.toImmutableList());
    this.resourcesProviders = resourcesProviders;
    this.pathToOverrideSymbolsFile =
        resourcesProviders.stream()
            .filter(provider -> provider.getOverrideSymbolsPath().isPresent())
            .map(provider -> provider.getOverrideSymbolsPath().get())
            .collect(ImmutableList.toImmutableList());
  }

  @Override
  public SortedSet<BuildRule> getBuildDeps() {
    ImmutableSortedSet.Builder<BuildRule> builder = ImmutableSortedSet.naturalOrder();

    resourcesProviders.forEach(
        provider -> {
          provider
              .getOverrideSymbolsPath()
              .ifPresent(path -> builder.addAll(ruleFinder.filterBuildRuleInputs(path)));
          provider.getResourceFilterRule().ifPresent(builder::add);
        });
    builder.addAll(allResourceDeps);
    pathToRDotTxtFiles.forEach(
        pathToRDotTxt -> builder.addAll(ruleFinder.filterBuildRuleInputs(pathToRDotTxt)));
    duplicateResourceWhitelistPath.ifPresent(
        p -> builder.addAll(ruleFinder.filterBuildRuleInputs(p)));
    return builder.build();
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext buildContext, BuildableContext buildableContext) {
    SourcePathResolverAdapter pathResolver = buildContext.getSourcePathResolver();
    ProjectFilesystem projectFilesystem = getProjectFilesystem();
    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    // Merge R.txt of HasAndroidRes and generate the resulting R.java files per package.
    RelPath rDotJavaSrc = getPathToGeneratedRDotJavaSrcFiles();

    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                buildContext.getBuildCellRootPath(), projectFilesystem, rDotJavaSrc)));

    MergeAndroidResourcesStep mergeStep =
        MergeAndroidResourcesStep.createStepForUberRDotJava(
            buildContext.getSourcePathResolver(),
            resourceDeps,
            pathToRDotTxtFiles.stream()
                .map(p -> pathResolver.getAbsolutePath(p).getPath())
                .collect(ImmutableList.toImmutableList()),
            ProjectFilesystemUtils.getPathForRelativePath(
                projectFilesystem.getRootPath(), rDotJavaSrc.getPath()),
            bannedDuplicateResourceTypes,
            duplicateResourceWhitelistPath.map(
                sourcePath -> pathResolver.getAbsolutePath(sourcePath).getPath()),
            pathToOverrideSymbolsFile.stream()
                .map(p -> pathResolver.getAbsolutePath(p).getPath())
                .collect(ImmutableList.toImmutableList()),
            resourceUnionPackage);
    steps.add(mergeStep);

    RelPath rzipPath = getPathToRZip();
    steps.add(RmStep.of(BuildCellRelativePath.of(rzipPath)));
    steps.add(
        ZipStep.of(
            projectFilesystem,
            rzipPath.getPath(),
            ImmutableSet.of(),
            false,
            ZipCompressionLevel.NONE,
            getPathToGeneratedRDotJavaSrcFiles().getPath()));

    // Ensure the generated R.txt and R.java files are also recorded.
    buildableContext.recordArtifact(rDotJavaSrc.getPath());
    buildableContext.recordArtifact(rzipPath.getPath());

    return steps.build();
  }

  private RelPath getPathToGeneratedRDotJavaSrcFiles() {
    return getPathToGeneratedRDotJavaSrcFiles(getBuildTarget(), getProjectFilesystem());
  }

  @VisibleForTesting
  static RelPath getPathToGeneratedRDotJavaSrcFiles(
      BuildTarget buildTarget, ProjectFilesystem filesystem) {
    return BuildTargetPaths.getScratchPath(filesystem, buildTarget, "__%s_rdotjava_src__");
  }

  public SourcePath getSourcePathToGeneratedRDotJavaSrcFiles() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), getPathToGeneratedRDotJavaSrcFiles());
  }

  @Nullable
  @Override
  public SourcePath getSourcePathToOutput() {
    return null;
  }

  public SourcePath getSourcePathToRZip() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), getPathToRZip());
  }

  private RelPath getPathToRZip() {
    return BuildTargetPaths.getScratchPath(
        getProjectFilesystem(), getBuildTarget(), "__%s_rzip.src.zip");
  }
}
