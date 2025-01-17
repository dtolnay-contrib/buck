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

package com.facebook.buck.io.filesystem.impl;

import static com.facebook.buck.util.string.MoreStrings.withoutSuffix;

import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.io.file.FakeFileAttributes;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.file.MostFiles;
import com.facebook.buck.io.filesystem.BuckPaths;
import com.facebook.buck.io.filesystem.CopySourceMode;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.util.config.RecursivePathSetting;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.sha1.Sha1HashCode;
import com.facebook.buck.util.timing.Clock;
import com.facebook.buck.util.timing.FakeClock;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Ordering;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import com.google.common.io.ByteStreams;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotLinkException;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import javax.annotation.Nullable;

// TODO(natthu): Implement methods that throw UnsupportedOperationException.
public class FakeProjectFilesystem extends DefaultProjectFilesystem {

  private static final Random RANDOM = new Random();
  private static final AbsPath DEFAULT_ROOT =
      AbsPath.of(Paths.get(".").toAbsolutePath()).normalize();

  private static final BasicFileAttributes DEFAULT_FILE_ATTRIBUTES =
      new BasicFileAttributes() {
        @Override
        @Nullable
        public FileTime lastModifiedTime() {
          return null;
        }

        @Override
        @Nullable
        public FileTime lastAccessTime() {
          return null;
        }

        @Override
        @Nullable
        public FileTime creationTime() {
          return null;
        }

        @Override
        public boolean isRegularFile() {
          return true;
        }

        @Override
        public boolean isDirectory() {
          return false;
        }

        @Override
        public boolean isSymbolicLink() {
          return false;
        }

        @Override
        public boolean isOther() {
          return false;
        }

        @Override
        public long size() {
          return 0;
        }

        @Override
        @Nullable
        public Object fileKey() {
          return null;
        }
      };

  private static final BasicFileAttributes DEFAULT_DIR_ATTRIBUTES =
      new BasicFileAttributes() {
        @Override
        @Nullable
        public FileTime lastModifiedTime() {
          return null;
        }

        @Override
        @Nullable
        public FileTime lastAccessTime() {
          return null;
        }

        @Override
        @Nullable
        public FileTime creationTime() {
          return null;
        }

        @Override
        public boolean isRegularFile() {
          return false;
        }

        @Override
        public boolean isDirectory() {
          return true;
        }

        @Override
        public boolean isSymbolicLink() {
          return false;
        }

        @Override
        public boolean isOther() {
          return false;
        }

        @Override
        public long size() {
          return 0;
        }

        @Override
        @Nullable
        public Object fileKey() {
          return null;
        }
      };

  private final Map<Path, byte[]> fileContents;
  private final Map<Path, ImmutableSet<FileAttribute<?>>> fileAttributes;
  private final Map<Path, FileTime> fileLastModifiedTimes;
  private final Map<Path, Path> symLinks;
  private final Set<Path> directories;
  private final Clock clock;

  // Generally, tests don't care whether files exist.
  private boolean ignoreValidityOfPaths = true;

  /**
   * @return A project filesystem in a temp directory that will be deleted recursively on jvm exit.
   */
  public static ProjectFilesystem createRealTempFilesystem() {
    AbsPath tempDir;
    try {
      tempDir = AbsPath.of(Files.createTempDirectory("pfs")).toRealPath();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  try {
                    MostFiles.deleteRecursively(tempDir.getPath());
                  } catch (IOException e) { // NOPMD
                    // Swallow. At least we tried, right?
                  }
                }));
    return new FakeProjectFilesystem(tempDir);
  }

  public static ProjectFilesystem createJavaOnlyFilesystem() {
    return createJavaOnlyFilesystem("/opt/src/buck");
  }

  public static ProjectFilesystem createJavaOnlyFilesystem(String rootPath) {
    boolean isWindows = Platform.detect() == Platform.WINDOWS;

    Configuration configuration = isWindows ? Configuration.windows() : Configuration.unix();
    rootPath = isWindows && !rootPath.startsWith("C:") ? "C:" + rootPath : rootPath;

    FileSystem vfs =
        Jimfs.newFileSystem(configuration.toBuilder().setAttributeViews("basic", "posix").build());

    AbsPath root = AbsPath.of(vfs.getPath(rootPath));
    try {
      Files.createDirectories(root.getPath());
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    return new DefaultProjectFilesystem(
        CanonicalCellName.rootCell(),
        root,
        new DefaultProjectFilesystemDelegate(root.getPath(), Optional.empty()),
        TestProjectFilesystems.BUCK_OUT_INCLUDE_TARGET_CONFIG_HASH_FOR_TEST) {
      @Override
      public Path resolve(Path path) {
        // Avoid resolving paths from different Java FileSystems.
        return resolve(path.toString()).getPath();
      }
    };
  }

  public static ProjectFilesystem createFilesystemWithTargetConfigHashInBuckPaths(
      boolean buckOutIncludeTargetConfigHash) {
    AbsPath root = DEFAULT_ROOT;
    return new DefaultProjectFilesystem(
        root,
        ImmutableSet.of(),
        BuckPaths.createDefaultBuckPaths(
            CanonicalCellName.rootCell(),
            root.getPath(),
            buckOutIncludeTargetConfigHash,
            RecursivePathSetting.<Boolean>builder().build()),
        new DefaultProjectFilesystemDelegate(root.getPath(), Optional.empty()));
  }

  public FakeProjectFilesystem() {
    this(CanonicalCellName.rootCell(), DEFAULT_ROOT);
  }

  public FakeProjectFilesystem(CanonicalCellName cellName) {
    this(cellName, DEFAULT_ROOT);
  }

  public FakeProjectFilesystem(AbsPath root) {
    this(CanonicalCellName.rootCell(), root);
  }

  public FakeProjectFilesystem(Path root) {
    this(AbsPath.of(root));
  }

  public FakeProjectFilesystem(CanonicalCellName cellName, AbsPath root) {
    this(FakeClock.doNotCare(), cellName, root, ImmutableSet.of());
  }

  public FakeProjectFilesystem(CanonicalCellName cellName, Path root) {
    this(cellName, AbsPath.of(root));
  }

  public FakeProjectFilesystem(Clock clock) {
    this(clock, CanonicalCellName.rootCell(), DEFAULT_ROOT, ImmutableSet.of());
  }

  public FakeProjectFilesystem(Set<Path> files) {
    this(CanonicalCellName.rootCell(), files);
  }

  public FakeProjectFilesystem(CanonicalCellName cellName, Set<Path> files) {
    this(FakeClock.doNotCare(), cellName, DEFAULT_ROOT, files);
  }

  public FakeProjectFilesystem(
      Clock clock, CanonicalCellName cellName, AbsPath root, Set<Path> files) {
    // For testing, we always use a DefaultProjectFilesystemDelegate so that the logic being
    // exercised is always the same, even if a test using FakeProjectFilesystem is used on EdenFS.
    super(
        cellName,
        root,
        new DefaultProjectFilesystemDelegate(root.getPath(), Optional.empty()),
        TestProjectFilesystems.BUCK_OUT_INCLUDE_TARGET_CONFIG_HASH_FOR_TEST);
    // We use LinkedHashMap to preserve insertion order, so the
    // behavior of this test is consistent across versions. (It also lets
    // us write tests which explicitly test iterating over entries in
    // different orders.)
    fileContents = new ConcurrentHashMap<>();
    fileLastModifiedTimes = new ConcurrentHashMap<>();
    FileTime modifiedTime = FileTime.fromMillis(clock.currentTimeMillis());
    for (Path file : files) {
      fileContents.put(file, new byte[0]);
      fileLastModifiedTimes.put(file, modifiedTime);
    }
    fileAttributes = new ConcurrentHashMap<>();
    symLinks = new ConcurrentHashMap<>();
    directories = new ConcurrentSkipListSet<>();
    directories.add(Paths.get(""));
    for (Path file : files) {
      Path dir = file.getParent();
      while (dir != null) {
        directories.add(dir);
        dir = dir.getParent();
      }
    }
    this.clock = Objects.requireNonNull(clock);
  }

  @Override
  public FakeProjectFilesystem clone() throws CloneNotSupportedException {
    return (FakeProjectFilesystem) super.clone();
  }

  @Override
  protected boolean shouldVerifyConstructorArguments() {
    return false;
  }

  @Override
  protected boolean shouldIgnoreValidityOfPaths() {
    return ignoreValidityOfPaths;
  }

  public FakeProjectFilesystem setIgnoreValidityOfPaths(boolean shouldIgnore) {
    this.ignoreValidityOfPaths = shouldIgnore;
    return this;
  }

  public byte[] getFileBytes(Path path) {
    return Objects.requireNonNull(fileContents.get(MorePaths.normalize(path)));
  }

  private void rmFile(Path path) {
    fileContents.remove(MorePaths.normalize(path));
    fileAttributes.remove(MorePaths.normalize(path));
    fileLastModifiedTimes.remove(MorePaths.normalize(path));
  }

  public ImmutableSet<FileAttribute<?>> getFileAttributesAtPath(Path path) {
    return Objects.requireNonNull(fileAttributes.get(path));
  }

  public void clear() {
    fileContents.clear();
    fileAttributes.clear();
    fileLastModifiedTimes.clear();
    symLinks.clear();
    directories.clear();
  }

  public BasicFileAttributes readBasicAttributes(Path pathRelativeToProjectRoot)
      throws IOException {
    if (!exists(pathRelativeToProjectRoot)) {
      throw new NoSuchFileException(pathRelativeToProjectRoot.toString());
    }
    return isFile(pathRelativeToProjectRoot)
        ? FakeFileAttributes.forFileWithSize(pathRelativeToProjectRoot, 0)
        : FakeFileAttributes.forDirectory(pathRelativeToProjectRoot);
  }

  @Override
  public <A extends BasicFileAttributes> A readAttributes(
      Path pathRelativeToProjectRoot, Class<A> type, LinkOption... options) throws IOException {
    if (type == BasicFileAttributes.class) {
      return type.cast(readBasicAttributes(pathRelativeToProjectRoot));
    }
    throw new UnsupportedOperationException("cannot mock instance of: " + type);
  }

  @Override
  public boolean exists(Path path, LinkOption... options) {
    return isFile(path) || isDirectory(path);
  }

  @Override
  public long getFileSize(Path path) throws IOException {
    if (!exists(path)) {
      throw new NoSuchFileException(path.toString());
    }
    return getFileBytes(path).length;
  }

  @Override
  public void deleteFileAtPath(Path path) throws IOException {
    if (exists(path)) {
      rmFile(path);
    } else {
      throw new NoSuchFileException(path.toString());
    }
  }

  @Override
  public boolean deleteFileAtPathIfExists(Path path) {
    if (exists(path)) {
      rmFile(path);
      return true;
    } else {
      return false;
    }
  }

  @Override
  public boolean isFile(Path path, LinkOption... options) {
    return fileContents.containsKey(MorePaths.normalize(path));
  }

  @Override
  public boolean isHidden(Path path) {
    return isFile(path) && path.getFileName().toString().startsWith(".");
  }

  @Override
  public boolean isDirectory(Path path, LinkOption... linkOptions) {
    return directories.contains(MorePaths.normalize(path));
  }

  @Override
  public boolean isExecutable(Path child) {
    return false;
  }

  /** Does not support symlinks. */
  @Override
  public final ImmutableCollection<Path> getDirectoryContents(Path pathRelativeToProjectRoot)
      throws IOException {
    Preconditions.checkState(isDirectory(pathRelativeToProjectRoot));
    try (DirectoryStream<Path> directoryStream =
        getDirectoryContentsStream(resolve(pathRelativeToProjectRoot))) {
      return StreamSupport.stream(directoryStream.spliterator(), false)
          .map(absolutePath -> relativize(absolutePath).getPath())
          .sorted(Comparator.naturalOrder())
          .collect(ImmutableList.toImmutableList());
    }
  }

  /** @return returns sorted absolute paths of everything under the given directory */
  @Override
  DirectoryStream<Path> getDirectoryContentsStream(Path absolutePath) {
    Preconditions.checkState(isDirectory(relativize(absolutePath)));
    return new DirectoryStream<Path>() {

      private final Stream<Path> iterable =
          Stream.concat(fileContents.keySet().stream(), directories.stream())
              .filter(
                  input -> {
                    if (input.equals(Paths.get(""))
                        || (input.isAbsolute() && input.getParent() == null)) {
                      return false;
                    }
                    return MorePaths.getParentOrEmpty(input)
                        .equals(relativize(absolutePath).getPath());
                  })
              .map(FakeProjectFilesystem.this::resolve);

      @Override
      public void close() {}

      @Override
      public Iterator<Path> iterator() {
        return iterable.iterator();
      }
    };
  }

  @Override
  public ImmutableSortedSet<Path> getMtimeSortedMatchingDirectoryContents(
      Path pathRelativeToProjectRoot, String globPattern) {
    Preconditions.checkState(isDirectory(pathRelativeToProjectRoot));
    PathMatcher pathMatcher = FileSystems.getDefault().getPathMatcher("glob:" + globPattern);

    return fileContents.keySet().stream()
        .filter(
            i ->
                i.getParent().equals(pathRelativeToProjectRoot)
                    && pathMatcher.matches(i.getFileName()))
        // Sort them in reverse order.
        .sorted(
            (f0, f1) -> {
              try {
                return getLastModifiedTimeFetcher()
                    .getLastModifiedTime(f1)
                    .compareTo(getLastModifiedTimeFetcher().getLastModifiedTime(f0));
              } catch (IOException e) {
                throw new RuntimeException(e);
              }
            })
        .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural()));
  }

  @Override
  public void walkFileTree(Path searchRoot, FileVisitor<Path> fileVisitor) throws IOException {
    walkRelativeFileTree(searchRoot, fileVisitor);
  }

  @Override
  public FileTime getLastModifiedTime(Path path) throws IOException {
    Path normalizedPath = MorePaths.normalize(path);
    if (!exists(normalizedPath)) {
      throw new NoSuchFileException(path.toString());
    }
    return Objects.requireNonNull(fileLastModifiedTimes.get(normalizedPath));
  }

  @Override
  public Path setLastModifiedTime(Path path, FileTime time) throws IOException {
    Path normalizedPath = MorePaths.normalize(path);
    if (!exists(normalizedPath)) {
      throw new NoSuchFileException(path.toString());
    }
    fileLastModifiedTimes.put(normalizedPath, time);
    return normalizedPath;
  }

  @Override
  public void deleteRecursivelyIfExists(Path path) {
    Path normalizedPath = MorePaths.normalize(path);
    for (Iterator<Path> iterator = fileContents.keySet().iterator(); iterator.hasNext(); ) {
      Path subPath = iterator.next();
      if (subPath.startsWith(normalizedPath)) {
        fileAttributes.remove(MorePaths.normalize(subPath));
        fileLastModifiedTimes.remove(MorePaths.normalize(subPath));
        iterator.remove();
      }
    }
    symLinks.keySet().removeIf(subPath -> subPath.startsWith(normalizedPath));
    fileLastModifiedTimes.remove(path);
    directories.remove(path);
  }

  @Override
  public void mkdirs(Path path) {
    for (Path parent = path; parent != null; parent = parent.getParent()) {
      directories.add(parent);
      fileLastModifiedTimes.put(parent, FileTime.fromMillis(clock.currentTimeMillis()));
    }
  }

  @Override
  public Path createNewFile(Path path) {
    writeBytesToPath(new byte[0], path);
    return path;
  }

  @Override
  public void writeLinesToPath(Iterable<String> lines, Path path, FileAttribute<?>... attrs) {
    StringBuilder builder = new StringBuilder();
    if (!Iterables.isEmpty(lines)) {
      Joiner.on(System.lineSeparator()).appendTo(builder, lines);
      builder.append(System.lineSeparator());
    }
    writeContentsToPath(builder.toString(), path, attrs);
  }

  @Override
  public void writeContentsToPath(String contents, Path path, FileAttribute<?>... attrs) {
    writeBytesToPath(contents.getBytes(StandardCharsets.UTF_8), path, attrs);
  }

  @Override
  public void writeBytesToPath(byte[] bytes, Path path, FileAttribute<?>... attrs) {
    Path normalizedPath = MorePaths.normalize(path);
    fileContents.put(normalizedPath, Objects.requireNonNull(bytes));
    fileAttributes.put(normalizedPath, ImmutableSet.copyOf(attrs));

    Path directory = normalizedPath.getParent();
    while (directory != null) {
      directories.add(directory);
      directory = directory.getParent();
    }
    fileLastModifiedTimes.put(normalizedPath, FileTime.fromMillis(clock.currentTimeMillis()));
  }

  @Override
  public OutputStream newFileOutputStream(
      Path pathRelativeToProjectRoot, FileAttribute<?>... attrs) {
    return new ByteArrayOutputStream() {
      @Override
      public void close() throws IOException {
        super.close();
        writeToMap();
      }

      @Override
      public void flush() throws IOException {
        super.flush();
        writeToMap();
      }

      private void writeToMap() {
        writeBytesToPath(toByteArray(), pathRelativeToProjectRoot, attrs);
      }
    };
  }

  /** Does not support symlinks. */
  @Override
  public InputStream newFileInputStream(Path pathRelativeToProjectRoot) throws IOException {
    byte[] contents = fileContents.get(normalizePathToProjectRoot(pathRelativeToProjectRoot));
    return new ByteArrayInputStream(contents);
  }

  private Path normalizePathToProjectRoot(Path pathRelativeToProjectRoot)
      throws NoSuchFileException {
    if (!exists(pathRelativeToProjectRoot)) {
      throw new NoSuchFileException(pathRelativeToProjectRoot.toString());
    }
    return MorePaths.normalize(pathRelativeToProjectRoot);
  }

  @Override
  public void copyToPath(InputStream inputStream, Path path, CopyOption... options)
      throws IOException {
    writeBytesToPath(ByteStreams.toByteArray(inputStream), path);
  }

  /** Does not support symlinks. */
  @Override
  public Optional<String> readFileIfItExists(Path path) {
    if (!exists(path)) {
      return Optional.empty();
    }
    return Optional.of(new String(getFileBytes(path), StandardCharsets.UTF_8));
  }

  /** Does not support symlinks. */
  @Override
  public Optional<String> readFirstLine(Path path) {
    List<String> lines;
    lines = readLines(path);

    return Optional.ofNullable(Iterables.get(lines, 0, null));
  }

  /** Does not support symlinks. */
  @Override
  public List<String> readLines(Path path) {
    Optional<String> contents = readFileIfItExists(path);
    if (!contents.isPresent() || contents.get().isEmpty()) {
      return ImmutableList.of();
    }
    String content = contents.get();
    content =
        content.endsWith(System.lineSeparator())
            ? withoutSuffix(content, System.lineSeparator())
            : content;
    return Splitter.on(System.lineSeparator()).splitToList(content);
  }

  @Override
  public Manifest getJarManifest(Path path) throws IOException {
    try (JarInputStream jar = new JarInputStream(newFileInputStream(path))) {

      Manifest result = jar.getManifest();
      if (result != null) {
        return result;
      }

      // JarInputStream will only find the manifest if it's the first entry, but we have code that
      // puts it elsewhere. We must search. Fortunately, this is test code! So we can be slow!
      JarEntry entry;
      while ((entry = jar.getNextJarEntry()) != null) {
        if (JarFile.MANIFEST_NAME.equals(entry.getName())) {
          result = new Manifest();
          result.read(jar);
          return result;
        }
      }
    }

    return null;
  }

  /** Does not support symlinks. */
  @Override
  public Sha1HashCode computeSha1(Path pathRelativeToProjectRootOrJustAbsolute) throws IOException {
    if (!exists(pathRelativeToProjectRootOrJustAbsolute)) {
      throw new NoSuchFileException(pathRelativeToProjectRootOrJustAbsolute.toString());
    }

    // Because this class is a fake, the file contents may not be available as a stream, so we load
    // all of the contents into memory as a byte[] and then hash them.
    byte[] fileContents = getFileBytes(pathRelativeToProjectRootOrJustAbsolute);
    HashCode hashCode = Hashing.sha1().newHasher().putBytes(fileContents).hash();
    return Sha1HashCode.fromHashCode(hashCode);
  }

  @Override
  public void copy(Path source, Path target, CopySourceMode sourceMode) {
    Path normalizedSourcePath = MorePaths.normalize(source);
    Path normalizedTargetPath = MorePaths.normalize(target);
    switch (sourceMode) {
      case FILE:
        ImmutableSet<FileAttribute<?>> attrs = fileAttributes.get(normalizedSourcePath);
        writeBytesToPath(
            fileContents.get(normalizedSourcePath),
            normalizedTargetPath,
            attrs.toArray(new FileAttribute[0]));
        break;
      case DIRECTORY_CONTENTS_ONLY:
      case DIRECTORY_AND_CONTENTS:
        throw new UnsupportedOperationException();
    }
  }

  /**
   * TODO(natthu): (1) Also traverse the directories. (2) Do not ignore return value of {@code
   * fileVisitor}.
   */
  @Override
  public final void walkRelativeFileTree(
      Path path,
      EnumSet<FileVisitOption> visitOptions,
      FileVisitor<Path> fileVisitor,
      boolean skipIgnored)
      throws IOException {

    if (!isDirectory(path)) {
      fileVisitor.visitFile(path, DEFAULT_FILE_ATTRIBUTES);
      return;
    }

    ImmutableCollection<Path> ents = getDirectoryContents(path);
    for (Path ent : ents) {
      if (!isDirectory(ent)) {
        FileVisitResult result = fileVisitor.visitFile(ent, DEFAULT_FILE_ATTRIBUTES);
        if (result == FileVisitResult.SKIP_SIBLINGS) {
          return;
        }
      } else {
        FileVisitResult result = fileVisitor.preVisitDirectory(ent, DEFAULT_DIR_ATTRIBUTES);
        if (result == FileVisitResult.SKIP_SIBLINGS) {
          return;
        }
        if (result != FileVisitResult.SKIP_SUBTREE) {
          walkRelativeFileTree(ent, fileVisitor);
          fileVisitor.postVisitDirectory(ent, null);
        }
      }
    }
  }

  @Override
  void walkFileTree(
      Path path,
      Set<FileVisitOption> options,
      FileVisitor<Path> fileVisitor,
      DirectoryStream.Filter<? super Path> ignoreFilter)
      throws IOException {
    if (!isDirectory(path)) {
      fileVisitor.visitFile(resolve(path), DEFAULT_FILE_ATTRIBUTES);
      return;
    }

    ImmutableCollection<Path> ents = getDirectoryContents(path);
    for (Path ent : ents) {
      if (!isDirectory(ent)) {
        FileVisitResult result = fileVisitor.visitFile(resolve(ent), DEFAULT_FILE_ATTRIBUTES);
        if (result == FileVisitResult.SKIP_SIBLINGS) {
          return;
        }
      } else {
        FileVisitResult result =
            fileVisitor.preVisitDirectory(resolve(ent), DEFAULT_DIR_ATTRIBUTES);
        if (result == FileVisitResult.SKIP_SIBLINGS) {
          return;
        }
        if (result != FileVisitResult.SKIP_SUBTREE) {
          walkFileTree(ent, options, fileVisitor, ignoreFilter);
          fileVisitor.postVisitDirectory(resolve(ent), null);
        }
      }
    }
  }

  @Override
  public void copyFolder(Path source, Path target) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void copyFile(Path source, Path target) {
    writeContentsToPath(readFileIfItExists(source).get(), target);
  }

  @Override
  public void createSymLink(Path symLink, Path realFile, boolean force) throws IOException {
    if (!force) {
      if (fileContents.containsKey(symLink) || directories.contains(symLink)) {
        throw new FileAlreadyExistsException(symLink.toString());
      }
    } else {
      rmFile(symLink);
      deleteRecursivelyIfExists(symLink);
    }
    symLinks.put(symLink, realFile);
  }

  @Override
  public Set<PosixFilePermission> getPosixFilePermissions(Path path) {
    return ImmutableSet.of(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.GROUP_READ,
        PosixFilePermission.OTHERS_READ);
  }

  @Override
  public boolean isSymLink(Path path) {
    return symLinks.containsKey(path);
  }

  @Override
  public Path readSymLink(Path path) throws IOException {
    Path target = symLinks.get(path);
    if (target == null) {
      throw new NotLinkException(path.toString());
    }
    return target;
  }

  @Override
  public void touch(Path fileToTouch) throws IOException {
    if (exists(fileToTouch)) {
      setLastModifiedTime(fileToTouch, FileTime.fromMillis(clock.currentTimeMillis()));
    } else {
      createNewFile(fileToTouch);
    }
  }

  @Override
  public Path createTempFile(
      Path directory, String prefix, String suffix, FileAttribute<?>... attrs) throws IOException {
    Path path;
    do {
      String str = new BigInteger(130, RANDOM).toString(32);
      path = directory.resolve(prefix + str + suffix);
    } while (exists(path));
    touch(path);
    return path;
  }

  @Override
  public void move(Path source, Path target, CopyOption... options) {
    fileContents.put(MorePaths.normalize(target), fileContents.remove(MorePaths.normalize(source)));
    fileAttributes.put(
        MorePaths.normalize(target), fileAttributes.remove(MorePaths.normalize(source)));
    fileLastModifiedTimes.put(
        MorePaths.normalize(target), fileLastModifiedTimes.remove(MorePaths.normalize(source)));
  }
}
