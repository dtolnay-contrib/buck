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

package com.facebook.buck.core.starlark.rule;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.ConfigurationBuildTargetFactoryForTests;
import com.facebook.buck.core.model.impl.BuildPaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Streams;
import com.sun.jna.Platform;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.function.BiFunction;
import java.util.regex.Pattern;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class SkylarkUserDefinedRuleIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Parameterized.Parameters(name = "{0}")
  public static Collection<Object[]> data() {
    return ImmutableList.of(
        new Object[] {"SKYLARK_WITHOUT_VERSIONS", false},
        new Object[] {"SKYLARK_WITH_VERSIONS", true});
  }

  @Parameterized.Parameter(value = 0)
  public String testName;

  @Parameterized.Parameter(value = 1)
  public boolean useVersions;

  ProjectWorkspace setupWorkspace(String scenario) throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, scenario, tmp);
    workspace.setUp();
    if (useVersions) {
      workspace.addBuckConfigLocalOption("build", "versions", "true");
    }
    return workspace;
  }

  @Test
  public void implementationFunctionIsCalledWithCtx() throws IOException {
    ProjectWorkspace workspace = setupWorkspace("implementation_is_called");

    workspace.runBuckBuild("//foo:bar").assertSuccess();

    ProcessResult failureRes = workspace.runBuckBuild("//foo:baz").assertFailure();
    assertThat(
        failureRes.getStderr(), Matchers.containsString("Expected to be called with name 'bar'"));
  }

  @Test
  public void implementationFunctionHasAccessToAttrs() throws IOException {
    ProjectWorkspace workspace = setupWorkspace("implementation_has_correct_attrs_in_ctx");

    workspace.runBuckBuild("//foo:").assertSuccess();
  }

  @Test
  public void printsProperly() throws IOException {

    ProjectWorkspace workspace = setupWorkspace("print_works_in_impl");

    ProcessResult result = workspace.runBuckBuild("//foo:prints").assertSuccess();
    assertThat(
        result.getStderr(),
        Matchers.matchesPattern(
            Pattern.compile(
                ".*^DEBUG: \\S+defs.bzl:4:10: printing at debug level.*",
                Pattern.MULTILINE | Pattern.DOTALL)));
  }

  @Test
  public void implementationFunctionCanDeclareFiles() throws IOException {
    ProjectWorkspace workspace = setupWorkspace("implementation_declares_artifacts");

    workspace.runBuckBuild("//foo:valid_filename").assertSuccess();
  }

  @Test
  public void implementationDeclareFilesFailsOnInvalidFiles() throws IOException {
    ProjectWorkspace workspace = setupWorkspace("implementation_declares_artifacts");

    assertThat(
        workspace.runBuckBuild("//foo:not_a_path").assertFailure().getStderr(),
        Matchers.containsString("Invalid path"));
    assertThat(
        workspace.runBuckBuild("//foo:rejected_path").assertFailure().getStderr(),
        Matchers.containsString("attempted to traverse upwards"));
  }

  @Test
  public void implementationWritesFilesProperly() throws IOException {
    ProjectWorkspace workspace = setupWorkspace("implementation_writes_files");

    ProjectFilesystem filesystem = workspace.getProjectFileSystem();

    Path exePath =
        BuildPaths.getGenDir(filesystem.getBuckPaths(), BuildTargetFactory.newInstance("//foo:exe"))
            .resolve("bar")
            .resolve("exe.sh");
    Path textPath =
        BuildPaths.getGenDir(
                filesystem.getBuckPaths(), BuildTargetFactory.newInstance("//foo:text"))
            .resolve("bar")
            .resolve("text.txt");
    Path withSpacesPath =
        BuildPaths.getGenDir(
                filesystem.getBuckPaths(), BuildTargetFactory.newInstance("//foo:with_spaces"))
            .resolve("bar")
            .resolve("with spaces.txt");
    Path exeStringPath =
        BuildPaths.getGenDir(
                filesystem.getBuckPaths(),
                BuildTargetFactory.newInstance("//foo:exe_string_output"))
            .resolve("bar")
            .resolve("exe.sh");
    Path textStringPath =
        BuildPaths.getGenDir(
                filesystem.getBuckPaths(),
                BuildTargetFactory.newInstance("//foo:text_string_output"))
            .resolve("bar")
            .resolve("text.txt");
    Path withSpacesStringPath =
        BuildPaths.getGenDir(
                filesystem.getBuckPaths(),
                BuildTargetFactory.newInstance("//foo:with_spaces_string_output"))
            .resolve("bar")
            .resolve("with spaces.txt");

    assertFalse(filesystem.exists(exePath));
    assertFalse(filesystem.exists(textPath));
    assertFalse(filesystem.exists(withSpacesPath));
    assertFalse(filesystem.exists(exeStringPath));
    assertFalse(filesystem.exists(textStringPath));
    assertFalse(filesystem.exists(withSpacesStringPath));

    workspace
        .runBuckBuild(
            "//foo:exe",
            "//foo:text",
            "//foo:with_spaces",
            "//foo:exe_string_output",
            "//foo:text_string_output",
            "//foo:with_spaces_string_output")
        .assertSuccess();

    assertEquals("exe content", filesystem.readFileIfItExists(filesystem.resolve(exePath)).get());
    assertEquals("text content", filesystem.readFileIfItExists(filesystem.resolve(textPath)).get());
    assertEquals(
        "with spaces content",
        filesystem.readFileIfItExists(filesystem.resolve(withSpacesPath)).get());
    assertEquals(
        "exe content", filesystem.readFileIfItExists(filesystem.resolve(exeStringPath)).get());
    assertEquals(
        "text content", filesystem.readFileIfItExists(filesystem.resolve(textStringPath)).get());
    assertEquals(
        "with spaces content",
        filesystem.readFileIfItExists(filesystem.resolve(withSpacesStringPath)).get());

    // Executable works a bit differently on windows
    if (!Platform.isWindows()) {
      assertTrue(filesystem.isExecutable(exePath));
      assertFalse(filesystem.isExecutable(textPath));
      assertFalse(filesystem.isExecutable(withSpacesPath));
      assertTrue(filesystem.isExecutable(exeStringPath));
      assertFalse(filesystem.isExecutable(textStringPath));
      assertFalse(filesystem.isExecutable(withSpacesStringPath));
    }
  }

  @Test
  public void builtInProvidersAreAvailableAtAnalysisTime() throws IOException {
    ProjectWorkspace workspace = setupWorkspace("implementation_return_values");

    ProcessResult result =
        workspace.runBuckBuild("//foo:can_use_providers_in_impl").assertSuccess();
    assertThat(result.getStderr(), Matchers.containsString("in bzl: DefaultInfo("));
  }

  @Test
  public void returnsAnErrorWhenNonListIsReturned() throws IOException {
    ProjectWorkspace workspace = setupWorkspace("implementation_return_values");

    assertThat(
        workspace.runBuckBuild("//foo:return_non_list").assertFailure().getStderr(),
        Matchers.containsString("got int, want sequence"));
  }

  @Test
  public void returnsAnErrorWhenItemInListIsNotProviderInfo() throws IOException {
    ProjectWorkspace workspace = setupWorkspace("implementation_return_values");

    assertThat(
        workspace.runBuckBuild("//foo:return_non_info_in_list").assertFailure().getStderr(),
        Matchers.containsString("got element of type int, want SkylarkProviderInfo"));
  }

  @Test
  public void returnsAnErrorWhenDuplicateProvidersReturned() throws IOException {
    ProjectWorkspace workspace = setupWorkspace("implementation_return_values");

    assertThat(
        workspace.runBuckBuild("//foo:return_duplicate_info_types").assertFailure().getStderr(),
        Matchers.containsString("returned two or more Info objects"));
  }

  @Test
  public void dependenciesAreAdded() throws IOException {
    ProjectWorkspace workspace = setupWorkspace("implementation_deps");

    ProcessResult depsQueryRes =
        workspace
            .runBuckCommand(
                "query",
                "--json",
                "deps(%s)",
                "//with_source_list:with_default_srcs",
                "//with_source_list:with_explicit_srcs",
                "//with_source:with_default_src",
                "//with_source:with_explicit_src",
                "//with_dep:with_default_dep",
                "//with_dep:with_explicit_dep",
                "//with_dep_list:with_default_deps",
                "//with_dep_list:with_explicit_deps")
            .assertSuccess();

    ProcessResult inputsQueryRes =
        workspace
            .runBuckCommand(
                "query",
                "--json",
                "inputs(%s)",
                "//with_source_list:with_default_srcs",
                "//with_source_list:with_explicit_srcs",
                "//with_source:with_default_src",
                "//with_source:with_explicit_src",
                "//with_dep:with_default_dep",
                "//with_dep:with_explicit_dep",
                "//with_dep_list:with_default_deps",
                "//with_dep_list:with_explicit_deps")
            .assertSuccess();

    BiFunction<JsonNode, String, ImmutableList<String>> toList =
        (JsonNode node, String field) ->
            Streams.stream(node.get(field).elements())
                .map(JsonNode::asText)
                .collect(ImmutableList.toImmutableList());

    JsonNode deps = ObjectMappers.READER.readTree(depsQueryRes.getStdout());
    JsonNode inputs = ObjectMappers.READER.readTree(inputsQueryRes.getStdout());

    assertThat(
        toList.apply(deps, "//with_source_list:with_default_srcs"),
        Matchers.containsInAnyOrder(
            "//with_source_list:default",
            "//with_source_list:with_default_srcs",
            "//with_source_list:hidden"));

    assertThat(
        toList.apply(deps, "//with_source_list:with_explicit_srcs"),
        Matchers.containsInAnyOrder(
            "//with_source_list:other",
            "//with_source_list:with_explicit_srcs",
            "//with_source_list:hidden"));

    assertThat(
        toList.apply(deps, "//with_source:with_default_src"),
        Matchers.containsInAnyOrder(
            "//with_source:default", "//with_source:with_default_src", "//with_source:hidden"));

    assertThat(
        toList.apply(deps, "//with_source:with_explicit_src"),
        Matchers.containsInAnyOrder(
            "//with_source:other", "//with_source:with_explicit_src", "//with_source:hidden"));

    assertThat(
        toList.apply(deps, "//with_dep:with_default_dep"),
        Matchers.containsInAnyOrder(
            "//with_dep:default", "//with_dep:with_default_dep", "//with_dep:hidden"));

    assertThat(
        toList.apply(deps, "//with_dep:with_explicit_dep"),
        Matchers.containsInAnyOrder(
            "//with_dep:other", "//with_dep:with_explicit_dep", "//with_dep:hidden"));

    assertThat(
        toList.apply(deps, "//with_dep_list:with_default_deps"),
        Matchers.containsInAnyOrder(
            "//with_dep_list:default", "//with_dep_list:with_default_deps"));

    assertThat(
        toList.apply(deps, "//with_dep_list:with_explicit_deps"),
        Matchers.containsInAnyOrder("//with_dep_list:other", "//with_dep_list:with_explicit_deps"));

    assertThat(
        toList.apply(inputs, "//with_source_list:with_default_srcs"),
        Matchers.containsInAnyOrder(
            Paths.get("with_source_list", "default_src.txt").toString(),
            Paths.get("with_source_list", "hidden_src.txt").toString()));

    assertThat(
        toList.apply(inputs, "//with_source_list:with_explicit_srcs"),
        Matchers.containsInAnyOrder(
            Paths.get("with_source_list", "some_src.txt").toString(),
            Paths.get("with_source_list", "hidden_src.txt").toString()));

    assertThat(
        toList.apply(inputs, "//with_source:with_default_src"),
        Matchers.containsInAnyOrder(
            Paths.get("with_source", "default_src.txt").toString(),
            Paths.get("with_source", "hidden_src.txt").toString()));

    assertThat(
        toList.apply(inputs, "//with_source:with_explicit_src"),
        Matchers.containsInAnyOrder(
            Paths.get("with_source", "some_src.txt").toString(),
            Paths.get("with_source", "hidden_src.txt").toString()));

    assertNull(inputs.get("//with_dep:with_default_dep"));
    assertNull(inputs.get("//with_dep:with_explicit_dep"));

    assertNull(inputs.get("//with_dep_list:with_default_deps"));
    assertNull(inputs.get("//with_dep_list:with_explicit_deps"));
  }

  @Test
  public void implementationGetsSourceFromSourceList() throws IOException {
    ProjectWorkspace workspace = setupWorkspace("implementation_gets_artifacts_from_source_list");
    ProjectFilesystem filesystem = workspace.getProjectFileSystem();

    workspace.runBuckBuild("//:with_sources").assertSuccess();

    assertEquals(
        "contents2",
        workspace.getFileContents(
            BuildPaths.getGenDir(
                    filesystem.getBuckPaths(), BuildTargetFactory.newInstance("//:with_sources"))
                .resolve("out2.txt")));
  }

  @Test
  public void implementationGetsArtifactFromSourceAttribute() throws IOException {
    ProjectWorkspace workspace = setupWorkspace("implementation_gets_artifacts_from_source");

    ProjectFilesystem filesystem = workspace.getProjectFileSystem();

    workspace.runBuckBuild("//:with_source").assertSuccess();

    assertEquals(
        "contents2",
        workspace.getFileContents(
            BuildPaths.getGenDir(
                    filesystem.getBuckPaths(), BuildTargetFactory.newInstance("//:with_source"))
                .resolve("out2.txt")));
  }

  @Test
  public void implementationGetsDepFromDepAttribute() throws IOException {
    ProjectWorkspace workspace = setupWorkspace("implementation_gets_dep_from_dep");

    ProjectFilesystem filesystem = workspace.getProjectFileSystem();

    workspace.runBuckBuild("//:with_dep").assertSuccess();

    assertEquals(
        "contents2",
        workspace.getFileContents(
            BuildPaths.getGenDir(
                    filesystem.getBuckPaths(), BuildTargetFactory.newInstance("//:with_dep"))
                .resolve("out2.txt")));
  }

  @Test
  public void implementationGetsProviderCollectionFromDepList() throws IOException {
    ProjectWorkspace workspace = setupWorkspace("implementation_gets_deps_from_dep_list");
    ProjectFilesystem filesystem = workspace.getProjectFileSystem();

    workspace.runBuckBuild("//:with_deps").assertSuccess();

    assertEquals(
        "contents2",
        workspace.getFileContents(
            BuildPaths.getGenDir(
                    filesystem.getBuckPaths(), BuildTargetFactory.newInstance("//:with_deps"))
                .resolve("out2.txt")));
  }

  private static ImmutableList<String> splitStderr(ProcessResult result) {
    return Splitter.on(System.lineSeparator()).splitToList(result.getStderr().trim()).stream()
        .map(String::trim)
        .collect(ImmutableList.toImmutableList());
  }

  @Test
  public void implementationCanRunCommands() throws IOException {
    ProjectWorkspace workspace = setupWorkspace("implementation_runs_actions");

    ProjectFilesystem filesystem = workspace.getProjectFileSystem();

    ProcessResult zeroResult = workspace.runBuckBuild("//foo:returning_zero").assertSuccess();
    ProcessResult oneResult = workspace.runBuckBuild("//foo:returning_one").assertFailure();
    ProcessResult zeroWithEnvResult =
        workspace.runBuckBuild("//foo:returning_zero_with_env").assertSuccess();
    ProcessResult oneWithEnvResult =
        workspace.runBuckBuild("//foo:returning_one_with_env").assertFailure();

    String[] expectedOneResult =
        new String[] {
          "Message on stderr",
          "arg[--out]",
          String.format(
              "arg[%s]",
              BuildPaths.getGenDir(
                      filesystem.getBuckPaths(),
                      BuildTargetFactory.newInstance("//foo:returning_one"))
                  .resolve("out.txt")),
          "arg[--bar]",
          "arg[some]",
          "arg[arg]",
          "arg[here]",
          String.format("PWD: %s", filesystem.getRootPath().toString())
        };

    String[] expectedOneWithEnvResult =
        new String[] {
          "Message on stderr",
          "arg[--out]",
          String.format(
              "arg[%s]",
              BuildPaths.getGenDir(
                      filesystem.getBuckPaths(),
                      BuildTargetFactory.newInstance("//foo:returning_one_with_env"))
                  .resolve("out.txt")),
          "arg[--bar]",
          "arg[some]",
          "arg[arg]",
          "arg[here]",
          String.format("PWD: %s", filesystem.getRootPath().toString()),
          "CUSTOM_ENV: CUSTOM"
        };

    assertThat(splitStderr(oneResult), Matchers.containsInRelativeOrder(expectedOneResult));
    assertThat(
        splitStderr(oneWithEnvResult), Matchers.containsInRelativeOrder(expectedOneWithEnvResult));
    // Make sure we're not spuriously printing output from the program on success
    assertFalse(zeroResult.getStderr().contains("CUSTOM_ENV"));
    assertFalse(zeroWithEnvResult.getStderr().contains("CUSTOM_ENV"));
  }

  @Test
  public void runActionFailsForInvalidParamTypes() throws IOException {
    ProjectWorkspace workspace = setupWorkspace("implementation_runs_actions");

    assertThat(
        workspace.runBuckBuild("//foo:invalid_arguments").assertFailure().getStderr(),
        Matchers.containsString("Invalid command line argument"));

    assertThat(
        workspace.runBuckBuild("//foo:invalid_env").assertFailure().getStderr(),
        Matchers.containsString("got dict<string, int> for 'env', want dict<string, string>"));

    assertThat(
        workspace.runBuckBuild("//foo:invalid_zeroargs").assertFailure().getStderr(),
        Matchers.containsString("At least one argument"));
  }

  @Test
  public void userDefinedProvidersCanBeUsedInProviderRestrictions() throws IOException {
    ProjectWorkspace workspace = setupWorkspace("user_defined_providers");

    workspace.runBuckBuild("//foo:does_not_require_content_info_missing").assertSuccess();
    workspace.runBuckBuild("//foo:does_not_require_content_info").assertSuccess();
    workspace.runBuckBuild("//foo:requires_content_info").assertSuccess();
    assertThat(
        workspace
            .runBuckBuild("//foo:requires_content_info_missing")
            // TODO(T47757795): Currently this causes fatal generic because we don't turn
            //                  VerifyException into an EvalException. Get logging / exceptions
            //                  working and tidy this up
            .assertExitCode(ExitCode.FATAL_GENERIC)
            .getStderr()
            .trim(),
        Matchers.containsString("expected provider ContentInfo to be present"));
  }

  @Test
  public void userDefinedProvidersArePassedBetweenDeps() throws IOException {
    ProjectWorkspace workspace = setupWorkspace("user_defined_providers");

    ProjectFilesystem filesystem = workspace.getProjectFileSystem();

    Path expectedLeafPath =
        BuildPaths.getGenDir(
                filesystem.getBuckPaths(), BuildTargetFactory.newInstance("//foo:leaf"))
            .resolve("leaf");

    workspace.runBuckBuild("//foo:leaf").assertSuccess();

    assertEquals(
        "from_root content + from_middle content",
        filesystem.readFileIfItExists(expectedLeafPath).get());
  }

  @Test
  public void compatibleWith() throws Exception {
    ProjectWorkspace workspace = setupWorkspace("compatible_with");

    ProjectFilesystem filesystem = workspace.getProjectFileSystem();

    ProcessResult result = workspace.runBuckBuild("//:file");
    result.assertFailure();

    assertThat(
        result.getStderr(),
        Matchers.containsString(
            "Cannot use select() expression when target platform is not specified"));

    ProcessResult result2 = workspace.runBuckBuild("--target-platforms=//:red-p", "//:file");
    result2.assertSuccess();

    Path expectedLeafPath =
        BuildPaths.getGenDir(
                filesystem.getBuckPaths(),
                BuildTargetFactory.newInstance(
                    "//:file",
                    ConfigurationBuildTargetFactoryForTests.newConfiguration("//:red-p")))
            .resolve("out.txt");

    assertEquals("contents", filesystem.readFileIfItExists(expectedLeafPath).get());
  }

  @Test
  public void implementationGetsArtifactFromOutputAttribute() throws IOException {
    ProjectWorkspace workspace = setupWorkspace("implementation_gets_artifacts_from_output");

    ProjectFilesystem filesystem = workspace.getProjectFileSystem();
    Path outputPath =
        BuildPaths.getGenDir(
                filesystem.getBuckPaths(), BuildTargetFactory.newInstance("//:with_contents"))
            .resolve("some_out.txt");

    workspace.runBuckBuild("//:with_contents").assertSuccess();
    assertEquals("some contents", workspace.getFileContents(outputPath));

    assertThat(
        workspace.runBuckBuild("//:without_contents").assertFailure().getStderr(),
        Matchers.containsString(
            "Artifact some_out.txt declared by //:without_contents is not bound to an action"));
    assertThat(
        workspace.runBuckBuild("//:invalid_path").assertFailure().getStderr(),
        Matchers.containsString(
            "Path 'foo\\u0000bar.txt' in target '//:invalid_path' is not valid"));
    assertThat(
        workspace.runBuckBuild("//:parent_path").assertFailure().getStderr(),
        Matchers.containsString(
            String.format(
                "Path '%s' in target '//:parent_path' attempted to traverse",
                Paths.get("..", "foo.txt"))));
    assertThat(
        workspace.runBuckBuild("//:dot_path").assertFailure().getStderr(),
        Matchers.containsString("Path '.' in target '//:dot_path' was empty"));
    assertThat(
        workspace.runBuckBuild("//:empty_path").assertFailure().getStderr(),
        Matchers.containsString("Path '' in target '//:empty_path' was empty"));
  }

  @Test
  public void implementationGetsArtifactsFromOutputListAttribute() throws IOException {
    ProjectWorkspace workspace = setupWorkspace("implementation_gets_artifacts_from_output_list");

    ProjectFilesystem filesystem = workspace.getProjectFileSystem();
    Path outputPath =
        BuildPaths.getGenDir(
                filesystem.getBuckPaths(), BuildTargetFactory.newInstance("//:with_contents"))
            .resolve("some_out.txt");

    workspace.runBuckBuild("//:with_contents").assertSuccess();
    assertEquals("some contents", workspace.getFileContents(outputPath));

    assertThat(
        workspace.runBuckBuild("//:without_contents").assertFailure().getStderr(),
        Matchers.containsString(
            "Artifact some_out.txt declared by //:without_contents is not bound to an action"));
    assertThat(
        workspace.runBuckBuild("//:invalid_path").assertFailure().getStderr(),
        Matchers.containsString(
            "Path 'foo\\u0000bar.txt' in target '//:invalid_path' is not valid"));
    assertThat(
        workspace.runBuckBuild("//:parent_path").assertFailure().getStderr(),
        Matchers.containsString(
            String.format(
                "Path '%s' in target '//:parent_path' attempted to traverse",
                Paths.get("..", "foo.txt"))));
    assertThat(
        workspace.runBuckBuild("//:dot_path").assertFailure().getStderr(),
        Matchers.containsString("Path '.' in target '//:dot_path' was empty"));
    assertThat(
        workspace.runBuckBuild("//:empty_path").assertFailure().getStderr(),
        Matchers.containsString("Path '' in target '//:empty_path' was empty"));
  }

  @Test
  public void copyFileCopiesFile() throws IOException {
    ProjectWorkspace workspace = setupWorkspace("implementation_copies_files");

    ProjectFilesystem filesystem = workspace.getProjectFileSystem();
    Path outputPath1 =
        BuildPaths.getGenDir(
                filesystem.getBuckPaths(), BuildTargetFactory.newInstance("//:write_string"))
            .resolve("out.txt");
    Path outputPath2 =
        BuildPaths.getGenDir(
                filesystem.getBuckPaths(), BuildTargetFactory.newInstance("//:copy_string"))
            .resolve("out_string.txt");
    Path outputPath3 =
        BuildPaths.getGenDir(
                filesystem.getBuckPaths(), BuildTargetFactory.newInstance("//:copy_artifact"))
            .resolve("out_artifact.txt");
    String expected = "some contents";

    workspace.runBuckBuild("//:copy_artifact").assertSuccess();
    assertEquals(expected, filesystem.readFileIfItExists(outputPath1).get().trim());
    assertEquals(expected, filesystem.readFileIfItExists(outputPath2).get().trim());
    assertEquals(expected, filesystem.readFileIfItExists(outputPath3).get().trim());
  }

  @Test
  public void runsIfRunInfoReturned() throws IOException {
    ProjectWorkspace workspace = setupWorkspace("implementation_handles_executable");

    ImmutableList<String> expectedWithRun =
        ImmutableList.of(
            "Message on stdout", "arg[rulearg1]", "arg[arg1]", "arg[arg2]", "CUSTOM_ENV: CUSTOM");
    ImmutableList<String> expectedWithoutRun =
        ImmutableList.of("Message on stdout", "arg[arg1]", "arg[arg2]", "CUSTOM_ENV: ");

    ImmutableList<String> actualReturnedDefaultAndRun =
        ImmutableList.copyOf(
            workspace
                .runBuckCommand("run", "//:with_default_and_run_info", "--", "arg1", "arg2")
                .assertSuccess()
                .getStdout()
                .split("\\r?\\n"));

    ImmutableList<String> actualReturnedDefault =
        ImmutableList.copyOf(
            workspace
                .runBuckCommand("run", "//:with_default_info", "--", "arg1", "arg2")
                .assertSuccess()
                .getStdout()
                .split("\\r?\\n"));

    ImmutableList<String> actualReturnedRun =
        ImmutableList.copyOf(
            workspace
                .runBuckCommand("run", "//:with_run_info", "--", "arg1", "arg2")
                .assertSuccess()
                .getStdout()
                .split("\\r?\\n"));

    ImmutableList<String> actualReturnedNeither =
        ImmutableList.copyOf(
            workspace
                .runBuckCommand(
                    "run", "//:with_implicit_default_and_run_info", "--", "arg1", "arg2")
                .assertSuccess()
                .getStdout()
                .split("\\r?\\n"));

    assertEquals(expectedWithRun, actualReturnedDefaultAndRun);
    assertEquals(expectedWithoutRun, actualReturnedDefault);
    assertEquals(expectedWithRun, actualReturnedRun);
    assertEquals(expectedWithoutRun, actualReturnedNeither);
  }

  @Test
  public void failsIfRunInfoReturnedOnInferringRule() throws IOException {
    ProjectWorkspace workspace = setupWorkspace("implementation_handles_executable");

    String runOutput =
        workspace
            .runBuckCommand("build", "//:with_inferring_and_explicit_run_info")
            .assertFailure()
            .getStderr();

    assertThat(
        runOutput,
        Matchers.containsString("specified `infer_run_info`, however a `RunInfo` object was"));
  }

  @Test
  public void failsIfRunInfoNotProvidedButZeroOrMoreThanOneArtifactsReturnedInDefaultInfo()
      throws IOException {
    ProjectWorkspace workspace = setupWorkspace("implementation_handles_executable");

    String zeroOutputsOut =
        workspace
            .runBuckCommand("build", "//:with_zero_outputs_implicit_run")
            .assertFailure()
            .getStderr();
    String twoOutputsOut =
        workspace
            .runBuckCommand("build", "//:with_two_outputs_implicit_run")
            .assertFailure()
            .getStderr();

    assertThat(
        zeroOutputsOut,
        Matchers.containsString(
            "This provider can only be inferred if the rule returns a single default"));
    assertThat(
        twoOutputsOut,
        Matchers.containsString(
            "This provider can only be inferred if the rule returns a single default"));
  }

  @Test
  public void testsIfTestAndRunInfoReturned() throws IOException {
    ProjectWorkspace workspace = setupWorkspace("implementation_handles_test");

    workspace
        .runBuckCommand("test", "//:implicit_default_implicit_run_implicit_test_info")
        .assertSuccess();
    workspace.runBuckCommand("test", "//:implicit_default_implicit_run_test_info").assertSuccess();
    workspace.runBuckCommand("test", "//:implicit_default_run_implicit_test_info").assertSuccess();
    workspace.runBuckCommand("test", "//:implicit_default_run_test_info").assertSuccess();
    workspace.runBuckCommand("test", "//:default_implicit_run_implicit_test_info").assertSuccess();
    workspace.runBuckCommand("test", "//:default_implicit_run_test_info").assertSuccess();
    workspace.runBuckCommand("test", "//:default_run_implicit_test_info").assertSuccess();
    workspace.runBuckCommand("test", "//:default_run_test_info").assertSuccess();
    workspace.runBuckCommand("test", "//:failing_test").assertTestFailure();
  }

  @Test
  public void failsIfTestInfoReturnedOnNonTestRule() throws IOException {
    ProjectWorkspace workspace = setupWorkspace("implementation_handles_test");

    String stderr =
        workspace.runBuckCommand("test", "//:nontest_with_test").assertFailure().getStderr();

    assertThat(stderr, Matchers.containsString("Please mark it as a test rule "));
  }

  @Test
  public void failsIfTestInfoReturnedButNoRunInfo() throws IOException {
    ProjectWorkspace workspace = setupWorkspace("implementation_handles_test");

    String stderr =
        workspace.runBuckCommand("test", "//:test_without_run_info").assertFailure().getStderr();

    assertThat(
        stderr,
        Matchers.containsString(
            "Either set `infer_run_info` to True to make Buck infer a RunInfo"));
  }

  @Test
  public void testRulesCanBeRun() throws IOException {
    ProjectWorkspace workspace = setupWorkspace("implementation_handles_test");

    workspace.runBuckCommand("run", "//:default_run_test_info").assertSuccess();
  }

  @Test
  public void repeatedRunsWork() throws IOException {
    ProjectWorkspace workspace = setupWorkspace("simple_rule");

    // Ensure that we persist the known rule types cache across invocations of buckd.
    // If we don't, then the rule_3 build will fail to find the UDR properly
    workspace.runBuckBuild("//foo:rule_1").assertSuccess();
    workspace.runBuckCommand("build", "//foo:rule_2").assertSuccess();
    workspace.runBuckCommand("build", "//foo:rule_3").assertSuccess();
  }

  @Test
  public void notJustList() throws Exception {
    // Check that UDR is OK to accept any sequence when it declares that it accepts
    // list or set

    ProjectWorkspace workspace = setupWorkspace("not_just_list");
    workspace.runBuckBuild("//...").assertSuccess();
  }
}
