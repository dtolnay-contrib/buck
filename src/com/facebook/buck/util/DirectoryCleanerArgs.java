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

package com.facebook.buck.util;

import com.facebook.buck.core.util.immutables.BuckStyleValueWithBuilder;
import java.util.Optional;
import org.immutables.value.Value;

@BuckStyleValueWithBuilder
public abstract class DirectoryCleanerArgs {

  public abstract DirectoryCleaner.PathSelector getPathSelector();

  @Value.Default
  public long getMaxTotalSizeBytes() {
    return Long.MAX_VALUE;
  }

  @Value.Default
  public int getMaxPathCount() {
    return Integer.MAX_VALUE;
  }

  @Value.Default
  public int getMinAmountOfEntriesToKeep() {
    return 0;
  }

  public abstract Optional<Long> getMaxBytesAfterDeletion();

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder extends ImmutableDirectoryCleanerArgs.Builder {}
}
