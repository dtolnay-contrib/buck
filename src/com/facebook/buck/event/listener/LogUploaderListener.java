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

import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.event.CommandEvent;
import com.facebook.buck.event.chrome_trace.ChromeTraceBuckConfig;
import com.facebook.buck.support.bgtasks.BackgroundTask;
import com.facebook.buck.support.bgtasks.BackgroundTask.Timeout;
import com.facebook.buck.support.bgtasks.TaskAction;
import com.facebook.buck.support.bgtasks.TaskManagerCommandScope;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.trace.uploader.launcher.UploaderLauncher;
import com.facebook.buck.util.trace.uploader.types.CompressionType;
import com.facebook.buck.util.trace.uploader.types.TraceKind;
import com.google.common.eventbus.Subscribe;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** Upload the buck log file to the trace endpoint when the build is finished. */
public class LogUploaderListener implements BuckEventListener {

  private final ChromeTraceBuckConfig config;
  private Optional<ExitCode> commandExitCode = Optional.empty();
  private final Path logFilePath;
  private final Path logDirectoryPath;
  private final BuildId buildId;
  private final TaskManagerCommandScope managerScope;
  private final TraceKind traceKind;

  public LogUploaderListener(
      ChromeTraceBuckConfig config,
      Path logFilePath,
      Path logDirectoryPath,
      BuildId buildId,
      TaskManagerCommandScope managerScope,
      TraceKind traceKind) {
    this.config = config;
    this.logFilePath = logFilePath;
    this.logDirectoryPath = logDirectoryPath;
    this.buildId = buildId;
    this.managerScope = managerScope;
    this.traceKind = traceKind;
  }

  @Subscribe
  public synchronized void commandFinished(CommandEvent.Finished finished) {
    commandExitCode = Optional.of(finished.getExitCode());
  }

  @Subscribe
  public synchronized void commandInterrupted(CommandEvent.Interrupted interrupted) {
    commandExitCode = Optional.of(interrupted.getExitCode());
  }

  @Override
  public synchronized void close() {
    Optional<URI> traceUploadUri = config.getTraceUploadUri();
    if (!traceUploadUri.isPresent()
        || !config.getLogUploadMode().shouldUploadLogs(commandExitCode)) {
      return;
    }

    LogUploaderListenerCloseArgs args =
        ImmutableLogUploaderListenerCloseArgs.ofImpl(
            traceUploadUri.get(), logDirectoryPath, logFilePath, buildId);
    BackgroundTask<LogUploaderListenerCloseArgs> task =
        BackgroundTask.of(
            "LogUploaderListener_close",
            new LogUploaderListenerCloseAction(traceKind),
            args,
            Timeout.of(config.getMaxUploadTimeoutInSeconds(), TimeUnit.SECONDS));
    managerScope.schedule(task);
  }

  /**
   * {@link TaskAction} implementation for close() in {@link LogUploaderListener}. Uploads log file
   * in background.
   */
  static class LogUploaderListenerCloseAction implements TaskAction<LogUploaderListenerCloseArgs> {

    private final TraceKind traceKind;

    public LogUploaderListenerCloseAction(TraceKind traceKind) {
      this.traceKind = traceKind;
    }

    @Override
    public void run(LogUploaderListenerCloseArgs args) throws InterruptedException, IOException {
      Path logFile = args.getLogDirectoryPath().resolve("upload_" + traceKind + ".log");
      Process uploadProcess =
          UploaderLauncher.uploadInBackground(
              args.getBuildId(),
              args.getLogFilePath(),
              traceKind,
              args.getTraceUploadURI(),
              logFile,
              CompressionType.NONE);
      UploaderLauncher.waitForProcessToFinish(uploadProcess);
    }
  }

  /** Task arguments passed to {@link LogUploaderListenerCloseAction}. */
  @BuckStyleValue
  abstract static class LogUploaderListenerCloseArgs {

    public abstract URI getTraceUploadURI();

    public abstract Path getLogDirectoryPath();

    public abstract Path getLogFilePath();

    public abstract BuildId getBuildId();
  }
}
