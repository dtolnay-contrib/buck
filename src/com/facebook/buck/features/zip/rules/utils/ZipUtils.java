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

package com.facebook.buck.features.zip.rules.utils;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.util.zip.collect.OnDuplicateEntry;
import com.facebook.buck.util.zip.collect.ZipEntrySourceCollection;
import com.facebook.buck.util.zip.collect.ZipEntrySourceCollectionBuilder;
import com.facebook.buck.util.zip.collect.ZipEntrySourceCollectionWriter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;

/** Utilities method related to zip_file rule */
public class ZipUtils {

  /** Creates zip file */
  public static void createZipFile(
      AbsPath ruleCellRoot,
      ImmutableMap<RelPath, RelPath> entryMap,
      ImmutableList<RelPath> zipSources,
      ImmutableSet<Pattern> entriesToExclude,
      OnDuplicateEntry onDuplicateEntry,
      RelPath outputPath)
      throws IOException {
    ZipEntrySourceCollection zipEntrySourceCollection =
        buildCollection(ruleCellRoot, entryMap, zipSources, entriesToExclude, onDuplicateEntry);
    new ZipEntrySourceCollectionWriter(ruleCellRoot)
        .copyToZip(zipEntrySourceCollection, outputPath.getPath());
  }

  private static ZipEntrySourceCollection buildCollection(
      AbsPath ruleCellRoot,
      ImmutableMap<RelPath, RelPath> entryMap,
      ImmutableList<RelPath> zipSources,
      ImmutableSet<Pattern> entriesToExclude,
      OnDuplicateEntry onDuplicateEntry) {
    ZipEntrySourceCollectionBuilder builder =
        new ZipEntrySourceCollectionBuilder(entriesToExclude, onDuplicateEntry);
    for (RelPath zipPath : zipSources) {
      AbsPath zipSourceAbsPath = ruleCellRoot.resolve(zipPath);
      try {
        builder.addZipFile(zipSourceAbsPath.getPath());
      } catch (IOException e) {
        throw new HumanReadableException(
            e, "Error while reading archive entries from %s: %s", zipSourceAbsPath, e.getMessage());
      }
    }
    for (Map.Entry<RelPath, RelPath> pathEntry : entryMap.entrySet()) {
      String entryName = pathEntry.getKey().toString();
      AbsPath entryAbsPath = ruleCellRoot.resolve(pathEntry.getValue());
      builder.addFile(entryName, entryAbsPath.getPath());
    }
    return builder.build();
  }

  /**
   * Returns a map where given {@link AbsPath} instances are resolved relatively to the given root
   * path.
   */
  public static ImmutableMap<RelPath, RelPath> toRelPathEntryMap(
      ImmutableMap<Path, AbsPath> entryPathToAbsolutePathMap, AbsPath rootPath) {
    return entryPathToAbsolutePathMap.entrySet().stream()
        .collect(
            ImmutableMap.toImmutableMap(
                e -> RelPath.of(e.getKey()), e -> rootPath.relativize(e.getValue())));
  }
}
