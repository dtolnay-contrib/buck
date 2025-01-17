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

package com.facebook.buck.features.gwt;

import com.facebook.buck.jvm.java.testutil.AbiCompilationModeTest;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.testutil.integration.ZipInspector;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;

public class GwtBinaryIntegrationTest extends AbiCompilationModeTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Test(timeout = (2 * 60 * 1000))
  public void shouldBeAbleToBuildAGwtBinary() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "gwt_binary", tmp);
    workspace.setUp();
    setWorkspaceCompilationMode(workspace);
    workspace.enableDirCache();

    workspace.runBuckBuild("//:binary").assertSuccess();
    workspace.deleteRecursivelyIfExists("buck-out/annotation");
    workspace.runBuckCommand("clean", "--keep-cache");
    workspace.copyFile(
        "com/example/gwt/emul/java/io/DataOutputStream.java.new",
        "com/example/gwt/emul/java/io/DataOutputStream.java");
    workspace.runBuckBuild("//:binary").assertSuccess();

    Path zip = workspace.buildAndReturnOutput("//:binary");
    ZipInspector inspector = new ZipInspector(zip);
    inspector.assertFileExists("a/a.devmode.js");
    inspector.assertFileExists("a/a.nocache.js");
    inspector.assertFileExists("a/clear.cache.gif");
    inspector.assertFileExists("a/compilation-mappings.txt");
  }
}
