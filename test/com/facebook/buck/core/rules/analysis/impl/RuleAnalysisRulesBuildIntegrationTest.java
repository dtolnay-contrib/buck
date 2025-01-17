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

package com.facebook.buck.core.rules.analysis.impl;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.description.Description;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rules.knowntypes.KnownNativeRuleTypes;
import com.facebook.buck.test.TestResultSummary;
import com.facebook.buck.test.result.type.ResultType;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import org.hamcrest.Matcher;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;

public class RuleAnalysisRulesBuildIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void ruleAnalysisRuleBuilds() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "basic_rule", tmp);
    workspace.setUp();

    setKnownRuleTypesFactoryFactory(workspace, new FakeRuleRuleDescription());

    Path resultPath = workspace.buildAndReturnOutput("//:bar");
    assertEquals(ImmutableList.of("testcontent"), Files.readAllLines(resultPath));
  }

  @Test
  public void ruleAnalysisRuleWithDepsBuildsAndRebuildsOnChange() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "rule_with_deps", tmp);

    workspace.setUp();
    workspace.enableDirCache();

    setKnownRuleTypesFactoryFactory(workspace, new BasicRuleRuleDescription());

    Path resultPath = workspace.buildAndReturnOutput("//:bar");

    /**
     * we should get something like
     *
     * <pre>
     * {
     * target: bar
     * val: 1
     * srcs :{}
     * dep: {
     *    {
     *      target: baz
     *      val: 4
     *      srcs :{}
     *      dep: {}
     *      outputs: [ <hash>/baz__/output ]
     *    },
     *    {
     *      target: foo
     *      val: 2
     *      srcs :{}
     *      dep: {
     *        {
     *          target: baz
     *          val: 4
     *          srcs :{}
     *          dep: {}
     *          outputs: [ <hash>/baz__/output ]
     *        }
     *      }
     *      outputs: [ <hash>/foo__/output ]
     *    },
     *    {
     *      target: faz
     *      val: 0
     *      srcs :{}
     *      dep: {}
     *      outputs: [ <hash>/faz__/output ]
     *    },
     *  }
     *  outputs: [ <hash>/bar__/output ]
     * }
     * </pre>
     */
    RuleOutput output =
        new RuleOutput(
            "bar",
            1,
            ImmutableList.of(),
            ImmutableList.of(
                new RuleOutput(
                    "baz",
                    4,
                    ImmutableList.of(),
                    ImmutableList.of(),
                    ImmutableList.of(Paths.get("baz__", "output"))),
                new RuleOutput(
                    "foo",
                    2,
                    ImmutableList.of(),
                    ImmutableList.of(
                        new RuleOutput(
                            "baz",
                            4,
                            ImmutableList.of(),
                            ImmutableList.of(),
                            ImmutableList.of(Paths.get("baz__", "output")))),
                    ImmutableList.of(Paths.get("foo__", "output"))),
                new RuleOutput(
                    "faz",
                    0,
                    ImmutableList.of(),
                    ImmutableList.of(),
                    ImmutableList.of(Paths.get("dir", "faz__", "output")))),
            ImmutableList.of(Paths.get("bar__", "output")));

    assertJsonEquals(output, resultPath);

    // clean
    workspace.runBuckCommand("clean", "--keep-cache");

    // rebuild should be same result and cached
    resultPath = workspace.buildAndReturnOutput("//:bar");
    assertJsonEquals(output, resultPath);

    workspace.getBuildLog().assertTargetWasFetchedFromCache("//:bar");

    // now make a change

    workspace.writeContentsToPath(
        "basic_rule(\n"
            + "    name = \"faz\",\n"
            + "    val = 10,\n"
            + "    visibility = [\"PUBLIC\"],\n"
            + ")",
        "dir/BUCK");

    resultPath = workspace.buildAndReturnOutput("//:bar");

    /**
     * we should get something like
     *
     * <pre>
     * {
     * target: bar
     * val: 1
     * srcs :{}
     * dep: {
     *    {
     *      target: baz
     *      val: 4
     *      srcs: {}
     *      dep: {}
     *      outputs: [ <hash>/baz__/output ]
     *    },
     *    {
     *      target: foo
     *      val: 2
     *      srcs: {}
     *      dep: {
     *        {
     *          target: baz
     *          val: 4
     *          srcs: {}
     *          dep: {}
     *          outputs: [ <hash>/baz__/output ]
     *        }
     *      }
     *      outputs: [ <hash>/foo__/output ]
     *    },
     *    {
     *      target: faz
     *      val: 10
     *      srcs: {}
     *      dep: {}
     *      outputs: [ <hash>/faz__/output ]
     *    },
     *  }
     *  outputs: [ <hash>/bar__/output ]
     * }
     * </pre>
     */
    output =
        new RuleOutput(
            "bar",
            1,
            ImmutableList.of(),
            ImmutableList.of(
                new RuleOutput(
                    "baz",
                    4,
                    ImmutableList.of(),
                    ImmutableList.of(),
                    ImmutableList.of(Paths.get("baz__", "output"))),
                new RuleOutput(
                    "foo",
                    2,
                    ImmutableList.of(),
                    ImmutableList.of(
                        new RuleOutput(
                            "baz",
                            4,
                            ImmutableList.of(),
                            ImmutableList.of(),
                            ImmutableList.of(Paths.get("baz__", "output")))),
                    ImmutableList.of(Paths.get("foo__", "output"))),
                new RuleOutput(
                    "faz",
                    10,
                    ImmutableList.of(),
                    ImmutableList.of(),
                    ImmutableList.of(Paths.get("dir/faz__", "output")))),
            ImmutableList.of(Paths.get("bar__", "output")));

    assertJsonEquals(output, resultPath);
  }

  @Test
  public void ruleAnalysisRuleWithLegacyCompatibilityBuilds() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "rule_with_legacy_deps", tmp);

    workspace.setUp();
    setKnownRuleTypesFactoryFactory(
        workspace, new BasicRuleRuleDescription(), new LegacyRuleDescription());

    Path resultPath = workspace.buildAndReturnOutput("//:bar");

    /**
     * we should get something like
     *
     * <pre>
     * {
     * target: bar
     * val: 1
     * srcs :{}
     * dep: {
     *    {
     *      target: baz
     *      val: 4
     *      srcs :{}
     *      dep: {}
     *      outputs: [ <hash>/baz__/output ]
     *    },
     *    {
     *      target: foo
     *      val: 2
     *      srcs :{}
     *      dep: {
     *        {
     *          target: baz
     *          val: 4
     *          srcs :{}
     *          dep: {}
     *          outputs: [ <hash>/baz__/output ]
     *        }
     *      }
     *      outputs: [ <hash>/foo__/output ]
     *    },
     *    {
     *      target: faz
     *      val: 0
     *      srcs :{}
     *      dep: {}
     *    },
     *    outputs: [ <hash>/bar__/output ]
     *  }
     * }
     * </pre>
     */
    RuleOutput output =
        new RuleOutput(
            "bar",
            1,
            ImmutableList.of(),
            ImmutableList.of(
                new RuleOutput(
                    "baz",
                    4,
                    ImmutableList.of(),
                    ImmutableList.of(),
                    ImmutableList.of(Paths.get("baz__", "output"))),
                new RuleOutput(
                    "foo",
                    2,
                    ImmutableList.of(),
                    ImmutableList.of(
                        new RuleOutput(
                            "baz",
                            4,
                            ImmutableList.of(),
                            ImmutableList.of(),
                            ImmutableList.of(Paths.get("baz__", "output")))),
                    ImmutableList.of(Paths.get("foo__", "output"))),
                new RuleOutput(
                    "faz",
                    0,
                    ImmutableList.of(),
                    ImmutableList.of(),
                    ImmutableList.of(Paths.get("output")))),
            ImmutableList.of(Paths.get("bar__", "output")));

    assertJsonEquals(output, resultPath);
  }

  @Test
  public void ruleAnalysisRuleWithTargetSrcsBuildsAndRebuildsOnChange() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "rule_with_target_srcs", tmp);

    workspace.setUp();
    workspace.enableDirCache();

    setKnownRuleTypesFactoryFactory(workspace, new BasicRuleRuleDescription());

    Path resultPath = workspace.buildAndReturnOutput("//:bar");

    /**
     * we should get something like
     *
     * <pre>
     * {
     * target: bar
     * val: 1
     * srcs: { <hash>/dir/foo__/output }
     * deps: {}
     * outputs: [ <hash>/bar__/output ]
     * }
     */
    RuleOutput output =
        new RuleOutput(
            "bar",
            1,
            ImmutableList.of(Paths.get("foo__", "output")),
            ImmutableList.of(),
            ImmutableList.of(Paths.get("bar__", "output")));

    assertJsonEquals(output, resultPath);

    // clean
    workspace.runBuckCommand("clean", "--keep-cache");

    // rebuild should be same result and cached
    resultPath = workspace.buildAndReturnOutput("//:bar");
    assertJsonEquals(output, resultPath);

    workspace.getBuildLog().assertTargetWasFetchedFromCache("//:bar");

    // now make a change

    workspace.writeContentsToPath(
        "basic_rule(\n"
            + "    name = \"foo\",\n"
            + "    val = 2,\n"
            + "    visibility = [\"PUBLIC\"],\n"
            + "    default_outs = [ \"foo_out\" ],"
            + ")",
        "dir/BUCK");

    resultPath = workspace.buildAndReturnOutput("//:bar");

    /**
     * we should get something like
     *
     * <pre>
     * {
     * target: bar
     * val: 1
     * srcs: { <hash>/dir/foo__/foo_out }
     * deps: {}
     * outputs: [ <hash>/bar__/output ]
     * }
     */
    output =
        new RuleOutput(
            "bar",
            1,
            ImmutableList.of(Paths.get("foo__", "foo_out")),
            ImmutableList.of(),
            ImmutableList.of(Paths.get("bar__", "output")));

    assertJsonEquals(output, resultPath);
  }

  private void validateTestResults(
      ProjectWorkspace workspace,
      Path eventJsonPath,
      String expectedTarget,
      boolean expectedSuccess,
      ImmutableList<String> labels,
      ImmutableList<String> contacts,
      String testName,
      String testCaseName,
      int exitCode)
      throws IOException {

    ImmutableList<JsonNode> failureResults = parseTestResults(eventJsonPath);

    // Useful deserializers aren't present for these events.... bleh.
    assertEquals(1, failureResults.size());
    JsonNode failureResult = failureResults.get(0).get("results");

    BuildTarget actualTarget =
        BuildTargetFactory.newInstance(
            failureResult.get("buildTarget").get("baseName").asText(),
            failureResult.get("buildTarget").get("shortName").asText());

    assertEquals(BuildTargetFactory.newInstance(expectedTarget), actualTarget);

    assertEquals(expectedSuccess, failureResult.get("success").asBoolean());
    assertEquals(expectedSuccess ? 0 : 1, failureResult.get("failureCount").asInt());
    assertEquals(1, failureResult.get("totalNumberOfTests").asInt());
    assertEquals(
        contacts,
        ImmutableList.copyOf(failureResult.get("contacts").elements()).stream()
            .map(JsonNode::asText)
            .collect(ImmutableList.toImmutableList()));
    assertEquals(
        labels,
        ImmutableList.copyOf(failureResult.get("labels").elements()).stream()
            .map(JsonNode::asText)
            .collect(ImmutableList.toImmutableList()));

    JsonNode testCase = failureResult.get("testCases").get(0);
    assertEquals(expectedTarget, testCase.get("testCaseName").asText());
    assertEquals(0, testCase.get("skippedCount").asInt());
    assertEquals(expectedSuccess ? 0 : 1, testCase.get("failureCount").asInt());
    assertEquals(expectedSuccess, testCase.get("success").asBoolean());

    TestResultSummary result =
        ObjectMappers.READER.treeToValue(
            testCase.get("testResults").get(0), TestResultSummary.class);
    assertEquals(testCaseName, result.getTestCaseName());
    assertEquals(expectedSuccess ? ResultType.SUCCESS : ResultType.FAILURE, result.getType());
    assertEquals(testName, result.getTestName());

    String rootString = workspace.getProjectFileSystem().getRootPath().toString();
    ImmutableList<String> expected =
        ImmutableList.of(
            "PWD: " + rootString,
            "pwd: " + rootString,
            "ENV: some-string",
            "EXIT_CODE: " + exitCode,
            "arg[foo]",
            "arg[1]",
            "arg[//foo:bar]");

    assertEquals(expected, ImmutableList.copyOf(result.getStdOut().trim().split("\\r?\n")));
  }

  private ImmutableList<JsonNode> parseTestResults(Path pathToJson) throws IOException {
    return Files.readAllLines(pathToJson, StandardCharsets.UTF_8).stream()
        .map(
            line -> {
              try {
                return ObjectMappers.READER.readTree(line);
              } catch (IOException e) {
                throw new RuntimeException(e);
              }
            })
        .filter(node -> node.get("type").asText().equals("ResultsAvailable"))
        .collect(ImmutableList.toImmutableList());
  }

  @Test
  public void ruleAnalysisRulesCanReturnNamedOutputs() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "rule_with_named_outputs", tmp)
            .setUp();
    setKnownRuleTypesFactoryFactory(workspace, new BasicRuleRuleDescription());

    Path resultPath = workspace.buildAndReturnOutput("//:foo");
    assertTrue(resultPath.endsWith("d-d-default!!!"));
    resultPath = workspace.buildAndReturnOutput("//:foo[bar]");
    assertTrue(resultPath.endsWith("baz"));
    resultPath = workspace.buildAndReturnOutput("//:foo[qux]");
    assertTrue(resultPath.endsWith("quux"));

    RuleOutput expectedOutput =
        new RuleOutput(
            "foo",
            3,
            ImmutableList.of(),
            ImmutableList.of(),
            ImmutableList.of(
                Paths.get("foo__", "d-d-default!!!"),
                Paths.get("foo__", "baz"),
                Paths.get("foo__", "quux")));

    assertJsonEquals(expectedOutput, resultPath);
  }

  @Test
  public void ruleAnalysisRulesCanConsumeNamedOutputs() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "rule_with_named_outputs", tmp)
            .setUp();
    setKnownRuleTypesFactoryFactory(workspace, new BasicRuleRuleDescription());

    Path resultPath = workspace.buildAndReturnOutput("//:rule_with_named_output_src");
    assertTrue(resultPath.endsWith("dundundun"));

    RuleOutput expectedOutput =
        new RuleOutput(
            "rule_with_named_output_src",
            2,
            ImmutableList.of(Paths.get("foo__", "baz")),
            ImmutableList.of(),
            ImmutableList.of(Paths.get("rule_with_named_output_src__", "dundundun")));

    assertJsonEquals(expectedOutput, resultPath);
  }

  @Test
  public void ruleAnalysisRulesCanConsumeDefaultOutputs() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "rule_with_named_outputs", tmp)
            .setUp();
    setKnownRuleTypesFactoryFactory(workspace, new BasicRuleRuleDescription());

    Path resultPath = workspace.buildAndReturnOutput("//:rule_with_default_output_src");
    assertTrue(resultPath.endsWith("heeheehee"));

    RuleOutput expectedOutput =
        new RuleOutput(
            "rule_with_default_output_src",
            1,
            ImmutableList.of(Paths.get("foo__", "d-d-default!!!")),
            ImmutableList.of(),
            ImmutableList.of(Paths.get("rule_with_default_output_src__", "heeheehee")));

    assertJsonEquals(expectedOutput, resultPath);
  }

  @Test
  public void failsIfTryToConsumeNonexistentNamedOutput() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "rule_with_named_outputs", tmp)
            .setUp();
    setKnownRuleTypesFactoryFactory(workspace, new BasicRuleRuleDescription());

    ProcessResult result =
        workspace
            .runBuckBuild("//:rule_with_nonexistent_named_src")
            .assertExitCode(ExitCode.FATAL_GENERIC);
    assertThat(
        result.getStderr(),
        Matchers.containsString("Cannot find output label [bad] for target //:foo"));
  }

  private void setKnownRuleTypesFactoryFactory(
      ProjectWorkspace workspace, Description<?>... descriptions) {
    workspace.setKnownRuleTypesFactoryFactory(
        (executor,
            pluginManager,
            sandboxExecutionStrategyFactory,
            knownConfigurationDescriptions) ->
            cell ->
                KnownNativeRuleTypes.of(
                    ImmutableList.copyOf(descriptions), ImmutableList.of(), ImmutableList.of()));
  }

  private void assertJsonEquals(RuleOutput expected, Path actualJsonPath) throws IOException {
    JsonParser parser = ObjectMappers.createParser(actualJsonPath);
    Map<String, Object> data = parser.readValueAs(Map.class);
    parser.close();

    assertThat(data, ruleOutputToMatchers(expected));
  }

  private static class RuleOutput {
    final String target;
    final int val;
    final List<Path> srcs;
    final List<RuleOutput> deps;
    final List<Path> outputs;

    private RuleOutput(
        String target, int val, List<Path> srcs, List<RuleOutput> deps, List<Path> outputs) {
      this.target = target;
      this.val = val;
      this.srcs = srcs;
      this.deps = deps;
      this.outputs = outputs;
    }
  }

  private Matcher<Map<String, Object>> ruleOutputToMatchers(RuleOutput ruleOutput) {
    Matcher<Map<? extends String, ?>> targetMatcher =
        Matchers.hasEntry("target", ruleOutput.target);
    Matcher<Map<? extends String, ?>> valMatcher = Matchers.hasEntry("val", ruleOutput.val);

    Matcher<Object> srcs = createEndOfPathMatcher(ruleOutput.srcs);
    Matcher<Object> deps =
        (Matcher<Object>)
            (Matcher<?>)
                Matchers.containsInAnyOrder(
                    Collections2.transform(
                        ruleOutput.deps,
                        d -> (Matcher<? super Object>) (Matcher<?>) ruleOutputToMatchers(d)));
    Matcher<Object> outputs = createEndOfPathMatcher(ruleOutput.outputs);

    Matcher<Map<? extends String, ?>> srcsMatcher = Matchers.hasEntry(Matchers.is("srcs"), srcs);
    Matcher<Map<? extends String, ?>> depMatcher = Matchers.hasEntry(Matchers.is("dep"), deps);
    Matcher<Map<? extends String, ?>> outputsMatcher =
        Matchers.hasEntry(Matchers.is("outputs"), outputs);

    Matcher<? extends Map<? extends String, ?>> matcher =
        Matchers.allOf(targetMatcher, valMatcher, srcsMatcher, depMatcher, outputsMatcher);
    return (Matcher<Map<String, Object>>) matcher;
  }

  private static Matcher<Object> createEndOfPathMatcher(List<Path> toMatch) {
    return (Matcher<Object>)
        (Matcher<?>)
            Matchers.containsInAnyOrder(
                Collections2.transform(
                    toMatch,
                    path ->
                        (Matcher<? super Object>) (Matcher<?>) Matchers.endsWith(path.toString())));
  }
}
