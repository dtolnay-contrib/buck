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

package com.facebook.buck.worker;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutor.LaunchedProcess;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.string.MoreStrings;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;

/**
 * Default implementation of {@link WorkerProcess} interface that implements {@link
 * WorkerProcessProtocol.CommandSender} protocol based on communication via JSON stream and via
 * files.
 */
public class DefaultWorkerProcess implements WorkerProcess {

  private static final Logger LOG = Logger.get(DefaultWorkerProcess.class);

  private final ProcessExecutor executor;
  private final ProcessExecutorParams processParams;
  private final ProjectFilesystem filesystem;
  private final Path tmpPath;
  private final Path stdErr;
  private final AtomicInteger currentMessageId = new AtomicInteger();
  private boolean handshakePerformed = false;
  @Nullable private WorkerProcessProtocol.CommandSender protocol;
  @Nullable private LaunchedProcess launchedProcess;

  /**
   * Worker process is a process that stays alive and receives commands which describe jobs. Worker
   * processes may be combined into pools so they can perform different jobs concurrently. It
   * communicates via JSON stream and via files. Submitted job blocks the calling thread until it
   * receives the result back. Worker process must understand the protocol that Buck will use to
   * communicate with it.
   *
   * @param executor Process executor that will start worker process.
   * @param processParams Arguments for process executor.
   * @param filesystem File system for the worker process.
   * @param stdErr path where stderr of a process is kept
   * @param tmpPath Temp folder.
   */
  public DefaultWorkerProcess(
      ProcessExecutor executor,
      ProcessExecutorParams processParams,
      ProjectFilesystem filesystem,
      Path stdErr,
      Path tmpPath) {
    this.executor = executor;
    this.stdErr = stdErr;
    this.processParams =
        processParams.withRedirectError(ProcessBuilder.Redirect.to(stdErr.toFile()));
    this.filesystem = filesystem;
    this.tmpPath = tmpPath;
  }

  @Override
  public boolean isAlive() {
    return launchedProcess != null && launchedProcess.isAlive();
  }

  /** Ensures process is launched and handshake has been established. */
  public synchronized void ensureLaunchAndHandshake() throws IOException {
    if (handshakePerformed) {
      return;
    }
    LOG.debug(
        "Starting up process %d using command: '%s'",
        this.hashCode(), Joiner.on(' ').join(processParams.getCommand()));
    launchedProcess = executor.launchProcess(processParams);
    protocol =
        new WorkerProcessProtocolZero.CommandSender(
            launchedProcess.getStdin(),
            launchedProcess.getStdout(),
            stdErr,
            () -> {
              if (launchedProcess != null) {
                launchedProcess.close();
              }
            },
            () -> launchedProcess != null && launchedProcess.isAlive());

    LOG.debug("Handshaking with process %d", this.hashCode());
    protocol.handshake(currentMessageId.getAndIncrement());
    handshakePerformed = true;
  }

  /** Submits a command to a worker tool process and waits for a result. */
  public synchronized WorkerJobResult submitAndWaitForJob(String jobArgs) throws IOException {
    Preconditions.checkState(
        protocol != null,
        "Tried to submit a job to the worker process before the handshake was performed.");

    int messageId = currentMessageId.getAndAdd(1);
    Path argsPath = Paths.get(tmpPath.toString(), String.format("%d.args", messageId));
    Path stdoutPath = Paths.get(tmpPath.toString(), String.format("%d.out", messageId));
    Path stderrPath = Paths.get(tmpPath.toString(), String.format("%d.err", messageId));
    filesystem.deleteFileAtPathIfExists(stdoutPath);
    filesystem.deleteFileAtPathIfExists(stderrPath);
    filesystem.writeContentsToPath(jobArgs, argsPath);

    LOG.debug(
        "Sending job %d to process %d \n" + " job arguments: '%s'",
        messageId, this.hashCode(), jobArgs);
    protocol.send(
        messageId, ImmutableWorkerProcessCommand.ofImpl(argsPath, stdoutPath, stderrPath));
    LOG.debug("Receiving response for job %d from process %d", messageId, this.hashCode());
    int exitCode = protocol.receiveCommandResponse(messageId);
    Optional<String> stdout = filesystem.readFileIfItExists(stdoutPath);
    Optional<String> stderr = filesystem.readFileIfItExists(stderrPath);
    LOG.debug(
        "Job %d for process %d finished \n"
            + "  exit code: %d \n"
            + "  stdout: %s \n"
            + "  stderr: %s",
        messageId, this.hashCode(), exitCode, stdout.orElse(""), stderr.orElse(""));

    return WorkerJobResult.of(exitCode, stdout, stderr);
  }

  @Override
  public synchronized void close() {
    LOG.debug("Closing process %d", this.hashCode());
    try {
      if (protocol != null) {
        protocol.close();
      }
      Files.deleteIfExists(stdErr);
    } catch (Exception e) {
      LOG.debug(e, "Error closing worker process %s.", processParams.getCommand());

      LOG.debug("Worker process stderr at %s", this.stdErr.toString());

      try {
        String workerStderr =
            MoreStrings.truncatePretty(filesystem.readFileIfItExists(this.stdErr).orElse(""))
                .trim()
                .replace("\n", "\nstderr: ");
        LOG.error(
            "Worker process "
                + Joiner.on(' ').join(processParams.getCommand())
                + " failed. stderr: %s",
            workerStderr);
      } catch (Throwable t) {
        LOG.error(t, "Couldn't read stderr on failing close!");
      }

      throw new HumanReadableException(
          e,
          "Error while trying to close the worker process %s.",
          Joiner.on(' ').join(processParams.getCommand()));
    }
  }

  @VisibleForTesting
  void setProtocol(WorkerProcessProtocol.CommandSender protocolMock) {
    this.protocol = protocolMock;
  }
}
