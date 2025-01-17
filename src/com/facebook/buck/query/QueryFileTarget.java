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

package com.facebook.buck.query;

import com.facebook.buck.core.sourcepath.PathSourcePath;
import javax.annotation.Nullable;

/**
 * Implementation of {@link ConfiguredQueryTarget} and {@link UnconfiguredQueryTarget} that wraps a
 * {@link PathSourcePath}.
 */
public final class QueryFileTarget implements ConfiguredQueryTarget, UnconfiguredQueryTarget {

  private final PathSourcePath path;

  private QueryFileTarget(PathSourcePath path) {
    this.path = path;
  }

  /**
   * Construct a new immutable {@code QueryFileTarget} instance.
   *
   * @param path The value for the {@code path} attribute
   * @return An immutable QueryFileTarget instance
   */
  public static QueryFileTarget of(PathSourcePath path) {
    return new QueryFileTarget(path);
  }

  /** @return The value of the {@code path} attribute */
  public PathSourcePath getPath() {
    return path;
  }

  @Override
  public String toString() {
    return path.toString();
  }

  @Override
  public boolean equals(@Nullable Object other) {
    if (!(other instanceof QueryFileTarget)) {
      return false;
    }

    QueryFileTarget that = (QueryFileTarget) other;
    return path.equals((that.path));
  }

  @Override
  public int hashCode() {
    return path.hashCode();
  }
}
