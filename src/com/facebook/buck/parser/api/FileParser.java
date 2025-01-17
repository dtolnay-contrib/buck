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

package com.facebook.buck.parser.api;

import com.facebook.buck.core.filesystems.ForwardRelPath;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;

/** Generic file parser, meant to be extended for more specific parsers. */
public interface FileParser<T extends FileManifest> extends AutoCloseable {

  /**
   * Collect all information from a particular, along with metadata about the information, for
   * example which other files were also parsed.
   *
   * @param parseFile should be an absolute path to a file. Must have rootPath as its prefix.
   */
  T getManifest(ForwardRelPath parseFile)
      throws BuildFileParseException, InterruptedException, IOException;

  /**
   * Collects the loaded files and extensions when parsing the {@code parseFile} build spec.
   *
   * @param parseFile should be an absolute path to a file. Must have rootPath as its prefix.
   */
  ImmutableSortedSet<String> getIncludedFiles(ForwardRelPath parseFile)
      throws BuildFileParseException, InterruptedException, IOException;

  @Override
  void close() throws BuildFileParseException, InterruptedException, IOException;
}
