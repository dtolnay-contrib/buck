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

package com.facebook.buck.cxx.toolchain.elf;

import static org.hamcrest.MatcherAssert.assertThat;

import com.facebook.buck.cxx.ElfFile;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.nio.ByteBufferUnmapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;

public class ElfSectionHeaderTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void write() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "samples", tmp);
    workspace.setUp();
    Path elfPath = workspace.resolve(Paths.get("le64.o"));

    // Overwrite .text section header with 87 in the info field.
    try (FileChannel channel =
        FileChannel.open(elfPath, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
      try (ByteBufferUnmapper unmapper =
          ByteBufferUnmapper.createUnsafe(
              channel.map(FileChannel.MapMode.READ_WRITE, 0, channel.size()))) {
        ByteBuffer buffer = unmapper.getByteBuffer();
        Elf elf = new Elf(buffer);
        Elf.ElfSectionLookupResult sectionResult = elf.getMandatorySectionByName(elfPath, ".text");
        buffer.position(
            (int) (elf.header.e_shoff + sectionResult.getIndex() * elf.header.e_shentsize));
        sectionResult.getSection().header.withInfo(87L).write(elf.header.ei_class, buffer);
      }
    }

    // Verify the result.
    Elf elf = ElfFile.mapReadOnly(elfPath);
    ElfSection section = elf.getMandatorySectionByName(elfPath, ".text").getSection();
    assertThat(section.header.sh_info, Matchers.equalTo(87L));
  }
}
