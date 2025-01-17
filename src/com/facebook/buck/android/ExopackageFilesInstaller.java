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

import com.facebook.buck.android.exopackage.AdbConfig;
import com.facebook.buck.android.exopackage.ExopackageInfo;
import com.facebook.buck.android.exopackage.ExopackageInstaller;
import com.facebook.buck.android.exopackage.IsolatedExopackageInfo;
import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.common.InstallTrigger;
import com.facebook.buck.core.rules.impl.AbstractBuildRule;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.util.MoreSuppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.SortedSet;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/**
 * Installs exopackage files to the device/devices. This will install all the missing .so/.dex/.apk
 * exo files.
 */
public class ExopackageFilesInstaller extends AbstractBuildRule {

  @AddToRuleKey private final InstallTrigger trigger;
  @AddToRuleKey private final SourcePath manifestPath;
  @AddToRuleKey private final SourcePath deviceExoContents;
  @AddToRuleKey private final ImmutableSortedSet<SourcePath> exoSourcePaths;

  private final Supplier<ImmutableSortedSet<BuildRule>> depsSupplier;
  private final ExopackageInfo exopackageInfo;
  private final AdbConfig adbConfig;

  public ExopackageFilesInstaller(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder sourcePathRuleFinder,
      SourcePath deviceExoContents,
      SourcePath manifestPath,
      ExopackageInfo exopackageInfo,
      AdbConfig adbConfig) {
    super(buildTarget, projectFilesystem);
    this.trigger = new InstallTrigger(projectFilesystem);
    this.deviceExoContents = deviceExoContents;
    this.manifestPath = manifestPath;
    this.exopackageInfo = exopackageInfo;
    this.exoSourcePaths = getExopackageSourcePaths(exopackageInfo);
    this.adbConfig = adbConfig;

    this.depsSupplier =
        MoreSuppliers.memoize(
            () ->
                ImmutableSortedSet.<BuildRule>naturalOrder()
                    .addAll(sourcePathRuleFinder.filterBuildRuleInputs(exoSourcePaths))
                    .addAll(
                        sourcePathRuleFinder.filterBuildRuleInputs(
                            Arrays.asList(manifestPath, deviceExoContents)))
                    .build());
  }

  private static ImmutableSortedSet<SourcePath> getExopackageSourcePaths(ExopackageInfo exoInfo) {
    return exoInfo
        .getRequiredPaths()
        .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural()));
  }

  @Override
  public SortedSet<BuildRule> getBuildDeps() {
    return depsSupplier.get();
  }

  @Override
  public ImmutableList<? extends Step> getBuildSteps(
      BuildContext buildContext, BuildableContext buildableContext) {
    ProjectFilesystem projectFilesystem = getProjectFilesystem();
    AbsPath rootPath = projectFilesystem.getRootPath();

    return ImmutableList.of(
        new AbstractExecutionStep("installing_exo_files") {
          @Override
          public StepExecutionResult execute(StepExecutionContext context)
              throws IOException, InterruptedException {

            trigger.verify(context);
            SourcePathResolverAdapter sourcePathResolver = buildContext.getSourcePathResolver();
            String packageName =
                AdbHelper.tryToExtractPackageNameFromManifest(
                    sourcePathResolver.getAbsolutePath(manifestPath).getPath());
            ImmutableSortedMap<String, ImmutableSortedSet<Path>> contents =
                ExopackageDeviceDirectoryLister.deserializeDirectoryContentsForPackage(
                    projectFilesystem,
                    sourcePathResolver.getCellUnsafeRelPath(deviceExoContents).getPath(),
                    packageName);
            context
                .getAndroidDevicesHelper()
                .get()
                .adbCallOrThrow(
                    "installing_exo_files",
                    device -> {
                      ImmutableSortedSet<Path> presentFiles =
                          Objects.requireNonNull(contents.get(device.getSerialNumber()));
                      IsolatedExopackageInfo isolatedExopackageInfo =
                          exopackageInfo.toIsolatedExopackageInfo(sourcePathResolver);
                      new ExopackageInstaller(
                              isolatedExopackageInfo,
                              context.getBuckEventBus(),
                              rootPath,
                              packageName,
                              device,
                              adbConfig.getSkipInstallMetadata())
                          .installMissingExopackageFiles(presentFiles);
                      return true;
                    },
                    true);
            return StepExecutionResults.SUCCESS;
          }
        });
  }

  @Nullable
  @Override
  public SourcePath getSourcePathToOutput() {
    return null;
  }

  @Override
  public boolean isCacheable() {
    return false;
  }
}
