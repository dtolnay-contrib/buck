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

package com.facebook.buck.file;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import java.nio.file.Path;
import org.hamcrest.BaseMatcher;
import org.hamcrest.Description;

public class PathDoesNotExistOnProjectFilesystem extends BaseMatcher<ProjectFilesystem> {
  final Path expectedPath;

  public PathDoesNotExistOnProjectFilesystem(Path expectedPath) {
    this.expectedPath = expectedPath;
  }

  @Override
  public boolean matches(Object o) {
    if (o instanceof ProjectFilesystem) {
      ProjectFilesystem filesystem = (ProjectFilesystem) o;
      return !filesystem.exists(expectedPath);
    }
    return false;
  }

  @Override
  public void describeTo(Description description) {
    description.appendText(String.format("%s to not exist", expectedPath.toString()));
  }

  @Override
  public void describeMismatch(Object item, Description description) {
    description.appendText(String.format("%s exists", expectedPath.toString()));
  }
}
