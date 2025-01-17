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

import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

/** Test for FileFinder. */
public class FileFinderTest {
  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void combine() {
    Object[] result = FileFinder.combine(ImmutableSet.of(), "foo", ImmutableSet.of()).toArray();
    Arrays.sort(result);
    Assert.assertArrayEquals(new String[] {"foo"}, result);

    result =
        FileFinder.combine(ImmutableSet.of(), "foo", ImmutableSet.of(".exe", ".com", ".bat"))
            .toArray();
    Arrays.sort(result);
    Assert.assertArrayEquals(new String[] {"foo.bat", "foo.com", "foo.exe"}, result);

    result = FileFinder.combine(ImmutableSet.of("lib", ""), "foo", ImmutableSet.of()).toArray();
    Arrays.sort(result);
    Assert.assertArrayEquals(new String[] {"foo", "libfoo"}, result);
  }

  @Test
  public void firstMatchInPath() throws IOException {
    Path fee = tmp.newFolder("fee").getPath();
    Path fie = tmp.newFolder("fie").getPath();
    tmp.newFile("fee/foo");
    tmp.newFile("fie/foo");
    ImmutableList<Path> searchPath = ImmutableList.of(fie, fee);
    Optional<Path> result =
        FileFinder.getOptionalFile(ImmutableSet.of("foo"), searchPath, Files::exists);
    Assert.assertTrue(result.isPresent());
    Assert.assertEquals(fie.resolve("foo"), result.get());
  }

  @Test
  public void matchAny() throws IOException {
    Path fee = tmp.newFolder("fee").getPath();
    tmp.newFile("fee/foo");
    Path fie = tmp.newFolder("fie").getPath();
    tmp.newFile("fie/bar");

    ImmutableSet<String> names = ImmutableSet.of("foo", "bar", "baz");
    Optional<Path> result =
        FileFinder.getOptionalFile(names, ImmutableSortedSet.of(fee), Files::exists);
    Assert.assertTrue(result.isPresent());
    Assert.assertEquals(fee.resolve("foo"), result.get());

    result = FileFinder.getOptionalFile(names, ImmutableSortedSet.of(fie), Files::exists);
    Assert.assertTrue(result.isPresent());
    Assert.assertEquals(fie.resolve("bar"), result.get());
  }

  @Test
  public void noMatch() throws IOException {
    Path fee = tmp.newFolder("fee").getPath();
    tmp.newFile("fee/foo");
    Path fie = tmp.newFolder("fie").getPath();
    tmp.newFile("fie/bar");

    Optional<Path> result =
        FileFinder.getOptionalFile(
            ImmutableSet.of("baz"), ImmutableSortedSet.of(fee, fie), Files::exists);
    Assert.assertFalse(result.isPresent());
  }
}
