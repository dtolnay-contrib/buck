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
import static org.junit.Assume.assumeThat;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.core.build.context.FakeBuildContext;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.cxx.toolchain.elf.Elf;
import com.facebook.buck.cxx.toolchain.elf.ElfSection;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.io.filesystem.impl.ProjectFilesystemUtils;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.EnvVariablesProvider;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;

public class ElfExtractSectionsStepTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private static final ImmutableSet<String> OBJCOPY_NAMES = ImmutableSet.of("objcopy", "gobjcopy");

  private Path assumeObjcopy() {
    assumeThat(Platform.detect(), Matchers.oneOf(Platform.LINUX, Platform.MACOS));
    ExecutableFinder finder = new ExecutableFinder();
    Optional<Path> objcopy = Optional.empty();
    for (String name : OBJCOPY_NAMES) {
      objcopy =
          finder.getOptionalExecutable(
              tmp.getRoot().getFileSystem().getPath(name), EnvVariablesProvider.getSystemEnv());
      if (objcopy.isPresent()) {
        break;
      }
    }
    assumeTrue(objcopy.isPresent());
    return objcopy.get();
  }

  @Test
  public void test() throws IOException, InterruptedException {

    // Only run if `objcopy` is available on the system.
    Path objcopy = assumeObjcopy();

    // Run the step.
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "elf_shared_lib", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem = TestProjectFilesystems.createProjectFilesystem(tmp.getRoot());
    Path output = tmp.getRoot().getFileSystem().getPath("libfoo.extracted.so");
    ElfExtractSectionsStep step =
        new ElfExtractSectionsStep(
            ImmutableList.of(objcopy.toString()),
            ImmutableSet.of(".dynamic"),
            filesystem,
            filesystem.getPath("libfoo.so"),
            filesystem,
            output,
            ProjectFilesystemUtils.relativize(
                filesystem.getRootPath(),
                FakeBuildContext.withSourcePathResolver(
                        new SourcePathResolverAdapter(
                            DefaultSourcePathResolver.from(new TestActionGraphBuilder())),
                        filesystem)
                    .getBuildCellRootPath()),
            false);
    step.execute(TestExecutionContext.newInstanceWithRealProcessExecutor());

    // Verify that the program table section is empty.
    Elf elf = ElfFile.mapReadOnly(filesystem.resolve(output));
    List<String> sections = new ArrayList<>();
    for (int index = 0; index < elf.getNumberOfSections(); index++) {
      ElfSection section = elf.getSectionByIndex(index);
      if (section.header.sh_flags != 0) {
        String name = elf.getSectionName(section.header);
        sections.add(name);
      }
    }
    assertThat(sections, Matchers.equalTo(ImmutableList.of(".dynamic")));
  }
}
