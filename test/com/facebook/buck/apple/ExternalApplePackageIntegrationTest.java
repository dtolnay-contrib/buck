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

package com.facebook.buck.apple;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.apple.toolchain.ApplePlatform;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;
import org.hamcrest.Matchers;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class ExternalApplePackageIntegrationTest {
  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Before
  public void setUp() {
    Assume.assumeThat(Platform.detect(), Matchers.is(Platform.MACOS));
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));
  }

  @Test
  public void usesExternalPackagerAndSetsSdkroot() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "external_apple_package", tmp);
    workspace.setUp();
    workspace.runBuckBuild("//:FooPackage#iphonesimulator-x86_64").assertSuccess();
    assertThat(
        workspace.getFileContents(
            BuildTargetPaths.getGenPath(
                    workspace.getProjectFileSystem().getBuckPaths(),
                    BuildTargetFactory.newInstance("//:FooPackage#iphonesimulator-x86_64"),
                    "%s")
                .resolve("FooPackage.omg")),
        matchesPattern("I AM A BUNDLE FROM .*/iPhoneSimulator\\.sdk .*/FooBundle.app\n"));
  }

  @Test
  public void useDefaultPlatformToDeterminePackagerIfPlatformFlavorIsOmitted() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "external_apple_package", tmp);
    workspace.setUp();
    workspace
        .runBuckBuild("--config=cxx.default_platform=iphonesimulator-x86_64", "//:FooPackage")
        .assertSuccess();
    assertThat(
        workspace.getFileContents(
            BuildTargetPaths.getGenPath(
                    workspace.getProjectFileSystem().getBuckPaths(),
                    BuildTargetFactory.newInstance("//:FooPackage"),
                    "%s")
                .resolve("FooPackage.omg")),
        matchesPattern("I AM A BUNDLE FROM .*/iPhoneSimulator\\.sdk .*/FooBundle.app\n"));
  }
}
