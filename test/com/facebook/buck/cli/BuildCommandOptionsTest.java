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

package com.facebook.buck.cli;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.command.config.BuildBuckConfig;
import com.facebook.buck.core.cell.CellName;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.jvm.java.DefaultJavaPackageFinder;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import org.hamcrest.Matchers;
import org.junit.Test;
import org.kohsuke.args4j.CmdLineException;

public class BuildCommandOptionsTest {

  @Test
  public void testCreateJavaPackageFinder() {
    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(
                ImmutableMap.of(JavaBuckConfig.SECTION, ImmutableMap.of("src_roots", "src, test")))
            .build();
    DefaultJavaPackageFinder javaPackageFinder =
        buckConfig.getView(JavaBuckConfig.class).createDefaultJavaPackageFinder();
    assertEquals(ImmutableSortedSet.of(), javaPackageFinder.getPathsFromRoot());
    assertEquals(ImmutableSet.of("src", "test"), javaPackageFinder.getPathElements());
  }

  @Test
  public void testCreateJavaPackageFinderFromEmptyBuckConfig() {
    BuckConfig buckConfig = FakeBuckConfig.empty();
    DefaultJavaPackageFinder javaPackageFinder =
        buckConfig.getView(JavaBuckConfig.class).createDefaultJavaPackageFinder();
    assertEquals(ImmutableSortedSet.<String>of(), javaPackageFinder.getPathsFromRoot());
    assertEquals(ImmutableSet.of(), javaPackageFinder.getPathsFromRoot());
  }

  @Test
  public void testCommandLineOptionOverridesOtherBuildThreadSettings() throws CmdLineException {
    BuildCommand command = new BuildCommand();

    AdditionalOptionsCmdLineParser parser = CmdLineParserFactory.create(command);
    parser.parseArgument("--num-threads", "42");

    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(
                command.getConfigOverrides(ImmutableMap.of()).getForCell(CellName.ROOT_CELL_NAME))
            .build();
    assertThat(buckConfig.getView(BuildBuckConfig.class).getNumThreads(), Matchers.equalTo(42));
  }

  @Test
  public void testCommandLineOptionsForOncalls() throws CmdLineException {
    BuildCommand command = new BuildCommand();

    AdditionalOptionsCmdLineParser parser = CmdLineParserFactory.create(command);
    parser.parseArgument(
        "--oncall",
        "build_infra",
        "--oncall",
        "test_infra",
        "--oncall",
        "android_infra",
        "--oncall",
        "ios_infra");

    ImmutableSet<String> oncalls = command.getOncalls();
    assertThat(
        oncalls, Matchers.contains("build_infra", "test_infra", "android_infra", "ios_infra"));
  }
}
