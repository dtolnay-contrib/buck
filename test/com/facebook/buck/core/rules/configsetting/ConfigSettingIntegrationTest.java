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

package com.facebook.buck.core.rules.configsetting;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class ConfigSettingIntegrationTest {

  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();
  @Rule public ExpectedException thrown = ExpectedException.none();

  private ProjectWorkspace setupWorkspace() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple_project", temporaryFolder);
    workspace.setUp();
    return workspace;
  }

  @Test
  public void testSelectWorksWithConfigurationValues() throws IOException {
    ProjectWorkspace workspace = setupWorkspace();

    Path output = workspace.buildAndReturnOutput("-c", "cat.file=a", ":cat");
    assertEquals("a", Files.readAllLines(output).get(0));

    output = workspace.buildAndReturnOutput("-c", "cat.file=b", ":cat");
    assertEquals("b", Files.readAllLines(output).get(0));
  }

  @Test
  public void testCanConcatListWithSelect() throws IOException {
    ProjectWorkspace workspace = setupWorkspace();

    Path output = workspace.buildAndReturnOutput("-c", "cat.file=a", ":select_concat_list");
    List<String> result = Files.readAllLines(output);
    assertEquals(2, result.size());
    assertTrue(result.contains("a"));
    assertTrue(result.contains("c"));

    output = workspace.buildAndReturnOutput("-c", "cat.file=b", ":select_concat_list");
    result = Files.readAllLines(output);
    assertEquals(2, result.size());
    assertTrue(result.contains("b"));
    assertTrue(result.contains("c"));
  }

  @Test
  public void testCanConcatStrWithSelect() throws IOException {
    ProjectWorkspace workspace = setupWorkspace();

    {
      Path output = workspace.buildAndReturnOutput("-c", "str.value=a", "//:select_concat_str");
      List<String> result = Files.readAllLines(output);
      assertEquals(ImmutableList.of("aa"), result);
    }

    {
      Path output = workspace.buildAndReturnOutput("-c", "str.value=b", "//:select_concat_str");
      List<String> result = Files.readAllLines(output);
      assertEquals(ImmutableList.of("bb"), result);
    }
  }

  @Test
  public void testUnresolvedConfigurationFailsTheBuild() throws IOException {
    ProjectWorkspace workspace = setupWorkspace();

    ProcessResult processResult = workspace.runBuckBuild(":cat");
    processResult.assertSuccess();
    assertThat(
        processResult.getStderr(),
        containsString("1 target skipped due to incompatibility with target configuration"));
  }

  @Test
  public void testDefaultIsUsedWhenNothingMatches() throws IOException {
    ProjectWorkspace workspace = setupWorkspace();

    Path output = workspace.buildAndReturnOutput(":cat_with_default");
    assertEquals("c", Files.readAllLines(output).get(0));
  }

  @Test
  public void testSelectLessSpecializedConfig() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple_project", temporaryFolder);
    workspace.setUp();

    Path output = workspace.buildAndReturnOutput("-c", "cat.file=b", ":cat_with_refined_config");
    assertEquals("b", Files.readAllLines(output).get(0));
  }

  @Test
  public void testSelectMoreSpecializedConfig() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple_project", temporaryFolder);
    workspace.setUp();

    Path output =
        workspace.buildAndReturnOutput(
            "-c", "cat.file=b", "-c", "cat.file2=c", ":cat_with_refined_config");
    assertEquals("c", Files.readAllLines(output).get(0));
  }

  @Test
  public void testNoneSetsValueToNull() throws IOException {
    ProjectWorkspace workspace = setupWorkspace();

    Path output = workspace.buildAndReturnOutput(":echo");
    assertEquals("cmd", Files.readAllLines(output).get(0).trim());

    output = workspace.buildAndReturnOutput("-c", "cat.file=a", ":echo");
    assertEquals("select", Files.readAllLines(output).get(0).trim());
  }

  @Test
  public void testConflictingConditionsWithNoneCauseError() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple_project", temporaryFolder);
    workspace.setUp();

    ProcessResult processResult =
        workspace.runBuckBuild("-c", "cat.file=a", "-c", "another.option=c", ":echo_with_one_none");
    processResult.assertFailure();
    assertThat(
        processResult.getStderr(),
        containsString(
            "When checking configurable attribute \"cmd\" in //:echo_with_one_none: "
                + "Ambiguous keys in select: //:a and //:c"));
  }

  @Test
  public void testConfigSettingCanResolveConstraints() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "project_with_constraints", temporaryFolder);
    workspace.setUp();

    Path output = workspace.buildAndReturnOutput("--target-platforms", "//:osx_x86_64", ":cat");
    assertEquals("a", Files.readAllLines(output).get(0));

    output = workspace.buildAndReturnOutput("--target-platforms", "//:linux_aarch64", ":cat");
    assertEquals("b", Files.readAllLines(output).get(0));
  }

  @Test
  public void testConfigSettingCanResolveConstraintsAndValues() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "project_with_constraints", temporaryFolder);
    workspace.setUp();

    Path output =
        workspace.buildAndReturnOutput(
            "-c",
            "cat.file=a",
            "--target-platforms",
            "//:osx_x86_64",
            ":cat_with_constraints_and_values");
    assertEquals("a", Files.readAllLines(output).get(0));

    output =
        workspace.buildAndReturnOutput(
            "-c",
            "cat.file=b",
            "--target-platforms",
            "//:linux_aarch64",
            ":cat_with_constraints_and_values");
    assertEquals("b", Files.readAllLines(output).get(0));
  }

  @Test
  public void testNonPlatformRuleCauseError() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "project_with_constraints", temporaryFolder);
    workspace.setUp();

    ProcessResult processResult =
        workspace.runBuckBuild("--target-platforms", "//:osx_config", ":cat");
    processResult.assertFailure();
    assertThat(
        processResult.getStderr(),
        containsString(
            "//:osx_config is used as a target platform, but not declared using a platform rule"));
  }

  @Test
  public void testNonMatchingPlatformCauseError() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "project_with_constraints", temporaryFolder);
    workspace.setUp();

    ProcessResult processResult =
        workspace.runBuckBuild("--target-platforms", "//:linux_arm", ":cat");
    processResult.assertSuccess();
    assertThat(
        processResult.getStderr(),
        containsString("1 target skipped due to incompatibility with target configuration"));
  }

  @Test
  public void testConfigSettingUsesMoreSpecializedConstraints() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "project_with_constraints", temporaryFolder);
    workspace.setUp();

    Path output =
        workspace.buildAndReturnOutput(
            "--target-platforms", "//:osx_x86_64", ":cat_with_specialized_constraints");
    assertEquals("b", Files.readAllLines(output).get(0));
  }

  @Test
  public void testConfigSettingUsesMoreSpecializedConfig() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "project_with_constraints", temporaryFolder);
    workspace.setUp();

    Path output =
        workspace.buildAndReturnOutput(
            "-c",
            "cat.file=a",
            "-c",
            "cat.file2=b",
            "--target-platforms",
            "//:osx_x86_64",
            ":cat_with_specialized_config");
    assertEquals("b", Files.readAllLines(output).get(0));
  }

  @Test
  public void testStringAttributeCanBeConcatenatedUsingSelects() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple_project", temporaryFolder);
    workspace.setUp();

    Path output =
        workspace.buildAndReturnOutput("-c", "cat.file=a", ":echo_with_concatenation_in_out");
    assertEquals("aac", output.getFileName().toString());

    output = workspace.buildAndReturnOutput("-c", "cat.file=b", ":echo_with_concatenation_in_out");
    assertEquals("abc", output.getFileName().toString());
  }

  @Test
  public void testOptionalAttributeCanBeConcatenatedUsingSelects() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple_project", temporaryFolder);
    workspace.setUp();

    workspace
        .runBuckBuild("-c", "java.version=7", ":java_library_with_target_version")
        .assertFailure();

    workspace
        .runBuckBuild("-c", "java.version=8", ":java_library_with_target_version")
        .assertSuccess();
  }

  @Test
  public void testStringWithMacrosAttributeCanBeConcatenatedUsingSelects() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple_project", temporaryFolder);
    workspace.setUp();

    Path output =
        workspace.buildAndReturnOutput("-c", "cat.file=a", ":echo_with_concatenation_in_cmd");
    assertEquals("a", Files.readAllLines(output).get(0).trim());

    output = workspace.buildAndReturnOutput("-c", "cat.file=b", ":echo_with_concatenation_in_cmd");
    assertEquals("b", Files.readAllLines(output).get(0).trim());
  }

  @Test
  public void testMapAttributeCanBeConcatenatedUsingSelects() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple_project", temporaryFolder);
    workspace.setUp();

    workspace
        .runBuckCommand("test", "-c", "cat.file=a", ":java_test_with_select_in_env")
        .assertSuccess();

    workspace
        .runBuckBuild("test", "-c", "cat.file=b", ":java_test_with_select_in_env")
        .assertFailure();
  }

  @Test
  public void testFailureWithDuplicateMapAttribute() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple_project", temporaryFolder);
    workspace.setUp();

    workspace
        .runBuckCommand(
            "test", "-c", "cat.file=a", ":java_test_with_duplicate_keys_in_select_in_env")
        .assertFailure("Duplicate keys found when trying to concatenate attributes: VARA");
  }

  @Test
  public void testBuildWorksWithNonExistingFiles() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple_project", temporaryFolder);
    workspace.setUp();

    Path output =
        workspace.buildAndReturnOutput("-c", "cat.file=a", ":genrule_with_non_existent_src");
    assertEquals("a", Files.readAllLines(output).get(0));
  }

  @Test
  public void testBuildFailsWithNonExistingFiles() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple_project", temporaryFolder);
    workspace.setUp();

    workspace
        .runBuckBuild("-c", "cat.file=b", ":genrule_with_non_existent_src")
        .assertFailure(
            "//:genrule_with_non_existent_src references non-existing file or directory 'd.txt'");
  }
}
