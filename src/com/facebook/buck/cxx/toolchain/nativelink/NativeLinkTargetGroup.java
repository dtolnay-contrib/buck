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

package com.facebook.buck.cxx.toolchain.nativelink;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.cxx.toolchain.CxxPlatform;

/** Represents a target of a native link. */
public interface NativeLinkTargetGroup {
  BuildTarget getBuildTarget();

  /**
   * @param includePrivateLinkerFlags whether to include rule-specific non-exported linker flags.
   */
  NativeLinkTarget getTargetForPlatform(
      CxxPlatform cxxPlatform, boolean includePrivateLinkerFlags, boolean preferStrippedObjects);
}
