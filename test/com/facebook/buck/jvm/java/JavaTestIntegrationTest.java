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

package com.facebook.buck.jvm.java;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.iterableWithSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.stringContainsInOrder;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeThat;

import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.jvm.java.version.utils.JavaVersionUtils;
import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.environment.EnvVariablesProvider;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.Rule;
import org.junit.Test;

public class JavaTestIntegrationTest {

  @Rule public TemporaryPaths temp = new TemporaryPaths();

  @Test
  public void shouldNotCompileIfDependsOnCompilerClasspath() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "missing_test_deps", temp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("build", "//:no-jsr-305");

    result.assertFailure();
    String stderr = result.getStderr();

    String lookFor;
    assumeThat(Platform.detect(), not(Platform.WINDOWS));
    if (JavaVersionUtils.getMajorVersion() <= 8) {
      // Javac emits different errors on Windows !?!
      if (Platform.detect() == Platform.WINDOWS) {
        // Note: javac puts wrong line ending
        lookFor =
            "cannot find symbol\n"
                + "  symbol:   class Nullable\n"
                + "  location: package javax.annotation"
                + System.lineSeparator()
                + "import javax.annotation.Nullable;";
      } else {
        lookFor =
            "cannot find symbol" + System.lineSeparator() + "import javax.annotation.Nullable;";
      }
    } else {
      if (Platform.detect() == Platform.WINDOWS) {
        lookFor =
            "cannot find symbol\n"
                + System.lineSeparator()
                + "  symbol:   class Nullable\n"
                + System.lineSeparator()
                + "  location: class com.facebook.buck.example.UsesNullable\n"
                + System.lineSeparator()
                + "  @Nullable private String foobar\n"
                + System.lineSeparator()
                + "   ^";
      } else {
        lookFor =
            "cannot find symbol"
                + System.lineSeparator()
                + "  @Nullable private String foobar;"
                + System.lineSeparator()
                + "   ^"
                + System.lineSeparator()
                + "  symbol:   class Nullable"
                + System.lineSeparator()
                + "  location: class com.facebook.buck.example.UsesNullable";
      }
    }
    assertTrue(stderr, stderr.contains(lookFor));
  }

  @Test
  public void shouldRefuseToRunJUnitTestsIfHamcrestNotOnClasspath() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "missing_test_deps", temp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("test", "//:no-hamcrest");

    // The bug this addresses was exposed as a missing output XML files. We expect the test to fail
    // with a warning to the user explaining that hamcrest was missing.
    result.assertTestFailure();
    String stderr = result.getStderr();
    assertTrue(
        stderr,
        stderr.contains(
            "Unable to locate hamcrest on the classpath. Please add as a test dependency."));
  }

  @Test
  public void shouldRefuseToRunJUnitTestsIfJUnitNotOnClasspath() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "missing_test_deps", temp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("test", "//:no-junit");

    // The bug this address was exposed as a missing output XML files. We expect the test to fail
    // with a warning to the user explaining that hamcrest was missing.
    result.assertTestFailure();
    String stderr = result.getStderr();
    assertTrue(
        stderr,
        stderr.contains(
            "Unable to locate junit on the classpath. Please add as a test dependency."));
  }

  @Test
  public void shouldRefuseToRunTestNgTestsIfTestNgNotOnClasspath() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "missing_test_deps", temp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("test", "//:no-testng");

    result.assertTestFailure();
    String stderr = result.getStderr();
    assertTrue(
        stderr,
        stderr.contains(
            "Unable to locate testng on the classpath. Please add as a test dependency."));
  }

  /**
   * There's a requirement that the JUnitRunner creates and runs tests on the same thread (thanks to
   * jmock having a thread guard), but we don't want to create lots of threads. Because of this the
   * runner uses one SingleThreadExecutor to run all tests. However, if one test schedules another
   * (as is the case with Suites and sub-tests) _and_ the buck config says that we're going to use a
   * custom timeout for tests, then both tests are created and executed using the same single thread
   * executor, in the following order:
   *
   * <p>create suite -> create test -> run suite -> run test
   *
   * <p>Obviously, that "run test" causes the deadlock, since suite hasn't finished executing and
   * won't until test completes, but test won't be run until suite finishes. Furrfu.
   */
  @Test
  public void shouldNotDeadlock() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "deadlock", temp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("test", "//:suite");

    result.assertSuccess();
  }

  @Test
  public void missingResultsFileIsTestFailure() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "java_test_missing_result_file", temp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("test", "//:simple");

    result.assertSpecialExitCode("test should fail", ExitCode.TEST_ERROR);
    String stderr = result.getStderr();
    assertTrue(stderr, stderr.contains("test exited before generating results file"));
  }

  @Test
  public void spinningTestTimesOutGlobalTimeout() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "slow_tests", temp);
    workspace.setUp();
    workspace.writeContentsToPath(
        "[test]" + System.lineSeparator() + "  rule_timeout = 250", ".buckconfig");

    ProcessResult result = workspace.runBuckCommand("test", "//:spinning");
    result.assertSpecialExitCode("test should fail", ExitCode.TEST_ERROR);
    String stderr = result.getStderr();
    assertTrue(stderr, stderr.contains("test timed out before generating results file"));
    assertThat(stderr, containsString("FAIL"));
    assertThat(stderr, containsString("250ms"));
  }

  @Test
  public void spinningTestTimesOutPerRuleTimeout() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "slow_tests_per_rule_timeout", temp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("test", "//:spinning");
    result.assertSpecialExitCode("test should fail", ExitCode.TEST_ERROR);
    String stderr = result.getStderr();
    assertTrue(stderr, stderr.contains("test timed out before generating results file"));
    assertThat(stderr, containsString("FAIL"));
    assertThat(stderr, containsString("100ms"));
  }

  @Test
  public void normalTestDoesNotTimeOut() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "slow_tests", temp);
    workspace.setUp();
    workspace.writeContentsToPath(
        "[test]" + System.lineSeparator() + "  rule_timeout = 10000", ".buckconfig");

    workspace.runBuckCommand("test", "//:slow").assertSuccess();
  }

  @Test
  public void normalTestInSrcZipDoesNotTimeOut() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "slow_tests", temp);
    workspace.setUp();
    workspace.writeContentsToPath(
        "[test]" + System.lineSeparator() + "  rule_timeout = 10000", ".buckconfig");
    ProcessResult test = workspace.runBuckCommand("test", "//:slow_zip");
    test.assertSuccess();
    assertThat(test.getStderr(), not(containsString("NO TESTS RAN")));
    assertThat(test.getStderr(), stringContainsInOrder("PASS", "SlowTest"));
  }

  @Test
  public void brokenTestGivesFailedTestResult() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "java_test_broken_test", temp);
    workspace.setUp();
    workspace.runBuckCommand("test", "//:simple").assertTestFailure();
  }

  @Test
  public void brokenTestInSrcZipGivesFailedTestResult() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "java_test_broken_test", temp);
    workspace.setUp();
    workspace.runBuckCommand("test", "//:simple_zip").assertTestFailure();
  }

  @Test
  public void staticInitializationException() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "static_initialization_test", temp);
    workspace.setUp();
    ProcessResult result = workspace.runBuckCommand("test", "//:npe");
    result.assertTestFailure();
    assertThat(result.getStderr(), containsString("com.facebook.buck.example.StaticErrorTest"));
  }

  @Test
  public void dependencyOnAnotherTest() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "depend_on_another_test", temp);
    workspace.setUp();
    ProcessResult result = workspace.runBuckCommand("test", "//:a");
    result.assertSuccess();
  }

  @Test
  public void macrosExpandedInVmArgsTest() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "vm_args_with_macro", temp);
    workspace.setUp();

    ProcessResult buildResult = workspace.runBuckCommand("test", "//:simple");
    buildResult.assertSuccess("Build should have succeeded.");
  }

  @Test
  public void testWithJni() throws IOException {
    assumeThat(Platform.detect(), not(Platform.WINDOWS));
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "test_with_jni", temp);
    workspace.setUp();
    ProcessResult result = workspace.runBuckCommand("test", "//:jtest");

    result.assertSuccess();
  }

  @Test
  public void testWithJniWithWhitelist() throws IOException {
    assumeThat(Platform.detect(), not(Platform.WINDOWS));
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "test_with_jni", temp);
    workspace.setUp();
    ProcessResult result = workspace.runBuckCommand("test", "//:jtest-skip-dep");
    result.assertSuccess();
  }

  @Test
  public void testWithJniWithWhitelistAndDangerousSymlink() throws IOException {
    assumeThat(Platform.detect(), not(Platform.WINDOWS));
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "test_with_jni", temp);
    workspace.setUp();
    String javaHome = EnvVariablesProvider.getSystemEnv().get("JAVA_HOME");
    ProcessResult result1 =
        workspace.runBuckCommand(
            "test",
            "//:jtest-pernicious",
            "//:jtest-symlink",
            "-c",
            "java_test_integration_test.java_home=" + (javaHome != null ? javaHome : ""));
    result1.assertSuccess();

    workspace.replaceFileContents("BUCK", "\"//:jlib-native\",  #delete-1", "");
    workspace.replaceFileContents("JTestWithoutPernicious.java", "@Test // getValue", "");
    workspace.replaceFileContents("JTestWithoutPernicious.java", "// @Test//noTestLib", "@Test");

    ProcessResult result2 =
        workspace.runBuckCommand("test", "//:jtest-pernicious", "//:jtest-symlink");
    result2.assertSuccess();
  }

  @Test
  public void testLDSymlinkTreeEnvVar() throws Exception {
    assumeThat(Platform.detect(), not(Platform.WINDOWS));
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "test_with_jni", temp);
    workspace.setUp();
    workspace.addBuckConfigLocalOption("test", "external_runner", "echo");
    workspace.addBuckConfigLocalOption(JavaBuckConfig.SECTION, "add_buck_ld_symlink_tree", "true");

    workspace.runBuckCommand("test", "//:jtest").assertSuccess();
    Path specOutput =
        workspace.getPath(
            workspace.getBuckPaths().getScratchDir().resolve("external_runner_specs.json"));
    JsonParser parser = ObjectMappers.createParser(specOutput);

    ArrayNode node = parser.readValueAsTree();
    JsonNode env = node.get(0).get("env");
    assertNotNull(env.get(JavaTestDescription.SYMLINK_TREE_ENV_VAR));
  }

  @Test
  public void testNativeRequiredPaths() throws Exception {
    assumeThat(Platform.detect(), not(Platform.WINDOWS));
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "test_with_jni", temp);
    workspace.setUp();
    workspace.addBuckConfigLocalOption("test", "external_runner", "echo");

    workspace.runBuckCommand("test", "//:jtest-symlink").assertSuccess();
    Path specOutput =
        workspace.getPath(
            workspace.getBuckPaths().getScratchDir().resolve("external_runner_specs.json"));
    ImmutableList<ImmutableMap<String, Object>> specs =
        ObjectMappers.readValue(
            specOutput, new TypeReference<ImmutableList<ImmutableMap<String, Object>>>() {});
    assertThat(specs, iterableWithSize(1));
    ImmutableMap<String, Object> spec = specs.get(0);
    assertThat(spec, hasKey("required_paths"));
    //noinspection unchecked
    ImmutableSortedSet<String> requiredPaths =
        ImmutableSortedSet.<String>naturalOrder()
            .addAll((Iterable<String>) spec.get("required_paths"))
            .build();

    ImmutableList<String> libjtestlibPaths =
        requiredPaths.stream()
            .filter(path -> path.contains("libjtestlib"))
            .collect(ImmutableList.toImmutableList());
    assertEquals(2, libjtestlibPaths.size());
  }

  @Test
  public void testForkMode() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "slow_tests", temp);
    workspace.setUp();
    ProcessResult result = workspace.runBuckCommand("test", "//:fork-mode");
    result.assertSuccess();
  }

  @Test
  public void testClasspath() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "test_rule_classpath", temp);
    workspace.setUp();
    ProcessResult result = workspace.runBuckCommand("audit", "classpath", "//:top");
    result.assertSuccess();
    ImmutableSortedSet<Path> actualPaths =
        Arrays.stream(result.getStdout().split("\\s+"))
            .map(input -> temp.getRoot().relativize(Paths.get(input)).getPath())
            .collect(ImmutableSortedSet.toImmutableSortedSet(Comparator.naturalOrder()));
    ImmutableSortedSet<Path> expectedPaths =
        ImmutableSortedSet.of(
            BuildTargetPaths.getGenPath(
                    workspace.getProjectFileSystem().getBuckPaths(),
                    BuildTargetFactory.newInstance("//:top"),
                    "lib__%s__output")
                .resolve("top.jar"),
            BuildTargetPaths.getGenPath(
                    workspace.getProjectFileSystem().getBuckPaths(),
                    BuildTargetFactory.newInstance("//:direct_dep"),
                    "lib__%s__output")
                .resolve("direct_dep.jar"),
            BuildTargetPaths.getGenPath(
                    workspace.getProjectFileSystem().getBuckPaths(),
                    BuildTargetFactory.newInstance("//:mid_test#testsjar"),
                    "lib__%s__output")
                .resolve("mid_test#testsjar.jar"),
            BuildTargetPaths.getGenPath(
                    workspace.getProjectFileSystem().getBuckPaths(),
                    BuildTargetFactory.newInstance("//:transitive_lib"),
                    "lib__%s__output")
                .resolve("transitive_lib.jar"));
    assertEquals(expectedPaths, actualPaths);
  }

  @Test
  public void testEnvLocationMacro() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "env_macros", temp);
    workspace.setUp();
    workspace.runBuckCommand("test", "//:env").assertSuccess();
  }

  @Test
  public void testExternalTestRunnerSpec() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "test_rule_classpath", temp);
    workspace.setUp();
    workspace.addBuckConfigLocalOption("test", "external_runner", "false");
    workspace.runBuckCommand("test", "//:top", "-c", "test.java_for_tests_version=11");
    Path specOutput =
        workspace.getPath(
            workspace.getBuckPaths().getScratchDir().resolve("external_runner_specs.json"));
    ImmutableList<ImmutableMap<String, Object>> specs =
        ObjectMappers.readValue(
            specOutput, new TypeReference<ImmutableList<ImmutableMap<String, Object>>>() {});
    assertThat(specs, iterableWithSize(1));
    ImmutableMap<String, Object> spec = specs.get(0);
    assertThat(spec, hasKey("required_paths"));
    //noinspection unchecked
    ImmutableSortedSet<String> requiredPaths =
        ImmutableSortedSet.<String>naturalOrder()
            .addAll((Iterable<String>) spec.get("required_paths"))
            .build();
    // The runtime classpath of the test should all be present in the required paths
    MoreAsserts.assertContainsOne(
        requiredPaths,
        workspace
            .getGenPath(BuildTargetFactory.newInstance("//:transitive_lib"), "lib__%s__output")
            .resolve("transitive_lib.jar")
            .toString());
    MoreAsserts.assertContainsOne(
        requiredPaths,
        workspace
            .getGenPath(BuildTargetFactory.newInstance("//:mid_test#testsjar"), "lib__%s__output")
            .resolve("mid_test#testsjar.jar")
            .toString());

    // The testrunner file path should be required.
    ImmutableList<String> testrunnerFilePath =
        requiredPaths.stream()
            .filter(path -> path.contains("testrunner"))
            .collect(ImmutableList.toImmutableList());
    assertEquals(1, testrunnerFilePath.size());

    // The java file path should be required.
    String binaryExtension = Platform.detect() == Platform.WINDOWS ? ".exe" : "";
    ImmutableList<String> javaFilePath =
        requiredPaths.stream()
            .filter(path -> path.endsWith("java" + binaryExtension))
            .collect(ImmutableList.toImmutableList());
    assertEquals(1, javaFilePath.size());

    // The classpath arg file should use absolute paths.
    ImmutableList<String> classpathArgfile =
        requiredPaths.stream()
            .filter(path -> path.contains("classpath-argfile"))
            .collect(ImmutableList.toImmutableList());
    assertEquals(1, classpathArgfile.size());
    Path classpathArgFilePath = Paths.get(classpathArgfile.get(0));
    for (String line : workspace.getProjectFileSystem().readLines(classpathArgFilePath)) {
      // Last line ends with a quote
      line = line.endsWith("\"") ? line.substring(0, line.length() - 1) : line;
      assertTrue(
          line.equals("-classpath") || line.contains("ant-out") || Paths.get(line).isAbsolute());
    }
  }

  @Test
  public void testClasspathArgFileHasRelativePaths() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "test_rule_classpath", temp);
    workspace.setUp();
    workspace.addBuckConfigLocalOption("test", "external_runner", "false");
    workspace.addBuckConfigLocalOption("test", "use_relative_paths_in_classpath_file", "true");
    workspace.runBuckCommand("test", "//:top", "-c", "test.java_for_tests_version=11");
    Path specOutput =
        workspace.getPath(
            workspace.getBuckPaths().getScratchDir().resolve("external_runner_specs.json"));
    ImmutableList<ImmutableMap<String, Object>> specs =
        ObjectMappers.readValue(
            specOutput, new TypeReference<ImmutableList<ImmutableMap<String, Object>>>() {});
    assertThat(specs, iterableWithSize(1));
    ImmutableMap<String, Object> spec = specs.get(0);
    assertThat(spec, hasKey("required_paths"));
    //noinspection unchecked
    ImmutableSortedSet<String> requiredPaths =
        ImmutableSortedSet.<String>naturalOrder()
            .addAll((Iterable<String>) spec.get("required_paths"))
            .build();

    // The classpath arg file should use relative paths.
    ImmutableList<String> classpathArgfile =
        requiredPaths.stream()
            .filter(path -> path.contains("classpath-argfile"))
            .collect(ImmutableList.toImmutableList());
    assertEquals(1, classpathArgfile.size());
    Path classpathArgFilePath = Paths.get(classpathArgfile.get(0));
    for (String line : workspace.getProjectFileSystem().readLines(classpathArgFilePath)) {
      // Last line ends with a quote
      line = line.endsWith("\"") ? line.substring(0, line.length() - 1) : line;
      assertTrue(
          line.equals("-classpath") || line.contains("ant-out") || !Paths.get(line).isAbsolute());
    }
  }

  @Test
  public void testProtocolJavaTestRuleShouldBuildAndGenerateSpec() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "testx_rule", temp);
    workspace.setUp();
    workspace.addBuckConfigLocalOption("test", "external_runner", "echo");
    ProcessResult result = workspace.runBuckCommand("test", "//:some_test");
    result.assertSuccess();
    Path specOutput =
        workspace.getPath(
            workspace.getBuckPaths().getScratchDir().resolve("external_runner_specs.json"));
    JsonParser parser = ObjectMappers.createParser(specOutput);

    ArrayNode node = parser.readValueAsTree();
    JsonNode spec = node.get(0).get("specs");

    assertEquals("spec", spec.get("my").textValue());

    JsonNode other = spec.get("other");
    assertTrue(other.isArray());
    assertTrue(other.has(0));
    assertEquals("stuff", other.get(0).get("complicated").textValue());
    assertEquals(1, other.get(0).get("integer").intValue());
    assertTrue(other.get(0).get("boolean").booleanValue());

    String cmd = spec.get("cmd").textValue();
    DefaultProcessExecutor processExecutor =
        new DefaultProcessExecutor(Console.createNullConsole());
    ProcessExecutor.Result processResult =
        processExecutor.launchAndExecute(
            ProcessExecutorParams.builder().addCommand(cmd.split(" ")).build());
    assertEquals(0, processResult.getExitCode());
  }

  @Test
  public void testProtocolJavaTestWithJVMArgsRuleShouldBuildAndGenerateSpec() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "testx_rule", temp);
    workspace.setUp();
    workspace.addBuckConfigLocalOption("test", "external_runner", "echo");
    ProcessResult resultWithJVMArgs = workspace.runBuckCommand("test", "//:some_test_with_jvm");
    resultWithJVMArgs.assertSuccess();
    Path specOutput =
        workspace.getPath(
            workspace.getBuckPaths().getScratchDir().resolve("external_runner_specs.json"));
    JsonParser parser = ObjectMappers.createParser(specOutput);

    ArrayNode node = parser.readValueAsTree();
    JsonNode spec = node.get(0).get("specs");

    assertEquals("spec", spec.get("my").textValue());

    JsonNode other = spec.get("other");
    assertTrue(other.isArray());
    assertTrue(other.has(0));
    assertEquals("stuff", other.get(0).get("complicated").textValue());
    assertEquals(1, other.get(0).get("integer").intValue());
    assertFalse(other.get(0).get("boolean").booleanValue());

    String cmd = spec.get("cmd").textValue();
    DefaultProcessExecutor processExecutor =
        new DefaultProcessExecutor(Console.createNullConsole());
    ProcessExecutor.Result processResult =
        processExecutor.launchAndExecute(
            ProcessExecutorParams.builder().addCommand(cmd.split(" ")).build());
    assertEquals(0, processResult.getExitCode());
  }

  @Test
  public void testSimpleWorkingJunitTest() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "java_test_working", temp);
    workspace.setUp();
    workspace.runBuckCommand("test", "//:java_test_working").assertSuccess();
  }

  @Test
  public void testSimpleWorkingJunitTestWithDepsQuery() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "java_test_deps_query", temp);
    workspace.setUp();
    workspace.runBuckCommand("test", "//:java_test_deps_query").assertSuccess();
  }

  @Test
  public void testSimpleWorkingJunitTestWithDependencyOrderClasspath() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "java_test_working", temp);
    workspace.setUp();
    workspace.addBuckConfigLocalOption("java", "use_dependency_order_classpath_for_tests", "true");
    workspace.runBuckCommand("test", "//:java_test_working").assertSuccess();
  }

  @Test
  public void testSimpleWorkingJunitTestWithDependencyOrderClasspathAsRuleOption()
      throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "java_test_working", temp);
    workspace.setUp();
    workspace
        .runBuckCommand("test", "//:java_test_working_with_dependency_order_classpath")
        .assertSuccess();
  }

  @Test
  public void testTypeJUnit5MissingDependencies() throws IOException {
    ProcessResult processResult =
        TestDataHelper.createProjectWorkspaceForScenario(this, "java_test_junit5", temp)
            .setUp()
            .runBuckCommand("test", "//:java_test_junit5_missing_engine");

    processResult.assertTestFailure();
    assertTrue(
        processResult
            .getStderr()
            .contains("Unable to locate junit-jupiter-engine on the classpath"));
  }

  @Test
  public void testTypeJUnit5() throws IOException {
    ProcessResult processResult =
        TestDataHelper.createProjectWorkspaceForScenario(this, "java_test_junit5", temp)
            .setUp()
            .runBuckCommand("test", "//:java_test_junit5_working");

    processResult.assertSuccess();

    // assert test executions are present
    String[] outputLines = processResult.getStderr().split(System.lineSeparator());
    assertTestOutput(outputLines, "JUnit4SimpleTest", 1, 0);
    assertTestOutput(outputLines, "JUnit5SimpleTest", 1, 0);
  }

  private void assertTestOutput(String[] outputLines, String testName, int passed, int failed) {
    Optional<String> test =
        Stream.of(outputLines)
            .filter(line -> line.contains(testName))
            .map(String::trim)
            .findFirst();
    assertTrue(testName, test.isPresent());
    // expected "PASS    <100ms  n Passed   0 Skipped   n Failed   $testName"
    test.ifPresent(
        output -> {
          assertTrue(output.startsWith("PASS "));
          assertTrue(output.contains(String.format(" %s Passed ", passed)));
          assertTrue(output.contains(String.format(" %s Failed ", failed)));
          assertTrue(output.endsWith(testName));
        });
  }
}
