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
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.SortedSet;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/**
 * Finishes exopackage installation by:
 * <li>updating the metadata.txt files for the different exopackage types
 * <li>deleting unwanted files (.so/.dex/.apk from previous installs)
 * <li>installing the apk
 * <li>killing the app/process
 */
public class ExopackageInstallFinisher extends AbstractBuildRule {

  // Due to the InstallTrigger, this rule will run on every build. The @AddToRuleKey fields and any
  // additional deps are required to just ensure that inputs are ready and that certain work (like
  // installing files) has already happened.
  @AddToRuleKey private final InstallTrigger trigger;
  @AddToRuleKey private final SourcePath deviceExoContents;
  @AddToRuleKey private final SourcePath apkPath;
  @AddToRuleKey private final SourcePath manifestPath;

  private final Supplier<ImmutableSortedSet<BuildRule>> depsSupplier;
  private final HasInstallableApk.ApkInfo apkInfo;
  private final AdbConfig adbConfig;

  public ExopackageInstallFinisher(
      BuildTarget installerTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder sourcePathRuleFinder,
      HasInstallableApk.ApkInfo apkInfo,
      ExopackageDeviceDirectoryLister directoryLister,
      ImmutableList<BuildRule> extraDeps,
      AdbConfig adbConfig) {
    super(installerTarget, projectFilesystem);
    this.trigger = new InstallTrigger(projectFilesystem);
    this.deviceExoContents = directoryLister.getSourcePathToOutput();
    this.apkInfo = apkInfo;
    this.apkPath = apkInfo.getApkPath();
    this.manifestPath = apkInfo.getManifestPath();
    this.adbConfig = adbConfig;

    this.depsSupplier =
        MoreSuppliers.memoize(
            () ->
                ImmutableSortedSet.<BuildRule>naturalOrder()
                    .add(directoryLister)
                    .addAll(extraDeps)
                    .addAll(
                        sourcePathRuleFinder.filterBuildRuleInputs(
                            Arrays.asList(apkPath, manifestPath)))
                    .build());
  }

  @Override
  public SortedSet<BuildRule> getBuildDeps() {
    return depsSupplier.get();
  }

  @Override
  public ImmutableList<? extends Step> getBuildSteps(
      BuildContext buildContext, BuildableContext buildableContext) {
    return ImmutableList.of(
        new AbstractExecutionStep("finishing_apk_installation") {
          @Override
          public StepExecutionResult execute(StepExecutionContext context)
              throws IOException, InterruptedException {
            trigger.verify(context);
            ExopackageInfo exoInfo = apkInfo.getExopackageInfo().get();
            SourcePathResolverAdapter sourcePathResolver = buildContext.getSourcePathResolver();
            String packageName =
                AdbHelper.tryToExtractPackageNameFromManifest(
                    sourcePathResolver.getAbsolutePath(manifestPath).getPath());
            ProjectFilesystem projectFilesystem = getProjectFilesystem();
            ImmutableSortedMap<String, ImmutableSortedSet<Path>> contents =
                ExopackageDeviceDirectoryLister.deserializeDirectoryContentsForPackage(
                    projectFilesystem,
                    sourcePathResolver.getCellUnsafeRelPath(deviceExoContents).getPath(),
                    packageName);
            IsolatedExopackageInfo isolatedExopackageInfo =
                exoInfo.toIsolatedExopackageInfo(sourcePathResolver);
            HasInstallableApk.IsolatedApkInfo isolatedApkInfo =
                HasInstallableApk.toIsolatedApkInfo(sourcePathResolver, apkInfo);
            context
                .getAndroidDevicesHelper()
                .get()
                .adbCallOrThrow(
                    "finishing_apk_installation_call",
                    device -> {
                      ExopackageInstaller installer =
                          new ExopackageInstaller(
                              isolatedExopackageInfo,
                              context.getBuckEventBus(),
                              projectFilesystem.getRootPath(),
                              packageName,
                              device,
                              adbConfig.getSkipInstallMetadata());
                      installer.finishExoFileInstallation(
                          ImmutableSortedSet.copyOf(contents.get(device.getSerialNumber())));
                      installer.installAndRestartApk(isolatedApkInfo);
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
