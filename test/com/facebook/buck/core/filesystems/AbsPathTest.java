/*
 * Copyright (c) Facebook, Inc. and its affiliates.
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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

public class AbsPathTest {

  @Test
  public void removePrefix() {
    AbsPath root = AbsPath.of(Paths.get(".").toAbsolutePath());
    assertEquals(RelPath.get(""), root.removePrefix(root));
    assertEquals(RelPath.get("foo"), root.resolve("foo").removePrefix(root));
    assertEquals(RelPath.get("foo/bar"), root.resolve("foo/bar").removePrefix(root));
  }

  @Test
  public void removePrefixIfStartsWith() {
    AbsPath root = AbsPath.of(Paths.get(".").toAbsolutePath());
    assertEquals(RelPath.get(""), root.removePrefixIfStartsWith(root));
    assertEquals(RelPath.get("foo"), root.resolve("foo").removePrefixIfStartsWith(root));
    assertEquals(RelPath.get("foo/bar"), root.resolve("foo/bar").removePrefixIfStartsWith(root));
    assertNull(root.resolve("foo/bar").removePrefixIfStartsWith(root.resolve("baz")));
  }

  @Test
  public void absPath() throws IOException {
    Path absPath = BuckUnixPathUtils.createPath("/a/b/c");
    assertSame(AbsPath.of(absPath), absPath);
  }

  @Test
  public void relPath() throws IOException {
    Path relPath = RelPath.get("a/b/c/testFile.txt").getPath();

    IllegalArgumentException exception =
        assertThrows(IllegalArgumentException.class, () -> AbsPath.of(relPath));
    assertThat(exception.getMessage(), startsWith("path must be absolute: "));
  }
}
