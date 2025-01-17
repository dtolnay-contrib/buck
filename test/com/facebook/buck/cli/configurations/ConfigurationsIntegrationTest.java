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

package com.facebook.buck.cli.configurations;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.android.AssumeAndroidPlatform;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.MoreStringsForTests;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.string.MoreStrings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;

public class ConfigurationsIntegrationTest {
  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void targetsInFileFilteredByConfigs() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "builds_with_target_filtering", tmp);
    workspace.setUp();

    workspace
        .runBuckCommand("build", "--target-platforms", "//config:osx_x86_64", "//compatible_with:")
        .assertSuccess();

    workspace.getBuildLog().assertTargetBuiltLocally("//compatible_with:cat_on_osx");
    workspace.getBuildLog().assertTargetIsAbsent("//compatible_with:cat_on_linux");

    workspace
        .runBuckCommand(
            "build", "--target-platforms", "//config:linux_x86_64", "//compatible_with:")
        .assertSuccess();

    workspace.getBuildLog().assertTargetBuiltLocally("//compatible_with:cat_on_linux");
    workspace.getBuildLog().assertTargetIsAbsent("//compatible_with:cat_on_osx");
  }

  @Test
  public void configurationRulesNotIncludedWhenBuildingUsingPattern() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "project_with_configuration_rules", tmp);
    workspace.setUp();

    workspace.runBuckCommand("build", ":").assertSuccess();
    ImmutableSet<BuildTarget> targets = workspace.getBuildLog().getAllTargets();

    assertEquals(1, targets.size());
    assertEquals("//:echo", Iterables.getOnlyElement(targets).toString());
  }

  @Test
  public void buildDoesNotFailWhenDepDoesNotMatchTargetPlatformAndChecksAreDisables()
      throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "builds_with_constraints", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "build",
            "--target-platforms",
            "//config:osx_x86-64",
            "-c",
            "parser.enable_target_compatibility_checks=false",
            "//:lib");
    result.assertSuccess();
  }

  @Test
  public void buildFailsWhenDepCompatiblePlatformDoesNotMatchTargetPlatform() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "builds_with_constraints", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "build", "--target-platforms", "//config:osx_x86-64", "//:lib_with_compatible_with");
    result.assertFailure();
    assertThat(
        result.getStderr(),
        MoreStringsForTests.containsIgnoringPlatformNewlines(
            "Build target //:dep_with_compatible_with is restricted to constraints "
                + "in \"compatible_with\" "
                + "that do not match the target platform //config:osx_x86-64.\n"
                + "Target compatible with configurations:\n//config:linux_config"));
  }

  @Test
  public void testIncompleteSelectGetsFilteredIfIncompatible() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "builds_with_target_filtering", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "build",
            "--target-platforms",
            "//config:linux_x86_64",
            "//compatible_with:constrained_select");
    result.assertSuccess();
    assertThat(result.getStderr(), Matchers.containsString("1 target skipped"));
  }

  @Test
  public void selectWithoutTargetPlatform() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "select_without_target_platform", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("build", "//:test-library");
    result.assertFailure();
    assertThat(
        result.getStderr(),
        MoreStringsForTests.containsIgnoringPlatformNewlines(
            "Cannot use select() expression when target platform is not specified\n"
                + "    At //:test-library"));
  }

  @Test
  public void buildFailsWhenNonConfigurableAttributeUsesSelect() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "builds_with_constraints", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("build", "//invalid:lib");
    result.assertFailure();
    assertThat(
        result.getStderr(),
        Matchers.containsString(
            "//invalid:lib: attribute 'compatible_with' cannot be configured using select"));
  }

  @Test
  public void changingTargetPlatformTriggersRebuild() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "builds_with_constraints", tmp);
    workspace.setUp();

    workspace.runBuckBuild(
        "--target-platforms", "//config:osx_x86-64", "//:platform_dependent_genrule");

    workspace.getBuildLog().assertTargetBuiltLocally("//:platform_dependent_genrule");

    workspace.runBuckBuild(
        "--target-platforms", "//config:linux_x86-64", "//:platform_dependent_genrule");

    workspace.getBuildLog().assertTargetBuiltLocally("//:platform_dependent_genrule");
  }

  @Test
  public void platformWithCircularDepTriggersFailure() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "builds_with_constraints", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckBuild(
            "--target-platforms",
            "//config:platform-with-circular-dep",
            "//:platform_dependent_genrule");

    result.assertFailure();
    assertThat(
        result.getStderr(),
        MoreStringsForTests.containsIgnoringPlatformNewlines(
            "Buck can't handle circular dependencies.\n"
                + "The following circular dependency has been found:\n"
                + "//config:platform-with-circular-dep -> //config:platform-with-circular-dep"));
  }

  @Test
  public void cpuConstraintsAreResolvedWithCustomHostPlatforms() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "builds_with_constraints", tmp);
    workspace.setUp();

    Path output =
        workspace.buildAndReturnOutput(
            "//:cpu_dependent_genrule", "--target-platforms", "//config:osx_x86-64");

    workspace.getBuildLog().assertTargetBuiltLocally("//:cpu_dependent_genrule");

    assertEquals("x86_64", workspace.getFileContents(output).trim());
  }

  @Test
  public void buildSucceedsWhenDepMatchesTargetPlatform() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "builds_with_constraints", tmp);
    workspace.setUp();

    workspace
        .runBuckCommand("build", "--target-platforms", "//config:linux_x86-64", "//:lib")
        .assertSuccess();
  }

  @Test
  public void buildConfigurationRule() throws Exception {
    // Test we can "build" configuration rules.
    // Not practically useful, but convenient if rule returned by query for example, and safe

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "build_configuration_rule", tmp);
    workspace.setUp();

    workspace.runBuckCommand("build", "//:os").assertSuccess();
  }

  @Test
  public void defaultTargetPlatformIsAppliedWhenNoTargetPlatformSpecified() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "builds_with_constraints", tmp);
    workspace.setUp();

    workspace.runBuckCommand("build", "//:lib-with-default-target-platform").assertSuccess();
  }

  @Test
  public void targetPlatformOverridesDefaultTargetPlatform() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "builds_with_constraints", tmp);
    workspace.setUp();

    workspace
        .runBuckCommand(
            "build",
            "--target-platforms",
            "//config:linux_x86-64",
            "//:lib-with-default-target-platform-useless")
        .assertSuccess();
  }

  @Test
  public void defaultTargetPlatformAppliesOnlyToRequestedTargets() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "builds_with_constraints", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("build", "//default_platform_only_leaf:leaf");
    result.assertFailure();

    // TODO(nga): Error is correctly produced by "dep" compatibility check
    // but the error message is incorrect.
    assertThat(
        result.getStderr(),
        MoreStringsForTests.containsIgnoringPlatformNewlines(
            "Cannot use select() expression when target platform is not specified\n"
                + "    At //default_platform_only_leaf:dep (builtin//platform:unconfigured)\n"
                + "    At //default_platform_only_leaf:intermediate (builtin//platform:unconfigured)\n"
                + "    At //default_platform_only_leaf:leaf (builtin//platform:unconfigured)"));
  }

  @Test
  public void testIncompatibleFilteringLogging() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "builds_with_target_filtering", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "build", "--target-platforms", "//config:linux_x86_64", "//compatible_with:cat_on_osx");
    result.assertSuccess();
    assertThat(
        result.getStderr(),
        Matchers.containsString(
            "1 target skipped due to incompatibility with target configuration"));
  }

  @Test
  public void defaultTargetPlatformInAndroidBinaryWithVersions() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "android_binary_default_t_p_versions", tmp);

    workspace.setUp();

    AssumeAndroidPlatform.get(workspace).assumeNdkIsAvailable();

    ProcessResult result = workspace.runBuckBuild("//:b");
    result.assertSuccess();
  }

  @Test
  public void changesInConfigurationRulesAreDetected() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "builds_with_constraints", tmp);
    workspace.setUp();

    Path output =
        workspace.buildAndReturnOutput(
            "//:platform_dependent_genrule", "--target-platforms", "//config-change:linux_x86-64");
    String linuxOutput = String.join(" ", Files.readAllLines(output)).trim();
    workspace.getBuildLog().assertTargetBuiltLocally("//:platform_dependent_genrule");

    assertEquals("linux", linuxOutput);

    workspace.writeContentsToPath(
        "platform(\n"
            + "    name = \"linux\",\n"
            + "    constraint_values = [\n"
            + "        \"buck//config/constraints:osx\",\n"
            + "    ],\n"
            + "    visibility = [\"PUBLIC\"],\n"
            + ")\n",
        "config-change/platform-dep/BUCK");

    output =
        workspace.buildAndReturnOutput(
            "//:platform_dependent_genrule", "--target-platforms", "//config-change:linux_x86-64");
    String osxOutput = String.join(" ", Files.readAllLines(output)).trim();
    workspace.getBuildLog().assertTargetBuiltLocally("//:platform_dependent_genrule");

    assertEquals("osx", osxOutput);
  }

  @Test
  public void wrongRuleType() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "wrong_rule_type", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckBuild("--target-platforms=//:p", "//:j");
    result.assertFailure();
    assertThat(
        result.getStderr(),
        Matchers.containsString(
            "requested rule //:c1 of type constraint_setting, but it was constraint_value"));
  }

  @Test
  public void cycle() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "cycle", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckBuild("--target-platforms=//:p", "//:j");
    result.assertFailure();
    assertThat(
        result.getStderr().replace("\r\n", "\n"),
        Matchers.containsString(
            "cycle detected when resolving configuration rule\n"
                + "    At //:cs\n"
                + "    At //:c2\n"
                + "    At //:cs\n"));
  }

  @Test
  public void requireTargetPlatformBuildFailure() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "require_target_platform", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckBuild("//:j");
    result.assertFailure();
    assertThat(
        result.getStderr(),
        Matchers.containsString(
            "parser.require_target_platform=true, "
                + "but global --target-platforms= is not specified "
                + "and target //:j does not specify default_target_platform"));
  }

  @Test
  public void requireTargetPlatformSpecifiedAtCommandLine() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "require_target_platform", tmp);
    workspace.setUp();

    // Good if specified on command line
    ProcessResult result = workspace.runBuckBuild("--target-platforms=//:p", "//:j");
    result.assertSuccess();
  }

  @Test
  public void buckconfigSpecifiesTargetPlatforms() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "buckconfig_specifies_target_platforms", tmp);
    workspace.setUp();

    // By default target platform is taken from buckconfig
    Path fromBuckconfig = workspace.buildAndReturnOutput("//:g");
    assertEquals(ImmutableList.of("from-buckconfig"), Files.readAllLines(fromBuckconfig));

    // Command line overrides buckconfig
    Path fromCmdline = workspace.buildAndReturnOutput("--target-platforms=//:p-cmdline", "//:g");
    assertEquals(ImmutableList.of("from-cmdline"), Files.readAllLines(fromCmdline));
  }

  @Test
  public void requireTargetPlatformDefaultTargetPlatform() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "require_target_platform", tmp);
    workspace.setUp();

    // Good if specified per target
    ProcessResult result = workspace.runBuckBuild("//:j-with-default-t-p");
    result.assertSuccess();
  }

  @Test
  public void configSettingUniqueConstraintSettings() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "config_setting_unique", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckBuild("--target-platforms=//:p", "//:j");
    result.assertFailure();
    assertThat(
        result.getStderr(),
        Matchers.containsString(
            "in config_setting rule //:c: "
                + "Duplicate constraint values detected: "
                + "constraint_setting //:pet has [//:cat, //:dog]"));
  }

  @Test
  public void platformUniqueConstraintSettings() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "platform_unique", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckBuild("--target-platforms=//:p", "//:j");
    result.assertFailure();
    assertThat(
        result.getStderr(),
        Matchers.containsString(
            "in platform rule //:p: "
                + "Duplicate constraint values detected: "
                + "constraint_setting //:pet has [//:cat, //:dog]"));
  }

  @Test
  public void detectorByTarget() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "detector_by_target", tmp);
    workspace.setUp();

    ImmutableMap<String, Path> result = workspace.buildMultipleAndReturnOutputs("//...");
    assertEquals(ImmutableList.of("foo-p"), Files.readAllLines(result.get("//foo:foo")));
    assertEquals(ImmutableList.of("bar-p"), Files.readAllLines(result.get("//bar/baz:baz")));
  }

  @Test
  public void targetPlatformsTwice() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "target_platforms_twice", tmp);
    workspace.setUp();

    workspace
        .runBuckBuild("--target-platforms=//:p", "--target-platforms=//:p", "//:j")
        .assertSuccess();
  }

  @Test
  public void prohibitNonUniqueConfAndFlavorInQuery() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "non_unique_conf_and_flavor_deny", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "query", "-c", "project.buck_out_include_target_config_hash=false", "deps(//...)");
    result.assertFailure();
    assertThat(
        result.getStderr(),
        Matchers.matchesPattern(
            "(?s).*Target //:j has more than one configurations \\(//:p-. and //:p-.\\)"
                + " with the same set of flavors \\[\\].*"));
  }

  @Test
  public void prohibitNonUniqueConfAndFlavorInBuild() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "non_unique_conf_and_flavor_deny", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "build", "-c", "project.buck_out_include_target_config_hash=false", "//...");
    result.assertFailure();
    assertThat(
        result.getStderr(),
        Matchers.matchesPattern(
            "(?s).*Target //:j has more than one configurations \\(//:p-. and //:p-.\\)"
                + " with the same set of flavors \\[\\].*"));
  }

  @Test
  public void allowNonUniqueConfAndFlavorInQuery() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "non_unique_conf_and_flavor_deny", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("query", "deps(//...)");
    result.assertSuccess();
    assertTrue(MoreStrings.lines(result.getStdout()).contains("//:j"));
    assertTrue(MoreStrings.lines(result.getStdout()).contains("//:k"));
  }

  @Test
  public void allowNonUniqueConfAndFlavorInBuild() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "non_unique_conf_and_flavor_deny", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("build", "//...");
    result.assertSuccess();
    workspace.getBuildLog().assertTargetBuiltLocally("//:j");
    workspace.getBuildLog().assertTargetBuiltLocally("//:k");
  }

  @Test
  public void allowNonUniqueTargersWithDifferentFlavorsInQuery() throws Exception {
    // To avoid test failures on Windows because of missing compiler
    Assume.assumeThat(Platform.detect(), is(not(Platform.WINDOWS)));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "non_unique_conf_and_flavor_allow", tmp);
    workspace.setUp();

    workspace.runBuckCommand("query", "deps(//...)").assertSuccess();
  }

  @Test
  public void allowNonUniqueTargersWithDifferentFlavorsInBuild() throws Exception {
    // To avoid test failures on Windows because of missing compiler
    Assume.assumeThat(Platform.detect(), is(not(Platform.WINDOWS)));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "non_unique_conf_and_flavor_allow", tmp);
    workspace.setUp();

    workspace.runBuckCommand("build", "//...").assertSuccess();
  }

  @Test
  public void unconfiguredTargetConfiguration() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "unconfigured_target_configuration", tmp);
    workspace.setUp();

    workspace.runBuckCommand("build", "//:j").assertSuccess();

    ProcessResult result =
        workspace.runBuckCommand(
            "build", "--target-platforms=builtin//platform:unconfigured", "//:j");
    result.assertFailure();
    assertThat(
        result.getStderr(),
        Matchers.containsString(
            "Cannot use select() expression when target platform is not specified"));
  }

  @Test
  public void exeTarget() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "exe_target", tmp);
    workspace.setUp();

    Path outTxt =
        workspace.buildAndReturnOutput("--target-platforms=//:t", "--host-platform=//:h", "//:g");
    List<String> lines = Files.readAllLines(outTxt);
    assertEquals(ImmutableList.of("tttarget"), lines);
  }

  @Test
  public void exeSwitchesToHost() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "exe_switches_to_host", tmp);
    workspace.setUp();

    Path outTxt =
        workspace.buildAndReturnOutput("--target-platforms=//:t", "--host-platform=//:h", "//:g");
    List<String> lines = Files.readAllLines(outTxt);
    assertEquals(ImmutableList.of("hhhost"), lines);
  }

  @Test
  public void exeSwitchesToHostNoHostPlatform() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "exe_switches_to_host", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckBuild("--target-platforms=//:t", "//:g");
    result.assertFailure();
    assertThat(
        result.getStderr(),
        MoreStringsForTests.containsIgnoringPlatformNewlines(
            "Cannot use select() expression when target platform is not specified\n"
                + "    At //:b"));
  }

  @Test
  public void hostPlatformDetector() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "host_platform_detector", tmp);
    workspace.setUp();

    // Not specifying target platform and host platform is detected
    Path result = workspace.buildAndReturnOutput("//:g");
    String actual = Files.readAllLines(result).get(0);
    String expected;
    switch (Platform.detect()) {
      case LINUX:
        expected = "lllinux";
        break;
      case WINDOWS:
        expected = "wwwindows";
        break;
      case MACOS:
        expected = "mmmacos";
        break;
      default:
        throw new AssertionError();
    }

    assertEquals(expected, actual);
  }

  @Test
  public void defaultHostPlatform() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "exe_switches_to_host", tmp);
    workspace.setUp();

    // Test that the `default_host_platform` attribute preempts a globally configured host platform.
    Path outTxt =
        workspace.buildAndReturnOutput("--target-platforms=//:t", "--host-platform=//:t", "//:n");
    List<String> lines = Files.readAllLines(outTxt);
    assertEquals(ImmutableList.of("hhhost"), lines);

    // Test that the `default_host_platform` attribute preempts the host platform detector spec.
    outTxt =
        workspace.buildAndReturnOutput(
            "--target-platforms=//:t",
            "-c",
            "parser.host_configuration_switch_enabled=true",
            "-c",
            "parser.host_platform_detector_spec=host_os:linux->//:t host_os:windows->//:t host_os:darwin->//:t",
            "//:n");
    lines = Files.readAllLines(outTxt);
    assertEquals(ImmutableList.of("hhhost"), lines);
  }

  @Test
  public void selectKeysSupersetCompatibleWith() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "select_keys_superset_compatible_with", tmp);
    workspace.setUp();

    ProcessResult buildResult = workspace.runBuckBuild("--target-platforms=//:p-linux", "//:g");
    buildResult.assertFailure();
    assertThat(
        buildResult.getStderr(),
        MoreStringsForTests.containsIgnoringPlatformNewlines(
            "When checking configurable attribute \"cmd\" in //:g:"
                + " select keys space must be a superset of compatible_with space; uncovered compatible_with entry: //:windows\n"
                + "    At //:g"));

    ProcessResult queryResult =
        workspace.runBuckCommand("cquery", "--target-platforms=//:p-linux", "//:g");
    queryResult.assertFailure();
    assertThat(
        queryResult.getStderr(),
        MoreStringsForTests.containsIgnoringPlatformNewlines(
            "When checking configurable attribute \"cmd\" in //:g:"
                + " select keys space must be a superset of compatible_with space; uncovered compatible_with entry: //:windows\n"
                + "    At //:g"));
  }
}
