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
import java.io.IOException;
import java.nio.file.Path;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class ElfVerNeedTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private ProjectWorkspace workspace;

  @Before
  public void setUp() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "samples", tmp);
    workspace.setUp();
  }

  @Test
  public void test() throws IOException {
    Path elfFilePath = workspace.resolve("libfoo.so");
    Elf elf = ElfFile.mapReadOnly(elfFilePath);
    ElfSection stringTable = elf.getMandatorySectionByName(elfFilePath, ".dynstr").getSection();
    ElfSection section = elf.getMandatorySectionByName(elfFilePath, ".gnu.version_r").getSection();
    assertThat(section.header.sh_type, Matchers.is(ElfSectionHeader.SHType.SHT_GNU_VERNEED));
    ElfVerNeed verNeed = ElfVerNeed.parse(elf.header.ei_class, section.body);
    assertThat(verNeed.entries, Matchers.hasSize(1));
    assertThat(
        stringTable.lookupString(verNeed.entries.get(0).getFirst().vn_file),
        Matchers.equalTo("libc.so.6"));
    assertThat(verNeed.entries.get(0).getSecond(), Matchers.hasSize(1));
    assertThat(
        stringTable.lookupString(verNeed.entries.get(0).getSecond().get(0).vna_name),
        Matchers.equalTo("GLIBC_2.2.5"));
  }
}
