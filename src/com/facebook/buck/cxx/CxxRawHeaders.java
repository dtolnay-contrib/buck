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

package com.facebook.buck.cxx;

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;
import javax.annotation.Nullable;

@BuckStyleValue
public abstract class CxxRawHeaders extends CxxHeaders {
  @Override
  @AddToRuleKey
  public final CxxPreprocessables.IncludeType getIncludeType() {
    return CxxPreprocessables.IncludeType.RAW;
  }

  @Override
  @Nullable
  public SourcePath getRoot() {
    return null;
  }

  @Override
  public Optional<SourcePath> getHeaderMap() {
    return Optional.empty();
  }

  @Override
  public Optional<AbsPath> getResolvedIncludeRoot(SourcePathResolverAdapter resolver) {
    return Optional.empty();
  }

  @Override
  public void addToHeaderPathNormalizer(HeaderPathNormalizer.Builder builder) {
    for (SourcePath sourcePath : getHeaders()) {
      builder.addHeader(sourcePath);
    }
  }

  @AddToRuleKey
  abstract ImmutableSortedSet<SourcePath> getHeaders();

  public static CxxRawHeaders of(ImmutableSortedSet<SourcePath> headers) {
    return ImmutableCxxRawHeaders.ofImpl(headers);
  }
}
