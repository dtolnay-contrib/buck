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

package com.facebook.buck.remoteexecution;

import com.facebook.buck.remoteexecution.proto.ActionHistoryInfo;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.types.Pair;

/** Provides rule's RE worker requirements for given target */
public interface WorkerRequirementsProvider {
  Pair<build.bazel.remote.execution.v2.Platform, ActionHistoryInfo> resolveRequirements();

  /** Returns the correct RE platform depending on the host platform */
  static String getDefaultPlatform() {
    if (Platform.detect() == Platform.WINDOWS) {
      return "windows";
    } else {
      return "linux-remote-execution";
    }
  }
}
