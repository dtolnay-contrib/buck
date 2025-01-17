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

package com.facebook.buck.features.apple.project;

import com.facebook.buck.apple.AppleBinaryDescription;
import com.facebook.buck.apple.AppleBundleDescription;
import com.facebook.buck.apple.AppleConfig;
import com.facebook.buck.apple.AppleLibraryDescription;
import com.facebook.buck.apple.AppleTestDescription;
import com.facebook.buck.apple.XCodeDescriptions;
import com.facebook.buck.apple.XCodeDescriptionsFactory;
import com.facebook.buck.cli.ProjectTestsMode;
import com.facebook.buck.command.config.BuildBuckConfig;
import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.CellProvider;
import com.facebook.buck.core.cell.Cells;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.description.BaseDescription;
import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.graph.transformation.executor.DepsAwareExecutor;
import com.facebook.buck.core.graph.transformation.model.ComputeResult;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.UnflavoredBuildTarget;
import com.facebook.buck.core.model.targetgraph.NoSuchTargetException;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphCreationResult;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.model.targetgraph.impl.TargetNodes;
import com.facebook.buck.core.parser.buildtargetparser.UnconfiguredBuildTargetViewFactory;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.config.registry.impl.ConfigurationRuleRegistryFactory;
import com.facebook.buck.core.rules.resolver.impl.MultiThreadedActionGraphBuilder;
import com.facebook.buck.core.rules.transformer.impl.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.core.util.graph.AcyclicDepthFirstPostOrderTraversal;
import com.facebook.buck.core.util.graph.CycleException;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.cxx.config.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.facebook.buck.cxx.toolchain.impl.LegacyToolchainProvider;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.features.alias.AbstractAliasDescription;
import com.facebook.buck.features.apple.common.PathOutputPresenter;
import com.facebook.buck.features.apple.common.XcodeWorkspaceConfigDescription;
import com.facebook.buck.features.apple.common.XcodeWorkspaceConfigDescriptionArg;
import com.facebook.buck.features.halide.HalideBuckConfig;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.parser.Parser;
import com.facebook.buck.parser.ParsingContext;
import com.facebook.buck.parser.SpeculativeParsing;
import com.facebook.buck.parser.config.ParserConfig;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.parser.exceptions.NoSuchBuildTargetException;
import com.facebook.buck.parser.spec.TargetNodeSpec;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.rules.keys.config.RuleKeyConfiguration;
import com.facebook.buck.swift.SwiftBuckConfig;
import com.facebook.buck.util.CloseableMemoizedSupplier;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.MoreExceptions;
import com.facebook.buck.util.ProcessManager;
import com.facebook.buck.util.collect.MoreSets;
import com.facebook.buck.util.config.Configs;
import com.facebook.buck.util.stream.RichStream;
import com.facebook.buck.versions.InstrumentedVersionedTargetGraphCache;
import com.facebook.buck.versions.VersionException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.annotation.concurrent.ThreadSafe;
import org.pf4j.PluginManager;

public class XCodeProjectCommandHelper {

  private static final Logger LOG = Logger.get(XCodeProjectCommandHelper.class);

  private static final String XCODE_PROCESS_NAME = "Xcode";

  private final BuckEventBus buckEventBus;
  private final PluginManager pluginManager;
  private final Parser parser;
  private final BuckConfig buckConfig;
  private final InstrumentedVersionedTargetGraphCache versionedTargetGraphCache;
  private final TypeCoercerFactory typeCoercerFactory;
  private final UnconfiguredBuildTargetViewFactory unconfiguredBuildTargetFactory;
  private final Cells cells;
  private final Optional<TargetConfiguration> targetConfiguration;
  private final ImmutableSet<Flavor> appleCxxFlavors;
  private final RuleKeyConfiguration ruleKeyConfiguration;
  private final Console console;
  private final Optional<ProcessManager> processManager;
  private final ImmutableMap<String, String> environment;
  private final ListeningExecutorService executorService;
  private final List<String> arguments;
  private final boolean sharedLibrariesInBundles;
  private final boolean withTests;
  private final boolean withoutTests;
  private final boolean withoutDependenciesTests;
  private final String modulesToFocusOn;
  private final boolean combinedProject;
  private final boolean createProjectSchemes;
  private final boolean dryRun;
  private final boolean readOnly;
  private final PathOutputPresenter outputPresenter;
  private final ParsingContext parsingContext;

  private final Function<Iterable<String>, ImmutableList<TargetNodeSpec>> argsParser;
  private final Function<ImmutableList<BuildTarget>, ExitCode> buildRunner;
  private final Supplier<DepsAwareExecutor<? super ComputeResult, ?>> depsAwareExecutorSupplier;

  public XCodeProjectCommandHelper(
      BuckEventBus buckEventBus,
      PluginManager pluginManager,
      Parser parser,
      BuckConfig buckConfig,
      InstrumentedVersionedTargetGraphCache versionedTargetGraphCache,
      TypeCoercerFactory typeCoercerFactory,
      UnconfiguredBuildTargetViewFactory unconfiguredBuildTargetFactory,
      Cells cells,
      RuleKeyConfiguration ruleKeyConfiguration,
      Optional<TargetConfiguration> targetConfiguration,
      Console console,
      Optional<ProcessManager> processManager,
      ImmutableMap<String, String> environment,
      ListeningExecutorService executorService,
      ListeningExecutorService parsingExecutorService,
      CloseableMemoizedSupplier<DepsAwareExecutor<? super ComputeResult, ?>> depsAwareExecutor,
      ImmutableSet<Flavor> appleCxxFlavors,
      boolean sharedLibrariesInBundles,
      boolean enableParserProfiling,
      boolean withTests,
      boolean withoutTests,
      boolean withoutDependenciesTests,
      String modulesToFocusOn,
      boolean combinedProject,
      boolean createProjectSchemes,
      boolean dryRun,
      boolean readOnly,
      PathOutputPresenter outputPresenter,
      Function<Iterable<String>, ImmutableList<TargetNodeSpec>> argsParser,
      Function<ImmutableList<BuildTarget>, ExitCode> buildRunner,
      List<String> arguments) {
    this.buckEventBus = buckEventBus;
    this.pluginManager = pluginManager;
    this.parser = parser;
    this.buckConfig = buckConfig;
    this.versionedTargetGraphCache = versionedTargetGraphCache;
    this.typeCoercerFactory = typeCoercerFactory;
    this.unconfiguredBuildTargetFactory = unconfiguredBuildTargetFactory;
    this.cells = cells;
    this.targetConfiguration = targetConfiguration;
    this.depsAwareExecutorSupplier = depsAwareExecutor;
    this.appleCxxFlavors = appleCxxFlavors;
    this.ruleKeyConfiguration = ruleKeyConfiguration;
    this.console = console;
    this.processManager = processManager;
    this.environment = environment;
    this.executorService = executorService;
    this.arguments = arguments;
    this.sharedLibrariesInBundles = sharedLibrariesInBundles;
    this.withTests = withTests;
    this.withoutTests = withoutTests;
    this.withoutDependenciesTests = withoutDependenciesTests;
    this.modulesToFocusOn = modulesToFocusOn;
    this.combinedProject = combinedProject;
    this.createProjectSchemes = createProjectSchemes;
    this.dryRun = dryRun;
    this.readOnly = readOnly;
    this.outputPresenter = outputPresenter;
    this.argsParser = argsParser;
    this.buildRunner = buildRunner;
    this.parsingContext =
        ParsingContext.builder(this.cells, parsingExecutorService)
            .setProfilingEnabled(enableParserProfiling)
            .setSpeculativeParsing(SpeculativeParsing.ENABLED)
            .setApplyDefaultFlavorsMode(
                buckConfig.getView(ParserConfig.class).getDefaultFlavorsMode())
            .build();
  }

  public ExitCode parseTargetsAndRunXCodeGenerator() throws IOException, InterruptedException {
    TargetGraphCreationResult targetGraphCreationResult;

    LOG.debug("Xcode project generation: Getting the target graph");

    try {
      ImmutableSet<BuildTarget> passedInTargetsSet =
          ImmutableSet.copyOf(
              Iterables.concat(
                  parser.resolveTargetSpecs(
                      parsingContext,
                      argsParser.apply(arguments.isEmpty() ? ImmutableList.of("//...") : arguments),
                      targetConfiguration)));
      if (passedInTargetsSet.isEmpty()) {
        throw new HumanReadableException("Could not find targets matching arguments");
      }
      targetGraphCreationResult = parser.buildTargetGraph(parsingContext, passedInTargetsSet);
      if (arguments.isEmpty()) {
        targetGraphCreationResult =
            targetGraphCreationResult.withBuildTargets(
                getRootsFromPredicate(
                    targetGraphCreationResult.getTargetGraph(),
                    node -> node.getDescription() instanceof XcodeWorkspaceConfigDescription));
      }
    } catch (BuildFileParseException e) {
      buckEventBus.post(ConsoleEvent.severe(MoreExceptions.getHumanReadableOrLocalizedMessage(e)));
      return ExitCode.PARSE_ERROR;
    } catch (HumanReadableException e) {
      buckEventBus.post(ConsoleEvent.severe(MoreExceptions.getHumanReadableOrLocalizedMessage(e)));
      return ExitCode.BUILD_ERROR;
    }

    LOG.debug("Xcode project generation: Killing existing Xcode if needed");

    checkForAndKillXcodeIfRunning(getIDEForceKill(buckConfig));

    LOG.debug("Xcode project generation: Getting more part of the target graph");

    try {
      targetGraphCreationResult =
          enhanceTargetGraphIfNeeded(
              depsAwareExecutorSupplier,
              targetGraphCreationResult,
              isWithTests(buckConfig),
              isWithDependenciesTests(buckConfig));
    } catch (BuildFileParseException | NoSuchTargetException | VersionException e) {
      buckEventBus.post(ConsoleEvent.severe(MoreExceptions.getHumanReadableOrLocalizedMessage(e)));
      return ExitCode.PARSE_ERROR;
    } catch (HumanReadableException e) {
      buckEventBus.post(ConsoleEvent.severe(MoreExceptions.getHumanReadableOrLocalizedMessage(e)));
      return ExitCode.BUILD_ERROR;
    }

    Optional<ImmutableMap<BuildTarget, TargetNode<?>>> sharedLibraryToBundle = Optional.empty();

    if (sharedLibrariesInBundles) {
      sharedLibraryToBundle =
          Optional.of(
              ProjectGenerator.computeSharedLibrariesToBundles(
                  targetGraphCreationResult.getTargetGraph().getNodes(),
                  targetGraphCreationResult.getTargetGraph()));
    }

    if (dryRun) {
      for (TargetNode<?> targetNode : targetGraphCreationResult.getTargetGraph().getNodes()) {
        console.getStdOut().println(targetNode.toString());
      }

      return ExitCode.SUCCESS;
    }

    LOG.debug("Xcode project generation: Run the project generator");

    return runXcodeProjectGenerator(targetGraphCreationResult, sharedLibraryToBundle);
  }

  private static String getIDEForceKillSectionName() {
    return "project";
  }

  private static String getIDEForceKillFieldName() {
    return "ide_force_kill";
  }

  private IDEForceKill getIDEForceKill(BuckConfig buckConfig) {
    Optional<IDEForceKill> forceKill =
        buckConfig.getEnum(
            getIDEForceKillSectionName(), getIDEForceKillFieldName(), IDEForceKill.class);
    if (forceKill.isPresent()) {
      return forceKill.get();
    }

    // Support legacy config if new key is missing.
    Optional<Boolean> legacyPrompty = buckConfig.getBoolean("project", "ide_prompt");
    if (legacyPrompty.isPresent()) {
      return legacyPrompty.get().booleanValue() ? IDEForceKill.PROMPT : IDEForceKill.NEVER;
    }

    return IDEForceKill.PROMPT;
  }

  private ProjectTestsMode getXcodeProjectTestsMode(BuckConfig buckConfig) {
    return buckConfig
        .getEnum("project", "xcode_project_tests_mode", ProjectTestsMode.class)
        .orElse(ProjectTestsMode.WITH_TESTS);
  }

  private ProjectTestsMode testsMode(BuckConfig buckConfig) {
    ProjectTestsMode parameterMode = getXcodeProjectTestsMode(buckConfig);

    if (withoutTests) {
      parameterMode = ProjectTestsMode.WITHOUT_TESTS;
    } else if (withoutDependenciesTests) {
      parameterMode = ProjectTestsMode.WITHOUT_DEPENDENCIES_TESTS;
    } else if (withTests) {
      parameterMode = ProjectTestsMode.WITH_TESTS;
    }

    return parameterMode;
  }

  private boolean isWithTests(BuckConfig buckConfig) {
    return testsMode(buckConfig) != ProjectTestsMode.WITHOUT_TESTS;
  }

  private boolean isWithDependenciesTests(BuckConfig buckConfig) {
    return testsMode(buckConfig) == ProjectTestsMode.WITH_TESTS;
  }

  /** Run xcode specific project generation actions. */
  private ExitCode runXcodeProjectGenerator(
      TargetGraphCreationResult targetGraphCreationResult,
      Optional<ImmutableMap<BuildTarget, TargetNode<?>>> sharedLibraryToBundle)
      throws IOException, InterruptedException {
    ExitCode exitCode = ExitCode.SUCCESS;
    AppleConfig appleConfig = buckConfig.getView(AppleConfig.class);
    ProjectGeneratorOptions options =
        ProjectGeneratorOptions.builder()
            .setShouldGenerateReadOnlyFiles(readOnly)
            .setShouldIncludeTests(isWithTests(buckConfig))
            .setShouldIncludeDependenciesTests(isWithDependenciesTests(buckConfig))
            .setShouldMergeHeaderMaps(appleConfig.shouldMergeHeaderMapsInXcodeProject())
            .setShouldAddLinkedLibrariesAsFlags(appleConfig.shouldAddLinkedLibrariesAsFlags())
            .setShouldLinkSystemSwift(appleConfig.shouldLinkSystemSwift())
            .setShouldForceLoadLinkWholeLibraries(
                appleConfig.shouldAddLinkerFlagsForLinkWholeLibraries())
            .setShouldUseShortNamesForTargets(true)
            .setShouldCreateDirectoryStructure(combinedProject)
            .setShouldGenerateProjectSchemes(createProjectSchemes)
            .build();

    LOG.debug("Xcode project generation: Generates workspaces for targets");

    ImmutableList<Result> results =
        generateWorkspacesForTargets(
            buckEventBus,
            pluginManager,
            cells,
            cells.getRootCell(),
            buckConfig,
            ruleKeyConfiguration,
            executorService,
            targetGraphCreationResult,
            options,
            appleCxxFlavors,
            getFocusModules(),
            new HashMap<>(),
            combinedProject,
            sharedLibraryToBundle);
    ImmutableSet<BuildTarget> requiredBuildTargets =
        results.stream()
            .flatMap(b -> b.getBuildTargets().stream())
            .collect(ImmutableSet.toImmutableSet());
    if (!requiredBuildTargets.isEmpty()) {
      exitCode = buildRunner.apply(requiredBuildTargets.asList());
    }

    // Write all output paths to stdout if requested.
    // IMPORTANT: this shuts down RenderingConsole since it writes to stdout.
    // (See DirtyPrintStreamDecorator and note how RenderingConsole uses it.)
    // Thus this must be the *last* thing we do, or we disable progress UI.
    //
    // This is still not the "right" way to do this; we should probably use
    // RenderingConsole#printToStdOut since it ensures we do one last render.
    for (Result result : results) {
      outputPresenter.present(
          result.inputTarget.getFullyQualifiedName(), result.outputRelativePath);
    }

    ProjectFilesystem rootFilesystem = cells.getRootCell().getFilesystem();

    for (PostBuildCopySpec sourceWithTarget :
        results.stream()
            .map(Result::getFilesToCopyAfterDependenciesAreBuilt)
            .flatMap(Collection::stream)
            .collect(Collectors.toList())) {
      rootFilesystem.createParentDirs(sourceWithTarget.getTo());
      rootFilesystem.copyFile(sourceWithTarget.getFrom(), sourceWithTarget.getTo());
    }

    return exitCode;
  }

  /** A result with metadata about the subcommand helper's output. */
  public static class Result {
    private final BuildTarget inputTarget;
    private final Path outputRelativePath;
    private final ImmutableSet<BuildTarget> buildTargets;
    private final ImmutableSet<PostBuildCopySpec> filesToCopyAfterDependenciesAreBuilt;

    public Result(
        BuildTarget inputTarget,
        Path outputRelativePath,
        ImmutableSet<BuildTarget> buildTargets,
        ImmutableSet<PostBuildCopySpec> filesToCopyAfterDependenciesAreBuilt) {
      this.inputTarget = inputTarget;
      this.outputRelativePath = outputRelativePath;
      this.buildTargets = buildTargets;
      this.filesToCopyAfterDependenciesAreBuilt = filesToCopyAfterDependenciesAreBuilt;
    }

    public BuildTarget getInputTarget() {
      return inputTarget;
    }

    public Path getOutputRelativePath() {
      return outputRelativePath;
    }

    public ImmutableSet<BuildTarget> getBuildTargets() {
      return buildTargets;
    }

    public ImmutableSet<PostBuildCopySpec> getFilesToCopyAfterDependenciesAreBuilt() {
      return filesToCopyAfterDependenciesAreBuilt;
    }
  }

  @VisibleForTesting
  static ImmutableList<Result> generateWorkspacesForTargets(
      BuckEventBus buckEventBus,
      PluginManager pluginManager,
      Cells cells,
      Cell cell,
      BuckConfig buckConfig,
      RuleKeyConfiguration ruleKeyConfiguration,
      ListeningExecutorService executorService,
      TargetGraphCreationResult targetGraphCreationResult,
      ProjectGeneratorOptions options,
      ImmutableSet<Flavor> appleCxxFlavors,
      FocusedModuleTargetMatcher focusModules,
      Map<Path, ProjectGenerator> projectGenerators,
      boolean combinedProject,
      Optional<ImmutableMap<BuildTarget, TargetNode<?>>> sharedLibraryToBundle)
      throws IOException, InterruptedException {

    LazyActionGraph lazyActionGraph =
        new LazyActionGraph(targetGraphCreationResult.getTargetGraph(), cells.getCellProvider());

    XCodeDescriptions xcodeDescriptions = XCodeDescriptionsFactory.create(pluginManager);

    LOG.debug(
        "Generating workspace for config targets %s", targetGraphCreationResult.getBuildTargets());
    ImmutableList.Builder<Result> generationResultsBuilder = ImmutableList.builder();
    for (BuildTarget inputTarget : targetGraphCreationResult.getBuildTargets()) {
      TargetNode<?> inputNode = targetGraphCreationResult.getTargetGraph().get(inputTarget);
      XcodeWorkspaceConfigDescriptionArg workspaceArgs;
      if (inputNode.getDescription() instanceof XcodeWorkspaceConfigDescription) {
        TargetNode<XcodeWorkspaceConfigDescriptionArg> castedWorkspaceNode =
            castToXcodeWorkspaceTargetNode(inputNode);
        workspaceArgs = castedWorkspaceNode.getConstructorArg();
      } else if (inputNode.getDescription() instanceof AbstractAliasDescription) {
        BuildTarget actualTarget = resolveBuildTargetFromAliasNode(inputNode);
        TargetNode<?> actualNode = targetGraphCreationResult.getTargetGraph().get(actualTarget);
        if (canGenerateImplicitWorkspaceForDescription(actualNode.getDescription())) {
          workspaceArgs = createImplicitWorkspaceArgs(actualNode);
        } else {
          throw new HumanReadableException(
              "%s must point to a xcode_workspace_config, apple_binary, apple_bundle, apple_library, or apple_test",
              inputNode);
        }
      } else if (canGenerateImplicitWorkspaceForDescription(inputNode.getDescription())) {
        workspaceArgs = createImplicitWorkspaceArgs(inputNode);
      } else {
        throw new HumanReadableException(
            "%s must be a xcode_workspace_config, apple_binary, apple_bundle, apple_library, apple_test, or an alias pointing to one of the above",
            inputNode);
      }

      AppleConfig appleConfig = buckConfig.getView(AppleConfig.class);
      HalideBuckConfig halideBuckConfig = new HalideBuckConfig(buckConfig);
      CxxBuckConfig cxxBuckConfig = new CxxBuckConfig(buckConfig);
      SwiftBuckConfig swiftBuckConfig = new SwiftBuckConfig(buckConfig);

      CxxPlatformsProvider cxxPlatformsProvider =
          cell.getToolchainProvider()
              .getByName(
                  CxxPlatformsProvider.DEFAULT_NAME,
                  inputTarget.getTargetConfiguration(),
                  CxxPlatformsProvider.class);

      CxxPlatform defaultCxxPlatform =
          LegacyToolchainProvider.getLegacyTotallyUnsafe(
              cxxPlatformsProvider.getDefaultUnresolvedCxxPlatform());
      Cell workspaceCell = cells.getCell(inputTarget.getCell());
      WorkspaceAndProjectGenerator generator =
          new WorkspaceAndProjectGenerator(
              xcodeDescriptions,
              cells,
              workspaceCell,
              targetGraphCreationResult.getTargetGraph(),
              workspaceArgs,
              inputTarget,
              options,
              combinedProject,
              focusModules,
              !appleConfig.getXcodeDisableParallelizeBuild(),
              defaultCxxPlatform,
              appleCxxFlavors,
              buckConfig.getView(ParserConfig.class).getBuildFileName().getName(),
              lazyActionGraph::getActionGraphBuilderWhileRequiringSubgraph,
              buckEventBus,
              ruleKeyConfiguration,
              halideBuckConfig,
              cxxBuckConfig,
              appleConfig,
              swiftBuckConfig,
              sharedLibraryToBundle);
      Objects.requireNonNull(
          executorService, "CommandRunnerParams does not have executor for PROJECT pool");
      Path outputPath =
          generator.generateWorkspaceAndDependentProjects(projectGenerators, executorService);

      ImmutableSet<BuildTarget> requiredBuildTargetsForWorkspace =
          generator.getRequiredBuildTargets();
      LOG.verbose(
          "Required build targets for workspace %s: %s",
          inputTarget, requiredBuildTargetsForWorkspace);

      Path absolutePath = workspaceCell.getFilesystem().resolve(outputPath);
      RelPath relativePath = cell.getFilesystem().relativize(absolutePath);

      generationResultsBuilder.add(
          new Result(
              inputTarget,
              relativePath.getPath(),
              requiredBuildTargetsForWorkspace,
              generator.getFilesToCopyAfterDependenciesAreBuilt()));
    }

    return generationResultsBuilder.build();
  }

  private FocusedModuleTargetMatcher getFocusModules() throws InterruptedException {
    if (modulesToFocusOn == null) {
      return FocusedModuleTargetMatcher.noFocus();
    }

    Iterable<String> patterns = Splitter.onPattern("\\s+").split(modulesToFocusOn);
    // Parse patterns with the following syntax:
    // https://dev.buck.build/concept/build_target_pattern.html
    ImmutableList<TargetNodeSpec> specs = argsParser.apply(patterns);

    // Resolve the list of targets matching the patterns.
    ImmutableSet<BuildTarget> passedInTargetsSet;
    try {
      passedInTargetsSet =
          parser
              .resolveTargetSpecs(
                  parsingContext.withSpeculativeParsing(SpeculativeParsing.DISABLED),
                  specs,
                  targetConfiguration)
              .stream()
              .flatMap(Collection::stream)
              .collect(ImmutableSet.toImmutableSet());
    } catch (HumanReadableException e) {
      buckEventBus.post(ConsoleEvent.severe(MoreExceptions.getHumanReadableOrLocalizedMessage(e)));
      return FocusedModuleTargetMatcher.noFocus();
    }
    LOG.verbose("Selected targets: %s", passedInTargetsSet);

    ImmutableSet<UnflavoredBuildTarget> passedInUnflavoredTargetsSet =
        RichStream.from(passedInTargetsSet)
            .map(BuildTarget::getUnflavoredBuildTarget)
            .toImmutableSet();
    LOG.verbose("Selected unflavored targets: %s", passedInUnflavoredTargetsSet);
    return FocusedModuleTargetMatcher.focusedOn(passedInUnflavoredTargetsSet);
  }

  @SuppressWarnings(value = "unchecked")
  private static BuildTarget resolveBuildTargetFromAliasNode(TargetNode<?> inputNode) {
    Preconditions.checkArgument(inputNode.getDescription() instanceof AbstractAliasDescription);
    // We are trying to use the same codepath for both Alias and ConfiguredAlias, which means we
    // don't have a single type for the constructor arg. We know it will be the right type though
    // since it is coming off of the right target node.
    TargetNode<BuildRuleArg> castedNode = (TargetNode<BuildRuleArg>) inputNode;
    AbstractAliasDescription<BuildRuleArg> description =
        (AbstractAliasDescription<BuildRuleArg>) castedNode.getDescription();
    return description.resolveActualBuildTarget(castedNode.getConstructorArg());
  }

  @SuppressWarnings(value = "unchecked")
  private static TargetNode<XcodeWorkspaceConfigDescriptionArg> castToXcodeWorkspaceTargetNode(
      TargetNode<?> targetNode) {
    Preconditions.checkArgument(
        targetNode.getDescription() instanceof XcodeWorkspaceConfigDescription);
    return (TargetNode<XcodeWorkspaceConfigDescriptionArg>) targetNode;
  }

  private void checkForAndKillXcodeIfRunning(IDEForceKill forceKill)
      throws InterruptedException, IOException {
    if (forceKill == IDEForceKill.NEVER) {
      // We don't even check if Xcode is running because pkill can hang.
      LOG.debug("Prompt to kill Xcode is disabled");
      return;
    }

    if (!processManager.isPresent()) {
      LOG.warn("Could not check if Xcode is running (no process manager)");
      return;
    }

    if (!processManager.get().isProcessRunning(XCODE_PROCESS_NAME)) {
      LOG.debug("Xcode is not running.");
      return;
    }

    switch (forceKill) {
      case PROMPT:
        {
          boolean canPromptResult = canPrompt(environment);
          if (canPromptResult) {
            if (prompt(
                "Xcode is currently running. Buck will modify files Xcode currently has "
                    + "open, which can cause it to become unstable.\n\n"
                    + "Kill Xcode and continue?")) {
              processManager.get().killProcess(XCODE_PROCESS_NAME);
            } else {
              console
                  .getStdOut()
                  .println(
                      console
                          .getAnsi()
                          .asWarningText(
                              "Xcode is running. Generated projects might be lost or corrupted if Xcode "
                                  + "currently has them open."));
            }
            console
                .getStdOut()
                .format(
                    "To disable this prompt in the future, add the following to %s: \n\n"
                        + "[%s]\n"
                        + "  %s = %s\n\n"
                        + "If you would like to always kill Xcode, use '%s'.\n",
                    cells
                        .getRootCell()
                        .getFilesystem()
                        .getRootPath()
                        .resolve(Configs.DEFAULT_BUCK_CONFIG_OVERRIDE_FILE_NAME),
                    getIDEForceKillSectionName(),
                    getIDEForceKillFieldName(),
                    IDEForceKill.NEVER.toString().toLowerCase(),
                    IDEForceKill.ALWAYS.toString().toLowerCase());
          } else {
            LOG.debug(
                "Xcode is running, but cannot prompt to kill it (force kill %s, can prompt %s)",
                forceKill.toString(), canPromptResult);
          }
          break;
        }
      case ALWAYS:
        {
          LOG.debug("Will try to force kill Xcode without prompting...");
          processManager.get().killProcess(XCODE_PROCESS_NAME);
          console.getStdOut().println(console.getAnsi().asWarningText("Xcode was force killed."));
          break;
        }
      case NEVER:
        break;
    }
  }

  private boolean canPrompt(ImmutableMap<String, String> environment) {
    String nailgunStdinTty = environment.get("NAILGUN_TTY_0");
    if (nailgunStdinTty != null) {
      return nailgunStdinTty.equals("1");
    } else {
      return System.console() != null;
    }
  }

  private boolean prompt(String prompt) throws IOException {
    Preconditions.checkState(canPrompt(environment));

    LOG.debug("Displaying prompt %s..", prompt);
    console.getStdOut().print(console.getAnsi().asWarningText(prompt + " [Y/n] "));

    // Do not close readers! Otherwise they close System.in in turn
    // Another design may be to provide reader in the context, to use instead of System.in
    BufferedReader bufferedStdinReader =
        new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    Optional<String> result = Optional.ofNullable(bufferedStdinReader.readLine());

    LOG.debug("Result of prompt: [%s]", result.orElse(""));
    return result.isPresent()
        && (result.get().isEmpty() || result.get().toLowerCase(Locale.US).startsWith("y"));
  }

  @VisibleForTesting
  static ImmutableSet<BuildTarget> getRootsFromPredicate(
      TargetGraph projectGraph, Predicate<TargetNode<?>> rootsPredicate) {
    return projectGraph.getNodes().stream()
        .filter(rootsPredicate)
        .map(TargetNode::getBuildTarget)
        .collect(ImmutableSet.toImmutableSet());
  }

  private TargetGraphCreationResult enhanceTargetGraphIfNeeded(
      Supplier<DepsAwareExecutor<? super ComputeResult, ?>> depsAwareExecutorSupplier,
      TargetGraphCreationResult targetGraphCreationResult,
      boolean isWithTests,
      boolean isWithDependenciesTests)
      throws IOException, InterruptedException, BuildFileParseException, VersionException {

    ImmutableSet<BuildTarget> originalBuildTargets = targetGraphCreationResult.getBuildTargets();

    if (isWithTests) {
      FocusedModuleTargetMatcher focusedModules = getFocusModules();

      ImmutableSet<BuildTarget> graphRootsOrSourceTargets =
          replaceWorkspacesWithSourceTargetsIfPossible(targetGraphCreationResult);

      ImmutableSet<BuildTarget> explicitTestTargets =
          getExplicitTestTargets(
              graphRootsOrSourceTargets,
              targetGraphCreationResult.getTargetGraph(),
              isWithDependenciesTests,
              focusedModules);

      targetGraphCreationResult =
          parser.buildTargetGraph(
              parsingContext,
              MoreSets.union(targetGraphCreationResult.getBuildTargets(), explicitTestTargets));
    }

    if (buckConfig.getView(BuildBuckConfig.class).getBuildVersions()) {
      targetGraphCreationResult =
          versionedTargetGraphCache.toVersionedTargetGraph(
              depsAwareExecutorSupplier.get(),
              buckConfig,
              typeCoercerFactory,
              unconfiguredBuildTargetFactory,
              targetGraphCreationResult,
              buckEventBus,
              cells);
    }
    return targetGraphCreationResult.withBuildTargets(originalBuildTargets);
  }

  @VisibleForTesting
  static ImmutableSet<BuildTarget> replaceWorkspacesWithSourceTargetsIfPossible(
      TargetGraphCreationResult targetGraphCreationResult) {
    Iterable<TargetNode<?>> targetNodes =
        targetGraphCreationResult
            .getTargetGraph()
            .getAll(targetGraphCreationResult.getBuildTargets());
    ImmutableSet.Builder<BuildTarget> resultBuilder = ImmutableSet.builder();
    for (TargetNode<?> node : targetNodes) {
      if (node.getDescription() instanceof XcodeWorkspaceConfigDescription) {
        TargetNode<XcodeWorkspaceConfigDescriptionArg> castedWorkspaceNode =
            castToXcodeWorkspaceTargetNode(node);
        Optional<BuildTarget> srcTarget = castedWorkspaceNode.getConstructorArg().getSrcTarget();
        if (srcTarget.isPresent()) {
          resultBuilder.add(srcTarget.get());
        } else {
          resultBuilder.add(node.getBuildTarget());
        }
      } else {
        resultBuilder.add(node.getBuildTarget());
      }
    }
    return resultBuilder.build();
  }

  private static boolean canGenerateImplicitWorkspaceForDescription(
      BaseDescription<?> description) {
    // We weren't given a workspace target, but we may have been given something that could
    // still turn into a workspace (for example, a library or an actual app rule). If that's the
    // case we still want to generate a workspace.
    return description instanceof AppleBinaryDescription
        || description instanceof AppleBundleDescription
        || description instanceof AppleLibraryDescription
        || description instanceof AppleTestDescription;
  }

  /**
   * @param sourceTargetNode - The TargetNode which will act as our fake workspaces `src_target`
   * @return Workspace Args that describe a generic Xcode workspace containing `src_target` and its
   *     tests
   */
  private static XcodeWorkspaceConfigDescriptionArg createImplicitWorkspaceArgs(
      TargetNode<?> sourceTargetNode) {
    return XcodeWorkspaceConfigDescriptionArg.builder()
        .setName("dummy")
        .setSrcTarget(sourceTargetNode.getBuildTarget())
        .build();
  }

  /**
   * @param buildTargets The set of targets for which we would like to find tests
   * @param projectGraph A TargetGraph containing all nodes and their tests.
   * @param shouldIncludeDependenciesTests Should or not include tests that test dependencies
   * @return A set of all test targets that test any of {@code buildTargets} or their dependencies.
   */
  @VisibleForTesting
  static ImmutableSet<BuildTarget> getExplicitTestTargets(
      ImmutableSet<BuildTarget> buildTargets,
      TargetGraph projectGraph,
      boolean shouldIncludeDependenciesTests,
      FocusedModuleTargetMatcher focusedModules) {
    Iterable<TargetNode<?>> projectRoots = projectGraph.getAll(buildTargets);
    Iterable<TargetNode<?>> nodes;
    if (shouldIncludeDependenciesTests) {
      nodes = projectGraph.getSubgraph(projectRoots).getNodes();
    } else {
      nodes = projectRoots;
    }

    return TargetNodes.getTestTargetsForNodes(
        RichStream.from(nodes)
            .filter(node -> focusedModules.isFocusedOn(node.getBuildTarget()))
            .iterator());
  }

  /**
   * An action graph where subtrees are populated as needed.
   *
   * <p>This is useful when only select sub-graphs of the action graph needs to be generated, but
   * the subgraph is not known at this point in time. The synchronization and bottom-up traversal is
   * necessary as this will be accessed from multiple threads during project generation, and
   * BuildRuleResolver is not 100% thread safe when it comes to mutations.
   */
  @ThreadSafe
  private static class LazyActionGraph {
    private final TargetGraph targetGraph;
    private final ActionGraphBuilder graphBuilder;
    private final Set<BuildTarget> traversedTargets;

    public LazyActionGraph(TargetGraph targetGraph, CellProvider cellProvider) {
      this.targetGraph = targetGraph;
      this.graphBuilder =
          new MultiThreadedActionGraphBuilder(
              MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor()),
              targetGraph,
              ConfigurationRuleRegistryFactory.createRegistry(targetGraph),
              new DefaultTargetNodeToBuildRuleTransformer(),
              cellProvider);
      this.traversedTargets = new HashSet<>();
    }

    public ActionGraphBuilder getActionGraphBuilderWhileRequiringSubgraph(TargetNode<?> root) {
      synchronized (this) {
        try {
          List<BuildTarget> currentTargets = new ArrayList<>();
          for (TargetNode<?> targetNode :
              new AcyclicDepthFirstPostOrderTraversal<TargetNode<?>>(
                      node ->
                          traversedTargets.contains(node.getBuildTarget())
                              ? Collections.emptyIterator()
                              : targetGraph.getOutgoingNodesFor(node).iterator())
                  .traverse(ImmutableList.of(root))) {
            if (!traversedTargets.contains(targetNode.getBuildTarget())
                && targetNode.getRuleType().isBuildRule()) {
              graphBuilder.requireRule(targetNode.getBuildTarget());
              currentTargets.add(targetNode.getBuildTarget());
            }
          }
          traversedTargets.addAll(currentTargets);
        } catch (NoSuchBuildTargetException e) {
          throw new HumanReadableException(e);
        } catch (CycleException e) {
          throw new RuntimeException(e);
        }
        return graphBuilder;
      }
    }
  }
}
