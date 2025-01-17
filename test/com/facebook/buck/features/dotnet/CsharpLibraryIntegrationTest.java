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

package com.facebook.buck.features.dotnet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.core.rules.analysis.config.RuleAnalysisComputationMode;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.testutil.ParameterizedTests;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.EnvVariablesProvider;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.HashMap;
import java.util.Objects;
import java.util.Optional;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class CsharpLibraryIntegrationTest {

  private static final ImmutableList<String> UNIT_TEST_FRAMEWORK_PATHS =
      ImmutableList.of(
          "C:/Program Files (x86)/Microsoft Visual Studio/2017/Professional/Common7/IDE/PublicAssemblies/Microsoft.VisualStudio.QualityTools.UnitTestFramework.dll");

  private static final ImmutableList<String> VSTEST_RUNNER_PATHS =
      ImmutableList.of(
          "C:\\Program Files (x86)\\Microsoft Visual Studio\\2017\\Professional\\Common7\\IDE\\CommonExtensions\\Microsoft\\TestWindow\\vstest.console.exe");

  private Path assumeUnitTestRunner() {
    return assumePathExists("Unit test runner in %s", VSTEST_RUNNER_PATHS);
  }

  private Path assumePathExists(String message, ImmutableList<String> paths) {
    Optional<Path> foundPath = Optional.empty();
    for (String pathString : paths) {
      Path path = Paths.get(pathString);
      if (Files.exists(path)) {
        foundPath = Optional.of(path);
        break;
      }
    }
    assumeTrue(String.format(message, paths), foundPath.isPresent());
    return foundPath.get();
  }

  private Path assumeUnitTestFramework() {
    return assumePathExists(
        "Expected UnitTestFramework.dll to exist in one of %s", UNIT_TEST_FRAMEWORK_PATHS);
  }

  private static final ImmutableList<String> CSC_DIRS =
      ImmutableList.of(
          "C:/tools/toolchains/vs2017_15.9/BuildTools/MSBuild/15.0/Bin/Roslyn",
          "C:\\Program Files (x86)\\Microsoft Visual Studio\\2017\\Professional\\MSBuild\\15.0\\Bin\\Roslyn");
  private static final String CSC_EXE = "csc.exe";

  private Path cscExe = Paths.get(".");

  private ProjectWorkspace workspace;
  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private ImmutableMap<String, String> env;

  @Parameterized.Parameters(name = "configure_csc={0}, rule_analysis_rules={1}")
  public static Collection<Object[]> data() {
    return ParameterizedTests.getPermutations(
        ImmutableList.of(false, true),
        ImmutableList.of(
            RuleAnalysisComputationMode.DISABLED, RuleAnalysisComputationMode.PROVIDER_COMPATIBLE));
  }

  @Parameterized.Parameter(value = 0)
  public boolean configureCsc;

  @Parameterized.Parameter(value = 1)
  public RuleAnalysisComputationMode ruleAnalysisComputationMode;

  @Before
  public void setUp() throws IOException {
    setUp("csc-tests");
  }

  public void setUp(String scenario) throws IOException {
    cscExe = checkAssumptions();
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, scenario, tmp);
    workspace.setUp();
    workspace.enableDirCache();
    if (configureCsc) {
      TestDataHelper.overrideBuckconfig(
          workspace, ImmutableMap.of("dotnet", ImmutableMap.of("csc", cscExe.toString())));
    }
    TestDataHelper.overrideBuckconfig(
        workspace,
        ImmutableMap.of(
            "rule_analysis", ImmutableMap.of("mode", ruleAnalysisComputationMode.name())));
    env = getEnv();
  }

  @Test
  public void shouldCompileLibraryWithSystemProvidedDeps() throws IOException {
    ProcessResult result = workspace.runBuckCommand(env, "build", "//src:simple", "--show-output");
    result.assertSuccess();
    Path output =
        Paths.get(
            Objects.requireNonNull(
                workspace.parseShowOutputStdoutAsStrings(result.getStdout()).get("//src:simple")));
    assertTrue(Files.exists(workspace.resolve(output)));
    workspace.runBuckCommand(env, "clean", "--keep-cache").assertSuccess();
    workspace.runBuckCommand(env, "build", "//src:simple").assertSuccess();
    workspace.getBuildLog().assertTargetWasFetchedFromCache("//src:simple");
    assertTrue(Files.exists(workspace.resolve(output)));
  }

  @Test
  public void shouldCompileLibraryWithAPrebuiltDependency() throws IOException {
    ProcessResult result =
        workspace.runBuckCommand(env, "build", "//src:prebuilt", "--show-output");
    result.assertSuccess();
    Path output =
        Paths.get(
            Objects.requireNonNull(
                workspace
                    .parseShowOutputStdoutAsStrings(result.getStdout())
                    .get("//src:prebuilt")));
    assertTrue(Files.exists(workspace.resolve(output)));
    workspace.runBuckCommand(env, "clean", "--keep-cache").assertSuccess();
    workspace.runBuckCommand(env, "build", "//src:prebuilt").assertSuccess();
    workspace.getBuildLog().assertTargetWasFetchedFromCache("//src:prebuilt");
    assertTrue(Files.exists(workspace.resolve(output)));
  }

  @Test
  public void shouldBeAbleToEmbedResourcesIntoTheBuiltDll() throws IOException {
    ProcessResult result = workspace.runBuckCommand(env, "build", "//src:embed", "--show-output");
    result.assertSuccess();
    Path output =
        Paths.get(
            Objects.requireNonNull(
                workspace.parseShowOutputStdoutAsStrings(result.getStdout()).get("//src:embed")));
    assertTrue(Files.exists(workspace.resolve(output)));
    workspace.runBuckCommand(env, "clean", "--keep-cache").assertSuccess();
    workspace.runBuckCommand(env, "build", "//src:embed").assertSuccess();
    workspace.getBuildLog().assertTargetWasFetchedFromCache("//src:embed");
    assertTrue(Files.exists(workspace.resolve(output)));
  }

  @Test
  public void shouldBeAbleToDependOnAnotherCsharpLibrary() throws IOException {
    ProcessResult result =
        workspace.runBuckCommand(env, "build", "//src:dependent", "--show-output");
    result.assertSuccess();
    Path output =
        Paths.get(
            Objects.requireNonNull(
                workspace
                    .parseShowOutputStdoutAsStrings(result.getStdout())
                    .get("//src:dependent")));
    assertTrue(Files.exists(workspace.resolve(output)));
    workspace.runBuckCommand(env, "clean", "--keep-cache").assertSuccess();
    workspace.runBuckCommand(env, "build", "//src:dependent").assertSuccess();
    workspace.getBuildLog().assertTargetWasFetchedFromCache("//src:dependent");
    assertTrue(Files.exists(workspace.resolve(output)));
  }

  @Test
  public void shouldCachePrebuiltCsharpLibrary() throws IOException {
    ProcessResult result = workspace.runBuckCommand(env, "build", "//lib:log4net", "--show-output");
    result.assertSuccess();
    Path output =
        Paths.get(
            Objects.requireNonNull(
                workspace.parseShowOutputStdoutAsStrings(result.getStdout()).get("//lib:log4net")));
    assertTrue(Files.exists(workspace.resolve(output)));
    workspace.runBuckCommand(env, "clean", "--keep-cache").assertSuccess();
    workspace.runBuckCommand(env, "build", "//lib:log4net").assertSuccess();
    workspace.getBuildLog().assertTargetWasFetchedFromCache("//lib:log4net");
    assertTrue(Files.exists(workspace.resolve(output)));
  }

  @Test
  @Ignore
  public void shouldBeAbleToAddTheSameResourceToADllTwice() {
    fail("Implement me, please!");
  }

  private Path checkAssumptions() {
    assumeTrue("Running on windows", Platform.detect() == Platform.WINDOWS);
    Optional<Path> csc = Optional.empty();
    for (String cscDir : CSC_DIRS) {
      csc =
          new ExecutableFinder()
              .getOptionalExecutable(Paths.get(cscDir, CSC_EXE), ImmutableMap.of());
    }
    assumeTrue(String.format("csc.exe (%s) is available", CSC_EXE), csc.isPresent());
    return csc.get();
  }

  private ImmutableMap<String, String> getEnv() {
    ImmutableMap<String, String> defaultEnv = EnvVariablesProvider.getSystemEnv();
    if (configureCsc) {
      return defaultEnv;
    } else {
      HashMap<String, String> patchedEnv = new HashMap<>(defaultEnv);
      patchedEnv.put("PATH", String.format("%s;%s", cscExe.getParent(), defaultEnv.get("PATH")));
      return ImmutableMap.copyOf(patchedEnv);
    }
  }

  @Test
  public void cSharpBinaryRuns() throws IOException {
    setUp("csharp_udr");

    assumeTrue(ruleAnalysisComputationMode != RuleAnalysisComputationMode.DISABLED);

    String result1 =
        workspace
            .runBuckCommand(env, "run", "//csharp_binary/src:simple_bin", "--", "foo", "bar baz")
            .assertSuccess()
            .getStdout();

    String result2 =
        workspace
            .runBuckCommand(
                env,
                "run",
                "//csharp_binary/src:main",
                "--",
                "format_string_1: {0}",
                "<replacement>")
            .assertSuccess()
            .getStdout();
    String result3 =
        workspace
            .runBuckCommand(
                env,
                "run",
                "//csharp_binary/src:alternate",
                "--",
                "format_string_1: {0}",
                "<replacement>")
            .assertSuccess()
            .getStdout();

    assertEquals(
        ImmutableList.of("foo", "bar baz"), ImmutableList.copyOf(result1.trim().split("\\r?\\n")));
    assertEquals(
        ImmutableList.of(
            "Lib1.format: format_string_1: <replacement>",
            "Lib2.format: format_string_1: <replacement>"),
        ImmutableList.copyOf(result2.trim().split("\\r?\\n")));
    assertEquals(
        ImmutableList.of(
            "Lib2.format: format_string_1: <replacement>",
            "Lib1.format: format_string_1: <replacement>"),
        ImmutableList.copyOf(result3.trim().split("\\r?\\n")));
  }

  @Test
  public void cSharpTestRuns() throws IOException {
    assumeTrue(ruleAnalysisComputationMode != RuleAnalysisComputationMode.DISABLED);
    Path unitTestPath = assumeUnitTestFramework();
    Path unitTestRunner = assumeUnitTestRunner();

    setUp("csharp_udr");
    workspace.writeContentsToPath(
        String.format("\"%s\" %%*", unitTestRunner), "csharp/src/runner.bat");
    Path testDllDest = workspace.resolve("src").resolve(unitTestPath.getFileName());

    workspace.copyFile(unitTestPath.toString(), testDllDest.toString());

    //    String result1 =

    workspace.runBuckCommand(env, "test", "//csharp_test/src:failing").assertTestFailure();
    workspace.runBuckCommand(env, "test", "//csharp_test/src:simple_passing").assertSuccess();

    workspace
        .runBuckCommand(
            env,
            "run",
            "//csharp_test/src:alternate",
            "--",
            "format_string_1: {0}",
            "<replacement>")
        .assertSuccess();
  }
}
