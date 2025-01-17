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

package com.facebook.buck.parser;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.Cells;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.filesystems.ForwardRelPath;
import com.facebook.buck.core.model.targetgraph.impl.Package;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.parser.api.PackageMetadata;
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.util.Optional;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class PerBuildStateCacheTest {

  private PackageCachePerBuild packageCache;
  private BuckEventBus eventBus;
  private ProjectFilesystem filesystem;
  private Cells cells;
  private Cell childCell;

  @Rule public TemporaryPaths tempDir = new TemporaryPaths();

  @Before
  public void setUp() throws IOException {
    eventBus = BuckEventBusForTests.newInstance();
    packageCache = new PackageCachePerBuild();
    filesystem = TestProjectFilesystems.createProjectFilesystem(tempDir.getRoot());
    tempDir.newFolder("xplat");
    tempDir.newFile("xplat/.buckconfig");
    BuckConfig config =
        FakeBuckConfig.builder()
            .setFilesystem(filesystem)
            .setSections(ImmutableMap.of("repositories", ImmutableMap.of("xplat", "xplat")))
            .build();
    cells = new TestCellBuilder().setFilesystem(filesystem).setBuckConfig(config).build();
    childCell = cells.getCell(CanonicalCellName.of(Optional.of("xplat")));
  }

  Package createPackage(Cell cell, ForwardRelPath packageFile) {
    return createPackage(cell, packageFile, PackageMetadata.EMPTY_SINGLETON);
  }

  Package createPackage(
      Cell cell,
      ForwardRelPath packageFile,
      Boolean inherit,
      ImmutableList<String> visibility,
      ImmutableList<String> within_view) {
    return createPackage(cell, packageFile, PackageMetadata.of(inherit, visibility, within_view));
  }

  Package createPackage(Cell cell, ForwardRelPath packageFile, PackageMetadata packageMetadata) {
    return PackageFactory.create(cell, packageFile, packageMetadata, Optional.empty());
  }

  @Test
  public void putPackageIfNotPresent() {
    ForwardRelPath packageFile = ForwardRelPath.of("Foo");

    Package pkg = createPackage(cells.getRootCell(), packageFile);

    Package cachedPackage =
        packageCache.putComputedNodeIfNotPresent(
            cells.getRootCell(), packageFile, pkg, false, DaemonicParserValidationToken.invalid());

    Assert.assertSame(cachedPackage, pkg);
  }

  @Test
  public void lookupPackage() {
    ForwardRelPath packageFile = ForwardRelPath.of("Foo");

    Optional<Package> lookupPackage =
        packageCache.lookupComputedNode(
            cells.getRootCell(), packageFile, DaemonicParserValidationToken.invalid());

    Assert.assertFalse(lookupPackage.isPresent());

    Package pkg = createPackage(cells.getRootCell(), packageFile);
    packageCache.putComputedNodeIfNotPresent(
        cells.getRootCell(), packageFile, pkg, false, DaemonicParserValidationToken.invalid());

    lookupPackage =
        packageCache.lookupComputedNode(
            cells.getRootCell(), packageFile, DaemonicParserValidationToken.invalid());
    Assert.assertSame(lookupPackage.get(), pkg);
  }

  @Test
  public void packageInRootCellIsNotInChildCell() {
    ForwardRelPath packageFile = ForwardRelPath.of("Foo");

    // Make sure to create two different packages
    Package pkg1 =
        createPackage(
            cells.getRootCell(),
            packageFile,
            false,
            ImmutableList.of("//bar/..."),
            ImmutableList.of());

    ForwardRelPath childPackageFile = ForwardRelPath.of("Foo");
    Package pkg2 =
        createPackage(
            childCell, childPackageFile, false, ImmutableList.of("//bar/..."), ImmutableList.of());

    packageCache.putComputedNodeIfNotPresent(
        cells.getRootCell(), packageFile, pkg1, false, DaemonicParserValidationToken.invalid());
    packageCache.putComputedNodeIfNotPresent(
        childCell, childPackageFile, pkg2, false, DaemonicParserValidationToken.invalid());

    Optional<Package> lookupPackage =
        packageCache.lookupComputedNode(
            cells.getRootCell(), packageFile, DaemonicParserValidationToken.invalid());
    Assert.assertSame(lookupPackage.get(), pkg1);
    Assert.assertNotSame(lookupPackage.get(), pkg2);

    lookupPackage =
        packageCache.lookupComputedNode(
            childCell, childPackageFile, DaemonicParserValidationToken.invalid());
    Assert.assertSame(lookupPackage.get(), pkg2);
    Assert.assertNotSame(lookupPackage.get(), pkg1);
  }
}
