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

package com.facebook.buck.step.fs;

import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.io.filesystem.BuildCellRelativePath;
import com.facebook.buck.step.Step;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

/**
 * Deletes the directory, if it exists, before creating it. {@link MakeCleanDirectoryStep} is
 * preferable to {@link MkdirStep} if the directory may contain many generated files and we want to
 * avoid the case where it could accidentally include generated files from a previous run in Buck.
 *
 * <p>For example, for a directory of {@code .class} files, if the user deletes a {@code .java} file
 * that generated one of the {@code .class} files, the {@code .class} file corresponding to the
 * deleted {@code .java} file should no longer be there when {@code javac} is run again.
 */
public final class MakeCleanDirectoryStep {

  public static ImmutableList<Step> of(BuildCellRelativePath path) {
    return ImmutableList.of(RmStep.of(path, true), MkdirStep.of(path));
  }

  public static ImmutableList<Step> ofExcludingPaths(
      BuildCellRelativePath path, ImmutableSet<RelPath> excludedPaths) {
    return ImmutableList.of(RmStep.recursiveOf(path, excludedPaths), MkdirStep.of(path));
  }

  private MakeCleanDirectoryStep() {}
}
