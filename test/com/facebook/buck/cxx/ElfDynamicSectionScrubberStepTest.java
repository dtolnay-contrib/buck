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

import static org.hamcrest.MatcherAssert.assertThat;

import com.facebook.buck.cxx.toolchain.elf.Elf;
import com.facebook.buck.cxx.toolchain.elf.ElfDynamicSection;
import com.facebook.buck.cxx.toolchain.elf.ElfSection;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;

public class ElfDynamicSectionScrubberStepTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void test() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "elf_shared_lib", tmp);
    workspace.setUp();

    ImmutableSet<ElfDynamicSection.DTag> whitelist =
        ImmutableSet.of(ElfDynamicSection.DTag.DT_SONAME, ElfDynamicSection.DTag.DT_NEEDED);
    ElfDynamicSectionScrubberStep step =
        ImmutableElfDynamicSectionScrubberStep.ofImpl(
            TestProjectFilesystems.createProjectFilesystem(tmp.getRoot()),
            tmp.getRoot().getFileSystem().getPath("libfoo.so"),
            whitelist,
            /* removeScrubbedTags */ false);
    step.execute(TestExecutionContext.newInstance());

    // Verify that the relevant dynamic section tag have been zero'd out.
    Elf elf = ElfFile.mapReadOnly(step.getFilesystem().resolve(step.getPath()));
    Optional<ElfSection> section =
        elf.getSectionByName(ElfDynamicSectionScrubberStep.SECTION)
            .map(Elf.ElfSectionLookupResult::getSection);
    ElfDynamicSection dynamic = ElfDynamicSection.parse(elf.header.ei_class, section.get().body);
    for (ElfDynamicSection.Entry entry : dynamic.entries) {
      if (!whitelist.contains(entry.d_tag)) {
        assertThat(entry.d_un, Matchers.equalTo(0L));
      }
    }
  }

  @Test
  public void testRemoveScrubbedTags() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "elf_shared_lib", tmp);
    workspace.setUp();

    ImmutableSet<ElfDynamicSection.DTag> whitelist =
        ImmutableSet.of(ElfDynamicSection.DTag.DT_SONAME, ElfDynamicSection.DTag.DT_NEEDED);
    ElfDynamicSectionScrubberStep step =
        ImmutableElfDynamicSectionScrubberStep.ofImpl(
            TestProjectFilesystems.createProjectFilesystem(tmp.getRoot()),
            tmp.getRoot().getFileSystem().getPath("libfoo.so"),
            whitelist,
            /* removeScrubbedTags */ true);
    step.execute(TestExecutionContext.newInstance());

    // Verify that the relevant dynamic section tag have been zero'd out.
    Elf elf = ElfFile.mapReadOnly(step.getFilesystem().resolve(step.getPath()));
    Optional<ElfSection> section =
        elf.getSectionByName(ElfDynamicSectionScrubberStep.SECTION)
            .map(Elf.ElfSectionLookupResult::getSection);
    ElfDynamicSection dynamic = ElfDynamicSection.parse(elf.header.ei_class, section.get().body);
    for (ElfDynamicSection.Entry entry : dynamic.entries) {
      assertThat(
          entry.d_tag,
          Matchers.anyOf(Matchers.in(whitelist), Matchers.is(ElfDynamicSection.DTag.DT_NULL)));
    }
  }
}
