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

package com.facebook.buck.versions;

import com.facebook.buck.core.graph.transformation.model.ComputeResult;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.Map;

@BuckStyleValue
interface VersionInfo extends ComputeResult {

  ImmutableMap<BuildTarget, ImmutableSet<Version>> getVersionDomain();

  static VersionInfo of(Map<? extends BuildTarget, ? extends ImmutableSet<Version>> versionDomain) {
    return ImmutableVersionInfo.ofImpl(versionDomain);
  }
}
