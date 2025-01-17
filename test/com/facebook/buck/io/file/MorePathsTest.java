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

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.io.filesystem.MoreProjectFilesystems;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.stream.RichStream;
import com.facebook.buck.util.types.Pair;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(JUnitParamsRunner.class)
public class MorePathsTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void testGetRelativePath() {
    // Path on base directory.
    assertEquals(
        RelPath.get("file"), MorePaths.getRelativePath(RelPath.get("file"), RelPath.get("")));

    // Path on base directory (using null).
    assertEquals(RelPath.get("file"), MorePaths.getRelativePath(RelPath.get("file"), null));

    // Path internal to base directory.
    assertEquals(
        RelPath.get("dir/file"),
        MorePaths.getRelativePath(RelPath.get("base/dir/file"), RelPath.get("base")));

    // Path external to base directory.
    assertEquals(
        RelPath.get("../dir1/file"),
        MorePaths.getRelativePath(RelPath.get("dir1/file"), RelPath.get("base")));
  }

  @Test
  public void testFilterForSubpaths() {
    AbsPath root = tmp.getRoot();
    ImmutableSet<Path> paths =
        RichStream.of(
                ".buckd",
                "foo/bar",
                root + "/buck-cache",
                Paths.get("/root/not/in/test").toAbsolutePath().toString())
            .map(Paths::get)
            .toImmutableSet();

    assertEquals(
        "Set should have been filtered down to paths contained under root.",
        ImmutableSet.of(Paths.get(".buckd"), Paths.get("foo/bar"), Paths.get("buck-cache")),
        MorePaths.filterForSubpaths(paths, root.getPath()));
  }

  @Test
  public void testCreateRelativeSymlinkToFilesInRoot() throws IOException {
    ProjectFilesystem projectFilesystem =
        TestProjectFilesystems.createProjectFilesystem(tmp.getRoot());
    tmp.newFile("biz.txt");

    RelPath pathToDesiredLinkUnderProjectRoot = RelPath.get("gamma.txt");
    RelPath pathToExistingFileUnderProjectRoot = RelPath.get("biz.txt");
    RelPath relativePath =
        MoreProjectFilesystems.createRelativeSymlink(
            pathToDesiredLinkUnderProjectRoot,
            pathToExistingFileUnderProjectRoot,
            projectFilesystem);

    AbsPath absolutePathToDesiredLinkUnderProjectRoot =
        projectFilesystem.resolve(pathToDesiredLinkUnderProjectRoot);
    assertTrue(Files.isSymbolicLink(absolutePathToDesiredLinkUnderProjectRoot.getPath()));

    Path targetOfSymbolicLink =
        Files.readSymbolicLink(absolutePathToDesiredLinkUnderProjectRoot.getPath());

    validateSymlinklTarget(
        pathToExistingFileUnderProjectRoot,
        pathToDesiredLinkUnderProjectRoot,
        absolutePathToDesiredLinkUnderProjectRoot,
        targetOfSymbolicLink,
        relativePath);

    AbsPath absolutePathToExistingFileUnderProjectRoot =
        projectFilesystem.resolve(pathToExistingFileUnderProjectRoot);
    Files.write(absolutePathToExistingFileUnderProjectRoot.getPath(), "Hello, World!".getBytes());
    String dataReadFromSymlink =
        new String(Files.readAllBytes(absolutePathToDesiredLinkUnderProjectRoot.getPath()));
    assertEquals("Hello, World!", dataReadFromSymlink);
  }

  @Test
  public void testCreateRelativeSymlinkToFileInRoot() throws IOException {
    ProjectFilesystem projectFilesystem =
        TestProjectFilesystems.createProjectFilesystem(tmp.getRoot());
    tmp.newFile("biz.txt");

    tmp.newFolder("alpha", "beta");
    RelPath pathToDesiredLinkUnderProjectRoot = RelPath.get("alpha/beta/gamma.txt");
    RelPath pathToExistingFileUnderProjectRoot = RelPath.get("biz.txt");
    RelPath relativePath =
        MoreProjectFilesystems.createRelativeSymlink(
            pathToDesiredLinkUnderProjectRoot,
            pathToExistingFileUnderProjectRoot,
            projectFilesystem);

    AbsPath absolutePathToDesiredLinkUnderProjectRoot =
        projectFilesystem.resolve(pathToDesiredLinkUnderProjectRoot);
    assertTrue(Files.isSymbolicLink(absolutePathToDesiredLinkUnderProjectRoot.getPath()));

    Path targetOfSymbolicLink =
        Files.readSymbolicLink(absolutePathToDesiredLinkUnderProjectRoot.getPath());

    validateSymlinklTarget(
        pathToExistingFileUnderProjectRoot,
        pathToDesiredLinkUnderProjectRoot,
        absolutePathToDesiredLinkUnderProjectRoot,
        targetOfSymbolicLink,
        relativePath);

    AbsPath absolutePathToExistingFileUnderProjectRoot =
        projectFilesystem.resolve(pathToExistingFileUnderProjectRoot);
    Files.write(absolutePathToExistingFileUnderProjectRoot.getPath(), "Hello, World!".getBytes());
    String dataReadFromSymlink =
        new String(Files.readAllBytes(absolutePathToDesiredLinkUnderProjectRoot.getPath()));
    assertEquals("Hello, World!", dataReadFromSymlink);
  }

  @Test
  public void testCreateRelativeSymlinkToFilesOfVaryingDepth() throws IOException {
    ProjectFilesystem projectFilesystem =
        TestProjectFilesystems.createProjectFilesystem(tmp.getRoot());
    tmp.newFolder("foo", "bar", "baz");
    tmp.newFile("foo/bar/baz/biz.txt");

    tmp.newFolder("alpha", "beta");
    RelPath pathToDesiredLinkUnderProjectRoot = RelPath.get("alpha/beta/gamma.txt");
    RelPath pathToExistingFileUnderProjectRoot = RelPath.get("foo/bar/baz/biz.txt");
    RelPath relativePath =
        MoreProjectFilesystems.createRelativeSymlink(
            pathToDesiredLinkUnderProjectRoot,
            pathToExistingFileUnderProjectRoot,
            projectFilesystem);

    AbsPath absolutePathToDesiredLinkUnderProjectRoot =
        projectFilesystem.resolve(pathToDesiredLinkUnderProjectRoot);
    assertTrue(Files.isSymbolicLink(absolutePathToDesiredLinkUnderProjectRoot.getPath()));

    Path targetOfSymbolicLink =
        Files.readSymbolicLink(absolutePathToDesiredLinkUnderProjectRoot.getPath());

    validateSymlinklTarget(
        pathToExistingFileUnderProjectRoot,
        pathToDesiredLinkUnderProjectRoot,
        absolutePathToDesiredLinkUnderProjectRoot,
        targetOfSymbolicLink,
        relativePath);

    AbsPath absolutePathToExistingFileUnderProjectRoot =
        projectFilesystem.resolve(pathToExistingFileUnderProjectRoot);
    Files.write(absolutePathToExistingFileUnderProjectRoot.getPath(), "Hello, World!".getBytes());
    String dataReadFromSymlink =
        new String(Files.readAllBytes(absolutePathToDesiredLinkUnderProjectRoot.getPath()));
    assertEquals("Hello, World!", dataReadFromSymlink);
  }

  @Test
  public void testExpandHomeDir() {
    Path homeDir = Paths.get(System.getProperty("user.home"));
    assertEquals(
        "Must expand home dir.",
        homeDir.resolve("foo"),
        MorePaths.expandHomeDir(Paths.get("~/foo")));

    assertEquals(
        "Must expand home dir by itself too.", homeDir, MorePaths.expandHomeDir(Paths.get("~")));

    Path relativePath = Paths.get("foo/bar");
    assertEquals(
        "Must not expand relative paths.", relativePath, MorePaths.expandHomeDir(relativePath));

    Path absolutePath = Paths.get("/foo/bar");
    assertEquals(
        "Must not expand absolute paths.", absolutePath, MorePaths.expandHomeDir(absolutePath));

    Path funnyHomePath = Paths.get("~jacko/foo");
    assertEquals(
        "Must only expand home paths starting with ~/",
        funnyHomePath,
        MorePaths.expandHomeDir(funnyHomePath));
  }

  @Test
  public void relativizeWithDotPathDoesNotAddExtraDotDotPath() {
    // Ensure workaround for bug JDK-6925169 for "." case.
    Path p1 = Paths.get("./a/b");
    Path p2 = Paths.get("c/d/e");
    assertThat(MorePaths.relativize(p1, p2), equalTo(Paths.get("../../c/d/e")));
  }

  @Test
  public void relativizeWithDotDotPathDoesNotAddExtraDotDotPath() {
    // Ensure workaround for bug JDK-6925169 for ".." case.
    Path p1 = Paths.get("a/../b");
    Path p2 = Paths.get("c/d/e");
    assertThat(MorePaths.relativize(p1, p2), equalTo(Paths.get("../c/d/e")));
  }

  @Test
  public void relativizeWithEmptyPath() {
    // Ensure workaround for Windows Path relativize bug
    Path p1 = Paths.get("");
    Path p2 = Paths.get("foo");
    assertThat(MorePaths.relativize(p1, p2), equalTo(Paths.get("foo")));
  }

  @Test
  @Parameters({
    ",,",
    ",a,a",
    "a,,..",
    "a,a,",
    "a/b/c,a/d/e,../../d/e",
    "a/b,a,..",
    "../a,b,../../b",
    "a,../b,../../b",
    "/a/b/c,/a/b/d,../d"
  })
  public void relativizeWithDotDotSupport(String basePath, String childPath, String expected) {
    assertThat(
        MorePaths.relativizeWithDotDotSupport(Paths.get(basePath), Paths.get(childPath)),
        equalTo(Paths.get(expected)));
  }

  @Test
  public void normalizeWithEmptyPath() {
    // Ensure workaround for Java Path normalize bug
    Path emptyPath = Paths.get("");
    assertThat(MorePaths.normalize(emptyPath), equalTo(emptyPath));
  }

  @Test
  public void fixPathCommonTest() {
    Path inputPath = Paths.get("subdir/subdir2/foo/bar/x.file");
    Path expecting = Paths.get("subdir/subdir2/foo/bar/x.file");
    assertEquals(expecting, MorePaths.fixPath(inputPath));
  }

  @Test
  public void fixPathAbsoluteTest() {
    Path inputPath = Paths.get("/subdir/subdir2/foo/bar/x.file");
    Path expecting = Paths.get("/subdir/subdir2/foo/bar/x.file");
    assertEquals(expecting, MorePaths.fixPath(inputPath));
  }

  @Test
  public void fixPathEmptyPartTest() {
    Path inputPath = Paths.get("subdir/subdir2//foo///bar/////x.file");
    Path expecting = Paths.get("subdir/subdir2/foo/bar/x.file");
    assertEquals(expecting, MorePaths.fixPath(inputPath));
  }

  @Test
  public void fixPathDotPartTest() {
    Path inputPath = Paths.get("subdir/subdir2/./foo/././bar/./x.file");
    Path expecting = Paths.get("subdir/subdir2/foo/bar/x.file");
    assertEquals(expecting, MorePaths.fixPath(inputPath));
  }

  @Test
  public void fixPathDotDotPartTest() {
    Path inputPath = Paths.get("subdir/subdir2/foo/../bar/x.file");
    Path expecting = Paths.get("subdir/subdir2/foo/../bar/x.file");
    // should be the same!  Does not fully "normalize" path.
    assertEquals(expecting, MorePaths.fixPath(inputPath));
  }

  @Test
  public void getNameWithoutExtension() {
    Path inputPath = Paths.get("subdir/subdir2/bar/x.file");
    assertEquals("x", MorePaths.getNameWithoutExtension(inputPath));
  }

  @Test
  public void splitOnCommonPrefixAbsolute() {
    Path first = Paths.get("/test/a/b/c");
    Path second = Paths.get("/test/b/c");
    Path third = Paths.get("/test/a/d");

    Optional<Pair<Path, ImmutableList<Path>>> result =
        MorePaths.splitOnCommonPrefix(ImmutableList.of(first, second, third));

    assertTrue(result.isPresent());
    assertEquals(Paths.get("/test"), result.get().getFirst());
    assertEquals(
        ImmutableList.of(Paths.get("a/b/c"), Paths.get("b/c"), Paths.get("a/d")),
        result.get().getSecond());
  }

  @Test
  public void splitOnCommonPrefixAbsoluteNoCommon() {
    Path first = Paths.get("/a/b/c");
    Path second = Paths.get("/b/c");
    Path third = Paths.get("/a/d");

    Optional<Pair<Path, ImmutableList<Path>>> result =
        MorePaths.splitOnCommonPrefix(ImmutableList.of(first, second, third));

    assertTrue(result.isPresent());
    assertEquals(Paths.get("/"), result.get().getFirst());
    assertEquals(
        ImmutableList.of(Paths.get("a/b/c"), Paths.get("b/c"), Paths.get("a/d")),
        result.get().getSecond());
  }

  @Test
  public void splitOnCommonPrefixRelative() {
    Path first = Paths.get("test/a/b/c");
    Path second = Paths.get("test/b/c");
    Path third = Paths.get("test/a/d");

    Optional<Pair<Path, ImmutableList<Path>>> result =
        MorePaths.splitOnCommonPrefix(ImmutableList.of(first, second, third));

    assertTrue(result.isPresent());
    assertEquals(Paths.get("test"), result.get().getFirst());
    assertEquals(
        ImmutableList.of(Paths.get("a/b/c"), Paths.get("b/c"), Paths.get("a/d")),
        result.get().getSecond());
  }

  @Test
  public void splitOnCommonPrefixRelativeNoCommon() {
    Path first = Paths.get("a/b/c");
    Path second = Paths.get("b/c");
    Path third = Paths.get("a/d");

    Optional<Pair<Path, ImmutableList<Path>>> result =
        MorePaths.splitOnCommonPrefix(ImmutableList.of(first, second, third));

    assertTrue(result.isPresent());
    assertEquals(Paths.get(""), result.get().getFirst());
    assertEquals(
        ImmutableList.of(Paths.get("a/b/c"), Paths.get("b/c"), Paths.get("a/d")),
        result.get().getSecond());
  }

  @Test
  public void splitOnCommonPrefixMixedFails() {
    Path first = Paths.get("/test/a/b/c");
    Path second = Paths.get("b/c");
    Path third = Paths.get("a/d");

    Optional<Pair<Path, ImmutableList<Path>>> result =
        MorePaths.splitOnCommonPrefix(ImmutableList.of(first, second, third));

    assertFalse(result.isPresent());
  }

  @Test
  @Parameters({
    "C:/some/path",
    "D:\\other\\path",
  })
  public void windowsLongPathStringHasCorrectPrefix(String testPathString) {
    Assume.assumeThat(Platform.detect(), is(Platform.WINDOWS));
    String uncPrefix = "\\\\?\\";

    AbsPath path = AbsPath.of(Paths.get(testPathString));
    String longPathString = MorePaths.getWindowsLongPathString(path);
    assertTrue(longPathString.startsWith(uncPrefix));
  }

  @Test
  @Parameters({
    "C:/some/path",
    "D:\\other\\path",
  })
  public void windowsLongPathStringIsAbsolute(String testPathString) {
    Assume.assumeThat(Platform.detect(), is(Platform.WINDOWS));
    String uncPrefix = "\\\\?\\";

    AbsPath path = AbsPath.of(Paths.get(testPathString));
    String longPathString = MorePaths.getWindowsLongPathString(path);
    Path pathComponent = Paths.get(longPathString.substring(uncPrefix.length()));
    assertTrue(pathComponent.isAbsolute());
  }

  private void validateSymlinklTarget(
      RelPath pathToExistingFileUnderProjectRoot,
      RelPath pathToDesiredLinkUnderProjectRoot,
      AbsPath absolutePathToDesiredLinkUnderProjectRoot,
      Path targetOfSymbolicLink,
      RelPath relativePath) {
    if (Platform.detect() == Platform.WINDOWS) {
      // On Windows Files.readSymbolicLink returns the absolute path to the target.
      RelPath relToLinkTargetPath =
          MorePaths.getRelativePath(
              pathToExistingFileUnderProjectRoot, pathToDesiredLinkUnderProjectRoot.getParent());
      AbsPath absolutePathToTargetUnderProjectRoot =
          absolutePathToDesiredLinkUnderProjectRoot
              .getParent()
              .resolve(relToLinkTargetPath)
              .normalize();
      assertEquals(absolutePathToTargetUnderProjectRoot, AbsPath.of(targetOfSymbolicLink));
    } else {
      assertEquals(relativePath, RelPath.of(targetOfSymbolicLink));
    }
  }

  @Test
  public void stripSuffix() {
    assertThat(
        MorePaths.stripSuffix(Paths.get("a/b/c"), Paths.get("c")),
        equalTo(Optional.of(Paths.get("a/b"))));
    assertThat(
        MorePaths.stripSuffix(Paths.get("a/b/c"), Paths.get("b/c")),
        equalTo(Optional.of(Paths.get("a"))));
    assertThat(
        MorePaths.stripSuffix(Paths.get("a/b/c"), Paths.get("a/b/c")),
        equalTo(Optional.of(Paths.get(""))));
    assertThat(
        MorePaths.stripSuffix(Paths.get("a/b/c"), Paths.get("d")), equalTo(Optional.empty()));
    assertThat(
        MorePaths.stripSuffix(Paths.get("a/b/c"), Paths.get("z/a/b/c")), equalTo(Optional.empty()));
  }
}
