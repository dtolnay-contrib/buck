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

import com.facebook.buck.apple.toolchain.AppleCxxPlatformsProvider;
import com.facebook.buck.cli.BuildCommandForAppleProjectGenerator;
import com.facebook.buck.cli.BuildCommandForProjectGenerators;
import com.facebook.buck.cli.Command;
import com.facebook.buck.cli.CommandRunnerParams;
import com.facebook.buck.cli.CommandThreadManager;
import com.facebook.buck.cli.ProjectGeneratorParameters;
import com.facebook.buck.cli.ProjectSubCommand;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.UnconfiguredTargetConfiguration;
import com.facebook.buck.core.model.targetgraph.TargetGraphCreationResult;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.features.apple.common.Mode;
import com.facebook.buck.features.apple.common.PrintStreamPathOutputPresenter;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.concurrent.ExecutorPool;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.IOException;
import java.util.List;
import javax.annotation.Nullable;
import org.kohsuke.args4j.Option;

public class XCodeProjectSubCommand extends ProjectSubCommand {

  private static final boolean DEFAULT_READ_ONLY_VALUE = false;
  private static final boolean DEFAULT_PROJECT_SCHEMES = false;
  private static final boolean DEFAULT_SHARED_LIBRARIES_AS_DYNAMIC_FRAMEWORKS = false;

  @Option(
      name = "--combined-project",
      usage = "Generate an xcode project of a target and its dependencies.")
  private boolean combinedProject;

  @Option(
      name = "--project-schemes",
      usage = "Generate an xcode scheme for each sub-project with its targets and tests.")
  private boolean projectSchemes = false;

  @Option(
      name = "--focus",
      usage =
          "Space separated list of build target full qualified names that should be part of "
              + "focused project. "
              + "For example, //Libs/CommonLibs:BaseLib //Libs/ImportantLib:ImportantLib")
  @Nullable
  private String modulesToFocusOn = null;

  @Option(
      name = "--exclude",
      usage =
          "Space separated list of build target full qualified names that should NOT be part of "
              + "focused project even if parent is in --focus. "
              + "For example, //Libs/CommonLibs:BaseLib //Libs/ImportantLib:ImportantLib")
  @Nullable
  private String modulesToExclude = null;

  @Option(
      name = "--exclude-from-build",
      usage =
          "Space separated list of build target full qualified names or regexes that should NOT "
              + "be built as part of the project command."
              + "For example, //Libs/CommonLibs:BaseLib //Libs/ImportantLib:...")
  @Nullable
  private String modulesToExcludeFromBuild = null;

  @Option(
      name = "--read-only",
      usage =
          "If true, generate project files read-only. Defaults to '"
              + DEFAULT_READ_ONLY_VALUE
              + "' if not specified in .buckconfig. (Only "
              + "applies to generated Xcode projects.)")
  private boolean readOnly = DEFAULT_READ_ONLY_VALUE;

  @Option(
      name = "--show-full-output",
      usage = "Print the absolute path to the output for each of the built rules.")
  private boolean showFullOutput;

  @Option(
      name = "--show-output",
      usage = "Print the path to the output for each of the built rules relative to the cell.")
  private boolean showOutput;

  @Option(name = "--experimental", usage = "Generate an experimental Xcode workspace.")
  private boolean experimental = false;

  @Option(
      name = "--should-merge-targets",
      usage = "Exposes deep dependency sources for build targets.")
  private boolean shouldMergeTargets = false;

  protected Mode getOutputMode() {
    if (this.showFullOutput) {
      return Mode.FULL;
    } else if (this.showOutput) {
      return Mode.SIMPLE;
    } else {
      return Mode.NONE;
    }
  }

  @Override
  public ExitCode run(
      CommandRunnerParams params,
      CommandThreadManager threadManager,
      ProjectGeneratorParameters projectGeneratorParameters,
      List<String> projectCommandArguments)
      throws IOException, InterruptedException {
    ListeningExecutorService executor = threadManager.getListeningExecutorService();
    AppleCxxPlatformsProvider appleCxxPlatformsProvider =
        params
            .getCells()
            .getRootCell()
            .getToolchainProvider()
            .getByName(
                AppleCxxPlatformsProvider.DEFAULT_NAME,
                params.getTargetConfiguration().orElse(UnconfiguredTargetConfiguration.INSTANCE),
                AppleCxxPlatformsProvider.class);
    if (!experimental) {
      XCodeProjectCommandHelper xcodeProjectCommandHelper =
          new XCodeProjectCommandHelper(
              params.getBuckEventBus(),
              params.getPluginManager(),
              params.getParser(),
              params.getBuckConfig(),
              params.getVersionedTargetGraphCache(),
              params.getTypeCoercerFactory(),
              params.getUnconfiguredBuildTargetFactory(),
              params.getCells(),
              params.getRuleKeyConfiguration(),
              params.getTargetConfiguration(),
              params.getConsole(),
              params.getProcessManager(),
              params.getEnvironment(),
              params.getExecutors().get(ExecutorPool.PROJECT),
              executor,
              params.getDepsAwareExecutorSupplier(),
              appleCxxPlatformsProvider.getUnresolvedAppleCxxPlatforms().getFlavors(),
              getSharedLibrariesInBundles(params.getBuckConfig()),
              projectGeneratorParameters.getEnableParserProfiling(),
              projectGeneratorParameters.isWithTests(),
              projectGeneratorParameters.isWithoutTests(),
              projectGeneratorParameters.isWithoutDependenciesTests(),
              modulesToFocusOn,
              combinedProject,
              getProjectSchemes(params.getBuckConfig()),
              projectGeneratorParameters.isDryRun(),
              getReadOnly(params.getBuckConfig()),
              new PrintStreamPathOutputPresenter(
                  params.getConsole().getStdOut(),
                  getOutputMode(),
                  params.getCells().getRootCell().getRoot().getPath()),
              projectGeneratorParameters.getArgsParser(),
              arguments -> {
                try {
                  return runBuild(params, arguments);
                } catch (Exception e) {
                  throw new RuntimeException("Cannot run a build", e);
                }
              },
              projectCommandArguments);
      return xcodeProjectCommandHelper.parseTargetsAndRunXCodeGenerator();
    } else {
      com.facebook.buck.features.apple.projectV2.XCodeProjectCommandHelper
          xcodeProjectCommandHelper =
              new com.facebook.buck.features.apple.projectV2.XCodeProjectCommandHelper(
                  params.getBuckEventBus(),
                  params.getPluginManager(),
                  params.getParser(),
                  params.getBuckConfig(),
                  params.getVersionedTargetGraphCache(),
                  params.getTypeCoercerFactory(),
                  params.getUnconfiguredBuildTargetFactory(),
                  params.getCells(),
                  params.getRuleKeyConfiguration(),
                  params.getTargetConfiguration(),
                  params.getConsole(),
                  params.getProcessManager(),
                  params.getEnvironment(),
                  params.getExecutors().get(ExecutorPool.PROJECT),
                  executor,
                  params.getDepsAwareExecutorSupplier(),
                  appleCxxPlatformsProvider.getUnresolvedAppleCxxPlatforms().getFlavors(),
                  getSharedLibrariesInBundles(params.getBuckConfig()),
                  projectGeneratorParameters.getEnableParserProfiling(),
                  projectGeneratorParameters.isWithTests(),
                  projectGeneratorParameters.isWithoutTests(),
                  projectGeneratorParameters.isWithoutDependenciesTests(),
                  shouldMergeTargets,
                  modulesToFocusOn,
                  modulesToExclude,
                  modulesToExcludeFromBuild,
                  getProjectSchemes(params.getBuckConfig()),
                  projectGeneratorParameters.isDryRun(),
                  getReadOnly(params.getBuckConfig()),
                  new PrintStreamPathOutputPresenter(
                      params.getConsole().getStdOut(),
                      getOutputMode(),
                      params.getCells().getRootCell().getRoot().getPath()),
                  projectGeneratorParameters.getArgsParser(),
                  (buildTargets, targetGraph, actionGraphBuilder) -> {
                    try {
                      return runProjectV2Build(
                          params, buildTargets, targetGraph, actionGraphBuilder);
                    } catch (Exception e) {
                      throw new RuntimeException("Cannot run a build", e);
                    }
                  },
                  projectCommandArguments,
                  params.getActionGraphProvider());
      return xcodeProjectCommandHelper.parseTargetsAndRunXCodeGenerator();
    }
  }

  private ExitCode runBuild(CommandRunnerParams params, ImmutableList<BuildTarget> arguments)
      throws Exception {
    BuildCommandForProjectGenerators buildCommand = new BuildCommandForProjectGenerators(arguments);
    return buildCommand.run(params);
  }

  private ExitCode runProjectV2Build(
      CommandRunnerParams params,
      ImmutableList<BuildTarget> arguments,
      TargetGraphCreationResult targetGraph,
      ActionGraphBuilder actionGraphBuilder)
      throws Exception {

    Command buildCommand =
        new BuildCommandForAppleProjectGenerator(arguments, targetGraph, actionGraphBuilder);

    return buildCommand.run(params);
  }

  private boolean getProjectSchemes(BuckConfig buckConfig) {
    // command line arg takes precedence over buck config
    return projectSchemes
        || buckConfig.getBooleanValue("project", "project_schemes", DEFAULT_PROJECT_SCHEMES);
  }

  private boolean getReadOnly(BuckConfig buckConfig) {
    if (readOnly) {
      return readOnly;
    }
    return buckConfig.getBooleanValue("project", "read_only", DEFAULT_READ_ONLY_VALUE);
  }

  private boolean getSharedLibrariesInBundles(BuckConfig buckConfig) {
    return buckConfig.getBooleanValue(
        "project", "shared_libraries_in_bundles", DEFAULT_SHARED_LIBRARIES_AS_DYNAMIC_FRAMEWORKS);
  }

  @Override
  public String getOptionValue() {
    return "xcode";
  }

  @Override
  public String getShortDescription() {
    return "project generation for XCode";
  }
}
