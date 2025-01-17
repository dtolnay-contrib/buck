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

package com.facebook.buck.util.cache.impl;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.io.filesystem.impl.DefaultProjectFilesystemDelegate;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.util.cache.FileHashCacheMode;
import com.facebook.buck.util.cache.HashCodeAndFileType;
import com.facebook.buck.util.zip.CustomJarOutputStream;
import com.facebook.buck.util.zip.CustomZipOutputStream;
import com.facebook.buck.util.zip.ZipOutputStreams;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.EnumSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import org.hamcrest.Matchers;
import org.hamcrest.junit.ExpectedException;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class DefaultFileHashCacheTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();
  @Rule public ExpectedException expectedException = ExpectedException.none();
  private final FileHashCacheMode fileHashCacheMode;

  @Parameterized.Parameters(name = "{0}")
  public static Collection<Object[]> data() {
    return EnumSet.allOf(FileHashCacheMode.class).stream()
        .map(v -> new Object[] {v})
        .collect(ImmutableList.toImmutableList());
  }

  public DefaultFileHashCacheTest(FileHashCacheMode fileHashCacheMode) {
    this.fileHashCacheMode = fileHashCacheMode;
  }

  @Test
  public void whenPathIsPutCacheContainsPath() throws IOException {
    ProjectFilesystem filesystem = TestProjectFilesystems.createProjectFilesystem(tmp.getRoot());
    Path path = Paths.get("SomeClass.java");
    filesystem.touch(path);

    DefaultFileHashCache cache =
        DefaultFileHashCache.createDefaultFileHashCache(filesystem, fileHashCacheMode, false);
    HashCodeAndFileType value = HashCodeAndFileType.ofFile(HashCode.fromInt(42));
    cache.fileHashCacheEngine.put(path, value);
    assertTrue("Cache should contain path", cache.getIfPresent(path).isPresent());
  }

  @Test
  public void whenPathIsPutPathGetReturnsHash() throws IOException {
    ProjectFilesystem filesystem = TestProjectFilesystems.createProjectFilesystem(tmp.getRoot());
    Path path = Paths.get("SomeClass.java");
    filesystem.touch(path);

    DefaultFileHashCache cache =
        DefaultFileHashCache.createDefaultFileHashCache(filesystem, fileHashCacheMode, false);
    HashCodeAndFileType value = HashCodeAndFileType.ofFile(HashCode.fromInt(42));
    cache.fileHashCacheEngine.put(path, value);
    assertEquals("Cache should contain hash", value.getHashCode(), cache.get(path));
  }

  @Test
  public void whenPathIsPutThenInvalidatedCacheDoesNotContainPath() throws IOException {
    ProjectFilesystem filesystem = TestProjectFilesystems.createProjectFilesystem(tmp.getRoot());
    Path path = Paths.get("SomeClass.java");
    filesystem.touch(path);

    DefaultFileHashCache cache =
        DefaultFileHashCache.createDefaultFileHashCache(filesystem, fileHashCacheMode, false);
    HashCodeAndFileType value = HashCodeAndFileType.ofFile(HashCode.fromInt(42));
    cache.fileHashCacheEngine.put(path, value);
    assertTrue("Cache should contain path", cache.getIfPresent(path).isPresent());
    filesystem.deleteFileAtPath(path);
    cache.invalidate(path);
    assertFalse("Cache should not contain pain", cache.getIfPresent(path).isPresent());
  }

  @Test
  public void invalidatingNonExistentEntryDoesNotThrow() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    DefaultFileHashCache cache =
        DefaultFileHashCache.createDefaultFileHashCache(filesystem, fileHashCacheMode, false);
    Path path = new File("SomeClass.java").toPath();
    assertFalse("Cache should not contain pain", cache.getIfPresent(path).isPresent());
    cache.invalidate(path);
    assertFalse("Cache should not contain pain", cache.getIfPresent(path).isPresent());
  }

  @Test
  public void getMissingPathThrows() throws IOException {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    DefaultFileHashCache cache =
        DefaultFileHashCache.createDefaultFileHashCache(filesystem, fileHashCacheMode, false);
    expectedException.expect(RuntimeException.class);
    cache.get(filesystem.getPath("hello.java"));
  }

  @Test
  public void whenPathsArePutThenInvalidateAllRemovesThem() throws IOException {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    DefaultFileHashCache cache =
        DefaultFileHashCache.createDefaultFileHashCache(filesystem, fileHashCacheMode, false);

    Path path1 = Paths.get("path1");
    filesystem.writeContentsToPath("contents1", path1);
    cache.get(path1);
    assertTrue(cache.willGet(path1));

    Path path2 = Paths.get("path2");
    filesystem.writeContentsToPath("contents2", path2);
    cache.get(path2);
    assertTrue(cache.willGet(path2));

    // Verify that `invalidateAll` clears everything from the cache.
    assertFalse(cache.fileHashCacheEngine.asMap().isEmpty());
    cache.invalidateAll();

    assertTrue(cache.fileHashCacheEngine.asMap().isEmpty());
  }

  @Test
  public void whenDirectoryIsPutThenInvalidatedCacheDoesNotContainPathOrChildren()
      throws IOException {
    ProjectFilesystem filesystem = TestProjectFilesystems.createProjectFilesystem(tmp.getRoot());
    DefaultFileHashCache cache =
        DefaultFileHashCache.createDefaultFileHashCache(filesystem, fileHashCacheMode, false);

    Path dir = filesystem.getPath("dir");
    filesystem.mkdirs(dir);
    Path child1 = dir.resolve("child1");
    filesystem.touch(child1);
    Path child2 = dir.resolve("child2");
    filesystem.touch(child2);

    cache.get(dir);
    assertTrue(cache.willGet(dir));
    assertTrue(cache.willGet(child1));
    assertTrue(cache.willGet(child2));

    cache.invalidate(dir);
    assertFalse(cache.getIfPresent(dir).isPresent());
    assertFalse(cache.getIfPresent(child1).isPresent());
    assertFalse(cache.getIfPresent(child2).isPresent());
  }

  @Test
  public void whenJarMemberWithHashInManifestIsQueriedThenCacheCorrectlyObtainsIt()
      throws IOException {
    ProjectFilesystem filesystem = createTempProjectFilesystem();
    DefaultFileHashCache cache =
        DefaultFileHashCache.createDefaultFileHashCache(filesystem, fileHashCacheMode, false);

    Path abiJarPath = Paths.get("test-abi.jar");
    Path memberPath = Paths.get("SomeClass.class");
    String memberContents = "Some contents";
    try (CustomJarOutputStream jar =
        ZipOutputStreams.newJarOutputStream(filesystem.newFileOutputStream(abiJarPath))) {
      jar.setEntryHashingEnabled(true);
      jar.writeEntry(
          memberPath.toString(),
          new ByteArrayInputStream(memberContents.getBytes(StandardCharsets.UTF_8)));
    }

    HashCode actual = cache.getForArchiveMember(abiJarPath, memberPath);
    HashCode expected = Hashing.murmur3_128().hashString(memberContents, StandardCharsets.UTF_8);

    assertEquals(expected, actual);
  }

  @Test(expected = NoSuchFileException.class)
  public void whenJarMemberWithoutHashInManifestIsQueriedThenThrow() throws IOException {
    Assume.assumeFalse(fileHashCacheMode == FileHashCacheMode.PARALLEL_COMPARISON);
    Assume.assumeFalse(fileHashCacheMode == FileHashCacheMode.LIMITED_PREFIX_TREE_PARALLEL);

    ProjectFilesystem filesystem = createTempProjectFilesystem();
    DefaultFileHashCache cache =
        DefaultFileHashCache.createDefaultFileHashCache(filesystem, fileHashCacheMode, false);

    Path abiJarPath = Paths.get("test-abi.jar");
    Path memberPath = Paths.get("Unhashed.txt");
    String memberContents = "Some contents";
    try (CustomJarOutputStream jar =
        ZipOutputStreams.newJarOutputStream(filesystem.newFileOutputStream(abiJarPath))) {
      jar.setEntryHashingEnabled(true);
      jar.writeEntry(
          "SomeClass.class",
          new ByteArrayInputStream(memberContents.getBytes(StandardCharsets.UTF_8)));
      jar.setEntryHashingEnabled(false);
      jar.writeEntry(
          memberPath.toString(),
          new ByteArrayInputStream(memberContents.getBytes(StandardCharsets.UTF_8)));
    }

    cache.getForArchiveMember(abiJarPath, memberPath);
  }

  @Test
  public void whenJarMemberWithoutManifestIsQueriedThenThrow() throws IOException {
    ProjectFilesystem filesystem = createTempProjectFilesystem();
    DefaultFileHashCache cache =
        DefaultFileHashCache.createDefaultFileHashCache(filesystem, fileHashCacheMode, false);

    Path abiJarPath = Paths.get("no-manifest.jar");
    Path memberPath = Paths.get("Empty.class");
    String memberContents = "Some contents";

    try (JarOutputStream jar = new JarOutputStream(filesystem.newFileOutputStream(abiJarPath))) {
      jar.putNextEntry(new JarEntry(memberPath.toString()));
      jar.write(memberContents.getBytes(StandardCharsets.UTF_8));
      jar.closeEntry();
    }

    cache.getForArchiveMember(abiJarPath, memberPath);

    HashCode actual = cache.getForArchiveMember(abiJarPath, memberPath);
    HashCode expected = Hashing.murmur3_128().hashString(memberContents, StandardCharsets.UTF_8);

    assertEquals(expected, actual);
  }

  @Test
  public void whenJarMemberWithEmptyManifestIsQueriedThenManifestIsGenerated() throws IOException {
    Assume.assumeFalse(fileHashCacheMode == FileHashCacheMode.PARALLEL_COMPARISON);
    Assume.assumeFalse(fileHashCacheMode == FileHashCacheMode.LIMITED_PREFIX_TREE_PARALLEL);
    ProjectFilesystem filesystem = createTempProjectFilesystem();
    DefaultFileHashCache cache =
        DefaultFileHashCache.createDefaultFileHashCache(filesystem, fileHashCacheMode, false);

    Path abiJarPath = Paths.get("empty-manifest.jar");
    Path memberPath = Paths.get("Empty.class");
    String memberContents = "Some contents";

    try (CustomZipOutputStream jar =
        ZipOutputStreams.newOutputStream(filesystem.newFileOutputStream(abiJarPath))) {
      jar.writeEntry(JarFile.MANIFEST_NAME, new ByteArrayInputStream(new byte[0]));
      jar.writeEntry(
          memberPath.toString(),
          new ByteArrayInputStream(memberContents.getBytes(StandardCharsets.UTF_8)));
    }

    cache.getForArchiveMember(abiJarPath, memberPath);

    HashCode actual = cache.getForArchiveMember(abiJarPath, memberPath);
    HashCode expected = Hashing.murmur3_128().hashString(memberContents, StandardCharsets.UTF_8);

    assertEquals(expected, actual);
  }

  @Test
  public void getSizeOfMissingPathThrows() throws IOException {
    ProjectFilesystem filesystem = createTempProjectFilesystem();
    Path input = filesystem.getPath("input");
    DefaultFileHashCache cache =
        DefaultFileHashCache.createDefaultFileHashCache(filesystem, fileHashCacheMode, false);
    expectedException.expect(RuntimeException.class);
    cache.getSize(input);
  }

  @Test
  public void getSizeOfFile() throws IOException {
    ProjectFilesystem filesystem = TestProjectFilesystems.createProjectFilesystem(tmp.getRoot());
    Path input = filesystem.getPath("input");
    filesystem.writeBytesToPath(new byte[123], input);
    DefaultFileHashCache cache =
        DefaultFileHashCache.createDefaultFileHashCache(filesystem, fileHashCacheMode, false);
    assertThat(cache.getSize(input), Matchers.equalTo(123L));
  }

  @Test
  public void getSizeOfDirectory() throws IOException {
    ProjectFilesystem filesystem = TestProjectFilesystems.createProjectFilesystem(tmp.getRoot());
    Path input = filesystem.getPath("input");
    filesystem.mkdirs(input);
    filesystem.writeBytesToPath(new byte[123], input.resolve("file1"));
    filesystem.writeBytesToPath(new byte[123], input.resolve("file2"));
    DefaultFileHashCache cache =
        DefaultFileHashCache.createDefaultFileHashCache(filesystem, fileHashCacheMode, false);
    assertThat(cache.getSize(input), Matchers.equalTo(246L));
  }

  @Test
  public void getFileSizeInvalidation() throws IOException {
    ProjectFilesystem filesystem = TestProjectFilesystems.createProjectFilesystem(tmp.getRoot());
    Path input = filesystem.getPath("input");
    filesystem.writeBytesToPath(new byte[123], input);
    DefaultFileHashCache cache =
        DefaultFileHashCache.createDefaultFileHashCache(filesystem, fileHashCacheMode, false);
    cache.getSize(input);
    cache.invalidate(input);
    assertNull(cache.fileHashCacheEngine.getSizeIfPresent(input));
  }

  @Test
  public void thatBuckoutCacheWillGetIsCorrect() throws IOException {
    ProjectFilesystem filesystem = TestProjectFilesystems.createProjectFilesystem(tmp.getRoot());
    RelPath buckOut = filesystem.getBuckPaths().getBuckOut();
    filesystem.mkdirs(buckOut);
    Path buckOutFile = buckOut.resolve("file.txt");
    Path otherFile = Paths.get("file.txt");
    filesystem.writeContentsToPath("data", buckOutFile);
    filesystem.writeContentsToPath("other data", otherFile);
    DefaultFileHashCache cache =
        DefaultFileHashCache.createBuckOutFileHashCache(filesystem, fileHashCacheMode, false);
    assertTrue(cache.willGet(filesystem.getPath("buck-out/file.txt")));
    assertFalse(cache.willGet(filesystem.getPath("buck-out/cells/file.txt")));
    assertFalse(cache.willGet(filesystem.getPath("file.txt")));
  }

  @Test
  public void thatNonBuckoutCacheWillGetIsCorrect() throws IOException {
    ProjectFilesystem filesystem = TestProjectFilesystems.createProjectFilesystem(tmp.getRoot());
    RelPath buckOut = filesystem.getBuckPaths().getBuckOut();
    filesystem.mkdirs(buckOut);
    Path buckOutFile = buckOut.resolve("file.txt");
    Path otherFile = Paths.get("file.txt");
    filesystem.writeContentsToPath("data", buckOutFile);
    filesystem.writeContentsToPath("other data", otherFile);
    DefaultFileHashCache cache =
        DefaultFileHashCache.createDefaultFileHashCache(filesystem, fileHashCacheMode, false);
    assertFalse(cache.willGet(filesystem.getPath("buck-out/file.txt")));
    assertFalse(cache.willGet(filesystem.getPath("buck-out/cells/file.txt")));
    assertTrue(cache.willGet(filesystem.getPath("file.txt")));
  }

  @Test
  public void thatBuckOutCacheUsesCorrectDelegate()
      throws NoSuchFieldException, IllegalAccessException {
    // TODO: add way to spoof that we're running on an eden filesystem so we can assert that
    // initially the filesystem contains an EdenProjectFilesystemDelegate, but that the
    // FileHashCache created with createBuckOutFileHashCache instead has a Filesystem with a
    // DefaultProjectFilesystemDelegate
    ProjectFilesystem filesystem = TestProjectFilesystems.createProjectFilesystem(tmp.getRoot());

    DefaultFileHashCache buckOutCache =
        DefaultFileHashCache.createBuckOutFileHashCache(filesystem, fileHashCacheMode, false);

    Class<?> buckOutCacheClass = buckOutCache.getFilesystem().getClass();
    Field buckOutCacheClassDelegate = buckOutCacheClass.getDeclaredField("delegate");
    buckOutCacheClassDelegate.setAccessible(true);
    assertTrue(
        buckOutCacheClassDelegate.get(buckOutCache.getFilesystem())
            instanceof DefaultProjectFilesystemDelegate);
  }

  private static ProjectFilesystem createTempProjectFilesystem() throws IOException {
    ProjectFilesystem filesystem = FakeProjectFilesystem.createRealTempFilesystem();
    return TestProjectFilesystems.createProjectFilesystem(filesystem.getRootPath().toRealPath());
  }
}
