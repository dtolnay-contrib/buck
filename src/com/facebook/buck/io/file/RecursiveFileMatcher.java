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

package com.facebook.buck.io.file;

import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.io.watchman.Capability;
import com.google.common.collect.ImmutableList;
import java.io.File;
import java.util.Objects;
import java.util.Set;

/** Matcher that matches paths within {@code basePath} directory. */
public class RecursiveFileMatcher implements PathMatcher {

  private final RelPath basePath;

  private RecursiveFileMatcher(RelPath basePath) {
    this.basePath = basePath;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof RecursiveFileMatcher)) {
      return false;
    }
    RecursiveFileMatcher that = (RecursiveFileMatcher) other;
    return Objects.equals(basePath, that.basePath);
  }

  @Override
  public int hashCode() {
    return Objects.hash(basePath);
  }

  @Override
  public String toString() {
    return String.format("%s basePath=%s", super.toString(), basePath);
  }

  @Override
  public boolean matches(RelPath path) {
    return path.startsWith(basePath.getPath());
  }

  public RelPath getPath() {
    return basePath;
  }

  @Override
  public ImmutableList<?> toWatchmanMatchQuery(Set<Capability> capabilities) {
    if (capabilities.contains(Capability.DIRNAME)) {
      return ImmutableList.of("dirname", getPath().toString());
    }
    return ImmutableList.of("match", getGlob(), "wholename");
  }

  @Override
  public PathOrGlob getPathOrGlob() {
    return PathOrGlob.path(getPath());
  }

  @Override
  public String getGlob() {
    return getPath() + File.separator + "**";
  }

  /** @return The matcher for paths that start with {@code basePath}. */
  public static RecursiveFileMatcher of(RelPath basePath) {
    return new RecursiveFileMatcher(basePath);
  }
}
