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
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeThat;

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.io.filesystem.BuckPaths;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;

/** Verifies that {@code buck build --build-report} works as intended. */
public class BuildReportIntegrationTest {
  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Rule public TemporaryPaths tmpFolderForBuildReport = new TemporaryPaths();

  @Test
  public void testBuildReportForSuccessfulBuild() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "build_report", tmp).setUp();

    AbsPath buildReport = tmpFolderForBuildReport.getRoot().resolve("build-report.txt");
    workspace
        .runBuckBuild(
            "--build-report",
            buildReport.toString(),
            "//:rule_with_output",
            "//:rule_without_output")
        .assertSuccess();

    assertTrue(Files.exists(buildReport.getPath()));

    TestUtils.assertBuildReport(
        workspace, tmp, buildReport, "expected_successful_build_report.json");
  }

  @Test
  public void testBuildReportWithFailure() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "build_report", tmp).setUp();

    AbsPath buildReport = tmpFolderForBuildReport.getRoot().resolve("build-report.txt");
    workspace
        .runBuckBuild(
            "--build-report", buildReport.toString(), "//:rule_with_output", "//:failing_rule")
        .assertFailure();

    TestUtils.assertBuildReport(workspace, tmp, buildReport, "expected_failed_build_report.json");
  }

  @Test
  public void testCompilerErrorIsIncluded() throws IOException {
    assumeThat(Platform.detect(), Matchers.not(Platform.WINDOWS));
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "build_report", tmp).setUp();

    AbsPath buildReport = tmpFolderForBuildReport.getRoot().resolve("build-report.txt");
    workspace
        .runBuckBuild("--build-report", buildReport.toString(), "//:failing_c_rule")
        .assertFailure();

    assertTrue(Files.exists(buildReport.getPath()));
    String buildReportContents =
        new String(Files.readAllBytes(buildReport.getPath()), StandardCharsets.UTF_8)
            .replace("\r\n", "\n");
    assertThat(buildReportContents, Matchers.containsString("stderr: failure.c"));
    assertThat(buildReportContents, Matchers.containsString("failure.c:2:3"));
  }

  @Test
  public void testOutputPathRelativeToRootCell() throws IOException {
    assumeThat(Platform.detect(), Matchers.not(Platform.WINDOWS));
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenarioWithoutDefaultCell(
            this, "build_report_with_cells", tmp);
    workspace.setUp();

    Path cell1Root = workspace.getPath("cell1");
    AbsPath buildReport = tmpFolderForBuildReport.getRoot().resolve("build-report.txt");

    ProcessResult buildResult =
        workspace.runBuckCommand(
            cell1Root, "build", "--build-report", buildReport.toString(), "cell2//:bar");
    buildResult.assertSuccess();

    assertTrue(Files.exists(buildReport.getPath()));
    JsonNode reportRoot =
        ObjectMappers.READER.readTree(ObjectMappers.createParser(buildReport.getPath()));

    assertEquals(
        "buck-out/cells/cell2/gen/"
            + BuildTargetPaths.getBasePath(
                    BuckPaths.DEFAULT_BUCK_OUT_INCLUDE_TARGET_CONFIG_HASH,
                    BuildTargetFactory.newInstance("cell2//:bar"),
                    "%s")
                .resolve("bar.txt"),
        reportRoot
            .get("results")
            .get("cell2//:bar")
            .get("outputs")
            .get("DEFAULT")
            .get(0)
            .textValue());
  }

  @Test
  public void multipleOutputPaths() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "build_report", tmp).setUp();
    RelPath expectedBasePath =
        BuildTargetPaths.getGenPath(
            workspace.getProjectFileSystem().getBuckPaths(),
            BuildTargetFactory.newInstance("//:rule_with_multiple_outputs"),
            "%s");
    AbsPath buildReport = tmpFolderForBuildReport.getRoot().resolve("build-report.txt");
    workspace
        .runBuckBuild("--build-report", buildReport.toString(), "//:rule_with_multiple_outputs")
        .assertSuccess();

    assertTrue(Files.exists(buildReport.getPath()));
    JsonNode reportRoot =
        ObjectMappers.READER.readTree(ObjectMappers.createParser(buildReport.getPath()));

    Map<String, List<String>> outputs =
        new ObjectMapper()
            .convertValue(
                reportRoot.get("results").get("//:rule_with_multiple_outputs").get("outputs"),
                Map.class);
    assertThat(
        outputs.get("output1"),
        Matchers.containsInAnyOrder(expectedBasePath.resolve("out1.txt").toString()));
    assertThat(
        outputs.get("output2"),
        Matchers.containsInAnyOrder(expectedBasePath.resolve("out2.txt").toString()));
    assertThat(
        outputs.get("DEFAULT"),
        Matchers.containsInAnyOrder(expectedBasePath.resolve("bar").toString()));
  }
}
