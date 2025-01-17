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

package com.facebook.buck.cxx;

import static java.nio.channels.FileChannel.MapMode.READ_ONLY;
import static org.hamcrest.MatcherAssert.assertThat;

import com.facebook.buck.cxx.toolchain.elf.Elf;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;

public class ElfClearProgramHeadersStepTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void test() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "elf_shared_lib", tmp);
    workspace.setUp();
    ElfClearProgramHeadersStep step =
        ImmutableElfClearProgramHeadersStep.ofImpl(
            TestProjectFilesystems.createProjectFilesystem(tmp.getRoot()),
            tmp.getRoot().getFileSystem().getPath("libfoo.so"));
    step.execute(TestExecutionContext.newInstance());

    // Verify that the program table section is empty.
    try (FileChannel channel =
        FileChannel.open(step.getFilesystem().resolve(step.getPath()), StandardOpenOption.READ)) {
      MappedByteBuffer buffer = channel.map(READ_ONLY, 0, channel.size());
      Elf elf = new Elf(buffer);
      // Program headers are at the beginning of the file, so truncating to `int` should be ok.
      buffer.position((int) elf.header.e_phoff);
      for (int index = 0; index < elf.header.e_phnum * elf.header.e_phentsize; index++) {
        assertThat((int) buffer.get(), Matchers.equalTo(0));
      }
    }
  }
}
