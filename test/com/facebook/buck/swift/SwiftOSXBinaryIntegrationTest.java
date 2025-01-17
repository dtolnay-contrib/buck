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

package com.facebook.buck.swift;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assume.assumeThat;

import com.facebook.buck.apple.AppleNativeIntegrationTestUtils;
import com.facebook.buck.apple.toolchain.ApplePlatform;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import java.io.IOException;
import org.junit.Rule;
import org.junit.Test;

public class SwiftOSXBinaryIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void swiftHelloWorldRunsAndPrintsMessageOnOSXWMO() throws IOException {
    swiftHelloWorldRunsAndPrintsMessageOnOSXImpl(false);
  }

  @Test
  public void swiftHelloWorldRunsAndPrintsMessageOnOSXIncremental() throws IOException {
    swiftHelloWorldRunsAndPrintsMessageOnOSXImpl(true);
  }

  private void swiftHelloWorldRunsAndPrintsMessageOnOSXImpl(boolean incremental)
      throws IOException {
    assumeThat(AppleNativeIntegrationTestUtils.isSwiftAvailable(ApplePlatform.MACOSX), is(true));
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "helloworld", tmp);
    workspace.addBuckConfigLocalOption("swift", "incremental", incremental ? "true" : "false");
    workspace.setUp();

    ProcessResult runResult = workspace.runBuckCommand("run", ":hello-bin#macosx-x86_64");
    runResult.assertSuccess();
    assertThat(runResult.getStdout(), equalTo("Hello, \uD83C\uDF0E!\n"));
  }

  @Test
  public void changingSourceOfSwiftLibraryDepRelinksBinaryWMO() throws IOException {
    changingSourceOfSwiftLibraryDepRelinksBinaryImpl(false);
  }

  @Test
  public void changingSourceOfSwiftLibraryDepRelinksBinaryIncremental() throws IOException {
    changingSourceOfSwiftLibraryDepRelinksBinaryImpl(true);
  }

  private void changingSourceOfSwiftLibraryDepRelinksBinaryImpl(boolean incremental)
      throws IOException {
    assumeThat(AppleNativeIntegrationTestUtils.isSwiftAvailable(ApplePlatform.MACOSX), is(true));
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "helloworld", tmp);
    workspace.addBuckConfigLocalOption("swift", "incremental", incremental ? "true" : "false");
    workspace.setUp();

    ProcessResult runResult = workspace.runBuckCommand("run", ":hello-bin#macosx-x86_64");
    runResult.assertSuccess();
    assertThat(runResult.getStdout(), equalTo("Hello, \uD83C\uDF0E!\n"));

    workspace.replaceFileContents("main.swift", "Hello", "Goodbye");

    ProcessResult secondRunResult = workspace.runBuckCommand("run", ":hello-bin#macosx-x86_64");
    secondRunResult.assertSuccess();
    assertThat(secondRunResult.getStdout(), equalTo("Goodbye, \uD83C\uDF0E!\n"));
  }

  @Test
  public void objcMixedSwiftRunsAndPrintsMessageOnOSXWMO() throws IOException {
    objcMixedSwiftRunsAndPrintsMessageOnOSXImpl(false);
  }

  @Test
  public void objcMixedSwiftRunsAndPrintsMessageOnOSXIncremental() throws IOException {
    objcMixedSwiftRunsAndPrintsMessageOnOSXImpl(true);
  }

  private void objcMixedSwiftRunsAndPrintsMessageOnOSXImpl(boolean incremental) throws IOException {
    assumeThat(AppleNativeIntegrationTestUtils.isSwiftAvailable(ApplePlatform.MACOSX), is(true));
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "objc_mix_swift", tmp);
    workspace.addBuckConfigLocalOption("swift", "incremental", incremental ? "true" : "false");
    workspace.setUp();
    workspace.addBuckConfigLocalOption("swift", "version", "4");

    ProcessResult runResult = workspace.runBuckCommand("run", ":DemoMix#macosx-x86_64");
    runResult.assertSuccess();
    assertThat(runResult.getStdout(), equalTo("Hello Swift\n"));
  }

  @Test
  public void swiftCallingObjCRunsAndPrintsMessageOnOSXWMO() throws IOException {
    swiftCallingObjCRunsAndPrintsMessageOnOSXImpl(false);
  }

  @Test
  public void swiftCallingObjCRunsAndPrintsMessageOnOSXIncremental() throws IOException {
    swiftCallingObjCRunsAndPrintsMessageOnOSXImpl(true);
  }

  private void swiftCallingObjCRunsAndPrintsMessageOnOSXImpl(boolean incremental)
      throws IOException {
    assumeThat(AppleNativeIntegrationTestUtils.isSwiftAvailable(ApplePlatform.MACOSX), is(true));
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "swift_calls_objc", tmp);
    workspace.addBuckConfigLocalOption("swift", "incremental", incremental ? "true" : "false");
    workspace.setUp();

    ProcessResult runResult = workspace.runBuckCommand("run", ":SwiftCallsObjC#macosx-x86_64");
    runResult.assertSuccess();
    assertThat(runResult.getStdout(), containsString("Hello ObjC\n"));
  }
}
