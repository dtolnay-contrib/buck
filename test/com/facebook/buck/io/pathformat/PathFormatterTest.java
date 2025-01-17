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

package com.facebook.buck.io.pathformat;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.filesystems.BuckFileSystem;
import com.facebook.buck.core.filesystems.BuckFileSystemProvider;
import com.facebook.buck.util.environment.Platform;
import java.nio.file.FileSystems;
import java.nio.file.Paths;
import org.junit.Test;

public class PathFormatterTest {
  @Test
  public void toStringWithUnixSeparatorDefaultPath() {
    assertEquals("foo/bar", PathFormatter.pathWithUnixSeparators("foo/bar"));
    assertEquals("foo/bar", PathFormatter.pathWithUnixSeparators(Paths.get("foo/bar")));
    if (Platform.detect() == Platform.WINDOWS) {
      assertEquals("foo/bar", PathFormatter.pathWithUnixSeparators(Paths.get("foo\\bar")));
    }

    BuckFileSystem buckFileSystem =
        new BuckFileSystem(
            new BuckFileSystemProvider(FileSystems.getDefault()),
            Paths.get(".").toAbsolutePath().toString());
    assertEquals(
        "foo/bar", PathFormatter.pathWithUnixSeparators(buckFileSystem.getPath("foo/bar")));
  }
}
