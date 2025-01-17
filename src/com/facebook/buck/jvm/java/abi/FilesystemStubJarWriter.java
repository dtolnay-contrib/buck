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

package com.facebook.buck.jvm.java.abi;

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.io.pathformat.PathFormatter;
import com.facebook.buck.util.function.ThrowingSupplier;
import com.facebook.buck.util.zip.CustomZipEntry;
import com.facebook.buck.util.zip.JarBuilder;
import com.facebook.buck.util.zip.JarEntrySupplier;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

/** A {@link StubJarWriter} that writes to a file. */
class FilesystemStubJarWriter implements StubJarWriter {

  private final AbsPath outputPath;
  private final JarBuilder jarBuilder;
  private boolean closed = false;

  public FilesystemStubJarWriter(AbsPath outputPath) {
    this.outputPath = outputPath;
    this.jarBuilder = new JarBuilder().setShouldHashEntries(true).setShouldMergeManifests(true);
  }

  @Override
  public void writeEntry(
      Path relativePath, ThrowingSupplier<InputStream, IOException> streamSupplier) {
    jarBuilder.addEntry(
        new JarEntrySupplier(
            new CustomZipEntry(PathFormatter.pathWithUnixSeparators(relativePath)),
            outputPath.toString(),
            streamSupplier));
  }

  @Override
  public void close() throws IOException {
    if (!closed) {
      jarBuilder.createJarFile(outputPath.getPath());
    }
    closed = true;
  }
}
