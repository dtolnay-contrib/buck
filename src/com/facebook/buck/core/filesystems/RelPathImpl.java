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

package com.facebook.buck.core.filesystems;

import java.nio.file.Path;

/**
 * Implementation of {@link com.facebook.buck.core.filesystems.RelPath} for paths which are not
 * {@link BuckUnixPath}.
 */
class RelPathImpl extends PathWrapperImpl implements RelPath {
  public RelPathImpl(Path path) {
    super(path);
    if (path.isAbsolute()) {
      throw new IllegalArgumentException("path must be relative: " + path);
    }
  }
}
