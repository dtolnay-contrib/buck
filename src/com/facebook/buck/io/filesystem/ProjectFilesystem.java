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
import com.facebook.buck.core.filesystems.FileName;
import com.facebook.buck.core.filesystems.ForwardRelPath;
import com.facebook.buck.core.filesystems.PathWrapper;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.io.file.PathMatcher;
import com.facebook.buck.util.sha1.Sha1HashCode;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.CopyOption;
import java.nio.file.FileSystem;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.jar.Manifest;

/** An injectable service for interacting with the filesystem relative to the cell root. */
public interface ProjectFilesystem {

  /**
   * @return the default view over this project filesystem, where the view root is the project root,
   *     and no ignored paths.
   */
  ProjectFilesystemView asView();

  AbsPath getRootPath();

  default FileSystem getFileSystem() {
    return getRootPath().getFileSystem();
  }

  ProjectFilesystem createBuckOutProjectFilesystem();

  /**
   * @return details about the delegate suitable for writing to a logger. It is recommended that the
   *     keys of this map are unique in namespace of the things a logger may want to log. Values
   *     must be {@link String}, {@code int}, or {@code boolean}.
   */
  ImmutableMap<String, ?> getDelegateDetails();

  /**
   * @return the specified {@code path} resolved against {@link #getRootPath()} to an absolute path.
   */
  Path resolve(Path path);

  AbsPath resolve(String path);

  /**
   * @return the specified {@code path} resolved against {@link #getRootPath()} to an absolute path.
   */
  default AbsPath resolve(ForwardRelPath path) {
    return AbsPath.of(resolve(path.toPath(getFileSystem())));
  }

  /**
   * @return the specified {@code path} resolved against {@link #getRootPath()} to an absolute path.
   */
  default AbsPath resolve(RelPath path) {
    return AbsPath.of(resolve(path.getPath()));
  }

  /** Construct a relative path between the project root and a given path. */
  RelPath relativize(Path path);

  default RelPath relativize(AbsPath path) {
    return relativize(path.getPath());
  }

  /** @return a set of {@link PathMatcher} objects ignored by {@link #isIgnored(RelPath)} */
  ImmutableSet<PathMatcher> getIgnoredPaths();

  /** @return A {@link ImmutableSet} of {@link PathMatcher} objects to have buck ignore. */
  ImmutableSet<PathMatcher> getIgnoredDirectories();

  Path getPathForRelativePath(Path pathRelativeToProjectRoot);

  default Path getPathForRelativePath(RelPath pathRelativeToProjectRoot) {
    return getPathForRelativePath(pathRelativeToProjectRoot.getPath());
  }

  AbsPath getPathForRelativePath(String pathRelativeToProjectRoot);

  /**
   * @param path Absolute path or path relative to the project root.
   * @return If {@code path} is relative, it is returned. If it is absolute and is inside the
   *     project root, it is relativized to the project root and returned. Otherwise an absent value
   *     is returned.
   */
  Optional<Path> getPathRelativeToProjectRoot(Path path);

  /**
   * As {@link #getPathForRelativePath(Path)}, but with the added twist that the existence of the
   * path is checked before returning.
   */
  Path getPathForRelativeExistingPath(Path pathRelativeToProjectRoot);

  boolean exists(Path pathRelativeToProjectRoot, LinkOption... options);

  default boolean exists(ForwardRelPath pathRelativeToProjectRoot, LinkOption... options) {
    return exists(pathRelativeToProjectRoot.toPath(getFileSystem()), options);
  }

  default boolean exists(RelPath pathRelativeToProjectRoot, LinkOption... options) {
    return exists(pathRelativeToProjectRoot.getPath(), options);
  }

  long getFileSize(Path pathRelativeToProjectRoot) throws IOException;

  /**
   * Deletes a file specified by its path relative to the project root.
   *
   * <p>Ignores the failure if the file does not exist.
   *
   * @param pathRelativeToProjectRoot path to the file
   * @return {@code true} if the file was deleted, {@code false} if it did not exist
   */
  boolean deleteFileAtPathIfExists(Path pathRelativeToProjectRoot) throws IOException;

  /**
   * Deletes a file specified by its path relative to the project root.
   *
   * @param pathRelativeToProjectRoot path to the file
   */
  void deleteFileAtPath(Path pathRelativeToProjectRoot) throws IOException;

  /** Checks whether there is a normal file at the specified path. */
  boolean isFile(Path pathRelativeToProjectRoot, LinkOption... options);

  /** Checks whether there is a normal file at the specified path. */
  default boolean isFile(ForwardRelPath pathRelativeToProjectRoot, LinkOption... options) {
    return isFile(pathRelativeToProjectRoot.toRelPath(getFileSystem()), options);
  }

  default boolean isFile(PathWrapper pathRelativeToProjectRoot, LinkOption... options) {
    return isFile(pathRelativeToProjectRoot.getPath(), options);
  }

  boolean isHidden(Path pathRelativeToProjectRoot) throws IOException;

  /**
   * Similar to {@link #walkFileTree(Path, FileVisitor)} except this takes in a path relative to the
   * project root.
   *
   * <p>This is deprecated. Please use {@link ProjectFilesystemView#walkRelativeFileTree(Path,
   * EnumSet, FileVisitor)} instead.
   */
  @Deprecated
  void walkRelativeFileTree(Path pathRelativeToProjectRoot, FileVisitor<Path> fileVisitor)
      throws IOException;

  /**
   * Similar to {@link #walkFileTree(Path, FileVisitor)} except this takes in a path relative to the
   * project root.
   *
   * <p>This is deprecated. Please use {@link ProjectFilesystemView#walkRelativeFileTree(Path,
   * EnumSet, FileVisitor)} instead.
   */
  @Deprecated
  void walkRelativeFileTree(
      Path pathRelativeToProjectRoot, FileVisitor<Path> fileVisitor, boolean skipIgnored)
      throws IOException;

  /**
   * Walks a project-root relative file tree with a visitor and visit options.
   *
   * <p>This is deprecated. Please use {@link ProjectFilesystemView#walkRelativeFileTree(Path,
   * EnumSet, FileVisitor)} instead.
   */
  @Deprecated
  void walkRelativeFileTree(
      Path pathRelativeToProjectRoot,
      EnumSet<FileVisitOption> visitOptions,
      FileVisitor<Path> fileVisitor,
      boolean skipIgnored)
      throws IOException;

  /**
   * Allows {@link Files#walkFileTree} to be faked in tests.
   *
   * <p>Use {@link ProjectFilesystemView#walkFileTree(Path, Set, FileVisitor)} instead.
   */
  @Deprecated
  void walkFileTree(Path root, FileVisitor<Path> fileVisitor) throws IOException;

  /** Use {@link ProjectFilesystemView#walkFileTree(Path, Set, FileVisitor)} instead */
  @Deprecated
  void walkFileTree(Path root, Set<FileVisitOption> options, FileVisitor<Path> fileVisitor)
      throws IOException;

  /** Use {@link ProjectFilesystemView#getFilesUnderPath(Path, EnumSet)} instead */
  @Deprecated
  ImmutableSet<Path> getFilesUnderPath(Path pathRelativeToProjectRoot) throws IOException;

  /**
   * Use {@link ProjectFilesystemView#getFilesUnderPath(Path, com.google.common.base.Predicate,
   * EnumSet)} instead
   */
  @Deprecated
  ImmutableSet<Path> getFilesUnderPath(Path pathRelativeToProjectRoot, Predicate<Path> predicate)
      throws IOException;

  /**
   * Use {@link ProjectFilesystemView#getFilesUnderPath(Path, com.google.common.base.Predicate,
   * EnumSet)} instead
   */
  @Deprecated
  ImmutableSet<Path> getFilesUnderPath(
      Path pathRelativeToProjectRoot,
      Predicate<Path> predicate,
      EnumSet<FileVisitOption> visitOptions)
      throws IOException;

  /** Allows {@link Files#isDirectory} to be faked in tests. */
  boolean isDirectory(Path child, LinkOption... linkOptions);

  default boolean isDirectory(PathWrapper path, LinkOption... linkOptions) {
    return isDirectory(path.getPath(), linkOptions);
  }

  /** Allows {@link Files#isExecutable} to be faked in tests. */
  boolean isExecutable(Path child);

  /** Use {@link ProjectFilesystemView#getDirectoryContents(Path)} instead */
  @Deprecated
  ImmutableCollection<Path> getDirectoryContents(Path pathToUse) throws IOException;

  /**
   * Returns the files inside {@code pathRelativeToProjectRoot} which match {@code globPattern},
   * ordered in descending last modified time order. This will not obey the results of {@link
   * #isIgnored(RelPath)}.
   */
  ImmutableSortedSet<Path> getMtimeSortedMatchingDirectoryContents(
      Path pathRelativeToProjectRoot, String globPattern) throws IOException;

  FileTime getLastModifiedTime(Path pathRelativeToProjectRoot) throws IOException;

  /** Sets the last modified time for the given path. */
  Path setLastModifiedTime(Path pathRelativeToProjectRoot, FileTime time) throws IOException;

  /** Sets the last modified time for the given path. */
  default void setLastModifiedTime(PathWrapper pathRelativeToProjectRoot, FileTime time)
      throws IOException {
    setLastModifiedTime(pathRelativeToProjectRoot.getPath(), time);
  }

  /**
   * Recursively delete everything under the specified path. Ignore the failure if the file at the
   * specified path does not exist.
   */
  void deleteRecursivelyIfExists(Path pathRelativeToProjectRoot) throws IOException;

  /**
   * Recursively delete everything under the specified path. Ignore the failure if the file at the
   * specified path does not exist.
   */
  default void deleteRecursivelyIfExists(PathWrapper pathRelativeToProjectRoot) throws IOException {
    deleteRecursivelyIfExists(pathRelativeToProjectRoot.getPath());
  }

  /**
   * Resolves the relative path against the project root and then calls {@link
   * Files#createDirectories(Path, FileAttribute[])}
   */
  void mkdirs(Path pathRelativeToProjectRoot) throws IOException;

  /**
   * Resolves the relative path against the project root and then calls {@link
   * Files#createDirectories(Path, FileAttribute[])}
   */
  default void mkdirs(PathWrapper pathRelativeToProjectRoot) throws IOException {
    mkdirs(pathRelativeToProjectRoot.getPath());
  }

  /** Creates a new file relative to the project root. */
  Path createNewFile(Path pathRelativeToProjectRoot) throws IOException;

  /**
   * @deprecated Prefer operating on {@code Path}s directly, replaced by {@link
   *     #createParentDirs(Path)}.
   */
  @Deprecated
  void createParentDirs(String pathRelativeToProjectRoot) throws IOException;

  /**
   * @param pathRelativeToProjectRoot Must identify a file, not a directory. (Unfortunately, we have
   *     no way to assert this because the path is not expected to exist yet.)
   */
  void createParentDirs(Path pathRelativeToProjectRoot) throws IOException;

  /**
   * @param pathRelativeToProjectRoot Must identify a file, not a directory. (Unfortunately, we have
   *     no way to assert this because the path is not expected to exist yet.)
   */
  default void createParentDirs(PathWrapper pathRelativeToProjectRoot) throws IOException {
    createParentDirs(pathRelativeToProjectRoot.getPath());
  }

  /**
   * Writes each line in {@code lines} with a trailing newline to a file at the specified path.
   *
   * <p>The parent path of {@code pathRelativeToProjectRoot} must exist.
   */
  void writeLinesToPath(
      Iterable<String> lines, Path pathRelativeToProjectRoot, FileAttribute<?>... attrs)
      throws IOException;

  /** Rel-path version of {@link #writeLinesToPath(Iterable, Path, FileAttribute[])}. */
  default void writeLinesToPath(
      Iterable<String> lines, RelPath pathRelativeToProjectRoot, FileAttribute<?>... attrs)
      throws IOException {
    writeLinesToPath(lines, pathRelativeToProjectRoot.getPath(), attrs);
  }

  void writeContentsToPath(
      String contents, Path pathRelativeToProjectRoot, FileAttribute<?>... attrs)
      throws IOException;

  default void writeContentsToPath(
      String contents, PathWrapper pathRelativeToProjectRoot, FileAttribute<?>... attrs)
      throws IOException {
    writeContentsToPath(contents, pathRelativeToProjectRoot.getPath(), attrs);
  }

  void writeBytesToPath(byte[] bytes, Path pathRelativeToProjectRoot, FileAttribute<?>... attrs)
      throws IOException;

  OutputStream newFileOutputStream(Path pathRelativeToProjectRoot, FileAttribute<?>... attrs)
      throws IOException;

  OutputStream newFileOutputStream(
      Path pathRelativeToProjectRoot, boolean append, FileAttribute<?>... attrs) throws IOException;

  OutputStream newUnbufferedFileOutputStream(
      Path pathRelativeToProjectRoot, boolean append, FileAttribute<?>... attrs) throws IOException;

  <A extends BasicFileAttributes> A readAttributes(
      Path pathRelativeToProjectRoot, Class<A> type, LinkOption... options) throws IOException;

  InputStream newFileInputStream(Path pathRelativeToProjectRoot) throws IOException;

  default InputStream newFileInputStream(PathWrapper pathRelativeToProjectRoot) throws IOException {
    return newFileInputStream(pathRelativeToProjectRoot.getPath());
  }

  /** @param inputStream Source of the bytes. This method does not close this stream. */
  void copyToPath(InputStream inputStream, Path pathRelativeToProjectRoot, CopyOption... options)
      throws IOException;

  /** Copies a file to an output stream. */
  void copyToOutputStream(Path pathRelativeToProjectRoot, OutputStream out) throws IOException;

  Optional<String> readFileIfItExists(Path pathRelativeToProjectRoot);

  default Optional<String> readFileIfItExists(PathWrapper pathRelativeToProjectRoot) {
    return readFileIfItExists(pathRelativeToProjectRoot.getPath());
  }

  /**
   * Attempts to read the first line of the file specified by the relative path. If the file does
   * not exist, is empty, or encounters an error while being read, {@link Optional#empty()} is
   * returned. Otherwise, an {@link Optional} with the first line of the file will be returned.
   *
   * <p>@deprecated PRefero operation on {@code Path}s directly, replaced by {@link
   * #readFirstLine(Path)}
   */
  Optional<String> readFirstLine(String pathRelativeToProjectRoot);

  /**
   * Attempts to read the first line of the file specified by the relative path. If the file does
   * not exist, is empty, or encounters an error while being read, {@link Optional#empty()} is
   * returned. Otherwise, an {@link Optional} with the first line of the file will be returned.
   */
  Optional<String> readFirstLine(Path pathRelativeToProjectRoot);

  /**
   * Attempts to read the first line of the specified file. If the file does not exist, is empty, or
   * encounters an error while being read, {@link Optional#empty()} is returned. Otherwise, an
   * {@link Optional} with the first line of the file will be returned.
   */
  Optional<String> readFirstLineFromFile(Path file);

  List<String> readLines(Path pathRelativeToProjectRoot) throws IOException;

  /**
   * @deprecated Prefer operation on {@code Path}s directly, replaced by {@link
   *     Files#newInputStream(Path, OpenOption...)}.
   */
  @Deprecated
  InputStream getInputStreamForRelativePath(Path path) throws IOException;

  Sha1HashCode computeSha1(Path pathRelativeToProjectRootOrJustAbsolute) throws IOException;

  String computeSha256(Path pathRelativeToProjectRoot) throws IOException;

  void copy(Path source, Path target, CopySourceMode sourceMode) throws IOException;

  void move(Path source, Path target, CopyOption... options) throws IOException;

  /** Moves the children of {@code source} into {@code target}, merging the two directories. */
  void mergeChildren(Path source, Path target, CopyOption... options) throws IOException;

  void copyFolder(Path source, Path target) throws IOException;

  void copyFile(Path source, Path target) throws IOException;

  void createSymLink(Path symLink, Path realFile, boolean force) throws IOException;

  default void createSymLink(PathWrapper symLink, Path realFile, boolean force) throws IOException {
    createSymLink(symLink.getPath(), realFile, force);
  }

  /**
   * Returns the set of POSIX file permissions, or the empty set if the underlying file system does
   * not support POSIX file attributes.
   */
  Set<PosixFilePermission> getPosixFilePermissions(Path path) throws IOException;

  /** Returns true if the file under {@code path} exists and is a symbolic link, false otherwise. */
  boolean isSymLink(Path path);

  /** Returns true if the file under {@code path} exists and is a symbolic link, false otherwise. */
  default boolean isSymLink(PathWrapper path) {
    return isSymLink(path.getPath());
  }

  /** Returns the target of the specified symbolic link. */
  Path readSymLink(Path path) throws IOException;

  /** Returns the target of the specified symbolic link. */
  default Path readSymLink(RelPath path) throws IOException {
    return readSymLink(path.getPath());
  }

  Manifest getJarManifest(Path path) throws IOException;

  /**
   * getPosixFileMode returns a number corresponding to stat()'s ST_MODE, for use when constructing
   * tar archives.
   */
  // TODO: the return value should fit in 'int'; prove it is so and change the return type to int.
  long getPosixFileMode(Path path) throws IOException;

  /**
   * getFileAttributesForZipEntry is similar to getPosixFileMode, but returns a number adjusted for
   * use in zip files.
   */
  long getFileAttributesForZipEntry(Path path) throws IOException;

  BuckPaths getBuckPaths();

  /**
   * Use {@link ProjectFilesystemView#isIgnored(Path)} instead
   *
   * @param path the path to check.
   * @return whether ignoredPaths contains path or any of its ancestors.
   */
  @Deprecated
  boolean isIgnored(RelPath path);

  /**
   * Use {@link ProjectFilesystemView#isIgnored(Path)} instead
   *
   * @param path the path to check.
   * @return whether ignoredPaths contains path or any of its ancestors.
   */
  @Deprecated
  default boolean isIgnored(ForwardRelPath path) {
    return isIgnored(path.toRelPath(getFileSystem()));
  }

  /**
   * Returns a relative path whose parent directory is guaranteed to exist. The path will be under
   * {@code buck-out}, so it is safe to write to.
   */
  Path createTempFile(String prefix, String suffix, FileAttribute<?>... attrs) throws IOException;

  /**
   * Prefer {@link #createTempFile(String, String, FileAttribute[])} so that temporary files are
   * guaranteed to be created under {@code buck-out}. This method will be deprecated once t12079608
   * is resolved.
   */
  Path createTempFile(Path directory, String prefix, String suffix, FileAttribute<?>... attrs)
      throws IOException;

  /**
   * Prefer {@link #createTempFile(String, String, FileAttribute[])} so that temporary files are
   * guaranteed to be created under {@code buck-out}. This method will be deprecated once t12079608
   * is resolved.
   */
  default Path createTempFile(
      PathWrapper directory, String prefix, String suffix, FileAttribute<?>... attrs)
      throws IOException {
    return createTempFile(directory.getPath(), prefix, suffix, attrs);
  }

  void touch(Path fileToTouch) throws IOException;

  /**
   * Converts a path string (or sequence of strings) to a Path with the same VFS as this instance.
   *
   * @see FileSystem#getPath(String, String...)
   */
  Path getPath(String first, String... rest);

  /**
   * Converts a path string (or sequence of strings) to a Path with the same VFS as this instance.
   *
   * @see FileSystem#getPath(String, String...)
   */
  default Path getPath(FileName fileName) {
    return getPath(fileName.getName());
  }
}
