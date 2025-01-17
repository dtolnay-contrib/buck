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
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.util.stream.RichStream;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.util.Optional;

public interface FilteredResourcesProvider {

  /** @return The set of res/ directories that should be used to calculate the final R.java file. */
  ImmutableList<SourcePath> getResDirectories();

  default ImmutableList<Path> getRelativeResDirectories(
      ProjectFilesystem filesystem, SourcePathResolverAdapter resolver) {
    return RichStream.from(getResDirectories())
        .map(d -> filesystem.relativize(resolver.getAbsolutePath(d)).getPath())
        .toImmutableList();
  }

  /**
   * @return The list of {@code strings.xml} files excluding whitelisted strings. Empty unless
   *     {@code ResourceCompressionMode.isStoreStringsAsAssets} is true.
   */
  ImmutableList<Path> getStringFiles();

  Optional<BuildRule> getResourceFilterRule();

  boolean hasResources();

  Optional<SourcePath> getOverrideSymbolsPath();
}
