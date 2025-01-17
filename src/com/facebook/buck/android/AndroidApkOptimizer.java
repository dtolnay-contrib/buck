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

import static com.facebook.buck.android.BinaryType.APK;

import com.facebook.buck.android.apk.KeystoreProperties;
import com.facebook.buck.android.toolchain.AndroidSdkLocation;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.ProjectFilesystemUtils;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.isolatedsteps.android.ZipalignStep;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Supplier;

/** The class executes all binary steps responsible for optimizing apk specific components. */
public class AndroidApkOptimizer extends AndroidBinaryOptimizer {

  AndroidApkOptimizer(
      BuildTarget buildTarget,
      ProjectFilesystem filesystem,
      AndroidSdkLocation androidSdkLocation,
      SourcePath keystorePath,
      SourcePath keystorePropertiesPath,
      boolean packageAssetLibraries,
      boolean compressAssetLibraries,
      Optional<CompressionAlgorithm> assetCompressionAlgorithm,
      boolean isCompressResources,
      Tool zipalignTool,
      boolean withDownwardApi) {
    super(
        buildTarget,
        filesystem,
        androidSdkLocation,
        keystorePath,
        keystorePropertiesPath,
        packageAssetLibraries,
        compressAssetLibraries,
        assetCompressionAlgorithm,
        isCompressResources,
        zipalignTool,
        APK,
        withDownwardApi);
  }

  @Override
  void getBinaryTypeSpecificBuildSteps(
      ImmutableList.Builder<Step> steps,
      Path apkToAlign,
      Path finalApkPath,
      Supplier<KeystoreProperties> keystoreProperties,
      BuildContext context) {
    Path zipalignedApkPath =
        AndroidBinaryPathUtility.getZipalignedApkPath(filesystem, buildTarget, binaryType);
    steps.add(
        new ZipalignStep(
            filesystem.getRootPath(),
            ProjectFilesystemUtils.relativize(
                filesystem.getRootPath(), context.getBuildCellRootPath()),
            RelPath.of(apkToAlign),
            RelPath.of(zipalignedApkPath),
            withDownwardApi,
            zipalignTool.getCommandPrefix(context.getSourcePathResolver())));
    steps.add(new ApkSignerStep(filesystem, zipalignedApkPath, finalApkPath, keystoreProperties));
  }
}
