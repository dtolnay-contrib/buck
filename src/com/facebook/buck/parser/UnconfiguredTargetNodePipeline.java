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

package com.facebook.buck.parser;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.core.filesystems.ForwardRelPath;
import com.facebook.buck.core.model.CellRelativePath;
import com.facebook.buck.core.model.FlavorSet;
import com.facebook.buck.core.model.RuleType;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.model.targetgraph.impl.Package;
import com.facebook.buck.core.model.targetgraph.raw.UnconfiguredTargetNode;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.event.SimplePerfEvent.Scope;
import com.facebook.buck.parser.PipelineNodeCache.Cache;
import com.facebook.buck.parser.api.RawTargetNode;
import com.facebook.buck.parser.config.ParserConfig;
import com.facebook.buck.parser.exceptions.BuildTargetException;
import com.facebook.buck.util.concurrent.MoreFutures;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.SettableFuture;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Converts nodes in a raw form (taken from build file parsers) into {@link UnconfiguredTargetNode}.
 */
public class UnconfiguredTargetNodePipeline implements AutoCloseable {
  private static final Logger LOG = Logger.get(UnconfiguredTargetNodePipeline.class);

  private final ListeningExecutorService executorService;
  private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
  private final BuckEventBus eventBus;
  private final PipelineNodeCache<UnconfiguredBuildTarget, UnconfiguredTargetNode> cache;
  private final ConcurrentHashMap<
          CellRelativePath, ListenableFuture<ImmutableList<UnconfiguredTargetNode>>>
      allNodeCache = new ConcurrentHashMap<>();
  private final Scope perfEventScope;
  private final SimplePerfEvent.PerfEventTitle perfEventTitle;
  /**
   * minimum duration time for performance events to be logged (for use with {@link
   * SimplePerfEvent}s). This is on the base class to make it simpler to enable verbose tracing for
   * all of the parsing pipelines.
   */
  private final long minimumPerfEventTimeMs;

  private final BuildFileRawNodeParsePipeline buildFileRawNodeParsePipeline;
  private final BuildTargetRawNodeParsePipeline buildTargetRawNodeParsePipeline;
  private final PackagePipeline packagePipeline;
  private final UnconfiguredTargetNodeFactory unconfiguredTargetNodeFactory;

  public UnconfiguredTargetNodePipeline(
      ListeningExecutorService executorService,
      DaemonicParserValidationToken validationToken,
      Cache<UnconfiguredBuildTarget, UnconfiguredTargetNode> cache,
      BuckEventBus eventBus,
      BuildFileRawNodeParsePipeline buildFileRawNodeParsePipeline,
      BuildTargetRawNodeParsePipeline buildTargetRawNodeParsePipeline,
      PackagePipeline packagePipeline,
      UnconfiguredTargetNodeFactory unconfiguredTargetNodeFactory) {
    this.executorService = executorService;
    this.eventBus = eventBus;
    this.buildFileRawNodeParsePipeline = buildFileRawNodeParsePipeline;
    this.buildTargetRawNodeParsePipeline = buildTargetRawNodeParsePipeline;
    this.packagePipeline = packagePipeline;
    this.unconfiguredTargetNodeFactory = unconfiguredTargetNodeFactory;
    this.minimumPerfEventTimeMs = LOG.isVerboseEnabled() ? 0 : 10;
    this.perfEventTitle = SimplePerfEvent.PerfEventTitle.of("GetRawTargetNode");
    this.perfEventScope =
        SimplePerfEvent.scope(
            eventBus.isolated(),
            SimplePerfEvent.PerfEventTitle.of("raw_target_node_parse_pipeline"));
    this.cache =
        new PipelineNodeCache<>(
            cache, validationToken, UnconfiguredTargetNodePipeline::targetNodeIsConfiguration);
  }

  private static boolean targetNodeIsConfiguration(UnconfiguredTargetNode targetNode) {
    return targetNode.getRuleType().getKind() == RuleType.Kind.CONFIGURATION;
  }

  /** Get or load all raw target nodes from a build file */
  public ListenableFuture<ImmutableList<UnconfiguredTargetNode>> getAllNodesJob(
      Cell cell, ForwardRelPath buildFile) {
    SettableFuture<ImmutableList<UnconfiguredTargetNode>> future = SettableFuture.create();
    ListenableFuture<ImmutableList<UnconfiguredTargetNode>> cachedFuture =
        allNodeCache.putIfAbsent(CellRelativePath.of(cell.getCanonicalName(), buildFile), future);

    if (cachedFuture != null) {
      return cachedFuture;
    }

    try {
      ListenableFuture<List<UnconfiguredTargetNode>> allNodesListJob =
          Futures.transformAsync(
              MoreFutures.combinedFutures(
                  packagePipeline.getPackageJob(cell, buildFile),
                  buildFileRawNodeParsePipeline.getFileJob(cell, buildFile),
                  executorService),
              resultingPair -> {
                ImmutableList<RawTargetNode> allToConvert =
                    ImmutableList.copyOf(resultingPair.getSecond().getTargets().values());
                if (shuttingDown()) {
                  return Futures.immediateCancelledFuture();
                }

                ImmutableList.Builder<ListenableFuture<UnconfiguredTargetNode>> allNodeJobs =
                    ImmutableList.builderWithExpectedSize(allToConvert.size());

                for (RawTargetNode from : allToConvert) {
                  UnconfiguredBuildTarget target =
                      UnconfiguredBuildTarget.of(
                          UnflavoredBuildTargetFactory.createFromRawNode(
                              cell.getRoot(),
                              cell.getCanonicalName(),
                              from,
                              cell.getRoot().resolve(buildFile)),
                          FlavorSet.NO_FLAVORS);
                  allNodeJobs.add(
                      cache.getJobWithCacheLookup(
                          cell,
                          target,
                          () ->
                              dispatchComputeNode(
                                  cell,
                                  target,
                                  DependencyStack.top(target),
                                  from,
                                  resultingPair.getFirst())));
                }

                return Futures.allAsList(allNodeJobs.build());
              },
              executorService);
      future.setFuture(Futures.transform(allNodesListJob, ImmutableList::copyOf, executorService));
    } catch (Throwable t) {
      future.setException(t);
    }
    return future;
  }

  /** Get build target by name, load if necessary */
  public ListenableFuture<UnconfiguredTargetNode> getNodeJob(
      Cell cell, UnconfiguredBuildTarget buildTarget, DependencyStack dependencyStack)
      throws BuildTargetException {

    ForwardRelPath buildFile =
        cell.getBuckConfigView(ParserConfig.class)
            .getRelativePathToBuildFile(cell, buildTarget, dependencyStack);

    return cache.getJobWithCacheLookup(
        cell,
        buildTarget,
        () ->
            Futures.transformAsync(
                MoreFutures.combinedFutures(
                    packagePipeline.getPackageJob(cell, buildFile),
                    buildTargetRawNodeParsePipeline.getNodeJob(cell, buildTarget, dependencyStack),
                    executorService),
                resultingPair -> {
                  RawTargetNode rawAttributes = resultingPair.getSecond();
                  return dispatchComputeNode(
                      cell, buildTarget, dependencyStack, rawAttributes, resultingPair.getFirst());
                },
                executorService));
  }

  private ListenableFuture<UnconfiguredTargetNode> dispatchComputeNode(
      Cell cell,
      UnconfiguredBuildTarget buildTarget,
      DependencyStack dependencyStack,
      RawTargetNode from,
      Package pkg)
      throws BuildTargetException {
    if (shuttingDown()) {
      return Futures.immediateCancelledFuture();
    }
    UnconfiguredTargetNode result;

    try (Scope scope =
        SimplePerfEvent.scopeIgnoringShortEvents(
            eventBus.isolated(),
            perfEventTitle,
            "target",
            buildTarget,
            perfEventScope,
            minimumPerfEventTimeMs,
            TimeUnit.MILLISECONDS)) {
      result =
          unconfiguredTargetNodeFactory.create(
              cell,
              cell.getBuckConfigView(ParserConfig.class)
                  .getAbsolutePathToBuildFile(cell, buildTarget, dependencyStack),
              buildTarget,
              dependencyStack,
              from,
              pkg);
    }
    return Futures.immediateFuture(result);
  }

  @Override
  public void close() {
    perfEventScope.close();
    shuttingDown.set(true);

    // `packagePipeline` was created prior to this object getting created and also holds on to its
    // own `perfEventScope`.
    // We need to `close()` it after we close our local `perfEventScope` so the perf event scopes
    // nest correctly.
    packagePipeline.close();

    // At this point external callers should not schedule more work, internally job creation
    // should also stop. Any scheduled futures should eventually cancel themselves (all of the
    // AsyncFunctions that interact with the Cache are wired to early-out if `shuttingDown` is
    // true).
    // We could block here waiting for all ongoing work to complete, however the user has already
    // gotten everything they want out of the pipeline, so the only interesting thing that could
    // happen here are exceptions thrown by the ProjectBuildFileParser as its shutting down. These
    // aren't critical enough to warrant bringing down the entire process, as they don't affect the
    // state that has already been extracted from the parser.
  }

  private boolean shuttingDown() {
    return shuttingDown.get();
  }
}
