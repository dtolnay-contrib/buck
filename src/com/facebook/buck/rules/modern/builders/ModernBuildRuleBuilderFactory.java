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

package com.facebook.buck.rules.modern.builders;

import com.facebook.buck.core.build.engine.BuildStrategyContext;
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.Cells;
import com.facebook.buck.core.exceptions.BuckUncheckedExecutionException;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.build.strategy.BuildRuleStrategy;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.remoteexecution.OOMWorkerRequirementsProvider;
import com.facebook.buck.remoteexecution.WorkerRequirementsProvider;
import com.facebook.buck.remoteexecution.config.RemoteExecutionConfig;
import com.facebook.buck.remoteexecution.factory.RemoteExecutionClientsFactory;
import com.facebook.buck.remoteexecution.interfaces.MetadataProvider;
import com.facebook.buck.rules.modern.config.HybridLocalBuildStrategyConfig;
import com.facebook.buck.rules.modern.config.ModernBuildRuleBuildStrategy;
import com.facebook.buck.rules.modern.config.ModernBuildRuleStrategyConfig;
import com.facebook.buck.util.ConsoleParams;
import com.facebook.buck.util.hashing.FileHashLoader;
import com.google.common.util.concurrent.Futures;
import java.io.IOException;
import java.util.Optional;

/**
 * Constructs various BuildRuleStrategies for ModernBuildRules based on the
 * modern_build_rule.strategy config option.
 */
public class ModernBuildRuleBuilderFactory {

  /** Creates a BuildRuleStrategy for ModernBuildRules based on the buck configuration. */
  public static Optional<BuildRuleStrategy> getBuildStrategy(
      ModernBuildRuleStrategyConfig config,
      RemoteExecutionConfig remoteExecutionConfig,
      BuildRuleResolver resolver,
      Cells cells,
      CellPathResolver cellResolver,
      FileHashLoader hashLoader,
      BuckEventBus eventBus,
      MetadataProvider metadataProvider,
      boolean remoteExecutionAutoEnabled,
      boolean forceDisableRemoteExecution,
      ConsoleParams consoleParams) {
    ModernBuildRuleBuildStrategy strategy;
    try {
      RemoteExecutionClientsFactory remoteExecutionFactory =
          new RemoteExecutionClientsFactory(remoteExecutionConfig);
      strategy = config.getBuildStrategy(remoteExecutionAutoEnabled, forceDisableRemoteExecution);
      WorkerRequirementsProvider workerRequirementsProvider =
          new OOMWorkerRequirementsProvider(
              remoteExecutionConfig.getStrategyConfig().tryLargerWorkerOnOom());
      switch (strategy) {
        case NONE:
          return Optional.empty();
        case DEBUG_RECONSTRUCT:
          return Optional.of(createReconstructing(resolver, cellResolver, cells));
        case DEBUG_PASSTHROUGH:
          return Optional.of(createPassthrough());
        case HYBRID_LOCAL:
          return Optional.of(
              createHybridLocal(
                  config.getHybridLocalConfig(),
                  remoteExecutionConfig,
                  resolver,
                  cells,
                  cellResolver,
                  hashLoader,
                  eventBus,
                  metadataProvider,
                  remoteExecutionAutoEnabled,
                  forceDisableRemoteExecution,
                  consoleParams));
        case REMOTE:
          return Optional.of(
              RemoteExecutionStrategy.createRemoteExecutionStrategy(
                  eventBus,
                  remoteExecutionConfig,
                  remoteExecutionFactory.create(eventBus, metadataProvider),
                  resolver,
                  cells,
                  hashLoader,
                  metadataProvider,
                  workerRequirementsProvider,
                  consoleParams));
      }
    } catch (IOException e) {
      throw new BuckUncheckedExecutionException(e, "When creating MBR build strategy.");
    }
    throw new IllegalStateException("Unrecognized build strategy " + strategy + ".");
  }

  private static BuildRuleStrategy createHybridLocal(
      HybridLocalBuildStrategyConfig hybridLocalConfig,
      RemoteExecutionConfig remoteExecutionConfig,
      BuildRuleResolver resolver,
      Cells cells,
      CellPathResolver cellResolver,
      FileHashLoader hashLoader,
      BuckEventBus eventBus,
      MetadataProvider metadataProvider,
      boolean remoteExecutionAutoEnabled,
      boolean forceDisableRemoteExecution,
      ConsoleParams consoleParams) {
    BuildRuleStrategy delegate =
        getBuildStrategy(
                hybridLocalConfig.getDelegateConfig(),
                remoteExecutionConfig,
                resolver,
                cells,
                cellResolver,
                hashLoader,
                eventBus,
                metadataProvider,
                remoteExecutionAutoEnabled,
                forceDisableRemoteExecution,
                consoleParams)
            .orElseThrow(
                () -> new HumanReadableException("Delegate config configured incorrectly."));
    return new HybridLocalStrategy(
        hybridLocalConfig.getLocalJobs(),
        hybridLocalConfig.getLocalDelegateJobs(),
        hybridLocalConfig.getDelegateJobs(),
        delegate,
        eventBus);
  }

  /** The passthrough strategy just forwards to executorRunner.runWithDefaultExecutor. */
  public static BuildRuleStrategy createPassthrough() {
    return new AbstractModernBuildRuleStrategy() {
      @Override
      public StrategyBuildResult build(BuildRule rule, BuildStrategyContext strategyContext) {
        return StrategyBuildResult.nonCancellable(
            Futures.submitAsync(
                strategyContext::runWithDefaultBehavior, strategyContext.getExecutorService()));
      }
    };
  }

  /**
   * The reconstructing strategy serializes and deserializes the build rule in memory and builds the
   * deserialized version.
   */
  public static BuildRuleStrategy createReconstructing(
      SourcePathRuleFinder ruleFinder, CellPathResolver cellResolver, Cells cells) {
    return new ReconstructingStrategy(ruleFinder, cellResolver, cells);
  }
}
