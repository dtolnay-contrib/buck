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

package com.facebook.buck.core.build.engine.impl;

import com.facebook.buck.artifact_cache.ArtifactCache;
import com.facebook.buck.artifact_cache.CacheResult;
import com.facebook.buck.artifact_cache.CacheResultType;
import com.facebook.buck.artifact_cache.RuleKeyCacheResult;
import com.facebook.buck.artifact_cache.RuleKeyCacheResultEvent;
import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.engine.BuildEngineBuildContext;
import com.facebook.buck.core.build.engine.BuildResult;
import com.facebook.buck.core.build.engine.BuildRuleStatus;
import com.facebook.buck.core.build.engine.BuildRuleSuccessType;
import com.facebook.buck.core.build.engine.BuildStrategyContext;
import com.facebook.buck.core.build.engine.RuleDepsCache;
import com.facebook.buck.core.build.engine.buildinfo.BuildInfo;
import com.facebook.buck.core.build.engine.buildinfo.BuildInfo.MetadataKey;
import com.facebook.buck.core.build.engine.buildinfo.BuildInfoRecorder;
import com.facebook.buck.core.build.engine.buildinfo.OnDiskBuildInfo;
import com.facebook.buck.core.build.engine.cache.manager.BuildCacheArtifactFetcher;
import com.facebook.buck.core.build.engine.cache.manager.BuildCacheArtifactUploader;
import com.facebook.buck.core.build.engine.cache.manager.BuildInfoStoreManager;
import com.facebook.buck.core.build.engine.cache.manager.BuildRuleScopeManager;
import com.facebook.buck.core.build.engine.cache.manager.DependencyFileRuleKeyManager;
import com.facebook.buck.core.build.engine.cache.manager.InputBasedRuleKeyManager;
import com.facebook.buck.core.build.engine.cache.manager.ManifestRuleKeyManager;
import com.facebook.buck.core.build.engine.cache.manager.ManifestRuleKeyService;
import com.facebook.buck.core.build.engine.cache.manager.ManifestRuleKeyServiceFactory;
import com.facebook.buck.core.build.engine.config.ResourceAwareSchedulingInfo;
import com.facebook.buck.core.build.engine.impl.CachingBuildEngine.StepType;
import com.facebook.buck.core.build.engine.manifest.ManifestFetchResult;
import com.facebook.buck.core.build.engine.manifest.ManifestStoreResult;
import com.facebook.buck.core.build.engine.type.BuildType;
import com.facebook.buck.core.build.engine.type.DepFiles;
import com.facebook.buck.core.build.engine.type.UploadToCacheResultType;
import com.facebook.buck.core.build.event.BuildRuleEvent;
import com.facebook.buck.core.build.event.BuildRuleExecutionEvent;
import com.facebook.buck.core.build.event.FinalizingBuildRuleEvent;
import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.build.execution.context.actionid.ActionId;
import com.facebook.buck.core.build.stats.BuildRuleDurationTracker;
import com.facebook.buck.core.exceptions.BuckUncheckedExecutionException;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.TargetConfigurationSerializer;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.attr.BuildOutputInitializer;
import com.facebook.buck.core.rules.attr.HasPostBuildSteps;
import com.facebook.buck.core.rules.attr.InitializableFromDisk;
import com.facebook.buck.core.rules.attr.SupportsDependencyFileRuleKey;
import com.facebook.buck.core.rules.attr.SupportsInputBasedRuleKey;
import com.facebook.buck.core.rules.build.strategy.BuildRuleStrategy;
import com.facebook.buck.core.rules.build.strategy.BuildRuleStrategy.StrategyBuildResult;
import com.facebook.buck.core.rules.pipeline.CompilationDaemonStep;
import com.facebook.buck.core.rules.pipeline.RulePipelineState;
import com.facebook.buck.core.rules.pipeline.StateHolder;
import com.facebook.buck.core.rules.pipeline.SupportsPipelining;
import com.facebook.buck.core.rules.schedule.OverrideScheduleRule;
import com.facebook.buck.core.rules.schedule.RuleScheduleInfo;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.PerfEvents;
import com.facebook.buck.event.ThrowableConsoleEvent;
import com.facebook.buck.rules.keys.DependencyFileEntry;
import com.facebook.buck.rules.keys.DependencyFileRuleKeyFactory;
import com.facebook.buck.rules.keys.RuleKeyDiagnostics;
import com.facebook.buck.rules.keys.RuleKeyFactories;
import com.facebook.buck.rules.keys.RuleKeyType;
import com.facebook.buck.rules.modern.PipelinedModernBuildRule;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepFailedException;
import com.facebook.buck.step.StepRunner;
import com.facebook.buck.util.ConsoleParams;
import com.facebook.buck.util.ContextualProcessExecutor;
import com.facebook.buck.util.Discardable;
import com.facebook.buck.util.ErrorLogger;
import com.facebook.buck.util.MoreSuppliers;
import com.facebook.buck.util.Scope;
import com.facebook.buck.util.Threads;
import com.facebook.buck.util.cache.FileHashCache;
import com.facebook.buck.util.concurrent.MoreFutures;
import com.facebook.buck.util.concurrent.ResourceAmounts;
import com.facebook.buck.util.concurrent.WeightedListeningExecutorService;
import com.facebook.buck.util.json.ObjectMappers;
import com.facebook.buck.util.types.Either;
import com.facebook.buck.util.types.Pair;
import com.facebook.buck.util.types.Unit;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.HashCode;
import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import com.google.protobuf.AbstractMessage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.annotation.Nullable;

class CachingBuildRuleBuilder {

  private static final Logger LOG = Logger.get(CachingBuildRuleBuilder.class);

  private final BuildRuleBuilderDelegate buildRuleBuilderDelegate;
  private final BuildType buildMode;
  private final boolean consoleLogBuildFailuresInline;
  private final FileHashCache fileHashCache;
  private final SourcePathResolverAdapter pathResolver;
  private final TargetConfigurationSerializer targetConfigurationSerializer;
  private final ResourceAwareSchedulingInfo resourceAwareSchedulingInfo;
  private final RuleKeyFactories ruleKeyFactories;
  private final WeightedListeningExecutorService service;
  private final BuildRule rule;
  private final ExecutionContext executionContext;
  private final ImmutableMap<String, String> processExecutorContext;
  private final OnDiskBuildInfo onDiskBuildInfo;
  private final Discardable<BuildInfoRecorder> buildInfoRecorder;
  private final BuildableContext buildableContext;
  private final BuildRulePipelinesRunner<? extends RulePipelineState> pipelinesRunner;
  private final BuckEventBus eventBus;
  private final BuildContext buildRuleBuildContext;
  private final ArtifactCache artifactCache;
  private final BuildId buildId;
  private final Set<String> depsWithCacheMiss = Collections.synchronizedSet(new HashSet<>());
  private final Optional<Long> defaultOutputHashSizeLimit;
  private final ImmutableMap<String, Long> ruleTypeOutputHashSizeLimit;

  private final BuildRuleScopeManager buildRuleScopeManager;

  private final RuleKey defaultKey;

  private final Supplier<Optional<RuleKey>> inputBasedKey;

  private final DependencyFileRuleKeyManager dependencyFileRuleKeyManager;
  private final BuildCacheArtifactFetcher buildCacheArtifactFetcher;
  private final InputBasedRuleKeyManager inputBasedRuleKeyManager;
  private final ManifestRuleKeyManager manifestRuleKeyManager;
  private final BuildCacheArtifactUploader buildCacheArtifactUploader;

  @Nullable private volatile Pair<Long, Long> ruleKeyCacheCheckTimestampsMillis = null;
  @Nullable private volatile Pair<Long, Long> inputRuleKeyCacheCheckTimestampsMillis = null;
  @Nullable private volatile Pair<Long, Long> manifestRuleKeyCacheCheckTimestampsMillis = null;
  @Nullable private volatile Pair<Long, Long> buildTimestampsMillis = null;

  // This is used to mark that we've invalidated cached state that is no longer valid if this rule's
  // outputs change. When we finish the rule, we verify that that invalidation has happened if this
  // rule has changed.
  private volatile boolean outputsCanChange = false;

  /**
   * This is used to weakly cache the manifest RuleKeyAndInputs. I
   *
   * <p>This is necessary because RuleKeyAndInputs may be very large, and due to the async nature of
   * CachingBuildRuleBuilder, there may be many BuildRules that are in-between two stages that both
   * need the manifest's RuleKeyAndInputs. If we just stored the RuleKeyAndInputs directly, we could
   * use too much memory.
   */
  private final Supplier<Optional<DependencyFileRuleKeyFactory.RuleKeyAndInputs>>
      manifestBasedKeySupplier;

  // These fields contain data that may be computed during a build.

  private volatile boolean depsAreAvailable;
  private final Optional<BuildRuleStrategy> customBuildRuleStrategy;

  private @Nullable volatile Throwable firstFailure = null;
  private @Nullable volatile StrategyBuildResult strategyResult = null;

  public CachingBuildRuleBuilder(
      BuildRuleBuilderDelegate buildRuleBuilderDelegate,
      Optional<Long> artifactCacheSizeLimit,
      Optional<Long> defaultOutputHashSizeLimit,
      ImmutableMap<String, Long> ruleTypeOutputHashSizeLimit,
      BuildInfoStoreManager buildInfoStoreManager,
      BuildType buildMode,
      BuildRuleDurationTracker buildRuleDurationTracker,
      boolean consoleLogBuildFailuresInline,
      RuleKeyDiagnostics<RuleKey, String> defaultRuleKeyDiagnostics,
      DepFiles depFiles,
      FileHashCache fileHashCache,
      long maxDepFileCacheEntries,
      SourcePathResolverAdapter pathResolver,
      TargetConfigurationSerializer targetConfigurationSerializer,
      ResourceAwareSchedulingInfo resourceAwareSchedulingInfo,
      RuleKeyFactories ruleKeyFactories,
      WeightedListeningExecutorService service,
      RuleDepsCache ruleDeps,
      BuildRule rule,
      BuildEngineBuildContext buildContext,
      ExecutionContext executionContext,
      OnDiskBuildInfo onDiskBuildInfo,
      BuildInfoRecorder buildInfoRecorder,
      BuildableContext buildableContext,
      BuildRulePipelinesRunner<? extends RulePipelineState> pipelinesRunner,
      Optional<BuildRuleStrategy> customBuildRuleStrategy) {
    this.buildRuleBuilderDelegate = buildRuleBuilderDelegate;
    this.defaultOutputHashSizeLimit = defaultOutputHashSizeLimit;
    this.ruleTypeOutputHashSizeLimit = ruleTypeOutputHashSizeLimit;
    this.buildMode = buildMode;
    this.consoleLogBuildFailuresInline = consoleLogBuildFailuresInline;
    this.fileHashCache = fileHashCache;
    this.pathResolver = pathResolver;
    this.targetConfigurationSerializer = targetConfigurationSerializer;
    this.resourceAwareSchedulingInfo = resourceAwareSchedulingInfo;
    this.ruleKeyFactories = ruleKeyFactories;
    this.service = service;
    this.rule = rule;
    this.executionContext = executionContext;
    this.onDiskBuildInfo = onDiskBuildInfo;
    this.buildInfoRecorder = new Discardable<>(buildInfoRecorder);
    this.buildableContext = buildableContext;
    this.pipelinesRunner = pipelinesRunner;
    this.eventBus = buildContext.getEventBus();
    this.buildRuleBuildContext = buildContext.getBuildContext();
    this.artifactCache = buildContext.getArtifactCache();
    this.buildId = buildContext.getBuildId();

    this.defaultKey = ruleKeyFactories.getDefaultRuleKeyFactory().build(rule);

    this.inputBasedKey = MoreSuppliers.memoize(this::calculateInputBasedRuleKey);
    this.manifestBasedKeySupplier =
        MoreSuppliers.weakMemoize(
            () -> {
              try (Scope ignored = buildRuleScope()) {
                return calculateManifestKey(eventBus);
              } catch (IOException e) {
                throw new RuntimeException(e);
              }
            });
    this.buildRuleScopeManager =
        new BuildRuleScopeManager(
            ruleKeyFactories,
            onDiskBuildInfo,
            buildRuleDurationTracker,
            defaultRuleKeyDiagnostics,
            executionContext.getRuleKeyDiagnosticsMode(),
            rule,
            ruleDeps,
            defaultKey,
            eventBus);
    this.dependencyFileRuleKeyManager =
        new DependencyFileRuleKeyManager(
            depFiles, rule, this.buildInfoRecorder, onDiskBuildInfo, ruleKeyFactories, eventBus);
    this.buildCacheArtifactFetcher =
        new BuildCacheArtifactFetcher(
            rule,
            buildRuleScopeManager,
            serviceByAdjustingDefaultWeightsTo(CachingBuildEngine.CACHE_CHECK_RESOURCE_AMOUNTS),
            this::onOutputsWillChange,
            eventBus,
            buildInfoStoreManager,
            onDiskBuildInfo);
    this.inputBasedRuleKeyManager =
        new InputBasedRuleKeyManager(
            eventBus,
            ruleKeyFactories,
            this.buildInfoRecorder,
            buildCacheArtifactFetcher,
            artifactCache,
            onDiskBuildInfo,
            rule,
            buildRuleScopeManager,
            inputBasedKey);

    ManifestRuleKeyService manifestRuleKeyService;
    manifestRuleKeyService =
        ManifestRuleKeyServiceFactory.fromArtifactCache(buildCacheArtifactFetcher, artifactCache);
    this.manifestRuleKeyManager =
        new ManifestRuleKeyManager(
            depFiles,
            rule,
            fileHashCache,
            maxDepFileCacheEntries,
            pathResolver,
            ruleKeyFactories,
            buildCacheArtifactFetcher,
            artifactCache,
            manifestBasedKeySupplier,
            manifestRuleKeyService);
    this.buildCacheArtifactUploader =
        new BuildCacheArtifactUploader(
            defaultKey,
            inputBasedKey,
            onDiskBuildInfo,
            rule,
            manifestRuleKeyManager,
            eventBus,
            artifactCache,
            artifactCacheSizeLimit);
    this.customBuildRuleStrategy = customBuildRuleStrategy;

    ConsoleParams consoleParams =
        ConsoleParams.of(
            executionContext.getAnsi().isAnsiTerminal(), executionContext.getVerbosity());

    this.processExecutorContext =
        ImmutableMap.of(
            CachingBuildEngine.BUILD_RULE_TYPE_CONTEXT_KEY,
            rule.getType(),
            CachingBuildEngine.STEP_TYPE_CONTEXT_KEY,
            StepType.BUILD_STEP.toString(),
            ContextualProcessExecutor.ANSI_ESCAPE_SEQUENCES_ENABLED,
            consoleParams.isAnsiEscapeSequencesEnabled(),
            ContextualProcessExecutor.VERBOSITY,
            consoleParams.getVerbosity());
  }

  // Return a `BuildResult.Builder` with rule-specific state pre-filled.
  private BuildResult.Builder buildResultBuilder() {
    return BuildResult.builder().setRule(rule).setDepsWithCacheMisses(depsWithCacheMiss);
  }

  private BuildResult success(BuildRuleSuccessType successType, CacheResult cacheResult) {
    return success(successType, cacheResult, Optional.empty());
  }

  private BuildResult success(
      BuildRuleSuccessType successType, CacheResult cacheResult, Optional<String> strategyResult) {
    return buildResultBuilder()
        .setStatus(BuildRuleStatus.SUCCESS)
        .setSuccessOptional(successType)
        .setCacheResult(cacheResult)
        .setStrategyResult(strategyResult)
        .build();
  }

  private BuildResult failure(Throwable thrown) {
    return buildResultBuilder().setStatus(BuildRuleStatus.FAIL).setFailureOptional(thrown).build();
  }

  private BuildResult canceled(@Nullable Throwable thrown) {
    return buildResultBuilder()
        .setStatus(BuildRuleStatus.CANCELED)
        .setFailureOptional(Objects.requireNonNull(thrown))
        .build();
  }

  /**
   * We have a lot of places where tasks are submitted into a service implicitly. There is no way to
   * assign custom weights to such tasks. By creating a temporary service with adjusted weights it
   * is possible to trick the system and tweak the weights.
   */
  private WeightedListeningExecutorService serviceByAdjustingDefaultWeightsTo(
      ResourceAmounts defaultAmounts) {
    return resourceAwareSchedulingInfo.adjustServiceDefaultWeightsTo(defaultAmounts, service);
  }

  ListenableFuture<BuildResult> build() {
    ListenableFuture<List<BuildResult>> depResults =
        Futures.immediateFuture(Collections.emptyList());

    // If we're performing a deep build, guarantee that all dependencies will *always* get
    // materialized locally
    if (buildMode == BuildType.DEEP || buildMode == BuildType.POPULATE_FROM_REMOTE_CACHE) {
      depResults = buildRuleBuilderDelegate.getDepResults(rule, executionContext);
    }

    ListenableFuture<BuildResult> buildResult =
        Futures.transformAsync(
            depResults,
            input -> buildOrFetchFromCache(),
            serviceByAdjustingDefaultWeightsTo(
                CachingBuildEngine.SCHEDULING_MORE_WORK_RESOURCE_AMOUNTS));

    // Check immediately (without posting a new task) for a failure so that we can short-circuit
    // pending work. Use .catchingAsync() instead of .catching() so that we can propagate unchecked
    // exceptions.
    buildResult =
        Futures.catchingAsync(
            buildResult,
            Throwable.class,
            throwable -> {
              Objects.requireNonNull(throwable);
              BuildRuleFailedException failedException = getFailedException(throwable);
              buildRuleBuilderDelegate.setFirstFailure(failedException);
              throw failedException;
            });

    buildResult =
        Futures.transform(
            buildResult,
            (result) -> {
              buildRuleBuilderDelegate.markRuleAsUsed(rule, eventBus);
              return result;
            },
            MoreExecutors.directExecutor());

    buildResult =
        Futures.transformAsync(
            buildResult,
            ruleAsyncFunction(this::finalizeBuildRule),
            serviceByAdjustingDefaultWeightsTo(
                CachingBuildEngine.RULE_KEY_COMPUTATION_RESOURCE_AMOUNTS));

    buildResult =
        Futures.catchingAsync(
            buildResult,
            Throwable.class,
            thrown -> {
              String message = String.format("Building rule [%s] failed.", rule.getBuildTarget());
              BuildRuleFailedException failedException = getFailedException(thrown);
              LOG.warn(failedException, message);
              if (consoleLogBuildFailuresInline) {
                // TODO(cjhopman): This probably shouldn't be a thing. Why can't we just rely on the
                // propagated failure being printed?
                eventBus.post(
                    ConsoleEvent.severe(ErrorLogger.getUserFriendlyMessage(failedException)));
              }
              recordFailureAndCleanUp(failedException);
              return Futures.immediateFuture(failure(thrown));
            });

    // Do things that need to happen after either success or failure, but don't block the dependents
    // while doing so:
    buildRuleBuilderDelegate.addAsyncCallback(
        MoreFutures.addListenableCallback(
            buildResult,
            new FutureCallback<BuildResult>() {
              @Override
              public void onSuccess(BuildResult input) {
                handleResult(input);

                // Reset interrupted flag once failure has been recorded.
                if (!input.isSuccess() && input.getFailure() instanceof InterruptedException) {
                  Threads.interruptCurrentThread();
                }
              }

              @Override
              public void onFailure(Throwable thrown) {
                throw new AssertionError("Dead code", thrown);
              }
            },
            serviceByAdjustingDefaultWeightsTo(
                CachingBuildEngine.RULE_KEY_COMPUTATION_RESOURCE_AMOUNTS)));
    return buildResult;
  }

  private void finalizeMatchingKey(BuildRuleSuccessType success) throws IOException {
    switch (success) {
      case MATCHING_RULE_KEY:
        // No need to record anything for matching rule key.
        return;

      case MATCHING_DEP_FILE_RULE_KEY:
        if (SupportsInputBasedRuleKey.isSupported(rule)) {
          // The input-based key should already be computed.
          inputBasedKey
              .get()
              .ifPresent(
                  key ->
                      getBuildInfoRecorder()
                          .addBuildMetadata(
                              BuildInfo.MetadataKey.INPUT_BASED_RULE_KEY, key.toString()));
        }
        // $FALL-THROUGH$
      case MATCHING_INPUT_BASED_RULE_KEY:
        getBuildInfoRecorder()
            .addBuildMetadata(BuildInfo.MetadataKey.RULE_KEY, defaultKey.toString());
        break;

      case BUILT_LOCALLY:
      case FETCHED_FROM_CACHE:
      case FETCHED_FROM_CACHE_INPUT_BASED:
      case FETCHED_FROM_CACHE_MANIFEST_BASED:
        throw new RuntimeException(String.format("Unexpected success type %s.", success));
    }

    // TODO(cjhopman): input-based/depfile each rewrite the matching key, that's unnecessary.
    // TODO(cjhopman): this writes the current build-id/timestamp/extra-data, that's probably
    // unnecessary.
    getBuildInfoRecorder()
        .assertOnlyHasKeys(
            BuildInfo.MetadataKey.RULE_KEY,
            BuildInfo.MetadataKey.INPUT_BASED_RULE_KEY,
            BuildInfo.MetadataKey.DEP_FILE_RULE_KEY,
            BuildInfo.MetadataKey.BUILD_ID);
    try {
      getBuildInfoRecorder().updateBuildMetadata();
    } catch (IOException e) {
      throw new IOException(String.format("Failed to write metadata to disk for %s.", rule), e);
    }
  }

  private ListenableFuture<BuildResult> finalizeBuildRule(BuildResult input) throws IOException {
    try {
      // If we weren't successful, exit now.
      if (input.getStatus() != BuildRuleStatus.SUCCESS) {
        return Futures.immediateFuture(input);
      }

      try (Scope ignored = PerfEvents.scope(eventBus, "finalizing_build_rule")) {
        // We shouldn't see any build fail result at this point.
        BuildRuleSuccessType success = input.getSuccess();

        if (success.outputsHaveChanged()) {
          // We could just invalidate the previous cached state here, but that would just be hiding
          // our failure to do the correct thing at the right time.
          Preconditions.checkState(
              outputsCanChange,
              "%s success type indicates that outputs have changed, but onOutputsWillChange wasn't called.",
              success);
        }

        switch (success) {
          case BUILT_LOCALLY:
            finalizeBuiltLocally();
            break;
          case FETCHED_FROM_CACHE:
          case FETCHED_FROM_CACHE_INPUT_BASED:
          case FETCHED_FROM_CACHE_MANIFEST_BASED:
            finalizeFetchedFromCache(success);
            break;
          case MATCHING_RULE_KEY:
          case MATCHING_INPUT_BASED_RULE_KEY:
          case MATCHING_DEP_FILE_RULE_KEY:
            finalizeMatchingKey(success);
            break;
        }
      } catch (Exception e) {
        throw new BuckUncheckedExecutionException(e, "When finalizing rule.");
      }

      // Give the rule a chance to populate its internal data structures now that all of
      // the files should be in a valid state.
      try {
        if (rule instanceof InitializableFromDisk) {
          doInitializeFromDisk((InitializableFromDisk<?>) rule);
        }
      } catch (IOException e) {
        throw new IOException(
            String.format("Error initializing %s from disk: %s.", rule, e.getMessage()), e);
      }

      return Futures.immediateFuture(input);
    } finally {
      // The BuildInfoRecorder should not be accessed after this point. It does not accurately
      // reflect the state of the buildrule.
      buildInfoRecorder.discard();
      FinalizingBuildRuleEvent.postEvent(eventBus, rule);
    }
  }

  private void finalizeFetchedFromCache(BuildRuleSuccessType success)
      throws StepFailedException, InterruptedException, IOException {
    // For rules fetched from cache, we want to overwrite just the minimum set of things from the
    // cache result. Ensure that nobody has accidentally added unnecessary information to the
    // recorder.
    getBuildInfoRecorder()
        .assertOnlyHasKeys(
            BuildInfo.MetadataKey.BUILD_ID,
            BuildInfo.MetadataKey.RULE_KEY,
            BuildInfo.MetadataKey.INPUT_BASED_RULE_KEY,
            BuildInfo.MetadataKey.DEP_FILE_RULE_KEY,
            BuildInfo.MetadataKey.MANIFEST_KEY);

    // The build has succeeded, whether we've fetched from cache, or built locally.
    // So run the post-build steps.
    if (rule instanceof HasPostBuildSteps) {
      executePostBuildSteps(((HasPostBuildSteps) rule).getPostBuildSteps(buildRuleBuildContext));
    }

    // Invalidate any cached hashes for the output paths, since we've updated them.
    for (Path path : onDiskBuildInfo.getOutputPaths()) {
      fileHashCache.invalidate(rule.getProjectFilesystem().resolve(path));
    }

    // If this rule was fetched from cache, seed the file hash cache with the recorded
    // output hashes from the build metadata.  Skip this if the output size is too big for
    // input-based rule keys.
    long outputSize =
        Long.parseLong(onDiskBuildInfo.getValue(BuildInfo.MetadataKey.OUTPUT_SIZE).getLeft());

    if (shouldWriteOutputHashes(outputSize)) {
      Optional<ImmutableMap<String, String>> hashes =
          onDiskBuildInfo.getMap(BuildInfo.MetadataKey.RECORDED_PATH_HASHES);
      Preconditions.checkState(hashes.isPresent());
      // Seed the cache with the hashes.
      for (Map.Entry<String, String> ent : hashes.get().entrySet()) {
        Path path = rule.getProjectFilesystem().getPath(ent.getKey());
        HashCode hashCode = HashCode.fromString(ent.getValue());
        fileHashCache.set(rule.getProjectFilesystem().resolve(path), hashCode);
      }
    }

    switch (success) {
      case FETCHED_FROM_CACHE:
        break;
      case FETCHED_FROM_CACHE_MANIFEST_BASED:
        if (SupportsInputBasedRuleKey.isSupported(rule)) {
          // The input-based key should already be computed.
          inputBasedKey
              .get()
              .ifPresent(
                  key ->
                      getBuildInfoRecorder()
                          .addBuildMetadata(
                              BuildInfo.MetadataKey.INPUT_BASED_RULE_KEY, key.toString()));
        }
        // $FALL-THROUGH$
      case FETCHED_FROM_CACHE_INPUT_BASED:
        getBuildInfoRecorder()
            .addBuildMetadata(BuildInfo.MetadataKey.RULE_KEY, defaultKey.toString());
        break;

      case BUILT_LOCALLY:

      case MATCHING_RULE_KEY:
      case MATCHING_INPUT_BASED_RULE_KEY:
      case MATCHING_DEP_FILE_RULE_KEY:
        throw new RuntimeException(String.format("Unexpected success type %s.", success));
    }

    // TODO(cjhopman): input-based/depfile each rewrite the matching key, that's unnecessary.
    // TODO(cjhopman): this writes the current build-id/timestamp/extra-data, that's probably
    // unnecessary.
    getBuildInfoRecorder()
        .assertOnlyHasKeys(
            BuildInfo.MetadataKey.RULE_KEY,
            BuildInfo.MetadataKey.INPUT_BASED_RULE_KEY,
            BuildInfo.MetadataKey.DEP_FILE_RULE_KEY,
            BuildInfo.MetadataKey.MANIFEST_KEY,
            BuildInfo.MetadataKey.BUILD_ID);
    try {
      getBuildInfoRecorder().updateBuildMetadata();
    } catch (IOException e) {
      throw new IOException(String.format("Failed to write metadata to disk for %s.", rule), e);
    }
  }

  private void finalizeBuiltLocally()
      throws IOException, StepFailedException, InterruptedException {
    BuildRuleSuccessType success = BuildRuleSuccessType.BUILT_LOCALLY;
    if (rule instanceof HasPostBuildSteps) {
      executePostBuildSteps(((HasPostBuildSteps) rule).getPostBuildSteps(buildRuleBuildContext));
    }

    // Invalidate any cached hashes for the output paths, since we've updated them.
    for (Path path : getBuildInfoRecorder().getRecordedPaths()) {
      fileHashCache.invalidate(rule.getProjectFilesystem().resolve(path));
    }

    // Doing this here is probably not strictly necessary, however in the case of
    // pipelined rules built locally we will never do an input-based cache check.
    // That check would have written the key to metadata, and there are some asserts
    // during cache upload that try to ensure they are present.
    if (SupportsInputBasedRuleKey.isSupported(rule)
        && !getBuildInfoRecorder()
            .getBuildMetadataFor(BuildInfo.MetadataKey.INPUT_BASED_RULE_KEY)
            .isPresent()
        && inputBasedKey.get().isPresent()) {
      getBuildInfoRecorder()
          .addBuildMetadata(
              BuildInfo.MetadataKey.INPUT_BASED_RULE_KEY, inputBasedKey.get().get().toString());
    }

    // If this rule uses dep files, make sure we store the new dep file
    // list and re-calculate the dep file rule key.
    if (dependencyFileRuleKeyManager.useDependencyFileRuleKey()) {

      // Query the rule for the actual inputs it used.
      ImmutableList<SourcePath> inputs =
          ((SupportsDependencyFileRuleKey) rule)
              .getInputsAfterBuildingLocally(
                  buildRuleBuildContext, executionContext.getCellPathResolver());

      // Record the inputs into our metadata for next time.
      // TODO(#9117006): We don't support a way to serlialize `SourcePath`s to the cache,
      // so need to use DependencyFileEntry's instead and recover them on deserialization.
      ImmutableList<String> inputStrings =
          inputs.stream()
              .map(inputString -> DependencyFileEntry.fromSourcePath(inputString, pathResolver))
              .map(ObjectMappers.toJsonFunction())
              .collect(ImmutableList.toImmutableList());
      getBuildInfoRecorder().addMetadata(BuildInfo.MetadataKey.DEP_FILE, inputStrings);

      // Re-calculate and store the depfile rule key for next time.
      Optional<DependencyFileRuleKeyFactory.RuleKeyAndInputs> depFileRuleKeyAndInputs =
          dependencyFileRuleKeyManager.calculateDepFileRuleKey(
              Optional.of(inputStrings), /* allowMissingInputs */ false);
      if (depFileRuleKeyAndInputs.isPresent()) {
        RuleKey depFileRuleKey = depFileRuleKeyAndInputs.get().getRuleKey();
        getBuildInfoRecorder()
            .addBuildMetadata(BuildInfo.MetadataKey.DEP_FILE_RULE_KEY, depFileRuleKey.toString());

        // Push an updated manifest to the cache.
        if (manifestRuleKeyManager.useManifestCaching()) {
          // TODO(cjhopman): This should be able to use manifestKeySupplier.
          try (Scope ignored = PerfEvents.scope(eventBus, "updating_and_storing_manifest")) {
            Optional<DependencyFileRuleKeyFactory.RuleKeyAndInputs> manifestKey =
                calculateManifestKey(eventBus);
            if (manifestKey.isPresent()) {
              getBuildInfoRecorder()
                  .addBuildMetadata(
                      BuildInfo.MetadataKey.MANIFEST_KEY,
                      manifestKey.get().getRuleKey().toString());

              long buildTimeMs =
                  buildTimestampsMillis == null
                      ? -1
                      : buildTimestampsMillis.getSecond() - buildTimestampsMillis.getFirst();

              ManifestStoreResult manifestStoreResult =
                  manifestRuleKeyManager.updateAndStoreManifest(
                      depFileRuleKeyAndInputs.get().getRuleKey(),
                      depFileRuleKeyAndInputs.get().getInputs(),
                      manifestKey.get(),
                      buildTimeMs);
              this.buildRuleScopeManager.setManifestStoreResult(manifestStoreResult);
              if (manifestStoreResult.getStoreFuture().isPresent()) {
                buildRuleBuilderDelegate.addAsyncCallback(
                    manifestStoreResult.getStoreFuture().get());
              }
            }
          }
        }
      }
    }

    // Make sure the origin field is filled in.
    getBuildInfoRecorder()
        .addBuildMetadata(BuildInfo.MetadataKey.ORIGIN_BUILD_ID, buildId.toString());
    // Make sure that all of the local files have the same values they would as if the
    // rule had been built locally.
    getBuildInfoRecorder()
        .addBuildMetadata(BuildInfo.MetadataKey.TARGET, rule.getBuildTarget().toString());
    getBuildInfoRecorder()
        .addBuildMetadata(
            BuildInfo.MetadataKey.CONFIGURATION,
            targetConfigurationSerializer.serialize(
                rule.getBuildTarget().getTargetConfiguration()));

    if (success.shouldWriteRecordedMetadataToDiskAfterBuilding()) {
      try {
        boolean clearExistingMetadata = success.shouldClearAndOverwriteMetadataOnDisk();
        getBuildInfoRecorder().writeMetadataToDisk(clearExistingMetadata);
      } catch (IOException e) {
        throw new IOException(String.format("Failed to write metadata to disk for %s.", rule), e);
      }
    }

    try (Scope ignored = PerfEvents.scope(eventBus, "computing_output_hashes")) {
      onDiskBuildInfo.calculateOutputSizeAndWriteMetadata(
          fileHashCache, getBuildInfoRecorder().getRecordedPaths(), this::shouldWriteOutputHashes);
    }
  }

  private boolean shouldWriteOutputHashes(long outputSize) {
    Optional<Long> sizeLimit = Optional.empty();

    // By definition, the threshold for rules is defined by, in order of priority:
    // 1. Rule-specific output size threshold
    // 2. Default rule output size threshold
    // 3. Input-based rulekey size limit

    Long ruleSizeLimit;
    if ((ruleSizeLimit = ruleTypeOutputHashSizeLimit.get(rule.getType())) != null) {
      sizeLimit = Optional.of(ruleSizeLimit);
    }

    if (!sizeLimit.isPresent()) {
      sizeLimit = defaultOutputHashSizeLimit;
    }

    if (!sizeLimit.isPresent()) {
      sizeLimit = ruleKeyFactories.getInputBasedRuleKeyFactory().getInputSizeLimit();
    }

    return !sizeLimit.isPresent() || (outputSize <= sizeLimit.get());
  }

  private BuildInfoRecorder getBuildInfoRecorder() {
    return buildInfoRecorder.get();
  }

  private void uploadToCache(BuildRuleSuccessType success) {
    try {
      // Push to cache.
      long buildTimeMs =
          buildTimestampsMillis == null
              ? -1
              : buildTimestampsMillis.getSecond() - buildTimestampsMillis.getFirst();
      buildCacheArtifactUploader.uploadToCache(success, buildTimeMs).get();
    } catch (Throwable t) {
      if (t instanceof ExecutionException) {
        eventBus.post(
            ConsoleEvent.warning(
                "Error uploading to cache for %s, with exception message: %s",
                rule, t.getMessage()));
      } else {
        eventBus.post(ThrowableConsoleEvent.create(t, "Error uploading to cache for %s.", rule));
      }
    }
  }

  private void handleResult(BuildResult input) {
    Optional<Long> outputSize = Optional.empty();
    Optional<HashCode> outputHash = Optional.empty();
    Optional<BuildRuleSuccessType> successType = Optional.empty();
    UploadToCacheResultType shouldUploadToCache = UploadToCacheResultType.UNCACHEABLE;

    try (Scope ignored = buildRuleScope()) {
      if (input.getStatus() == BuildRuleStatus.SUCCESS) {
        BuildRuleSuccessType success = input.getSuccess();
        successType = Optional.of(success);

        // Try get the output size.
        Either<String, Exception> outputSizeString =
            onDiskBuildInfo.getValue(MetadataKey.OUTPUT_SIZE);
        long outputSizeValue =
            outputSizeString.transform(
                Long::parseLong,
                right -> {
                  throw new RuntimeException(
                      "OUTPUT_SIZE should always be computed and present for rule "
                          + rule.toStringWithConfiguration(),
                      right);
                });

        outputSize = Optional.of(outputSizeValue);

        // All rules should have output_size/output_hash in their artifact metadata.
        Either<String, Exception> hashString = onDiskBuildInfo.getValue(MetadataKey.OUTPUT_HASH);
        hashString
            .getRightOption()
            .ifPresent(
                right -> {
                  if (shouldWriteOutputHashes(outputSizeValue)) {
                    // OUTPUT_HASH should only be missing if we exceed the hash size limit.
                    LOG.warn(
                        hashString.getRight(),
                        "OUTPUT_HASH is unexpectedly missing for %s",
                        rule.toStringWithConfiguration());
                  }
                });

        outputHash = hashString.getLeftOption().map(HashCode::fromString);

        // Determine if this is rule is cacheable.
        Optional<CacheResult> cacheResult = input.getCacheResult();
        shouldUploadToCache =
            buildCacheArtifactUploader.shouldUploadToCache(success, cacheResult, outputSizeValue);

        // Upload it to the cache.
        if (shouldUploadToCache.equals(UploadToCacheResultType.CACHEABLE)) {
          uploadToCache(success);
        }
      }

      buildRuleScopeManager.finished(
          input,
          outputSize,
          outputHash,
          successType,
          shouldUploadToCache,
          Optional.ofNullable(ruleKeyCacheCheckTimestampsMillis),
          Optional.ofNullable(inputRuleKeyCacheCheckTimestampsMillis),
          Optional.ofNullable(manifestRuleKeyCacheCheckTimestampsMillis),
          Optional.ofNullable(buildTimestampsMillis));
    }
  }

  private ListenableFuture<Optional<BuildResult>> buildLocally(
      final CacheResult cacheResult, final ListeningExecutorService service) {
    @SuppressWarnings("PMD.PrematureDeclaration")
    long start = System.currentTimeMillis();

    onRuleAboutToBeBuilt();

    BuildRuleSteps<RulePipelineState> buildRuleSteps = new BuildRuleSteps<>(cacheResult);
    BuildStrategyContext strategyContext =
        new BuildStrategyContext() {
          @Override
          public ListenableFuture<Optional<BuildResult>> runWithDefaultBehavior() {

            if (SupportsPipelining.isSupported(rule)) {
              return pipelinesRunner.runPipelineStartingAt(
                  buildRuleBuildContext, (SupportsPipelining<?>) rule, service);
            }

            service.submit(buildRuleSteps::runWithDefaultExecutor);
            return buildRuleSteps.future;
          }

          @Override
          public ListeningExecutorService getExecutorService() {
            return serviceByAdjustingDefaultWeightsTo(
                CachingBuildEngine.SCHEDULING_MORE_WORK_RESOURCE_AMOUNTS);
          }

          @Override
          public BuildResult createBuildResult(
              BuildRuleSuccessType successType, Optional<String> strategyResult) {
            return success(successType, cacheResult, strategyResult);
          }

          @Override
          public BuildResult createCancelledResult(Throwable throwable) {
            return canceled(throwable);
          }

          @Override
          public StepExecutionContext getExecutionContext() {
            return buildRuleSteps.ruleExecutionContext;
          }

          @Override
          public Scope buildRuleScope() {
            return CachingBuildRuleBuilder.this.buildRuleScope();
          }

          @Override
          public BuildContext getBuildRuleBuildContext() {
            return buildRuleBuildContext;
          }

          @Override
          public BuildableContext getBuildableContext() {
            return buildableContext;
          }
        };

    ListenableFuture<Optional<BuildResult>> future;
    if (customBuildRuleStrategy.isPresent() && customBuildRuleStrategy.get().canBuild(rule)) {
      this.strategyResult = customBuildRuleStrategy.get().build(rule, strategyContext);
      future = strategyResult.getBuildResult();
    } else {
      future = strategyContext.runWithDefaultBehavior();
    }

    return Futures.transform(
        future,
        p -> {
          buildTimestampsMillis = new Pair<>(start, System.currentTimeMillis());
          return p;
        },
        MoreExecutors.directExecutor());
  }

  private ListenableFuture<Optional<BuildResult>> checkManifestBasedCaches() {
    Optional<DependencyFileRuleKeyFactory.RuleKeyAndInputs> manifestKeyAndInputs =
        manifestBasedKeySupplier.get();
    if (!manifestKeyAndInputs.isPresent()) {
      return Futures.immediateFuture(Optional.empty());
    }
    getBuildInfoRecorder()
        .addBuildMetadata(
            BuildInfo.MetadataKey.MANIFEST_KEY, manifestKeyAndInputs.get().getRuleKey().toString());
    try (Scope ignored = PerfEvents.scope(eventBus, "checking_cache_depfile_based")) {
      long start = System.currentTimeMillis();
      return Futures.transform(
          manifestRuleKeyManager.performManifestBasedCacheFetch(manifestKeyAndInputs.get()),
          (ManifestFetchResult result) -> {
            buildRuleScopeManager.setManifestFetchResult(result);
            manifestRuleKeyCacheCheckTimestampsMillis =
                new Pair<>(start, System.currentTimeMillis());
            if (!result.getRuleCacheResult().isPresent()) {
              return Optional.empty();
            }
            if (!result.getRuleCacheResult().get().getType().isSuccess()) {
              return Optional.empty();
            }
            return Optional.of(
                success(
                    BuildRuleSuccessType.FETCHED_FROM_CACHE_MANIFEST_BASED,
                    result.getRuleCacheResult().get()));
          },
          MoreExecutors.directExecutor());
    }
  }

  private Optional<BuildResult> checkMatchingDepfile() throws IOException {
    return dependencyFileRuleKeyManager.checkMatchingDepfile()
        ? Optional.of(
            success(
                BuildRuleSuccessType.MATCHING_DEP_FILE_RULE_KEY,
                CacheResult.localKeyUnchangedHit()))
        : Optional.empty();
  }

  private ListenableFuture<Optional<BuildResult>> checkInputBasedCaches() {
    long start = System.currentTimeMillis();
    return Futures.transform(
        inputBasedRuleKeyManager.checkInputBasedCaches(),
        optionalResult ->
            optionalResult.map(
                result -> {
                  inputRuleKeyCacheCheckTimestampsMillis =
                      new Pair<>(start, System.currentTimeMillis());
                  return success(result.getFirst(), result.getSecond());
                }),
        MoreExecutors.directExecutor());
  }

  private ListenableFuture<BuildResult> buildOrFetchFromCache() {
    // If we've already seen a failure, exit early.
    if (!shouldKeepGoing()) {
      return Futures.immediateFuture(canceled(firstFailure));
    }

    // 1. Check if it's already built.
    try (Scope ignored = buildRuleScope()) {
      Optional<BuildResult> buildResult = checkMatchingLocalKey();
      if (buildResult.isPresent()) {
        return Futures.immediateFuture(buildResult.get());
      }
    }

    AtomicReference<CacheResult> rulekeyCacheResult = new AtomicReference<>();
    ListenableFuture<Optional<BuildResult>> buildResultFuture;

    // 2. Rule key cache lookup.
    buildResultFuture =
        // TODO(cjhopman): This should follow the same, simple pattern as everything else. With a
        // large ui.thread_line_limit, SuperConsole tries to redraw more lines than are available.
        // These cache threads make it more likely to hit that problem when SuperConsole is aware
        // of them.
        Futures.transform(
            performRuleKeyCacheCheck(/* cacheHitExpected */ false),
            cacheResult -> {
              Objects.requireNonNull(cacheResult);
              cacheResult.getType().verifyValidFinalType();
              rulekeyCacheResult.set(cacheResult);
              return getBuildResultForRuleKeyCacheResult(cacheResult);
            },
            MoreExecutors.directExecutor());

    // 3. Build deps.
    buildResultFuture =
        transformBuildResultAsyncIfNotPresent(
            buildResultFuture,
            () -> {
              if (SupportsPipelining.isSupported(rule)) {
                addToPipelinesRunner(
                    (SupportsPipelining<?>) rule, Objects.requireNonNull(rulekeyCacheResult.get()));
              }

              return Futures.transformAsync(
                  buildRuleBuilderDelegate.getDepResults(rule, executionContext),
                  this::handleDepsResults,
                  serviceByAdjustingDefaultWeightsTo(
                      CachingBuildEngine.SCHEDULING_MORE_WORK_RESOURCE_AMOUNTS));
            });

    // 4. Return to the current rule and check if it was (or is being) built in a pipeline with
    // one of its dependencies
    if (SupportsPipelining.isSupported(rule)) {
      buildResultFuture =
          transformBuildResultAsyncIfNotPresent(
              buildResultFuture,
              () -> {
                SupportsPipelining<?> pipelinedRule = (SupportsPipelining<?>) rule;
                return pipelinesRunner.isPipelineReady(pipelinedRule)
                    ? pipelinesRunner.getFuture(pipelinedRule)
                    : Futures.immediateFuture(Optional.empty());
              });
    }

    // 5. Return to the current rule and check caches to see if we can avoid building
    if (SupportsInputBasedRuleKey.isSupported(rule)) {
      buildResultFuture =
          transformBuildResultAsyncIfNotPresent(buildResultFuture, this::checkInputBasedCaches);
    }

    // 6. Then check if the depfile matches.
    if (dependencyFileRuleKeyManager.useDependencyFileRuleKey()) {
      buildResultFuture =
          transformBuildResultIfNotPresent(
              buildResultFuture,
              this::checkMatchingDepfile,
              serviceByAdjustingDefaultWeightsTo(CachingBuildEngine.CACHE_CHECK_RESOURCE_AMOUNTS));
    }

    // 7. Check for a manifest-based cache hit.
    if (manifestRuleKeyManager.useManifestCaching()) {
      buildResultFuture =
          transformBuildResultAsyncIfNotPresent(buildResultFuture, this::checkManifestBasedCaches);
    }

    // 8. Fail if populating the cache and cache lookups failed.
    if (buildMode == BuildType.POPULATE_FROM_REMOTE_CACHE) {
      buildResultFuture =
          transformBuildResultIfNotPresent(
              buildResultFuture,
              () -> {
                LOG.info("Cannot populate cache for " + rule.toStringWithConfiguration());
                return Optional.of(
                    canceled(
                        new HumanReadableException(
                            "Skipping %s: in cache population mode local builds are disabled",
                            rule)));
              },
              MoreExecutors.newDirectExecutorService());
    }

    // 9. Build the current rule locally, if we have to.
    buildResultFuture =
        transformBuildResultAsyncIfNotPresent(
            buildResultFuture,
            () ->
                buildLocally(
                    Objects.requireNonNull(rulekeyCacheResult.get()),
                    service
                        // This needs to adjust the default amounts even in the non-resource-aware
                        // scheduling case so that RuleScheduleInfo works correctly.
                        .withDefaultAmounts(getRuleResourceAmounts())));

    if (SupportsPipelining.isSupported(rule)) {
      buildResultFuture.addListener(
          () -> pipelinesRunner.removeRule((SupportsPipelining<?>) rule),
          MoreExecutors.directExecutor());
    }

    // Unwrap the result.
    return Futures.transform(buildResultFuture, Optional::get, MoreExecutors.directExecutor());
  }

  private boolean shouldKeepGoing() {
    return firstFailure == null;
  }

  private <State extends RulePipelineState> void addToPipelinesRunner(
      SupportsPipelining<State> rule, CacheResult cacheResult) {

    @SuppressWarnings("unchecked")
    BuildRulePipelinesRunner<State> pipelinesRunner =
        (BuildRulePipelinesRunner<State>) this.pipelinesRunner;
    pipelinesRunner.addRule(
        rule,
        (stateHolder, isFirstStage) ->
            new RunnableWithFuture<Optional<BuildResult>>() {
              final BuildRuleSteps<State> steps =
                  new BuildRuleSteps<>(cacheResult, stateHolder, isFirstStage);

              @Override
              public ListenableFuture<Optional<BuildResult>> getFuture() {
                return steps.getFuture();
              }

              @Override
              public void run() {
                onRuleAboutToBeBuilt();
                steps.runWithDefaultExecutor();
              }
            });
  }

  private Optional<BuildResult> checkMatchingLocalKey() {
    Optional<RuleKey> cachedRuleKey = onDiskBuildInfo.getRuleKey(BuildInfo.MetadataKey.RULE_KEY);
    if (defaultKey.equals(cachedRuleKey.orElse(null))) {

      if (!checkArtifactMetadataFileIntegrity()) {
        try {
          onDiskBuildInfo.deleteExistingMetadata();
        } catch (IOException e) {
          throw new BuckUncheckedExecutionException(e);
        }
        return Optional.empty();
      }

      return Optional.of(
          success(BuildRuleSuccessType.MATCHING_RULE_KEY, CacheResult.localKeyUnchangedHit()));
    }
    return Optional.empty();
  }

  private boolean checkArtifactMetadataFileIntegrity() {
    // Check the integrity of ARTIFACT_METADATA file
    Either<String, Exception> outputSizeString = onDiskBuildInfo.getValue(MetadataKey.OUTPUT_SIZE);

    if (outputSizeString.isRight()) {
      LOG.warn("Could not find OUTPUT_SIZE from ARTIFACT_METADATA file");
      return false;
    } else {
      long outputSizeValue = Long.parseLong(outputSizeString.getLeft());

      if (shouldWriteOutputHashes(outputSizeValue)
          && onDiskBuildInfo.getValue(MetadataKey.OUTPUT_HASH).isRight()) {
        LOG.warn("Could not find OUTPUT_HASH from ARTIFACT_METADATA file");
        return false;
      }
    }
    return true;
  }

  private ListenableFuture<CacheResult> performRuleKeyCacheCheck(boolean cacheHitExpected) {
    long cacheRequestTimestampMillis = System.currentTimeMillis();
    return Futures.transform(
        buildCacheArtifactFetcher
            .tryToFetchArtifactFromBuildCacheAndOverlayOnTopOfProjectFilesystem(
                defaultKey,
                artifactCache,
                // TODO(simons): This should be a shared between all tests, not one per cell
                rule.getProjectFilesystem()),
        cacheResult -> {
          RuleKeyCacheResult ruleKeyCacheResult =
              RuleKeyCacheResult.of(
                  rule.getFullyQualifiedName(),
                  defaultKey.toString(),
                  cacheRequestTimestampMillis,
                  RuleKeyType.DEFAULT,
                  cacheResult.getType(),
                  cacheResult.twoLevelContentHashKey());
          ruleKeyCacheCheckTimestampsMillis =
              new Pair<>(cacheRequestTimestampMillis, System.currentTimeMillis());
          eventBus.post(new RuleKeyCacheResultEvent(ruleKeyCacheResult, cacheHitExpected));
          return cacheResult;
        },
        MoreExecutors.directExecutor());
  }

  private Optional<BuildResult> getBuildResultForRuleKeyCacheResult(CacheResult cacheResult) {
    if (!cacheResult.getType().isSuccess()) {
      return Optional.empty();
    }
    return Optional.of(success(BuildRuleSuccessType.FETCHED_FROM_CACHE, cacheResult));
  }

  private ListenableFuture<Optional<BuildResult>> handleDepsResults(List<BuildResult> depResults) {
    Optional<BuildResult> failedResult = Optional.empty();
    Optional<BuildResult> cancelledResult = Optional.empty();
    for (BuildResult depResult : depResults) {
      if (buildMode != BuildType.POPULATE_FROM_REMOTE_CACHE && !depResult.isSuccess()) {
        if (depResult.getStatus() == BuildRuleStatus.CANCELED) {
          cancelledResult = Optional.of(canceled(depResult.getFailure()));
        } else {
          failedResult = Optional.of(failure(depResult.getFailure()));
        }
      }

      if (depResult
          .getCacheResult()
          .orElse(CacheResult.skipped())
          .getType()
          .equals(CacheResultType.MISS)) {
        depsWithCacheMiss.add(depResult.getRule().getFullyQualifiedName());
      }
    }
    Optional<BuildResult> result = failedResult.isPresent() ? failedResult : cancelledResult;
    depsAreAvailable = !result.isPresent();
    return Futures.immediateFuture(result);
  }

  private void recordFailureAndCleanUp(BuildRuleFailedException failure) {
    // Make this failure visible for other rules, so that they can stop early.
    buildRuleBuilderDelegate.setFirstFailure(Objects.requireNonNull(failure));

    // If we failed, cleanup the state of this rule.
    // TODO(mbolin): Delete all files produced by the rule, as they are not guaranteed
    // to be valid at this point?
    try {
      onDiskBuildInfo.deleteExistingMetadata();
    } catch (Throwable t) {
      eventBus.post(ThrowableConsoleEvent.create(t, "Error when deleting metadata for %s.", rule));
    }
  }

  private BuildRuleFailedException getFailedException(Throwable thrown) {
    if (thrown instanceof BuildRuleFailedException) {
      return (BuildRuleFailedException) thrown;
    }
    return new BuildRuleFailedException(thrown, rule.getBuildTarget());
  }

  /**
   * onOutputsWillChange() should be called once we've determined that the outputs are going to
   * change from their previous state (e.g. because we're about to build locally or unzip an
   * artifact from the cache).
   */
  private void onOutputsWillChange() throws IOException {
    outputsCanChange = true;
    if (rule instanceof InitializableFromDisk) {
      ((InitializableFromDisk<?>) rule).getBuildOutputInitializer().invalidate();
    }
    onDiskBuildInfo.deleteExistingMetadata();
    // TODO(cjhopman): Delete old outputs.
  }

  private void onRuleAboutToBeBuilt() {
    try {
      onOutputsWillChange();
    } catch (IOException e) {
      throw new BuckUncheckedExecutionException(e);
    }
    buildRuleBuilderDelegate.onRuleAboutToBeBuilt(rule);
    eventBus.post(BuildRuleEvent.willBuildLocally(rule));
  }

  private void executePostBuildSteps(Iterable<Step> postBuildSteps)
      throws InterruptedException, StepFailedException {

    LOG.debug("Running post-build steps for %s", rule);

    for (Step step : postBuildSteps) {
      ContextualProcessExecutor contextualProcessExecutor =
          new ContextualProcessExecutor(
              this.executionContext.getProcessExecutor(), processExecutorContext);
      ExecutionContext executionContext =
          this.executionContext.withProcessExecutor(contextualProcessExecutor);

      BuildTarget buildTarget = rule.getBuildTarget();
      StepRunner.runStep(
          StepExecutionContext.from(
              executionContext,
              rule.getProjectFilesystem().getRootPath(),
              ActionId.of(buildTarget),
              Optional.of(buildTarget)),
          step,
          Optional.of(buildTarget));

      // Check for interruptions that may have been ignored by step.
      if (Thread.interrupted()) {
        Threads.interruptCurrentThread();
        throw new InterruptedException();
      }
    }

    LOG.debug("Finished running post-build steps for %s", rule);
  }

  private <T> void doInitializeFromDisk(InitializableFromDisk<T> initializable) throws IOException {
    try (Scope ignored = PerfEvents.scope(eventBus, "initialize_from_disk")) {
      BuildOutputInitializer<T> buildOutputInitializer = initializable.getBuildOutputInitializer();
      buildOutputInitializer.initializeFromDisk(pathResolver);
    }
  }

  private Optional<DependencyFileRuleKeyFactory.RuleKeyAndInputs> calculateManifestKey(
      BuckEventBus eventBus) throws IOException {
    Preconditions.checkState(depsAreAvailable);
    return manifestRuleKeyManager.calculateManifestKey(eventBus);
  }

  private Optional<RuleKey> calculateInputBasedRuleKey() {
    Preconditions.checkState(depsAreAvailable);
    return inputBasedRuleKeyManager.calculateInputBasedRuleKey();
  }

  private ResourceAmounts getRuleResourceAmounts() {
    if (resourceAwareSchedulingInfo.isResourceAwareSchedulingEnabled()) {
      return resourceAwareSchedulingInfo.getResourceAmountsForRule(rule);
    } else {
      return getResourceAmountsForRuleWithCustomScheduleInfo();
    }
  }

  private ResourceAmounts getResourceAmountsForRuleWithCustomScheduleInfo() {
    Preconditions.checkArgument(!resourceAwareSchedulingInfo.isResourceAwareSchedulingEnabled());
    RuleScheduleInfo ruleScheduleInfo;
    if (rule instanceof OverrideScheduleRule) {
      ruleScheduleInfo = ((OverrideScheduleRule) rule).getRuleScheduleInfo();
    } else {
      ruleScheduleInfo = RuleScheduleInfo.DEFAULT;
    }
    return ResourceAmounts.of(ruleScheduleInfo.getJobsMultiplier(), 0, 0, 0);
  }

  private Scope buildRuleScope() {
    return buildRuleScopeManager.scope();
  }

  // Wrap an async function in rule resume/suspend events.
  private <F, T> AsyncFunction<F, T> ruleAsyncFunction(AsyncFunction<F, T> delegate) {
    return input -> {
      try (Scope ignored = buildRuleScope()) {
        return delegate.apply(input);
      }
    };
  }

  private ListenableFuture<Optional<BuildResult>> transformBuildResultIfNotPresent(
      ListenableFuture<Optional<BuildResult>> future,
      Callable<Optional<BuildResult>> function,
      ListeningExecutorService executor) {
    return transformBuildResultAsyncIfNotPresent(
        future,
        () ->
            executor.submit(
                () -> {
                  if (!shouldKeepGoing()) {
                    return Optional.of(canceled(firstFailure));
                  }
                  try (Scope ignored = buildRuleScope()) {
                    return function.call();
                  }
                }));
  }

  private ListenableFuture<Optional<BuildResult>> transformBuildResultAsyncIfNotPresent(
      ListenableFuture<Optional<BuildResult>> future,
      Callable<ListenableFuture<Optional<BuildResult>>> function) {
    // Immediately (i.e. without posting a task), returns the current result if it's already present
    // or a cancelled result if we've already seen a failure.
    return Futures.transformAsync(
        future,
        (result) -> {
          if (result.isPresent()) {
            return Futures.immediateFuture(result);
          }
          if (!shouldKeepGoing()) {
            return Futures.immediateFuture(Optional.of(canceled(firstFailure)));
          }
          return function.call();
        },
        MoreExecutors.directExecutor());
  }

  public void cancel(Throwable throwable) {
    firstFailure = throwable;
    // TODO(cjhopman): There's a small race here where we might delegate to the strategy after
    // checking here if it's null. I don't think we care, though.
    if (strategyResult != null) {
      Objects.requireNonNull(strategyResult).cancel(throwable);
    }
  }

  /** Encapsulates the steps involved in building a single {@link BuildRule} locally. */
  public class BuildRuleSteps<State extends RulePipelineState> {

    private final CacheResult cacheResult;
    private final SettableFuture<Optional<BuildResult>> future = SettableFuture.create();
    private final StepExecutionContext ruleExecutionContext;
    private final Optional<Pair<StateHolder<State>, Boolean>> stateHolderOptional;

    public BuildRuleSteps(CacheResult cacheResult) {
      this(cacheResult, Optional.empty());
    }

    public BuildRuleSteps(
        CacheResult cacheResult, StateHolder<State> stateHolder, boolean isFirstStage) {
      this(cacheResult, Optional.of(new Pair<>(stateHolder, isFirstStage)));
    }

    private BuildRuleSteps(
        CacheResult cacheResult, Optional<Pair<StateHolder<State>, Boolean>> stateHolderOptional) {
      cacheResult.getType().verifyValidFinalType();
      this.cacheResult = cacheResult;
      this.stateHolderOptional = stateHolderOptional;
      ContextualProcessExecutor contextualProcessExecutor =
          new ContextualProcessExecutor(
              executionContext.getProcessExecutor(), processExecutorContext);
      this.ruleExecutionContext =
          StepExecutionContext.from(
              executionContext.withProcessExecutor(contextualProcessExecutor),
              rule.getProjectFilesystem().getRootPath(),
              ActionId.of(rule.getBuildTarget()),
              Optional.of(rule.getBuildTarget()));

      if (stateHolderOptional.isPresent()) {
        @SuppressWarnings("unchecked")
        SupportsPipelining<State> pipelinedRule = (SupportsPipelining<State>) rule;
        if (pipelinedRule.supportsCompilationDaemon()) {
          appendCompilationStep(pipelinedRule);
        }
      }
    }

    private void appendCompilationStep(SupportsPipelining<State> pipelinedRule) {
      StateHolder<State> stateHolder = getInitializedStateHolder();
      CompilationDaemonStep compilationDaemonStep = stateHolder.getCompilationDaemonStep();
      AbstractMessage pipelinedCommand = getPipelinedCommand(pipelinedRule);
      ActionId actionId = ActionId.of(pipelinedRule.getBuildTarget());
      compilationDaemonStep.appendStepWithCommand(actionId, pipelinedCommand);
    }

    private AbstractMessage getPipelinedCommand(SupportsPipelining<State> pipelinedRule) {
      Preconditions.checkState(pipelinedRule instanceof PipelinedModernBuildRule);
      @SuppressWarnings("unchecked")
      PipelinedModernBuildRule<State, ?> pipelinedModernBuildRule =
          (PipelinedModernBuildRule<State, ?>) pipelinedRule;
      return pipelinedModernBuildRule.getPipelinedCommand(buildRuleBuildContext);
    }

    public SettableFuture<Optional<BuildResult>> getFuture() {
      return future;
    }

    public void runWithDefaultExecutor() {
      try {
        if (!shouldKeepGoing()) {
          future.set(Optional.of(canceled(firstFailure)));
          return;
        }

        try (Scope ignored = buildRuleScope()) {
          LOG.debug("Building locally: %s", rule.toStringWithConfiguration());
          // Attempt to get an approximation of how long it takes to actually run the command.
          long start = System.nanoTime();
          executeCommands(ruleExecutionContext);
          long end = System.nanoTime();
          LOG.debug(
              "Build completed: %s %s (%dns)",
              rule.getType(), rule.toStringWithConfiguration(), end - start);
        }

        // Set the future outside of the scope, to match the behavior of other steps that use
        // futures provided by the ExecutorService.
        future.set(Optional.of(success(BuildRuleSuccessType.BUILT_LOCALLY, cacheResult)));
      } catch (Throwable t) {
        future.setException(t);
      }
    }

    private void executeCommands(StepExecutionContext executionContext)
        throws StepFailedException, InterruptedException {
      try (Scope ignored = BuildRuleExecutionEvent.scope(eventBus, rule)) {
        // Get and run all of the commands.
        for (Step step : getSteps()) {
          StepRunner.runStep(executionContext, step, Optional.of(rule.getBuildTarget()));
          rethrowIgnoredInterruptedException(step);
        }
      }
    }

    private ImmutableList<? extends Step> getSteps() {
      try (Scope ignored = PerfEvents.scope(eventBus, "get_build_steps")) {
        if (stateHolderOptional.isPresent()) {
          @SuppressWarnings("unchecked")
          SupportsPipelining<State> pipelinedRule = (SupportsPipelining<State>) rule;
          StateHolder<State> stateHolder = getInitializedStateHolder();
          if (pipelinedRule.supportsCompilationDaemon()) {
            return getCompilationDaemonSteps(stateHolder, pipelinedRule, buildableContext);
          }
          return pipelinedRule.getPipelinedBuildSteps(
              buildRuleBuildContext, buildableContext, stateHolder);
        }

        return rule.getBuildSteps(buildRuleBuildContext, buildableContext);
      }
    }

    private StateHolder<State> getInitializedStateHolder() {
      Pair<StateHolder<State>, Boolean> pair = stateHolderOptional.get();
      StateHolder<State> stateHolder = pair.getFirst();
      boolean isFirst = pair.getSecond();
      stateHolder.setFirstStage(isFirst);
      return stateHolder;
    }

    private ImmutableList<Step> getCompilationDaemonSteps(
        StateHolder<State> stateHolder,
        SupportsPipelining<State> pipelinedRule,
        BuildableContext buildableContext) {
      Preconditions.checkState(pipelinedRule instanceof PipelinedModernBuildRule);
      @SuppressWarnings("unchecked")
      PipelinedModernBuildRule<State, ?> pipelinedModernBuildRule =
          (PipelinedModernBuildRule<State, ?>) pipelinedRule;

      ImmutableList.Builder<Step> stepsBuilder = ImmutableList.builder();
      pipelinedModernBuildRule.appendWithCommonSetupSteps(
          buildRuleBuildContext, buildableContext, stepsBuilder);
      stepsBuilder.add(stateHolder.getCompilationDaemonStep());
      return stepsBuilder.build();
    }

    private void rethrowIgnoredInterruptedException(Step step) throws InterruptedException {
      // Check for interruptions that may have been ignored by step.
      if (Thread.interrupted()) {
        Thread.currentThread().interrupt();
        throw new InterruptedException(
            "Thread was interrupted inside the executed step: " + step.getShortName());
      }
    }
  }

  public interface BuildRuleBuilderDelegate {

    void markRuleAsUsed(BuildRule rule, BuckEventBus eventBus);

    void setFirstFailure(BuildRuleFailedException throwable);

    ListenableFuture<List<BuildResult>> getDepResults(
        BuildRule rule, ExecutionContext executionContext);

    void addAsyncCallback(ListenableFuture<Unit> callback);

    void onRuleAboutToBeBuilt(BuildRule rule);
  }
}
