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

package com.facebook.buck.features.apple.common;

import com.google.common.collect.ImmutableMap;

/** Types of actions in a scheme which include targets to build. */
public enum SchemeActionType {
  BUILD,
  LAUNCH,
  TEST,
  PROFILE,
  ANALYZE,
  ARCHIVE;

  public static final ImmutableMap<SchemeActionType, String> DEFAULT_CONFIG_NAMES =
      ImmutableMap.of(
          SchemeActionType.LAUNCH, "Debug",
          SchemeActionType.TEST, "Debug",
          SchemeActionType.PROFILE, "Release",
          SchemeActionType.ANALYZE, "Debug",
          SchemeActionType.ARCHIVE, "Release");
}
