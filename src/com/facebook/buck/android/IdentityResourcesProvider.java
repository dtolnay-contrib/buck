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

import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.util.Optional;

public class IdentityResourcesProvider implements FilteredResourcesProvider {

  private final ImmutableList<SourcePath> resDirectories;

  public IdentityResourcesProvider(ImmutableList<SourcePath> resDirectories) {
    this.resDirectories = resDirectories;
  }

  @Override
  public ImmutableList<SourcePath> getResDirectories() {
    return resDirectories;
  }

  @Override
  public ImmutableList<Path> getStringFiles() {
    return ImmutableList.of();
  }

  @Override
  public Optional<BuildRule> getResourceFilterRule() {
    return Optional.empty();
  }

  @Override
  public Optional<SourcePath> getOverrideSymbolsPath() {
    return Optional.empty();
  }

  @Override
  public boolean hasResources() {
    return !resDirectories.isEmpty();
  }
}
