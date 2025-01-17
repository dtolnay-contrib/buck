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

package com.facebook.buck.io.filesystem;

import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.io.watchman.Watchman;
import com.facebook.buck.util.config.Config;
import java.util.Optional;

public interface ProjectFilesystemFactory {
  ProjectFilesystem createProjectFilesystem(
      CanonicalCellName cellName,
      AbsPath root,
      Config config,
      Optional<EmbeddedCellBuckOutInfo> embeddedCellBuckOutInfo,
      boolean buckOutIncludeTargetConfigHash,
      Watchman watchman);

  ProjectFilesystem createProjectFilesystem(
      CanonicalCellName cellName,
      AbsPath root,
      Config config,
      boolean buckOutIncludeTargetConfigHash,
      Watchman watchman);

  ProjectFilesystem createProjectFilesystem(
      CanonicalCellName cellName,
      AbsPath root,
      boolean buckOutIncludeTargetCofigHash,
      Watchman watchman);

  ProjectFilesystem createOrThrow(
      CanonicalCellName cellName,
      AbsPath path,
      boolean buckOutIncludeTargetCofigHash,
      Watchman watchman);
}
