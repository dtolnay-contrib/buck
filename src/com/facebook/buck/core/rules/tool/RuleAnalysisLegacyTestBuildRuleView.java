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

package com.facebook.buck.core.rules.tool;

import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildPaths;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.actions.Action;
import com.facebook.buck.core.rules.impl.RuleAnalysisLegacyBuildRuleView;
import com.facebook.buck.core.rules.providers.collect.ProviderInfoCollection;
import com.facebook.buck.core.rules.providers.lib.RunInfo;
import com.facebook.buck.core.rules.providers.lib.TestInfo;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.test.rule.ExternalTestRunnerRule;
import com.facebook.buck.core.test.rule.ExternalTestRunnerTestSpec;
import com.facebook.buck.core.test.rule.ExternalTestSpec;
import com.facebook.buck.core.test.rule.TestRule;
import com.facebook.buck.io.filesystem.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.shell.RunTestAndRecordResultStep;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.test.TestCaseSummary;
import com.facebook.buck.test.TestResultSummary;
import com.facebook.buck.test.TestResults;
import com.facebook.buck.test.TestRunningOptions;
import com.facebook.buck.util.collect.MoreSets;
import com.facebook.buck.util.json.ObjectMappers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * As {@link RuleAnalysisLegacyBuildRuleView}, but also implements {@link TestRule} so that this
 * rule can be run by {@code buck test}
 */
public class RuleAnalysisLegacyTestBuildRuleView extends RuleAnalysisLegacyBinaryBuildRuleView
    implements TestRule, ExternalTestRunnerRule {

  private final RunInfo runInfo;
  private final TestInfo testInfo;

  /**
   * Create an instance of {@link RuleAnalysisLegacyTestBuildRuleView}
   *
   * @param type the type of this {@link BuildRule}
   * @param buildTarget the {@link BuildTarget} of this analysis rule
   * @param action the action of the result for which we want to provide the {@link BuildRule} view
   * @param ruleResolver the current {@link BuildRuleResolver} to query dependent rules
   * @param projectFilesystem the filesystem
   * @param providerInfoCollection the providers returned by this build target
   * @param withDownwardApi whether downward API is enabled for Rule Analysis
   * @throws IllegalStateException if {@code providerInfoCollection} does not contain an instance of
   *     both {@link RunInfo} and {@link TestInfo}
   */
  public RuleAnalysisLegacyTestBuildRuleView(
      String type,
      BuildTarget buildTarget,
      Optional<Action> action,
      BuildRuleResolver ruleResolver,
      ProjectFilesystem projectFilesystem,
      ProviderInfoCollection providerInfoCollection,
      boolean withDownwardApi) {
    super(
        type,
        buildTarget,
        action,
        ruleResolver,
        projectFilesystem,
        providerInfoCollection,
        withDownwardApi);
    this.runInfo =
        providerInfoCollection
            .get(RunInfo.PROVIDER)
            .orElseThrow(
                () ->
                    new HumanReadableException(
                        "Attempted to create an executable rule (%s) without a RunInfo provider",
                        buildTarget));
    this.testInfo =
        providerInfoCollection
            .get(TestInfo.PROVIDER)
            .orElseThrow(
                () ->
                    new HumanReadableException(
                        "Attempted to create a test rule (%s) without a TestInfo provider",
                        buildTarget));
  }

  @Override
  public ImmutableList<Step> runTests(
      StepExecutionContext executionContext,
      TestRunningOptions options,
      BuildContext buildContext,
      TestReportingCallback testReportingCallback) {
    RunInfoLegacyTool tool = RunInfoLegacyTool.of(runInfo);
    return ImmutableList.<Step>builder()
        .addAll(
            MakeCleanDirectoryStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    buildContext.getBuildCellRootPath(),
                    getProjectFilesystem(),
                    getPathToTestOutputDirectory())))
        .add(
            new RunTestAndRecordResultStep(
                getProjectFilesystem(),
                tool.getCommandPrefix(buildContext.getSourcePathResolver()),
                tool.getEnvironment(buildContext.getSourcePathResolver()),
                testInfo.typedTimeoutMs(),
                getBuildTarget(),
                getPathToTestOutputResult(),
                testInfo.testCaseName(),
                testInfo.testName(),
                options.isRunWithDownwardApi()))
        .build();
  }

  @Override
  public Callable<TestResults> interpretTestResults(
      StepExecutionContext executionContext,
      SourcePathResolverAdapter pathResolver,
      boolean isUsingTestSelectors) {
    return () -> {
      Optional<String> resultsFileContents =
          getProjectFilesystem().readFileIfItExists(getPathToTestOutputResult());
      TestResultSummary testResultSummary =
          ObjectMappers.readValue(resultsFileContents.get(), TestResultSummary.class);
      TestCaseSummary testCaseSummary =
          new TestCaseSummary(
              getBuildTarget().getFullyQualifiedName(), ImmutableList.of(testResultSummary));
      return TestResults.of(
          getBuildTarget(),
          ImmutableList.of(testCaseSummary),
          testInfo.contacts(),
          testInfo.labels());
    };
  }

  private Path getPathToTestOutputResult() {
    return getPathToTestOutputDirectory().resolve("output.json");
  }

  @Override
  public ImmutableSet<String> getLabels() {
    return testInfo.labels();
  }

  @Override
  public ImmutableSet<String> getContacts() {
    return testInfo.contacts();
  }

  @Override
  public Optional<String> getOncall() {
    return MoreSets.only(getContacts());
  }

  @Override
  public Path getPathToTestOutputDirectory() {
    return BuildPaths.getGenDir(getProjectFilesystem().getBuckPaths(), getBuildTarget())
        .resolve("__test_output__");
  }

  @Override
  public boolean runTestSeparately() {
    return testInfo.runTestsSeparately();
  }

  @Override
  public boolean supportsStreamingTests() {
    return false;
  }

  @Override
  public ExternalTestSpec getExternalTestRunnerSpec(
      StepExecutionContext executionContext,
      TestRunningOptions testRunningOptions,
      BuildContext buildContext) {
    RunInfoLegacyTool tool = RunInfoLegacyTool.of(runInfo);

    ExternalTestRunnerTestSpec.Builder builder =
        ExternalTestRunnerTestSpec.builder()
            .setCwd(executionContext.getBuildCellRootPath())
            .setEnv(tool.getEnvironment(buildContext.getSourcePathResolver()))
            .addAllCommand(tool.getCommandPrefix(buildContext.getSourcePathResolver()))
            .addAllContacts(testInfo.contacts())
            .addAllLabels(testInfo.labels())
            .setTarget(getBuildTarget())
            //    .setNeededCoverage(testInfo.neededCoverage())
            .setType(testInfo.type());
    // TODO(nmj): Add needed coverage
    // TODO(nmj): Add required paths when Runtime files API is implemented/stabilized
    return builder.build();
  }
}
