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

import com.facebook.buck.android.AndroidApk;
import com.facebook.buck.android.AndroidInstrumentationApk;
import com.facebook.buck.android.AndroidInstrumentationTest;
import com.facebook.buck.android.HasInstallableApk;
import com.facebook.buck.android.device.TargetDeviceOptions;
import com.facebook.buck.android.exopackage.AndroidDevicesHelperFactory;
import com.facebook.buck.command.Build;
import com.facebook.buck.command.config.BuildBuckConfig;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.engine.BuildEngine;
import com.facebook.buck.core.build.engine.config.CachingBuildEngineBuckConfig;
import com.facebook.buck.core.build.engine.delegate.LocalCachingBuildEngineDelegate;
import com.facebook.buck.core.build.engine.impl.CachingBuildEngine;
import com.facebook.buck.core.build.event.BuildEvent;
import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.build.execution.context.actionid.ActionId;
import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.Cells;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.ForwardRelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.CellRelativePath;
import com.facebook.buck.core.model.actiongraph.ActionGraphAndBuilder;
import com.facebook.buck.core.model.targetgraph.TargetGraphCreationResult;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.model.targetgraph.impl.TargetNodes;
import com.facebook.buck.core.resources.ResourcesConfig;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.attr.HasRuntimeDeps;
import com.facebook.buck.core.test.rule.ExternalTestRunnerRule;
import com.facebook.buck.core.test.rule.ExternalTestSpec;
import com.facebook.buck.core.test.rule.TestRule;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.downwardapi.config.DownwardApiConfig;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.jvm.java.JavaLibraryClasspathProvider;
import com.facebook.buck.jvm.java.JavaLibraryWithTests;
import com.facebook.buck.jvm.java.JavaTest;
import com.facebook.buck.parser.ParsingContext;
import com.facebook.buck.parser.SpeculativeParsing;
import com.facebook.buck.parser.config.ParserConfig;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.parser.spec.BuildFileSpec;
import com.facebook.buck.parser.spec.TargetNodePredicateSpec;
import com.facebook.buck.remoteexecution.config.RemoteExecutionConfig;
import com.facebook.buck.rules.keys.RuleKeyCacheRecycler;
import com.facebook.buck.rules.keys.RuleKeyCacheScope;
import com.facebook.buck.rules.keys.RuleKeyFactories;
import com.facebook.buck.rules.modern.builders.ModernBuildRuleBuilderFactory;
import com.facebook.buck.rules.modern.config.ModernBuildRuleConfig;
import com.facebook.buck.step.AdbOptions;
import com.facebook.buck.support.build.report.BuildReportConfig;
import com.facebook.buck.support.fix.BuckRunSpec;
import com.facebook.buck.test.CoverageReportFormat;
import com.facebook.buck.test.TestRunningOptions;
import com.facebook.buck.test.config.TestBuckConfig;
import com.facebook.buck.test.external.ExternalTestRunEvent;
import com.facebook.buck.test.external.ExternalTestSpecCalculationEvent;
import com.facebook.buck.util.CommandLineException;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.ConsoleParams;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.ForwardingProcessListener;
import com.facebook.buck.util.ListeningProcessExecutor;
import com.facebook.buck.util.MoreExceptions;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.collect.MoreSets;
import com.facebook.buck.util.concurrent.ConcurrencyLimit;
import com.facebook.buck.util.json.ObjectMappers;
import com.facebook.buck.util.stream.RichStream;
import com.facebook.buck.versions.VersionException;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import javax.annotation.Nullable;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;
import org.kohsuke.args4j.OptionDef;
import org.kohsuke.args4j.spi.Messages;
import org.kohsuke.args4j.spi.OptionHandler;
import org.kohsuke.args4j.spi.Parameters;
import org.kohsuke.args4j.spi.Setter;

public class TestCommand extends BuildCommand {

  private static final Logger LOG = Logger.get(TestCommand.class);
  protected static final String EXTERNAL_RUNNER_AND_UNSUPPORTED_DEBUG_MODE =
      "\nThe flag `--debug` when using an external runner can lead to undefined behaviour. Please make sure your external runner supports debug mode."
          + "\n[Internal Only] Alternatively, check if your test type/platform is supported by fdb https://fburl.com/fdb, otherwise use -c test.external_runner=\"\" to use the buck internal runner. \nTo run your tests with fdb, use: \n   fdb buck test <target>" // MOE:strip_line
      ;

  @Option(name = "--all", usage = "Whether all of the tests should be run. ")
  private boolean all = false;

  @Option(name = "--code-coverage", usage = "Whether code coverage information will be generated.")
  private boolean isCodeCoverageEnabled = false;

  @Option(
      name = "--code-coverage-format",
      usage = "Comma separated Formats to be used for coverage",
      handler = CoverageReportFormatsHandler.class)
  private CoverageReportFormat[] coverageReportFormats =
      new CoverageReportFormat[] {CoverageReportFormat.HTML};

  @Option(name = "--code-coverage-title", usage = "Title used for coverage")
  private String coverageReportTitle = "Code-Coverage Analysis";

  @Option(
      name = "--debug",
      usage = "Whether the test will start suspended with a JDWP debug port of 5005")
  private boolean isDebugEnabled = false;

  @Option(name = "--xml", usage = "Where to write test output as XML.")
  @Nullable
  private String pathToXmlTestOutput = null;

  @Option(
      name = "--run-with-java-agent",
      usage = "Whether the test will start a java profiling agent")
  @Nullable
  private String pathToJavaAgent = null;

  @Option(name = "--build-filtered", usage = "Whether to build filtered out tests.")
  @Nullable
  private Boolean isBuildFiltered = null;

  // TODO(#9061229): See if we can remove this option entirely. For now, the
  // underlying code has been removed, and this option is ignored.
  @Option(
      name = "--ignore-when-dependencies-fail",
      aliases = {"-i"},
      usage = "Deprecated option (ignored).",
      hidden = true)
  @SuppressWarnings("PMD.UnusedPrivateField")
  private boolean isIgnoreFailingDependencies;

  @Option(
      name = "--shuffle",
      usage =
          "Randomize the order in which test classes are executed."
              + "WARNING: only works for Java tests!")
  private boolean isShufflingTests;

  @Option(
      name = "--exclude-transitive-tests",
      usage =
          "Only run the tests targets that were specified on the command line (without adding "
              + "more tests by following dependencies).")
  private boolean shouldExcludeTransitiveTests;

  @Option(
      name = "--test-runner-env",
      usage =
          "Add or override an environment variable passed to the test runner. Later occurrences "
              + "override earlier occurrences. Currently this only support Apple(ios/osx) tests.",
      handler = EnvironmentOverrideOptionHandler.class)
  private Map<String, String> environmentOverrides = new HashMap<>();

  @AdditionalOptions @SuppressFieldNotInitialized private AdbCommandLineOptions adbOptions;

  @AdditionalOptions @SuppressFieldNotInitialized
  private TargetDeviceCommandLineOptions targetDeviceOptions;

  @AdditionalOptions @SuppressFieldNotInitialized private TestSelectorOptions testSelectorOptions;

  @AdditionalOptions @SuppressFieldNotInitialized private TestLabelOptions testLabelOptions;

  @Option(
      name = "--",
      usage =
          "When an external test runner is specified to be used (in the .buckconfig file), "
              + "all options specified after -- get forwarded directly to the external test runner. "
              + "Available options after -- are specific to that particular test runner and you may "
              + "also want to consult its help pages.",
      handler = ConsumeAllOptionsHandler.class)
  private List<String> withDashArguments = new ArrayList<>();

  public boolean isRunAllTests() {
    return all || getArguments().isEmpty();
  }

  public AdbOptions getAdbOptions(BuckConfig buckConfig) {
    return adbOptions.getAdbOptions(buckConfig);
  }

  public TargetDeviceOptions getTargetDeviceOptions() {
    return targetDeviceOptions.getTargetDeviceOptions();
  }

  public boolean isMatchedByLabelOptions(BuckConfig buckConfig, Set<String> labels) {
    return testLabelOptions.isMatchedByLabelOptions(buckConfig, labels);
  }

  public boolean shouldExcludeTransitiveTests() {
    return shouldExcludeTransitiveTests;
  }

  public boolean shouldExcludeWin() {
    return testLabelOptions.shouldExcludeWin();
  }

  public boolean isBuildFiltered(BuckConfig buckConfig) {
    return isBuildFiltered != null
        ? isBuildFiltered
        : buckConfig.getView(TestBuckConfig.class).isBuildingFilteredTestsEnabled();
  }

  public int getNumTestThreads(BuckConfig buckConfig) {
    if (isDebugEnabled) {
      return 1;
    }
    return buckConfig.getView(TestBuckConfig.class).getNumTestThreads();
  }

  public int getNumTestManagedThreads(ResourcesConfig resourcesConfig) {
    if (isDebugEnabled) {
      return 1;
    }
    return resourcesConfig.getManagedThreadCount();
  }

  private TestRunningOptions getTestRunningOptions(CommandRunnerParams params) {
    // this.coverageReportFormats should never be empty, but doing this to avoid problems with
    // EnumSet.copyOf throwing Exception on empty parameter.
    EnumSet<CoverageReportFormat> coverageFormats = EnumSet.noneOf(CoverageReportFormat.class);
    coverageFormats.addAll(Arrays.asList(this.coverageReportFormats));

    BuckConfig buckConfig = params.getBuckConfig();
    TestBuckConfig testBuckConfig = buckConfig.getView(TestBuckConfig.class);
    DownwardApiConfig downwardApiConfig = buckConfig.getView(DownwardApiConfig.class);

    TestRunningOptions.Builder builder =
        TestRunningOptions.builder()
            .setRunAllTests(isRunAllTests())
            .setTestSelectorList(testSelectorOptions.getTestSelectorList())
            .setShouldExplainTestSelectorList(testSelectorOptions.shouldExplain())
            .setShufflingTests(isShufflingTests)
            .setPathToXmlTestOutput(Optional.ofNullable(pathToXmlTestOutput))
            .setPathToJavaAgent(Optional.ofNullable(pathToJavaAgent))
            .setSuperProjectRootPath(params.getCells().getSuperRootPath())
            .setCoverageReportFormats(coverageFormats)
            .setCoverageReportTitle(coverageReportTitle)
            .setEnvironmentOverrides(environmentOverrides)
            .setJavaTempDir(params.getBuckConfig().getView(JavaBuckConfig.class).getJavaTempDir())
            .setTargetDevice(targetDeviceOptions.getTargetDeviceOptional())
            .setCodeCoverageEnabled(isCodeCoverageEnabled)
            .setDebugEnabled(isDebugEnabled)
            .setDefaultTestTimeoutMillis(testBuckConfig.getDefaultTestTimeoutMillis())
            .setInclNoLocationClassesEnabled(testBuckConfig.isInclNoLocationClassesEnabled())
            .setRunWithDownwardApi(downwardApiConfig.isEnabledForTests());

    Optional<ImmutableList<String>> coverageIncludes = testBuckConfig.getCoverageIncludes();
    Optional<ImmutableList<String>> coverageExcludes = testBuckConfig.getCoverageExcludes();

    coverageIncludes.ifPresent(strings -> builder.setCoverageIncludes(String.join(",", strings)));
    coverageExcludes.ifPresent(strings -> builder.setCoverageExcludes(String.join(",", strings)));

    return builder.build();
  }

  private ConcurrencyLimit getTestConcurrencyLimit(CommandRunnerParams params) {
    ResourcesConfig resourcesConfig = params.getBuckConfig().getView(ResourcesConfig.class);
    return new ConcurrencyLimit(
        getNumTestThreads(params.getBuckConfig()),
        resourcesConfig.getResourceAllocationFairness(),
        getNumTestManagedThreads(resourcesConfig),
        resourcesConfig.getDefaultResourceAmounts(),
        resourcesConfig.getMaximumResourceAmounts());
  }

  private ExitCode runTestsInternal(
      CommandRunnerParams params,
      BuildEngine buildEngine,
      Build build,
      BuildContext buildContext,
      Iterable<TestRule> testRules,
      ImmutableSet<JavaLibrary> rulesUnderTestForCoverage)
      throws InterruptedException, IOException {

    if (!withDashArguments.isEmpty()) {
      throw new CommandLineException(
          "unexpected arguments after \"--\" when using internal runner");
    }

    ProjectFilesystem filesystem = params.getCells().getRootCell().getFilesystem();

    try (CommandThreadManager testPool =
        new CommandThreadManager("Test-Run", getTestConcurrencyLimit(params))) {

      int exitCodeInt =
          TestRunning.runTests(
              params,
              testRules,
              rulesUnderTestForCoverage,
              StepExecutionContext.from(
                  build.getExecutionContext(),
                  filesystem.getRootPath(),
                  ActionId.of("test-running-" + buildContext.getEventBus().getBuildId().toString()),
                  Optional.empty()),
              getTestRunningOptions(params),
              testPool.getWeightedListeningExecutorService(),
              buildEngine,
              buildContext,
              build.getGraphBuilder());
      return ExitCode.map(exitCodeInt);
    }
  }

  private ExitCode runTestsExternal(
      CommandRunnerParams params,
      Build build,
      Iterable<String> command,
      Iterable<TestRule> testRules,
      BuildContext buildContext,
      boolean isTtyForExternalTestRunnerEnabled,
      String clientId)
      throws InterruptedException, IOException {
    Optional<TestRule> nonExternalTestRunnerRule =
        StreamSupport.stream(testRules.spliterator(), /* parallel */ true)
            .filter(rule -> !(rule instanceof ExternalTestRunnerRule))
            .findAny();
    BuckEventBus buckEventBus = params.getBuckEventBus();
    if (nonExternalTestRunnerRule.isPresent()) {
      buckEventBus.post(
          ConsoleEvent.severe(
              String.format(
                  "Test %s does not support external test running",
                  nonExternalTestRunnerRule.get().getBuildTarget())));
      return ExitCode.BUILD_ERROR;
    }

    TestRunningOptions options = getTestRunningOptions(params);
    ProjectFilesystem filesystem = params.getCells().getRootCell().getFilesystem();
    AbsPath infoFile =
        filesystem
            .resolve(filesystem.getBuckPaths().getScratchDir())
            .resolve("external_runner_specs.json");

    try (SimplePerfEvent.Scope event =
        SimplePerfEvent.scope(
            buildContext.getEventBus().isolated(),
            SimplePerfEvent.PerfEventTitle.of("external-test-runner-specs"))) {
      LOG.info("Starting to write external test runner specs.");

      boolean parallelExternalTestSpecComputationEnabled =
          params
              .getBuckConfig()
              .getView(TestBuckConfig.class)
              .isParallelExternalTestSpecComputationEnabled();

      // Walk the test rules, collecting all the specs.
      ImmutableList<ExternalTestSpec> specs =
          StreamSupport.stream(testRules.spliterator(), parallelExternalTestSpecComputationEnabled)
              .map(ExternalTestRunnerRule.class::cast)
              .map(
                  rule -> {
                    BuildTarget buildTarget = rule.getBuildTarget();
                    try {
                      buckEventBus.post(ExternalTestSpecCalculationEvent.started(buildTarget));
                      return rule.getExternalTestRunnerSpec(
                          StepExecutionContext.from(
                              build.getExecutionContext(),
                              filesystem.getRootPath(),
                              ActionId.of(buildTarget),
                              Optional.of(buildTarget)),
                          options,
                          buildContext);
                    } finally {
                      buckEventBus.post(ExternalTestSpecCalculationEvent.finished(buildTarget));
                    }
                  })
              .collect(ImmutableList.toImmutableList());

      StreamSupport.stream(testRules.spliterator(), parallelExternalTestSpecComputationEnabled)
          .map(ExternalTestRunnerRule.class::cast)
          .forEach(
              rule -> {
                try {
                  rule.onPreTest(buildContext);
                } catch (IOException e) {
                  throw new RuntimeException(e);
                }
              });

      // Serialize the specs to a file to pass into the test runner.
      Files.createDirectories(infoFile.getParent().getPath());
      Files.deleteIfExists(infoFile.getPath());
      ObjectMappers.WRITER.withDefaultPrettyPrinter().writeValue(infoFile.toFile(), specs);

      LOG.info("Finished writing external test runner specs.");
    }
    if (!withDashArguments.contains("--buck-test-info")) {
      withDashArguments.add("--buck-test-info");
      withDashArguments.add(infoFile.toString());
    }
    if (!withDashArguments.contains("--jobs") && !withDashArguments.contains("-j")) {
      withDashArguments.add("--jobs");
      withDashArguments.add(String.valueOf(getTestConcurrencyLimit(params).threadLimit));
    }

    AbsPath repositoryRoot = filesystem.getRootPath();
    Path rootPath = repositoryRoot.getPath();

    ImmutableMap<String, String> environmentVariables =
        ImmutableMap.<String, String>builder()
            .putAll(params.getEnvironment())
            .put("BUCK_CLIENT_ID", clientId)
            .build();

    if (isTtyForExternalTestRunnerEnabled && commandArgsFile != null) {
      ImmutableList<String> commandWithArgs =
          ImmutableList.<String>builder().addAll(command).addAll(withDashArguments).build();
      BuckRunSpec runSpec =
          BuckRunSpec.of(
              commandWithArgs,
              environmentVariables,
              Optional.of(rootPath),
              /* is_fix_script */ false,
              /* print_command */ false);
      Files.write(Paths.get(commandArgsFile), ObjectMappers.WRITER.writeValueAsBytes(runSpec));

      // The BuckRunSpec contains the actual external test runner command that
      // will be run by the python wrapper. The wrapper will run it and
      // propagate the correct status code. Return success here to indicate
      // that the build and writing of the command file succeeded.
      return ExitCode.SUCCESS;
    } else {
      // Launch and run the external test runner, forwarding it's stdout/stderr to the console.
      // We wait for it to complete then returns its error code.
      ListeningProcessExecutor processExecutor = new ListeningProcessExecutor();

      ProcessExecutorParams.Builder builder =
          ProcessExecutorParams.builder()
              .addAllCommand(command)
              .addAllCommand(withDashArguments)
              .setEnvironment(environmentVariables)
              .setDirectory(rootPath);
      ProcessExecutorParams processExecutorParams = builder.build();

      ForwardingProcessListener processListener =
          new ForwardingProcessListener(
              params.getConsole().getStdOut(), params.getConsole().getStdErr());
      ImmutableSet<String> testTargets =
          StreamSupport.stream(testRules.spliterator(), /* parallel */ false)
              .map(BuildRule::getBuildTarget)
              .map(Object::toString)
              .collect(ImmutableSet.toImmutableSet());
      ListeningProcessExecutor.LaunchedProcess process =
          processExecutor.launchProcess(processExecutorParams, processListener);
      ExitCode exitCode = ExitCode.FATAL_GENERIC;
      try {
        buckEventBus.post(
            ExternalTestRunEvent.started(
                options.isRunAllTests(),
                options.getTestSelectorList(),
                options.shouldExplainTestSelectorList(),
                testTargets));
        exitCode = ExitCode.map(processExecutor.waitForProcess(process));
        return exitCode;
      } finally {
        buckEventBus.post(ExternalTestRunEvent.finished(testTargets, exitCode));
        processExecutor.destroyProcess(process, /* force */ false);
        processExecutor.waitForProcess(process);
      }
    }
  }

  @Override
  protected void assertArguments(CommandRunnerParams params) {
    // If the person said to run everything, run everything.
    if (all) {
      return;
    }
    super.assertArguments(params);
  }

  @Override
  public ExitCode runWithoutHelp(CommandRunnerParams params) throws Exception {

    assertArguments(params);

    LOG.debug("Running with arguments %s", getArguments());

    ListeningProcessExecutor processExecutor = new ListeningProcessExecutor();
    try (CommandThreadManager pool =
            new CommandThreadManager("Test", getConcurrencyLimit(params.getBuckConfig()));
        BuildPrehook prehook = getPrehook(processExecutor, params)) {
      prehook.startPrehookScript();
      BuildEvent.Started started = BuildEvent.started(getArguments());
      params.getBuckEventBus().post(started);

      // The first step is to parse all of the build files. This will populate the parser and find
      // all of the test rules.
      TargetGraphCreationResult targetGraphCreationResult;
      ParserConfig parserConfig = params.getBuckConfig().getView(ParserConfig.class);
      ParsingContext parsingContext =
          createParsingContext(params.getCells(), pool.getListeningExecutorService())
              .withApplyDefaultFlavorsMode(parserConfig.getDefaultFlavorsMode())
              .withSpeculativeParsing(SpeculativeParsing.ENABLED);

      ImmutableSet<BuildTarget> explicitBuildTargets = ImmutableSet.of();
      Cells cells = params.getCells();
      try {

        // If the user asked to run all of the tests, parse all of the build files looking for any
        // test rules.
        if (isRunAllTests()) {
          targetGraphCreationResult =
              params
                  .getParser()
                  .buildTargetGraphWithoutTopLevelConfigurationTargets(
                      parsingContext,
                      ImmutableList.of(
                          TargetNodePredicateSpec.of(
                              BuildFileSpec.fromRecursivePath(
                                  CellRelativePath.of(
                                      cells.getRootCell().getCanonicalName(),
                                      ForwardRelPath.of(""))),
                              true)),
                      params.getTargetConfiguration());
          targetGraphCreationResult = targetGraphCreationResult.withBuildTargets(ImmutableSet.of());

          // Otherwise, the user specified specific test targets to build and run, so build a graph
          // around these.
        } else {
          LOG.debug("Parsing graph for arguments %s", getArguments());
          targetGraphCreationResult =
              params
                  .getParser()
                  .buildTargetGraphWithoutTopLevelConfigurationTargets(
                      parsingContext,
                      parseArgumentsAsTargetNodeSpecs(
                          cells,
                          params.getClientWorkingDir(),
                          getArguments(),
                          params.getBuckConfig()),
                      params.getTargetConfiguration());

          explicitBuildTargets = targetGraphCreationResult.getBuildTargets();
          LOG.debug("Got explicit build targets %s", explicitBuildTargets);
          ImmutableSet.Builder<BuildTarget> testTargetsBuilder = ImmutableSet.builder();
          for (TargetNode<?> node :
              targetGraphCreationResult
                  .getTargetGraph()
                  .getAll(targetGraphCreationResult.getBuildTargets())) {
            ImmutableSortedSet<BuildTarget> nodeTests = TargetNodes.getTestTargetsForNode(node);
            if (!nodeTests.isEmpty()) {
              LOG.debug("Got tests for target %s: %s", node.getBuildTarget(), nodeTests);
              testTargetsBuilder.addAll(nodeTests);
            }
          }
          ImmutableSet<BuildTarget> testTargets = testTargetsBuilder.build();
          if (!testTargets.isEmpty()) {
            LOG.debug("Got related test targets %s, building new target graph...", testTargets);
            ImmutableSet<BuildTarget> allTargets =
                MoreSets.union(targetGraphCreationResult.getBuildTargets(), testTargets);
            targetGraphCreationResult =
                params.getParser().buildTargetGraph(parsingContext, allTargets);
            LOG.debug("Finished building new target graph with tests.");
          }
        }

        if (params.getBuckConfig().getView(BuildBuckConfig.class).getBuildVersions()) {
          targetGraphCreationResult = toVersionedTargetGraph(params, targetGraphCreationResult);
        }

      } catch (BuildFileParseException | VersionException e) {
        params
            .getBuckEventBus()
            .post(ConsoleEvent.severe(MoreExceptions.getHumanReadableOrLocalizedMessage(e)));
        return ExitCode.PARSE_ERROR;
      }

      ActionGraphAndBuilder actionGraphAndBuilder =
          params.getActionGraphProvider().getActionGraph(targetGraphCreationResult);
      // Look up all of the test rules in the action graph.
      Iterable<TestRule> testRules =
          Iterables.filter(actionGraphAndBuilder.getActionGraph().getNodes(), TestRule.class);

      // Unless the user requests that we build filtered tests, filter them out here, before
      // the build.
      if (!isBuildFiltered(params.getBuckConfig())) {
        testRules = filterTestRules(params.getBuckConfig(), explicitBuildTargets, testRules);
      }

      ImmutableSet<BuildRule> rulesToMaterializeForAnalysis = ImmutableSet.of();
      ImmutableSet<JavaLibrary> rulesUnderTestForCoverage = ImmutableSet.of();
      if (isCodeCoverageEnabled) {
        // Ensure that the libraries that we want to inspect after the build are built/fetched.
        // This requires that the rules under test are materialized as well as any generated srcs
        // that they use
        BuildRuleResolver ruleResolver = actionGraphAndBuilder.getActionGraphBuilder();
        ImmutableSet<JavaLibrary> explicitJavaLibraryRules =
            params
                    .getBuckConfig()
                    .getView(TestBuckConfig.class)
                    .includeExplicitTargetsInTestCoverage()
                ? ruleResolver.getAllRules(explicitBuildTargets).stream()
                    .filter(JavaLibrary.class::isInstance)
                    .map(JavaLibrary.class::cast)
                    .collect(ImmutableSet.toImmutableSet())
                : ImmutableSet.of();
        rulesUnderTestForCoverage =
            ImmutableSet.<JavaLibrary>builder()
                .addAll(getRulesUnderTest(testRules))
                .addAll(explicitJavaLibraryRules)
                .build();
        rulesToMaterializeForAnalysis =
            RichStream.from(rulesUnderTestForCoverage)
                .flatMap(
                    lib -> RichStream.from(ruleResolver.filterBuildRuleInputs(lib.getJavaSrcs())))
                .concat(RichStream.from(rulesUnderTestForCoverage))
                .collect(ImmutableSet.toImmutableSet());
      }

      CachingBuildEngineBuckConfig cachingBuildEngineBuckConfig =
          params.getBuckConfig().getView(CachingBuildEngineBuckConfig.class);
      try (RuleKeyCacheScope<RuleKey> ruleKeyCacheScope =
          getDefaultRuleKeyCacheScope(
              params,
              new RuleKeyCacheRecycler.SettingsAffectingCache(
                  params.getBuckConfig().getView(BuildBuckConfig.class).getKeySeed(),
                  actionGraphAndBuilder.getActionGraph()))) {
        boolean remoteExecutionAutoEnabled =
            params
                .getBuckConfig()
                .getView(RemoteExecutionConfig.class)
                .isRemoteExecutionAutoEnabled(
                    params.getBuildEnvironmentDescription().getUser(), getArguments());
        LocalCachingBuildEngineDelegate localCachingBuildEngineDelegate =
            new LocalCachingBuildEngineDelegate(params.getFileHashCache());
        Console console = params.getConsole();
        Cell rootCell = cells.getRootCell();
        CellPathResolver cellPathResolver = rootCell.getCellPathResolver();
        try (CachingBuildEngine cachingBuildEngine =
                new CachingBuildEngine(
                    localCachingBuildEngineDelegate,
                    ModernBuildRuleBuilderFactory.getBuildStrategy(
                        params.getBuckConfig().getView(ModernBuildRuleConfig.class),
                        params.getBuckConfig().getView(RemoteExecutionConfig.class),
                        actionGraphAndBuilder.getActionGraphBuilder(),
                        cells,
                        cellPathResolver,
                        localCachingBuildEngineDelegate.getFileHashCache(),
                        params.getBuckEventBus(),
                        params.getMetadataProvider(),
                        remoteExecutionAutoEnabled,
                        isRemoteExecutionForceDisabled(),
                        ConsoleParams.of(
                            console.getAnsi().isAnsiTerminal(), console.getVerbosity())),
                    pool.getWeightedListeningExecutorService(),
                    getBuildEngineMode().orElse(cachingBuildEngineBuckConfig.getBuildEngineMode()),
                    cachingBuildEngineBuckConfig.getBuildDepFiles(),
                    cachingBuildEngineBuckConfig.getBuildMaxDepFileCacheEntries(),
                    cachingBuildEngineBuckConfig.getBuildArtifactCacheSizeLimit(),
                    cachingBuildEngineBuckConfig.getDefaultOutputHashSizeLimit(),
                    cachingBuildEngineBuckConfig.getRuleTypeOutputHashSizeLimit(),
                    cachingBuildEngineBuckConfig.shouldUseParallelDepsResolving(),
                    actionGraphAndBuilder.getActionGraphBuilder(),
                    actionGraphAndBuilder.getBuildEngineActionToBuildRuleResolver(),
                    params.getTargetConfigurationSerializer(),
                    params.getBuildInfoStoreManager(),
                    cachingBuildEngineBuckConfig.getResourceAwareSchedulingInfo(),
                    cachingBuildEngineBuckConfig.getConsoleLogBuildRuleFailuresInline(),
                    RuleKeyFactories.of(
                        params.getRuleKeyConfiguration(),
                        localCachingBuildEngineDelegate.getFileHashCache(),
                        actionGraphAndBuilder.getActionGraphBuilder(),
                        params
                            .getBuckConfig()
                            .getView(BuildBuckConfig.class)
                            .getBuildInputRuleKeyFileSizeLimit(),
                        ruleKeyCacheScope.getCache()));
            Build build =
                new Build(
                    actionGraphAndBuilder.getActionGraphBuilder(),
                    cells,
                    cachingBuildEngine,
                    params.getArtifactCacheFactory().newInstance(),
                    params
                        .getBuckConfig()
                        .getView(JavaBuckConfig.class)
                        .createDefaultJavaPackageFinder(),
                    params.getClock(),
                    getExecutionContext(),
                    isKeepGoing(),
                    params.getBuckConfig().getView(BuildReportConfig.class).getMaxNumberOfEntries(),
                    params
                        .getBuckConfig()
                        .getView(BuildReportConfig.class)
                        .getShouldPrintUnconfiguredSection())) {

          // Build all of the test rules and runtime deps.
          Iterable<BuildTarget> targets =
              RichStream.from(testRules)
                  .filter(HasRuntimeDeps.class::isInstance)
                  .map(HasRuntimeDeps.class::cast)
                  .flatMap(
                      rule -> rule.getRuntimeDeps(actionGraphAndBuilder.getActionGraphBuilder()))
                  .concat(RichStream.from(testRules).map(TestRule::getBuildTarget))
                  .concat(
                      RichStream.from(rulesToMaterializeForAnalysis).map(BuildRule::getBuildTarget))
                  .toImmutableList();
          ExitCode exitCode =
              build.executeAndPrintFailuresToEventBus(
                  targets,
                  params.getBuckEventBus(),
                  console,
                  getPathToBuildReport(params.getBuckConfig()));
          params.getBuckEventBus().post(BuildEvent.finished(started, exitCode));
          if (exitCode != ExitCode.SUCCESS) {
            return exitCode;
          }

          // If the user requests that we build tests that we filter out, then we perform
          // the filtering here, after we've done the build but before we run the tests.
          if (isBuildFiltered(params.getBuckConfig())) {
            testRules = filterTestRules(params.getBuckConfig(), explicitBuildTargets, testRules);
          }
          BuildContext buildContext =
              BuildContext.of(
                  actionGraphAndBuilder.getActionGraphBuilder().getSourcePathResolver(),
                  rootCell.getRoot(),
                  cells
                      .getBuckConfig()
                      .getView(JavaBuckConfig.class)
                      .createDefaultJavaPackageFinder(),
                  params.getBuckEventBus(),
                  params
                      .getBuckConfig()
                      .getView(BuildBuckConfig.class)
                      .getShouldDeleteTemporaries(),
                  cellPathResolver);

          ExternalTestRunnerProvider externalRunnerProvider =
              new ExternalTestRunnerProvider(params.getBuckEventBus());

          TestBuckConfig testBuckConfig = params.getBuckConfig().getView(TestBuckConfig.class);
          // Once all of the rules are built, then run the tests.
          Optional<ImmutableList<String>> externalTestRunner =
              externalRunnerProvider.getExternalTestRunner(params.getBuckConfig(), testRules);
          if (externalTestRunner.isPresent()) {
            displayIfNeededExternalRunnerAndUnsupportedFeature(
                params.getBuckEventBus(), isDebugEnabled);
            return runTestsExternal(
                params,
                build,
                externalTestRunner.get(),
                testRules,
                buildContext,
                testBuckConfig.isTtyForExternalTestRunnerEnabled(),
                testBuckConfig.getClientId());
          }
          return runTestsInternal(
              params,
              cachingBuildEngine,
              build,
              buildContext,
              testRules,
              rulesUnderTestForCoverage);
        }
      }
    }
  }

  @VisibleForTesting
  void displayIfNeededExternalRunnerAndUnsupportedFeature(
      BuckEventBus buckEventBus, boolean isDebugEnabled) {
    if (isDebugEnabled) {
      buckEventBus.post(ConsoleEvent.warning(EXTERNAL_RUNNER_AND_UNSUPPORTED_DEBUG_MODE));
    }
  }

  @Override
  protected ExecutionContext.Builder getExecutionContextBuilder(CommandRunnerParams params) {
    return super.getExecutionContextBuilder(params)
        .setAndroidDevicesHelper(
            AndroidDevicesHelperFactory.get(
                params.getCells().getRootCell().getToolchainProvider(),
                this::getExecutionContext,
                params.getBuckConfig(),
                getAdbOptions(params.getBuckConfig()),
                getTargetDeviceOptions()));
  }

  @Override
  public boolean isReadOnly() {
    return false;
  }

  @VisibleForTesting
  Iterable<TestRule> filterTestRules(
      BuckConfig buckConfig,
      ImmutableSet<BuildTarget> explicitBuildTargets,
      Iterable<TestRule> testRules) {

    ImmutableSortedSet.Builder<TestRule> builder =
        ImmutableSortedSet.orderedBy(Comparator.comparing(TestRule::getFullyQualifiedName));

    for (TestRule rule : testRules) {
      boolean explicitArgument = explicitBuildTargets.contains(rule.getBuildTarget());
      boolean matchesLabel = isMatchedByLabelOptions(buckConfig, rule.getLabels());

      // We always want to run the rules that are given on the command line. Always. Unless we don't
      // want to.
      if (shouldExcludeWin() && !matchesLabel) {
        continue;
      }

      // The testRules Iterable contains transitive deps of the arguments given on the command line,
      // filter those out if such is the user's will.
      if (shouldExcludeTransitiveTests() && !explicitArgument) {
        continue;
      }

      // Normal behavior is to include all rules that match the given label as well as any that
      // were explicitly specified by the user.
      if (explicitArgument || matchesLabel) {
        builder.add(rule);
      }
    }

    return builder.build();
  }

  @Override
  public void printUsage(PrintStream stream) {
    stream.println("Usage:");
    stream.println("  " + "buck test [<targets>] [<options>]");
    stream.println();

    stream.println("Description:");
    stream.println("  Builds and runs the tests for one or more specified targets.");
    stream.println("  You can either directly specify test targets, or any other target which");
    stream.println("  contains a `tests = ['...']` field to specify its tests. Alternatively,");
    stream.println("  by specifying no targets all of the tests will be run.");
    stream.println("  Tests get run by the internal test runner unless an external test runner");
    stream.println("  is specified in the .buckconfig file. Note that not all of the options");
    stream.println("  are applicable to all build rule types. Likewise, when an external test");
    stream.println("  runner is being used, some of the options listed here may not apply, and");
    stream.println("  you may need to use options specific to that test runner. See -- option.");
    stream.println();

    stream.println("Options:");
    new AdditionalOptionsCmdLineParser(getPluginManager(), this).printUsage(stream);
    stream.println();
  }

  @Override
  public String getShortDescription() {
    return "builds and runs the tests for the specified target";
  }

  /**
   * args4j does not support parsing repeated (or delimiter separated) Enums by default. {@link
   * CoverageReportFormatsHandler} implements args4j behavior for CoverageReportFormat.
   */
  public static class CoverageReportFormatsHandler extends OptionHandler<CoverageReportFormat> {

    public CoverageReportFormatsHandler(
        CmdLineParser parser, OptionDef option, Setter<CoverageReportFormat> setter) {
      super(parser, option, setter);
    }

    @Override
    public int parseArguments(Parameters params) throws CmdLineException {
      Set<String> parsed =
          Splitter.on(",").splitToList(params.getParameter(0)).stream()
              .map(s -> s.replaceAll("-", "_").toLowerCase())
              .collect(Collectors.toSet());
      List<CoverageReportFormat> formats = new ArrayList<>();
      for (CoverageReportFormat format : CoverageReportFormat.values()) {
        if (parsed.remove(format.name().toLowerCase())) {
          formats.add(format);
        }
      }

      if (parsed.size() != 0) {
        String invalidFormats = String.join(",", parsed);
        if (option.isArgument()) {
          throw new CmdLineException(
              owner, Messages.ILLEGAL_OPERAND, option.toString(), invalidFormats);
        } else {
          throw new CmdLineException(
              owner, Messages.ILLEGAL_OPERAND, params.getParameter(-1), invalidFormats);
        }
      }

      for (CoverageReportFormat format : formats) {
        setter.addValue(format);
      }
      return 1;
    }

    @Override
    public String getDefaultMetaVariable() {
      return Arrays.stream(CoverageReportFormat.values())
          .map(Enum::name)
          .collect(Collectors.joining(" | ", "[", "]"));
    }

    @Override
    public String getMetaVariable(ResourceBundle rb) {
      return getDefaultMetaVariable();
    }
  }

  @Override
  public boolean performsBuild() {
    return true;
  }

  /** It prints error message when users do not pass arguments to underlying binary correctly. */
  @Override
  public void handleException(CmdLineException e) throws CmdLineException {
    handleException(e, "If using an external runner, remember to use '--'.");
  }

  /** Generates the set of Java library rules under test. */
  static ImmutableSet<JavaLibrary> getRulesUnderTest(Iterable<TestRule> tests) {
    ImmutableSet.Builder<JavaLibrary> rulesUnderTest = ImmutableSet.builder();

    // Gathering all rules whose source will be under test.
    for (TestRule test : tests) {
      if (test instanceof JavaTest) {
        // Look at the transitive dependencies for `tests` attribute that refers to this test.
        JavaTest javaTest = (JavaTest) test;

        ImmutableSet<JavaLibrary> transitiveDeps =
            JavaLibraryClasspathProvider.getAllReachableJavaLibraries(
                ImmutableSet.of(javaTest.getCompiledTestsLibrary()));
        for (JavaLibrary dep : transitiveDeps) {
          if (dep instanceof JavaLibraryWithTests) {
            ImmutableSortedSet<BuildTarget> depTests = ((JavaLibraryWithTests) dep).getTests();
            if (depTests.contains(test.getBuildTarget())) {
              rulesUnderTest.add(dep);
            }
          }
        }
      }
      if (test instanceof AndroidInstrumentationTest) {
        // Look at the transitive dependencies for `tests` attribute that refers to this test.
        AndroidInstrumentationTest androidInstrumentationTest = (AndroidInstrumentationTest) test;

        HasInstallableApk apk = androidInstrumentationTest.getApk();
        if (apk instanceof AndroidApk) {
          AndroidApk androidApk = (AndroidApk) apk;
          Iterable<JavaLibrary> transitiveDeps = androidApk.getTransitiveClasspathDeps();

          if (androidApk instanceof AndroidInstrumentationApk) {
            transitiveDeps =
                Iterables.concat(
                    transitiveDeps,
                    ((AndroidInstrumentationApk) androidApk)
                        .getApkUnderTest()
                        .getTransitiveClasspathDeps());
          }
          for (JavaLibrary dep : transitiveDeps) {
            if (dep instanceof JavaLibraryWithTests) {
              ImmutableSortedSet<BuildTarget> depTests = ((JavaLibraryWithTests) dep).getTests();
              if (depTests.contains(test.getBuildTarget())) {
                rulesUnderTest.add(dep);
              }
            }
          }
        }
      }
    }

    return rulesUnderTest.build();
  }
}
