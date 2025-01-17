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

package com.facebook.buck.cli;

import com.facebook.buck.android.AndroidBuckConfig;
import com.facebook.buck.android.exopackage.AdbConfig;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.step.AdbOptions;
import com.facebook.buck.util.environment.Platform;
import com.google.common.annotations.VisibleForTesting;
import org.kohsuke.args4j.Option;

public class AdbCommandLineOptions {

  @VisibleForTesting static final String ADB_THREADS_LONG_ARG = "--adb-threads";
  @VisibleForTesting static final String ADB_THREADS_SHORT_ARG = "-T";

  @Option(
      name = ADB_THREADS_LONG_ARG,
      aliases = {ADB_THREADS_SHORT_ARG},
      usage =
          "Number of threads to use for adb operations. "
              + "Defaults to number of connected devices.")
  private int adbThreadCount = 0;

  @VisibleForTesting static final String MULTI_INSTALL_MODE_SHORT_ARG = "-x";
  @VisibleForTesting static final String MULTI_INSTALL_MODE_LONG_ARG = "--all-devices";

  @Option(
      name = MULTI_INSTALL_MODE_LONG_ARG,
      aliases = {MULTI_INSTALL_MODE_SHORT_ARG},
      usage = "Install .apk on all connected devices and/or emulators (multi-install mode)")
  private boolean multiInstallMode;

  public AdbOptions getAdbOptions(BuckConfig buckConfig) {
    if (buckConfig.getView(AdbConfig.class).getMultiInstallMode()) {
      multiInstallMode = true;
    }
    AndroidBuckConfig androidBuckConfig = new AndroidBuckConfig(buckConfig, Platform.detect());
    return new AdbOptions(adbThreadCount, multiInstallMode, androidBuckConfig.getAdbTimeout());
  }
}
