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

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import java.nio.file.Path;

/**
 * A path which is relative to the build cell root, i.e. the top-level cell in which the build was
 * invoked.
 *
 * <p>See {@link com.facebook.buck.core.build.context.BuildContext#getBuildCellRootPath}.
 */
@BuckStyleValue
public abstract class BuildCellRelativePath {

  public abstract RelPath getPathRelativeToBuildCellRoot();

  /** Construct from three paths. */
  public static BuildCellRelativePath fromCellRelativePath(
      Path buildCellRootPath,
      ProjectFilesystem cellProjectFilesystem,
      Path cellRelativeOrAbsolutePath) {
    if (buildCellRootPath.equals(cellProjectFilesystem.getRootPath().getPath())
        && !cellRelativeOrAbsolutePath.isAbsolute()) {
      return BuildCellRelativePath.of(cellRelativeOrAbsolutePath);
    }
    return of(
        buildCellRootPath.relativize(cellProjectFilesystem.resolve(cellRelativeOrAbsolutePath)));
  }

  /** Construct from three paths. */
  public static BuildCellRelativePath fromCellRelativePath(
      AbsPath buildCellRootPath,
      ProjectFilesystem cellProjectFilesystem,
      Path cellRelativeOrAbsolutePath) {
    return fromCellRelativePath(
        buildCellRootPath.getPath(), cellProjectFilesystem, cellRelativeOrAbsolutePath);
  }

  /** Construct from three paths. */
  public static BuildCellRelativePath fromCellRelativePath(
      Path buildCellRootPath, ProjectFilesystem cellProjectFilesystem, RelPath cellRelativePath) {
    return fromCellRelativePath(
        buildCellRootPath, cellProjectFilesystem, cellRelativePath.getPath());
  }

  /** Construct from three paths. */
  public static BuildCellRelativePath fromCellRelativePath(
      AbsPath buildCellRootPath,
      ProjectFilesystem cellProjectFilesystem,
      RelPath cellRelativePath) {
    return fromCellRelativePath(
        buildCellRootPath, cellProjectFilesystem, cellRelativePath.getPath());
  }

  public static BuildCellRelativePath of(Path pathRelativeToBuildCellRoot) {
    return of(RelPath.of(pathRelativeToBuildCellRoot));
  }

  public static BuildCellRelativePath of(RelPath pathRelativeToBuildCellRoot) {
    return ImmutableBuildCellRelativePath.ofImpl(pathRelativeToBuildCellRoot);
  }

  @Override
  public String toString() {
    return String.format("%s (relative to build cell root)", getPathRelativeToBuildCellRoot());
  }
}
