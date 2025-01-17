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

package com.facebook.buck.event.listener;

import com.facebook.buck.artifact_cache.ArtifactCacheEvent;
import com.facebook.buck.artifact_cache.CacheResultType;
import com.facebook.buck.artifact_cache.HttpArtifactCacheEvent;
import com.facebook.buck.core.build.event.BuildRuleEvent;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.BuildRuleKeys;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.log.InvocationInfo;
import com.facebook.buck.logd.client.LogStreamFactory;
import com.facebook.buck.logd.proto.LogType;
import com.facebook.buck.support.bgtasks.BackgroundTask;
import com.facebook.buck.support.bgtasks.TaskAction;
import com.facebook.buck.support.bgtasks.TaskManagerCommandScope;
import com.facebook.buck.support.build.report.RuleKeyLogFileUploader;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.ThrowingPrintWriter;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.eventbus.Subscribe;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.annotation.concurrent.GuardedBy;

public class RuleKeyLoggerListener implements BuckEventListener {
  private static final Logger LOG = Logger.get(RuleKeyLoggerListener.class);

  private static final int DEFAULT_MIN_LINES_FOR_AUTO_FLUSH = 100;

  private final InvocationInfo info;
  private final ExecutorService outputExecutor;
  private final int minLinesForAutoFlush;
  private final ProjectFilesystem projectFilesystem;
  private final TaskManagerCommandScope managerScope;
  private final Optional<RuleKeyLogFileUploader> ruleKeyLogFileUploader;
  private final LogStreamFactory logStreamFactory;

  private final Object lock;

  @GuardedBy("lock")
  private List<String> logLines;

  public RuleKeyLoggerListener(
      ProjectFilesystem projectFilesystem,
      InvocationInfo info,
      ExecutorService outputExecutor,
      TaskManagerCommandScope managerScope,
      Optional<RuleKeyLogFileUploader> ruleKeyLogFileUploader,
      LogStreamFactory logStreamFactory) {
    this(
        projectFilesystem,
        info,
        outputExecutor,
        managerScope,
        ruleKeyLogFileUploader,
        logStreamFactory,
        DEFAULT_MIN_LINES_FOR_AUTO_FLUSH);
  }

  @VisibleForTesting
  public RuleKeyLoggerListener(
      ProjectFilesystem projectFilesystem,
      InvocationInfo info,
      ExecutorService outputExecutor,
      TaskManagerCommandScope managerScope,
      Optional<RuleKeyLogFileUploader> ruleKeyLogFileUploader,
      LogStreamFactory logStreamFactory,
      int minLinesForAutoFlush) {
    this.projectFilesystem = projectFilesystem;
    this.minLinesForAutoFlush = minLinesForAutoFlush;
    this.info = info;
    this.lock = new Object();
    this.outputExecutor = outputExecutor;
    this.logLines = new ArrayList<>();
    this.managerScope = managerScope;
    this.ruleKeyLogFileUploader = ruleKeyLogFileUploader;
    this.logStreamFactory = logStreamFactory;
  }

  @Subscribe
  public void onArtifactCacheEvent(HttpArtifactCacheEvent.Finished event) {
    if (event.getOperation() != ArtifactCacheEvent.Operation.FETCH
        || !event.getCacheResult().isPresent()) {
      return;
    }

    CacheResultType cacheResultType = event.getCacheResult().get().getType();
    if (cacheResultType != CacheResultType.MISS && cacheResultType != CacheResultType.ERROR) {
      return;
    }

    List<String> newLogLines =
        event.getRuleKeys().stream()
            .map(key -> String.format("http\t%s\t%s", key.toString(), cacheResultType.toString()))
            .collect(Collectors.toList());
    synchronized (lock) {
      logLines.addAll(newLogLines);
    }

    flushLogLinesIfNeeded();
  }

  @Subscribe
  public void onBuildRuleEvent(BuildRuleEvent.Finished event) {
    BuildRuleKeys ruleKeys = event.getRuleKeys();
    BuildTarget target = event.getBuildRule().getBuildTarget();
    String ruleKeyLine = toTsv(target, ruleKeys.getRuleKey());
    String inputRuleKeyLine = null;
    if (ruleKeys.getInputRuleKey().isPresent()) {
      inputRuleKeyLine = toTsv(target, ruleKeys.getInputRuleKey().get());
    }

    synchronized (lock) {
      logLines.add(ruleKeyLine);
      if (inputRuleKeyLine != null) {
        logLines.add(inputRuleKeyLine);
      }
    }

    flushLogLinesIfNeeded();
  }

  private static String toTsv(BuildTarget target, RuleKey ruleKey) {
    return String.format("target\t%s\t%s", target.toString(), ruleKey.toString());
  }

  @VisibleForTesting
  Path getLogFilePath() {
    Path logDir = projectFilesystem.resolve(info.getLogDirectoryPath());
    return logDir.resolve(BuckConstant.RULE_KEY_LOGGER_FILE_NAME);
  }

  private void flushLogLinesIfNeeded() {
    synchronized (lock) {
      if (logLines.size() > minLinesForAutoFlush) {
        submitFlushLogLines();
      }
    }
  }

  private void submitFlushLogLines() {
    synchronized (lock) {
      List<String> linesToFlush = logLines;
      logLines = new ArrayList<>();
      if (!linesToFlush.isEmpty()) {
        outputExecutor.execute(() -> actuallyFlushLogLines(linesToFlush));
      }
    }
  }

  private void actuallyFlushLogLines(List<String> linesToFlush) {
    Path path = getLogFilePath();
    try {
      projectFilesystem.createParentDirs(path);
      try (OutputStream os =
              logStreamFactory.createLogStream(path.toString(), LogType.RULE_KEY_LOG);
          ThrowingPrintWriter out = new ThrowingPrintWriter(os, StandardCharsets.UTF_8)) {
        for (String line : linesToFlush) {
          out.println(line);
        }
      }
    } catch (IOException e) {
      LOG.error(e, "Failed to flush [%d] logLines to file [%s].", linesToFlush.size(), path);
    }
  }

  @Override
  public void close() {
    submitFlushLogLines();

    RuleKeyLoggerListenerCloseArgs args =
        ImmutableRuleKeyLoggerListenerCloseArgs.ofImpl(
            outputExecutor,
            ruleKeyLogFileUploader,
            info.getLogDirectoryPath().resolve(BuckConstant.RULE_KEY_LOGGER_FILE_NAME));

    BackgroundTask<RuleKeyLoggerListenerCloseArgs> task =
        BackgroundTask.of(
            "RuleKeyLoggerListener_close", new RuleKeyLoggerListenerCloseAction(), args);
    managerScope.schedule(task);
  }

  /**
   * {@link TaskAction} implementation for {@link RuleKeyLoggerListener}. Waits for listener's
   * output executor to finish and close.
   */
  static class RuleKeyLoggerListenerCloseAction
      implements TaskAction<RuleKeyLoggerListenerCloseArgs> {
    @Override
    public void run(RuleKeyLoggerListenerCloseArgs args) {
      args.getOutputExecutor().shutdown();
      try {
        args.getOutputExecutor().awaitTermination(1, TimeUnit.HOURS);
        args.getRuleKeyLogFileUploader()
            .ifPresent(uploader -> uploader.uploadRuleKeyLogFile(args.getRuleKeyLogFilePath()));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /** Arguments to {@link RuleKeyLoggerListenerCloseAction}. */
  @BuckStyleValue
  abstract static class RuleKeyLoggerListenerCloseArgs {
    public abstract ExecutorService getOutputExecutor();

    public abstract Optional<RuleKeyLogFileUploader> getRuleKeyLogFileUploader();

    public abstract Path getRuleKeyLogFilePath();
  }
}
