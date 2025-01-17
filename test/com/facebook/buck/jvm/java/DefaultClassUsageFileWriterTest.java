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

package com.facebook.buck.jvm.java;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.TestCellPathResolver;
import com.facebook.buck.core.cell.impl.CellPathResolverUtils;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.testutil.JsonMatcher;
import com.facebook.buck.util.Escaper;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Files;
import javax.tools.JavaFileObject;
import org.junit.Test;

public class DefaultClassUsageFileWriterTest {

  private static final String OTHER_FILE_NAME = JavaFileObject.Kind.OTHER.toString();
  private static final String SOURCE_FILE_NAME = JavaFileObject.Kind.SOURCE.toString();
  private static final String HTML_FILE_NAME = JavaFileObject.Kind.HTML.toString();

  private static final String[] FILE_NAMES = {"A", "B", "C", "D", "E", "F"};
  private static final String SINGLE_NON_JAVA_FILE_NAME = "NonJava";

  @Test
  public void fileReadOrderDoesntAffectClassesUsedOutput() throws IOException {
    ProjectFilesystem filesystem = FakeProjectFilesystem.createRealTempFilesystem();
    AbsPath testJarPath = filesystem.getPathForRelativePath("test.jar");
    AbsPath testTwoJarPath = filesystem.getPathForRelativePath("test2.jar");

    AbsPath outputOne = filesystem.getPathForRelativePath("used-classes-one.json");
    AbsPath outputTwo = filesystem.getPathForRelativePath("used-classes-two.json");

    FakeStandardJavaFileManager fakeFileManager = new FakeStandardJavaFileManager();
    fakeFileManager.addFile(testJarPath, OTHER_FILE_NAME, JavaFileObject.Kind.OTHER);
    fakeFileManager.addFile(testJarPath, SOURCE_FILE_NAME, JavaFileObject.Kind.SOURCE);
    fakeFileManager.addFile(testJarPath, HTML_FILE_NAME, JavaFileObject.Kind.HTML);
    fakeFileManager.addFile(testJarPath, SINGLE_NON_JAVA_FILE_NAME, JavaFileObject.Kind.OTHER);
    for (String fileName : FILE_NAMES) {
      fakeFileManager.addFile(testJarPath, fileName, JavaFileObject.Kind.CLASS);
    }
    for (String fileName : FILE_NAMES) {
      fakeFileManager.addFile(testTwoJarPath, fileName, JavaFileObject.Kind.CLASS);
    }

    DefaultClassUsageFileWriter writerOne = new DefaultClassUsageFileWriter();
    ClassUsageTracker trackerOne = new ClassUsageTracker();
    {
      ListenableFileManager wrappedFileManager = new ListenableFileManager(fakeFileManager);
      wrappedFileManager.addListener(trackerOne);
      for (JavaFileObject javaFileObject : wrappedFileManager.list(null, null, null, false)) {
        javaFileObject.openInputStream();
      }
    }
    filesystem.createParentDirs(outputOne);
    writerOne.writeFile(
        trackerOne.getClassUsageMap(),
        filesystem.relativize(outputOne),
        filesystem.getRootPath(),
        filesystem.getBuckPaths().getConfiguredBuckOut(),
        ImmutableMap.of());

    DefaultClassUsageFileWriter writerTwo = new DefaultClassUsageFileWriter();
    ClassUsageTracker trackerTwo = new ClassUsageTracker();
    {
      ListenableFileManager wrappedFileManager = new ListenableFileManager(fakeFileManager);
      wrappedFileManager.addListener(trackerTwo);
      Iterable<JavaFileObject> fileObjects = wrappedFileManager.list(null, null, null, false);
      for (JavaFileObject javaFileObject : FluentIterable.from(fileObjects).toList().reverse()) {
        javaFileObject.openInputStream();
      }
    }
    filesystem.createParentDirs(outputTwo);
    writerTwo.writeFile(
        trackerTwo.getClassUsageMap(),
        filesystem.relativize(outputTwo),
        filesystem.getRootPath(),
        filesystem.getBuckPaths().getConfiguredBuckOut(),
        ImmutableMap.of());

    assertEquals(
        new String(Files.readAllBytes(outputOne.getPath())),
        new String(Files.readAllBytes(outputTwo.getPath())));
  }

  @Test
  public void classUsageFileWriterHandlesCrossCell() throws IOException {
    ProjectFilesystem homeFs = FakeProjectFilesystem.createRealTempFilesystem();
    ProjectFilesystem awayFs = FakeProjectFilesystem.createRealTempFilesystem();
    ProjectFilesystem externalFs = FakeProjectFilesystem.createRealTempFilesystem();

    AbsPath rootPath = homeFs.getRootPath();
    CellPathResolver cellPathResolver =
        TestCellPathResolver.create(
            homeFs.getRootPath(), ImmutableMap.of("AwayCell", awayFs.getRootPath().getPath()));

    AbsPath testJarPath = homeFs.getPathForRelativePath("home.jar");
    AbsPath testTwoJarPath = awayFs.getPathForRelativePath("away.jar");
    AbsPath externalJarPath = externalFs.getPathForRelativePath("external.jar");

    AbsPath outputOne = homeFs.getPathForRelativePath("used-classes-one.json");

    FakeStandardJavaFileManager fakeFileManager = new FakeStandardJavaFileManager();
    fakeFileManager.addFile(testJarPath, "HomeCellClass", JavaFileObject.Kind.CLASS);
    fakeFileManager.addFile(testTwoJarPath, "AwayCellClass", JavaFileObject.Kind.CLASS);
    fakeFileManager.addFile(externalJarPath, "ExternalClass", JavaFileObject.Kind.CLASS);

    DefaultClassUsageFileWriter writer = new DefaultClassUsageFileWriter();
    ClassUsageTracker trackerOne = new ClassUsageTracker();
    ListenableFileManager wrappedFileManager = new ListenableFileManager(fakeFileManager);
    wrappedFileManager.addListener(trackerOne);
    for (JavaFileObject javaFileObject : wrappedFileManager.list(null, null, null, false)) {
      javaFileObject.openInputStream();
    }
    homeFs.createParentDirs(outputOne);
    writer.writeFile(
        trackerOne.getClassUsageMap(),
        homeFs.relativize(outputOne),
        rootPath,
        homeFs.getBuckPaths().getConfiguredBuckOut(),
        CellPathResolverUtils.getCellToPathMappings(rootPath, cellPathResolver));

    // The xcell file should appear relative to the "home" filesystem, and the external class
    // which is not under any cell in the project should not appear at all.
    AbsPath expectedAwayCellPath =
        rootPath.getRoot().resolve("AwayCell").resolve(awayFs.relativize(testTwoJarPath).getPath());
    Escaper.Quoter quoter =
        Platform.detect() == Platform.WINDOWS
            ? Escaper.Quoter.DOUBLE_WINDOWS_JAVAC
            : Escaper.Quoter.DOUBLE;
    String escapedExpectedAwayCellPath = quoter.quote(expectedAwayCellPath.toString());
    assertThat(
        new String(Files.readAllBytes(outputOne.getPath())),
        new JsonMatcher(
            String.format(
                "{%s: {\"AwayCellClass\":1},\"home.jar\":{\"HomeCellClass\":1}}",
                escapedExpectedAwayCellPath)));
  }
}
