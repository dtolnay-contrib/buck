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

package com.facebook.buck.io.watchman;

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.ForwardRelPath;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.event.WatchmanStatusEvent;
import com.facebook.buck.io.file.PathMatcher;
import com.facebook.buck.io.watchman.WatchmanEvent.Type;
import com.facebook.buck.util.Threads;
import com.facebook.buck.util.concurrent.MostExecutors;
import com.facebook.buck.util.types.Either;
import com.facebook.buck.util.types.Unit;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.eventbus.EventBus;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Queries Watchman for changes to a path. */
public class WatchmanWatcher {

  // Action to take if Watchman indicates a fresh instance (which happens
  // both on the first buckd command as well as if Watchman needs to recrawl
  // for any reason).
  public enum FreshInstanceAction {
    NONE,
    POST_OVERFLOW_EVENT
  }

  private static final Logger LOG = Logger.get(WatchmanWatcher.class);
  /**
   * The maximum number of watchman changes to process in each call to postEvents before giving up
   * and generating an overflow. The goal is to be able to process a reasonable number of human
   * generated changes quickly, but not spend a long time processing lots of changes after a branch
   * switch which will end up invalidating the entire cache anyway. If overflow is negative calls to
   * postEvents will just generate a single overflow event.
   */
  private static final int OVERFLOW_THRESHOLD = 10_000;

  /** Attach changed files to the perf trace, if there aren't too many. */
  private static final int TRACE_CHANGES_THRESHOLD = 10;

  private final EventBus fileChangeEventBus;
  private final WatchmanClient watchmanClient;
  private final int numThreads;

  private final long timeoutNanos;
  private final long queryWarnTimeoutNanos;

  private static class CellQueryState {
    private final WatchmanWatcherQuery query;
    /** This one is mutable. */
    private final WatchmanCursor cursor;

    public CellQueryState(WatchmanWatcherQuery query, WatchmanCursor cursor) {
      Preconditions.checkArgument(query != null);
      Preconditions.checkArgument(cursor != null);
      this.query = query;
      this.cursor = cursor;
    }

    private WatchmanQuery.Query toQuery() {
      return query.toQuery(cursor.get());
    }
  }

  private final ImmutableMap<AbsPath, CellQueryState> queriesByCell;

  public WatchmanWatcher(
      Watchman watchman,
      EventBus fileChangeEventBus,
      ImmutableSet<PathMatcher> ignorePaths,
      Map<AbsPath, WatchmanCursor> cursors,
      int numThreads) {
    this(
        fileChangeEventBus,
        watchman.getPooledClient(),
        watchman.getQueryPollTimeoutNanos(),
        watchman.getQueryWarnTimeoutNanos(),
        createQueries(watchman.getProjectWatches(), ignorePaths, watchman.getCapabilities()),
        cursors,
        numThreads);
  }

  @VisibleForTesting
  WatchmanWatcher(
      EventBus fileChangeEventBus,
      WatchmanClient watchmanClient,
      long timeoutNanos,
      long queryWarnTimeoutNanos,
      ImmutableMap<AbsPath, WatchmanWatcherQuery> queries,
      Map<AbsPath, WatchmanCursor> cursors,
      int numThreads) {
    Preconditions.checkArgument(
        queries.keySet().equals(cursors.keySet()),
        "watchman query keys %s should be equal to watchman cursor keys %s",
        queries.keySet(),
        cursors.keySet());

    this.fileChangeEventBus = fileChangeEventBus;
    this.watchmanClient = watchmanClient;
    this.timeoutNanos = timeoutNanos;
    this.queryWarnTimeoutNanos = queryWarnTimeoutNanos;
    this.numThreads = numThreads;

    this.queriesByCell =
        queries.keySet().stream()
            .collect(
                ImmutableMap.toImmutableMap(
                    k -> k, k -> new CellQueryState(queries.get(k), cursors.get(k))));
  }

  @VisibleForTesting
  static ImmutableMap<AbsPath, WatchmanWatcherQuery> createQueries(
      ImmutableMap<AbsPath, ProjectWatch> projectWatches,
      ImmutableSet<PathMatcher> ignorePaths,
      Set<Capability> watchmanCapabilities) {
    ImmutableMap.Builder<AbsPath, WatchmanWatcherQuery> watchmanQueryBuilder =
        ImmutableMap.builder();
    for (Map.Entry<AbsPath, ProjectWatch> entry : projectWatches.entrySet()) {
      watchmanQueryBuilder.put(
          entry.getKey(), createQuery(entry.getValue(), ignorePaths, watchmanCapabilities));
    }
    return watchmanQueryBuilder.build();
  }

  @VisibleForTesting
  static WatchmanWatcherQuery createQuery(
      ProjectWatch projectWatch,
      ImmutableSet<PathMatcher> ignorePaths,
      Set<Capability> watchmanCapabilities) {
    WatchRoot watchRoot = projectWatch.getWatchRoot();
    ForwardRelPath watchPrefix = projectWatch.getProjectPrefix();

    // Exclude any expressions added to this list.
    List<Object> excludeAnyOf = Lists.newArrayList("anyof");

    // Exclude all directories.
    excludeAnyOf.add(Lists.newArrayList("type", "d"));

    // Exclude all files under directories in project.ignorePaths.
    //
    // Note that it's OK to exclude .git in a query (event though it's
    // not currently OK to exclude .git in .watchmanconfig). This id
    // because watchman's .git cookie magic is done before the query
    // is applied.
    for (PathMatcher ignorePathOrGlob : ignorePaths) {
      excludeAnyOf.add(ignorePathOrGlob.toWatchmanMatchQuery(watchmanCapabilities));
    }

    return ImmutableWatchmanWatcherQuery.ofImpl(
        watchRoot,
        ImmutableList.of("not", excludeAnyOf),
        ImmutableList.of("name", "exists", "new", "type"),
        watchPrefix);
  }

  @VisibleForTesting
  Optional<WatchmanQuery.Query> getWatchmanQuery(AbsPath cellPath) {
    CellQueryState state = queriesByCell.get(cellPath);
    return Optional.ofNullable(state.toQuery());
  }

  /**
   * Query Watchman for file change events. If too many events are pending or an error occurs an
   * overflow event is posted to the EventBus signalling that events may have been lost (and so
   * typically caches must be cleared to avoid inconsistency). Interruptions and IOExceptions are
   * propagated to callers, but typically if overflow events are handled conservatively by
   * subscribers then no other remedial action is required.
   *
   * <p>Any diagnostics posted by Watchman are added to watchmanDiagnosticCache.
   */
  public void postEvents(BuckEventBus buckEventBus, FreshInstanceAction freshInstanceAction)
      throws IOException, InterruptedException {
    // Speculatively set to false
    AtomicBoolean filesHaveChanged = new AtomicBoolean(false);
    ExecutorService executorService =
        MostExecutors.newMultiThreadExecutor(getClass().getName(), numThreads);
    buckEventBus.post(WatchmanStatusEvent.started());

    try {
      ConcurrentLinkedQueue<WatchmanWatcherOneBigEvent> bigEvents = new ConcurrentLinkedQueue<>();
      List<Callable<Unit>> watchmanQueries = new ArrayList<>();
      for (Map.Entry<AbsPath, CellQueryState> e : queriesByCell.entrySet()) {
        AbsPath cellPath = e.getKey();
        CellQueryState state = e.getValue();
        watchmanQueries.add(
            () -> {
              WatchmanWatcherQuery query = state.query;
              WatchmanCursor cursor = state.cursor;
              try (SimplePerfEvent.Scope perfEvent =
                  SimplePerfEvent.scope(
                      buckEventBus.isolated(),
                      SimplePerfEvent.PerfEventTitle.of("check_watchman"),
                      "cell",
                      cellPath)) {
                // Include the cellPath in the finished event so it can be matched with the begin
                // event.
                perfEvent.appendFinishedInfo("cell", cellPath);
                postEvents(
                    buckEventBus,
                    freshInstanceAction,
                    cellPath,
                    watchmanClient,
                    query,
                    cursor,
                    filesHaveChanged,
                    perfEvent,
                    bigEvents);
              }
              return Unit.UNIT;
            });
      }

      // Run all of the Watchman queries in parallel. This can be significant if you have a lot of
      // cells.
      List<Future<Unit>> futures = executorService.invokeAll(watchmanQueries);
      List<ExecutionException> exceptions = new ArrayList<>();
      for (Future<Unit> future : futures) {
        try {
          future.get();
        } catch (ExecutionException e) {
          exceptions.add(e);
        }
      }

      WatchmanWatcherOneBigEvent bigEvent = WatchmanWatcherOneBigEvent.merge(bigEvents);
      if (!bigEvent.isEmpty()) {
        fileChangeEventBus.post(bigEvent);
      }

      for (ExecutionException exception : exceptions) {
        Throwable cause = exception.getCause();
        if (cause != null) {
          Throwables.throwIfUnchecked(cause);
          Throwables.propagateIfPossible(cause, IOException.class);
          Throwables.propagateIfPossible(cause, InterruptedException.class);
        }
        throw new RuntimeException(exception);
      }

      if (!filesHaveChanged.get()) {
        buckEventBus.post(WatchmanStatusEvent.zeroFileChanges());
      }
    } finally {
      buckEventBus.post(WatchmanStatusEvent.finished());
      executorService.shutdown();
    }
  }

  @SuppressWarnings("unchecked")
  private void postEvents(
      BuckEventBus buckEventBus,
      FreshInstanceAction freshInstanceAction,
      AbsPath cellPath,
      WatchmanClient client,
      WatchmanWatcherQuery query,
      WatchmanCursor cursor,
      AtomicBoolean filesHaveChanged,
      SimplePerfEvent.Scope perfEvent,
      ConcurrentLinkedQueue<WatchmanWatcherOneBigEvent> bigEvents)
      throws IOException, InterruptedException {
    try {
      Either<WatchmanQueryResp.Generic, WatchmanClient.Timeout> queryResponse;
      try (SimplePerfEvent.Scope ignored =
          SimplePerfEvent.scope(buckEventBus.isolated(), "query")) {
        queryResponse =
            client.queryWithTimeout(
                timeoutNanos, queryWarnTimeoutNanos, query.toQuery(cursor.get()));
      } catch (WatchmanQueryFailedException e1) {
        // This message is not de-duplicated via WatchmanDiagnostic.
        WatchmanWatcherException e = new WatchmanWatcherException(e1);
        LOG.debug(e, "Error in Watchman output. Posting an overflow event to flush the caches");
        WatchmanOverflowEvent overflow =
            ImmutableWatchmanOverflowEvent.ofImpl(
                cellPath, "Watchman error occurred: " + e.getMessage());
        postWatchEvent(buckEventBus, overflow);
        bigEvents.add(WatchmanWatcherOneBigEvent.overflow(overflow));
        throw e;
      }

      try (SimplePerfEvent.Scope ignored =
          SimplePerfEvent.scope(buckEventBus.isolated(), "process_response")) {
        if (!queryResponse.isLeft()) {
          LOG.warn(
              "Could not get response from Watchman for query %s within %d ms",
              query, TimeUnit.NANOSECONDS.toMillis(timeoutNanos));
          WatchmanOverflowEvent overflow =
              ImmutableWatchmanOverflowEvent.ofImpl(
                  cellPath,
                  "Timed out after "
                      + TimeUnit.NANOSECONDS.toSeconds(timeoutNanos)
                      + " sec waiting for watchman query.");
          postWatchEvent(buckEventBus, overflow);
          bigEvents.add(WatchmanWatcherOneBigEvent.overflow(overflow));
          filesHaveChanged.set(true);
          return;
        }

        WatchmanQueryResp.Generic response = queryResponse.getLeft();

        if (cursor.get().startsWith("c:")) {
          // Update the clockId
          String newCursor =
              Optional.ofNullable((String) response.getResp().get("clock"))
                  .orElse(WatchmanFactory.NULL_CLOCK);
          LOG.debug("Updating Watchman Cursor from %s to %s", cursor.get(), newCursor);
          cursor.set(newCursor);
        }

        Boolean isFreshInstance = (Boolean) response.getResp().get("is_fresh_instance");
        if (isFreshInstance != null && isFreshInstance) {
          LOG.debug(
              "Watchman indicated a fresh instance (fresh instance action %s)",
              freshInstanceAction);
          switch (freshInstanceAction) {
            case NONE:
              break;
            case POST_OVERFLOW_EVENT:
              ImmutableWatchmanOverflowEvent overflow =
                  ImmutableWatchmanOverflowEvent.ofImpl(
                      cellPath, "Watchman has been initialized recently.");
              postWatchEvent(buckEventBus, overflow);
              bigEvents.add(WatchmanWatcherOneBigEvent.overflow(overflow));
              break;
          }
          filesHaveChanged.set(true);
          return;
        }

        List<Map<String, Object>> files =
            (List<Map<String, Object>>) response.getResp().get("files");
        if (files == null) {
          if (freshInstanceAction == FreshInstanceAction.NONE) {
            filesHaveChanged.set(true);
          }
          return;
        }
        LOG.debug("Watchman indicated %d changes", files.size());
        if (files.size() > OVERFLOW_THRESHOLD) {
          LOG.warn(
              "Posting overflow event: too many files changed: %d > %d",
              files.size(), OVERFLOW_THRESHOLD);
          WatchmanOverflowEvent overflow =
              ImmutableWatchmanOverflowEvent.ofImpl(cellPath, "Too many files changed.");
          postWatchEvent(buckEventBus, overflow);
          bigEvents.add(WatchmanWatcherOneBigEvent.overflow(overflow));
          filesHaveChanged.set(true);
          return;
        }
        if (files.size() < TRACE_CHANGES_THRESHOLD) {
          perfEvent.appendFinishedInfo("files", files);
        } else {
          perfEvent.appendFinishedInfo("files_sample", files.subList(0, TRACE_CHANGES_THRESHOLD));
        }

        ImmutableList.Builder<WatchmanPathEvent> pathEvents = ImmutableList.builder();

        for (Map<String, Object> file : files) {
          String fileName = (String) file.get("name");
          if (fileName == null) {
            LOG.warn("Filename missing from watchman file response %s", file);
            WatchmanOverflowEvent overflow =
                ImmutableWatchmanOverflowEvent.ofImpl(
                    cellPath, "Filename missing from watchman response.");
            postWatchEvent(buckEventBus, overflow);
            bigEvents.add(WatchmanWatcherOneBigEvent.overflow(overflow));
            filesHaveChanged.set(true);
            return;
          }
          Boolean fileNew = (Boolean) file.get("new");
          WatchmanEvent.Kind kind = WatchmanEvent.Kind.MODIFY;
          if (fileNew != null && fileNew) {
            kind = WatchmanEvent.Kind.CREATE;
          }
          Boolean fileExists = (Boolean) file.get("exists");
          if (fileExists != null && !fileExists) {
            kind = WatchmanEvent.Kind.DELETE;
          }

          // Following legacy behavior, everything we get from Watchman is interpreted as file
          // changes unless explicitly specified with `type` field
          WatchmanEvent.Type type = Type.FILE;
          String stype = (String) file.get("type");
          if (stype != null) {
            switch (stype) {
              case "d":
                type = Type.DIRECTORY;
                break;
              case "l":
                type = Type.SYMLINK;
                break;
            }
          }

          ForwardRelPath filePath = ForwardRelPath.of(fileName);

          if (type != WatchmanEvent.Type.DIRECTORY) {
            // WatchmanPathEvent is sent for everything but directories - this is legacy
            // behavior and we want to keep it.
            // TODO(buck_team): switch everything to use WatchmanMultiplePathEvent and retire
            // WatchmanPathEvent
            ImmutableWatchmanPathEvent pathEvent =
                ImmutableWatchmanPathEvent.ofImpl(cellPath, kind, filePath);
            postWatchEvent(buckEventBus, pathEvent);
            pathEvents.add(pathEvent);
          }
        }

        ImmutableList<WatchmanPathEvent> pathEventsList = pathEvents.build();
        if (!pathEventsList.isEmpty()) {
          bigEvents.add(WatchmanWatcherOneBigEvent.pathEvents(pathEventsList));
        }

        if (!files.isEmpty() || freshInstanceAction == FreshInstanceAction.NONE) {
          filesHaveChanged.set(true);
        }
      }
    } catch (InterruptedException e) {
      String message = "The communication with watchman daemon has been interrupted.";
      LOG.warn(e, message);
      // Events may have been lost, signal overflow.
      ImmutableWatchmanOverflowEvent overflow =
          ImmutableWatchmanOverflowEvent.ofImpl(cellPath, message);
      postWatchEvent(buckEventBus, overflow);
      bigEvents.add(WatchmanWatcherOneBigEvent.overflow(overflow));
      Threads.interruptCurrentThread();
      throw e;
    } catch (WatchmanWatcherException e) {
      // handled above
      throw e;
    } catch (Throwable e) {
      String message =
          "There was an error while communicating with the watchman daemon: " + e.getMessage();
      LOG.error(e, message);
      // Events may have been lost, signal overflow.
      WatchmanOverflowEvent overflow = ImmutableWatchmanOverflowEvent.ofImpl(cellPath, message);
      postWatchEvent(buckEventBus, overflow);
      bigEvents.add(WatchmanWatcherOneBigEvent.overflow(overflow));
      throw e;
    }
  }

  private void postWatchEvent(BuckEventBus eventBus, WatchmanEvent event) {
    LOG.debug("Posting WatchEvent: %s", event);
    fileChangeEventBus.post(event);

    // Post analogous Status events for logging/status.
    if (event instanceof WatchmanOverflowEvent) {
      WatchmanOverflowEvent overflowEvent = (WatchmanOverflowEvent) event;

      eventBus.post(
          WatchmanStatusEvent.overflow(
              overflowEvent.getReason(), overflowEvent.getCellPath().getPath()));
    } else if (event instanceof WatchmanPathEvent) {
      WatchmanPathEvent pathEvent = (WatchmanPathEvent) event;
      switch (pathEvent.getKind()) {
        case CREATE:
          eventBus.post(WatchmanStatusEvent.fileCreation(pathEvent.toString()));
          return;
        case DELETE:
          eventBus.post(WatchmanStatusEvent.fileDeletion(pathEvent.toString()));
          return;
        case MODIFY:
          // No analog for this event.
          return;
      }
      throw new IllegalStateException("Unhandled case: " + pathEvent.getKind());
    }
  }
}
