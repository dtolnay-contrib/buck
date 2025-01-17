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

package com.facebook.buck.features.haskell;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assume.assumeThat;

import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.util.Collection;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class HaskellBinaryIntegrationTest {

  @Parameterized.Parameters(name = "{0}")
  public static Collection<Object[]> data() {
    return ImmutableList.copyOf(
        new Object[][] {
          {Linker.LinkableDepType.STATIC}, {Linker.LinkableDepType.SHARED},
        });
  }

  private HaskellVersion version;
  private ProjectWorkspace workspace;

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Parameterized.Parameter(value = 0)
  public Linker.LinkableDepType linkStyle;

  private String getLinkFlavor() {
    return linkStyle.toString().toLowerCase().replace('_', '-');
  }

  @Before
  public void setUp() throws IOException {

    // We don't currently support windows.
    assumeThat(Platform.detect(), Matchers.not(Platform.WINDOWS));

    // Verify that the system contains a compiler.
    version = HaskellTestUtils.assumeSystemCompiler();

    // Setup the workspace.
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "binary_test", tmp);
    workspace.setUp();

    // Write out the `.buckconfig`.
    workspace.writeContentsToPath(HaskellTestUtils.formatHaskellConfig(version), ".buckconfig");
  }

  @Test
  public void simple() {
    ProcessResult result = workspace.runBuckCommand("run", "//:foo#default," + getLinkFlavor());
    result.assertSuccess();
    assertThat(result.getStdout(), Matchers.equalTo("5"));
  }

  @Test
  public void ghcLinkerFlags() {
    ProcessResult result =
        workspace.runBuckCommand(
            "run", "//:foo_rtsflags#default," + getLinkFlavor(), "-- +RTS -A512m -RTS");
    result.assertSuccess();
    assertThat(result.getStdout(), Matchers.equalTo("5"));
  }

  @Test
  public void dependency() {
    ProcessResult result =
        workspace.runBuckCommand("run", "//:dependent#default," + getLinkFlavor());
    result.assertSuccess();
    assertThat(result.getStdout(), Matchers.equalTo("5"));
  }

  @Test
  public void mutuallyRecursive() {
    // .hs-boot doesn't work well with -i (empty import directory list) for ghc <8
    assumeThat(version.getMajorVersion(), Matchers.greaterThanOrEqualTo(8));
    ProcessResult result =
        workspace.runBuckCommand("run", "//:mutually_recursive#default," + getLinkFlavor());
    result.assertSuccess();
    assertThat(result.getStdout(), Matchers.equalTo("55\n"));
  }

  @Test
  public void foreign() {
    ProcessResult result = workspace.runBuckCommand("run", "//:foreign#default," + getLinkFlavor());
    result.assertSuccess();
    assertThat(result.getStdout(), Matchers.equalTo("hello world"));
  }

  @Test
  public void cxxGenrule() {
    ProcessResult result =
        workspace.runBuckCommand(
            "run", "-c", "cxx.cppflags=-some-flag", "//:gen_main#default," + getLinkFlavor());
    result.assertSuccess();
    assertThat(result.getStdout().trim(), Matchers.equalTo("-some-flag"));
  }

  @Test
  public void cHeader() {
    ProcessResult result =
        workspace.runBuckCommand("run", "//:hs_header#default," + getLinkFlavor());
    result.assertSuccess();
    assertThat(result.getStdout(), Matchers.equalTo("hello"));
  }

  @Test
  public void buildError() {
    ProcessResult result = workspace.runBuckBuild("//:error#default," + getLinkFlavor());
    result.assertFailure();
  }
}
