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
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.ForwardRelPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.io.watchman.WatchmanEvent.Kind;
import com.facebook.buck.io.watchman.WatchmanOverflowEvent;
import com.facebook.buck.io.watchman.WatchmanPathEvent;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.util.cache.FileHashCacheMode;
import com.facebook.buck.util.cache.HashCodeAndFileType;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.HashCode;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.EnumSet;
import org.hamcrest.junit.ExpectedException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class WatchedFileHashCacheTest {
  private final FileHashCacheMode fileHashCacheMode;

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Rule public ExpectedException expectedException = ExpectedException.none();

  @Parameterized.Parameters(name = "{0}")
  public static Collection<Object[]> data() {
    return EnumSet.allOf(FileHashCacheMode.class).stream()
        .map(v -> new Object[] {v})
        .collect(ImmutableList.toImmutableList());
  }

  public WatchedFileHashCacheTest(FileHashCacheMode fileHashCacheMode) {
    this.fileHashCacheMode = fileHashCacheMode;
  }

  @Test
  public void whenNotifiedOfOverflowEventCacheIsCleared() throws IOException {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    WatchedFileHashCache cache =
        new WatchedFileHashCache(
            filesystem, fileHashCacheMode, false
            /** loggingEnabled */
            );
    Path path = new File("SomeClass.java").toPath();
    filesystem.touch(path);

    HashCodeAndFileType value = HashCodeAndFileType.ofFile(HashCode.fromInt(42));
    cache.fileHashCacheEngine.put(path, value);
    cache.fileHashCacheEngine.putSize(path, 1234L);
    cache.onFileSystemChange(WatchmanOverflowEvent.of(filesystem.getRootPath(), ""));

    assertFalse("Cache should not contain path", cache.getIfPresent(path).isPresent());
    assertThat(
        "Cache should not contain path",
        cache.fileHashCacheEngine.getSizeIfPresent(path),
        nullValue());
  }

  @Test
  public void whenNotifiedOfCreateEventCacheEntryIsRemoved() throws IOException {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    WatchedFileHashCache cache =
        new WatchedFileHashCache(
            filesystem, fileHashCacheMode, false
            /** loggingEnabled */
            );
    Path path = Paths.get("SomeClass.java");
    filesystem.touch(path);

    HashCodeAndFileType value = HashCodeAndFileType.ofFile(HashCode.fromInt(42));
    cache.fileHashCacheEngine.put(path, value);
    cache.fileHashCacheEngine.putSize(path, 1234L);
    cache.onFileSystemChange(
        WatchmanPathEvent.of(filesystem.getRootPath(), Kind.CREATE, ForwardRelPath.ofPath(path)));
    assertFalse("Cache should not contain path", cache.getIfPresent(path).isPresent());
    assertThat(
        "Cache should not contain path",
        cache.fileHashCacheEngine.getSizeIfPresent(path),
        nullValue());
  }

  @Test
  public void whenNotifiedOfChangeEventCacheEntryIsRemoved() throws IOException {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    WatchedFileHashCache cache =
        new WatchedFileHashCache(
            filesystem, fileHashCacheMode, false
            /** loggingEnabled */
            );
    Path path = Paths.get("SomeClass.java");
    filesystem.touch(path);

    HashCodeAndFileType value = HashCodeAndFileType.ofFile(HashCode.fromInt(42));
    cache.fileHashCacheEngine.put(path, value);
    cache.fileHashCacheEngine.putSize(path, 1234L);
    cache.onFileSystemChange(
        WatchmanPathEvent.of(filesystem.getRootPath(), Kind.MODIFY, ForwardRelPath.ofPath(path)));
    assertFalse("Cache should not contain path", cache.getIfPresent(path).isPresent());
    assertThat(
        "Cache should not contain path",
        cache.fileHashCacheEngine.getSizeIfPresent(path),
        nullValue());
  }

  @Test
  public void whenNotifiedOfDeleteEventCacheEntryIsRemoved() throws IOException {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    WatchedFileHashCache cache =
        new WatchedFileHashCache(
            filesystem, fileHashCacheMode, false
            /** loggingEnabled */
            );
    Path path = Paths.get("SomeClass.java");
    filesystem.touch(path);

    HashCodeAndFileType value = HashCodeAndFileType.ofFile(HashCode.fromInt(42));
    cache.fileHashCacheEngine.put(path, value);
    cache.fileHashCacheEngine.putSize(path, 1234L);
    cache.onFileSystemChange(
        WatchmanPathEvent.of(filesystem.getRootPath(), Kind.DELETE, ForwardRelPath.ofPath(path)));
    assertFalse("Cache should not contain path", cache.getIfPresent(path).isPresent());
    assertThat(
        "Cache should not contain path",
        cache.fileHashCacheEngine.getSizeIfPresent(path),
        nullValue());
  }

  @Test
  public void directoryHashChangesWhenFileInsideDirectoryChanges() throws IOException {
    ProjectFilesystem filesystem = TestProjectFilesystems.createProjectFilesystem(tmp.getRoot());
    WatchedFileHashCache cache =
        new WatchedFileHashCache(
            filesystem, fileHashCacheMode, false
            /** loggingEnabled */
            );
    tmp.newFolder("foo", "bar");
    AbsPath inputFile = tmp.newFile("foo/bar/baz");
    Files.write(inputFile.getPath(), "Hello world".getBytes(StandardCharsets.UTF_8));

    Path dir = Paths.get("foo/bar");
    HashCode dirHash = cache.get(dir);
    Files.write(inputFile.getPath(), "Goodbye world".getBytes(StandardCharsets.UTF_8));
    cache.onFileSystemChange(
        WatchmanPathEvent.of(
            filesystem.getRootPath(), Kind.MODIFY, ForwardRelPath.ofPath(dir.resolve("baz"))));
    HashCode dirHash2 = cache.get(dir);
    assertNotEquals(dirHash, dirHash2);
  }

  @Test
  public void whenNotifiedOfChangeToSubPathThenDirCacheEntryIsRemoved() throws IOException {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    WatchedFileHashCache cache =
        new WatchedFileHashCache(
            filesystem, fileHashCacheMode, false
            /** loggingEnabled */
            );
    Path dir = Paths.get("foo/bar/baz");
    filesystem.mkdirs(dir);

    HashCodeAndFileType value = HashCodeAndFileType.ofDirectory(HashCode.fromInt(42));
    cache.fileHashCacheEngine.put(dir, value);
    cache.fileHashCacheEngine.putSize(dir, 1234L);
    cache.onFileSystemChange(
        WatchmanPathEvent.of(
            filesystem.getRootPath(), Kind.CREATE, ForwardRelPath.ofPath(dir.resolve("blech"))));
    assertFalse("Cache should not contain path", cache.getIfPresent(dir).isPresent());
    assertThat(
        "Cache should not contain path",
        cache.fileHashCacheEngine.getSizeIfPresent(dir),
        nullValue());
  }

  @Test
  public void whenDirectoryIsPutThenInvalidatedCacheDoesNotContainPathOrChildren()
      throws IOException {
    ProjectFilesystem filesystem = TestProjectFilesystems.createProjectFilesystem(tmp.getRoot());
    WatchedFileHashCache cache =
        new WatchedFileHashCache(
            filesystem, fileHashCacheMode, false
            /** loggingEnabled */
            );

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

    // Trigger an event on the directory.
    cache.onFileSystemChange(
        WatchmanPathEvent.of(filesystem.getRootPath(), Kind.MODIFY, ForwardRelPath.ofPath(dir)));

    assertFalse(cache.getIfPresent(dir).isPresent());
    assertFalse(cache.getIfPresent(child1).isPresent());
    assertFalse(cache.getIfPresent(child2).isPresent());
  }

  @Test
  public void whenNotifiedOfParentChangeEventCacheEntryIsRemoved() throws IOException {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    WatchedFileHashCache cache =
        new WatchedFileHashCache(
            filesystem, fileHashCacheMode, false
            /** loggingEnabled */
            );
    Path parent = filesystem.getPath("directory");
    Path path = parent.resolve("SomeClass.java");
    filesystem.mkdirs(parent);
    filesystem.touch(path);

    HashCodeAndFileType value = HashCodeAndFileType.ofFile(HashCode.fromInt(42));
    cache.fileHashCacheEngine.put(path, value);
    cache.fileHashCacheEngine.putSize(path, 1234L);
    cache.onFileSystemChange(
        WatchmanPathEvent.of(filesystem.getRootPath(), Kind.MODIFY, ForwardRelPath.ofPath(parent)));
    assertFalse("Cache should not contain path", cache.getIfPresent(path).isPresent());
    assertThat(
        "Cache should not contain path",
        cache.fileHashCacheEngine.getSizeIfPresent(path),
        nullValue());
  }

  @Test
  public void thatWillGetIsCorrect() throws IOException {
    ProjectFilesystem filesystem = TestProjectFilesystems.createProjectFilesystem(tmp.getRoot());
    RelPath buckOut = filesystem.getBuckPaths().getBuckOut();
    filesystem.mkdirs(buckOut);
    Path buckOutFile = buckOut.resolve("file.txt");
    Path otherFile = Paths.get("file.txt");
    filesystem.writeContentsToPath("data", buckOutFile);
    filesystem.writeContentsToPath("other data", otherFile);
    WatchedFileHashCache cache =
        new WatchedFileHashCache(
            filesystem, fileHashCacheMode, false
            /** loggingEnabled */
            );
    assertFalse(cache.willGet(filesystem.getPath("buck-out/file.txt")));
    assertTrue(cache.willGet(filesystem.getPath("file.txt")));
  }
}
