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

package com.facebook.buck.core.cell;

import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.filesystems.AbsPath;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * View of a subset of cells of a cell path resolver.
 *
 * <p>Views are used for non-root cells, to ensure that only the subset of cell names that the cell
 * declares are visible within that cell.
 */
public final class CellPathResolverView extends AbstractCellPathResolver {
  private final CellPathResolver delegate;
  private final ImmutableSet<String> declaredCellNames;
  private final AbsPath cellPath;
  private final CellNameResolver cellNameResolver;

  public CellPathResolverView(
      CellPathResolver delegate,
      CellNameResolver cellNameResolver,
      ImmutableSet<String> declaredCellNames,
      AbsPath cellPath) {
    this.delegate = delegate;
    this.cellNameResolver = cellNameResolver;
    Optional<String> thisName = delegate.getCanonicalCellName(cellPath);
    if (thisName.isPresent()) {
      // A cell should be able to view into itself even if it doesn't explicitly specify it.
      this.declaredCellNames =
          ImmutableSet.copyOf(Sets.union(declaredCellNames, ImmutableSet.of(thisName.get())));
    } else {
      this.declaredCellNames = declaredCellNames;
    }
    this.cellPath = cellPath;
  }

  @Override
  public CellNameResolver getCellNameResolver() {
    return cellNameResolver;
  }

  @Override
  public NewCellPathResolver getNewCellPathResolver() {
    return delegate.getNewCellPathResolver();
  }

  @Override
  public Optional<AbsPath> getCellPath(CanonicalCellName cellName) {
    Optional<String> legacyName = cellName.getLegacyName();
    if (legacyName.isPresent()) {
      if (declaredCellNames.contains(legacyName.get())) {
        return delegate.getCellPath(cellName);
      } else {
        return Optional.empty();
      }
    } else {
      return Optional.of(cellPath);
    }
  }

  @Override
  public ImmutableMap<String, AbsPath> getCellPathsByRootCellExternalName() {
    return ImmutableMap.copyOf(
        Maps.filterKeys(
            delegate.getCellPathsByRootCellExternalName(), declaredCellNames::contains));
  }

  @Override
  public Optional<String> getCanonicalCellName(Path cellPath) {
    // TODO(cjhopman): This should verify that this path is visible in this view.
    return delegate.getCanonicalCellName(cellPath);
  }

  @Override
  public boolean equals(Object another) {
    if (this == another) return true;
    return another instanceof CellPathResolverView && equalTo((CellPathResolverView) another);
  }

  private boolean equalTo(CellPathResolverView another) {
    return delegate.equals(another.delegate)
        && declaredCellNames.equals(another.declaredCellNames)
        && cellPath.equals(another.cellPath);
  }

  @Override
  public int hashCode() {
    return Objects.hash(delegate, declaredCellNames, cellPath);
  }
}
