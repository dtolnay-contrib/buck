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

import com.facebook.buck.android.exopackage.AndroidDevice;
import com.facebook.buck.android.exopackage.AndroidDevicesHelper;
import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;

public class ApkInstallStep implements Step {

  private final SourcePathResolverAdapter pathResolver;
  private final HasInstallableApk hasInstallableApk;

  public ApkInstallStep(SourcePathResolverAdapter pathResolver, HasInstallableApk apk) {
    this.pathResolver = pathResolver;
    this.hasInstallableApk = apk;
  }

  @Override
  public StepExecutionResult execute(StepExecutionContext context) throws InterruptedException {
    AndroidDevicesHelper adbHelper = context.getAndroidDevicesHelper().get();
    if (adbHelper.getDevices(true).isEmpty()) {
      return StepExecutionResults.SUCCESS;
    }

    adbHelper.installApk(pathResolver, hasInstallableApk, /*installViaSd=*/ false, /*quiet=*/ true);
    return StepExecutionResults.SUCCESS;
  }

  @Override
  public String getShortName() {
    return "install apk";
  }

  @Override
  public String getDescription(StepExecutionContext context) {
    StringBuilder builder = new StringBuilder();

    AndroidDevicesHelper adbHelper = context.getAndroidDevicesHelper().get();
    for (AndroidDevice device : adbHelper.getDevices(true)) {
      if (builder.length() != 0) {
        builder.append("\n");
      }
      builder.append("adb -s ");
      builder.append(device.getSerialNumber());
      builder.append(" install ");
      builder.append(hasInstallableApk.getApkInfo().getApkPath());
    }
    return builder.toString();
  }
}
