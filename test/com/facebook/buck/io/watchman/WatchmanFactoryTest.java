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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.event.console.TestEventConsole;
import com.facebook.buck.io.FakeExecutableFinder;
import com.facebook.buck.io.watchman.WatchmanFactory.InitialWatchmanClientFactory;
import com.facebook.buck.util.FakeListeningProcessExecutor;
import com.facebook.buck.util.FakeListeningProcessState;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.bser.BserSerializer;
import com.facebook.buck.util.timing.SettableFakeClock;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class WatchmanFactoryTest {

  private final String root = Paths.get("/some/root").toAbsolutePath().toString();
  private final ImmutableSet<AbsPath> rootPaths = ImmutableSet.of(AbsPath.get(root));
  private final String exe = Paths.get("/opt/bin/watchman").toAbsolutePath().toString();
  private final FakeExecutableFinder finder = new FakeExecutableFinder(Paths.get(exe));
  private final ImmutableMap<String, String> env = ImmutableMap.of();
  private static final WatchmanQuery.Version VERSION_QUERY =
      WatchmanQuery.version(
          ImmutableList.of("cmd-watch-project"),
          ImmutableList.of(
              "term-dirname",
              "cmd-watch-project",
              "wildmatch",
              "wildmatch_multislash",
              "glob_generator",
              "clock-sync-timeout"));

  private static WatchmanFactory createFakeWatchmanFactory(
      Path socketName,
      long queryElapsedTimeNanos,
      ImmutableMap<WatchmanQuery<?>, ImmutableMap<String, Object>> queryResults) {
    InitialWatchmanClientFactory factory =
        (path, console, clock) -> {
          if (path.equals(socketName)) {
            return new FakeWatchmanClient(queryElapsedTimeNanos, queryResults);
          } else {
            throw new IOException(String.format("bad path (%s != %s", path, socketName));
          }
        };
    return new WatchmanFactory(factory);
  }

  private static ByteBuffer bserSerialized(Object obj) throws IOException {
    ByteBuffer buf = ByteBuffer.allocate(256).order(ByteOrder.nativeOrder());
    ByteBuffer result = new BserSerializer().serializeToBuffer(obj, buf);
    // Prepare the buffer for reading.
    result.flip();
    return result;
  }

  @Test
  public void shouldReturnEmptyWatchmanIfNotOnPath() throws InterruptedException {
    FakeExecutableFinder finder = new FakeExecutableFinder();
    SettableFakeClock clock = SettableFakeClock.DO_NOT_CARE;
    FakeListeningProcessExecutor executor =
        new FakeListeningProcessExecutor(ImmutableMultimap.of());
    WatchmanFactory watchmanFactory = new WatchmanFactory();
    Watchman watchman =
        watchmanFactory.build(
            executor,
            rootPaths,
            env,
            finder,
            new TestEventConsole(),
            clock,
            Optional.empty(),
            1_000,
            TimeUnit.SECONDS.toNanos(10),
            TimeUnit.SECONDS.toNanos(1));

    assertTrue(watchman instanceof WatchmanFactory.NullWatchman);
  }

  @Test
  public void shouldReturnEmptyWatchmanIfVersionCheckFails() throws InterruptedException {
    SettableFakeClock clock = SettableFakeClock.DO_NOT_CARE;
    FakeListeningProcessExecutor executor =
        new FakeListeningProcessExecutor(
            ImmutableMultimap.<ProcessExecutorParams, FakeListeningProcessState>builder()
                .putAll(
                    ProcessExecutorParams.ofCommand(exe, "--output-encoding=bser", "get-sockname"),
                    FakeListeningProcessState.ofExit(1))
                .build(),
            clock);

    WatchmanFactory watchmanFactory = new WatchmanFactory();
    Watchman watchman =
        watchmanFactory.build(
            executor,
            rootPaths,
            env,
            finder,
            new TestEventConsole(),
            clock,
            Optional.empty(),
            1_000,
            TimeUnit.SECONDS.toNanos(10),
            TimeUnit.SECONDS.toNanos(1));

    assertTrue(watchman instanceof WatchmanFactory.NullWatchman);
  }

  @Test
  public void shouldReturnNullWatchmanIfExtendedVersionCheckMissing()
      throws InterruptedException, IOException {
    SettableFakeClock clock = SettableFakeClock.DO_NOT_CARE;
    FakeListeningProcessExecutor executor =
        new FakeListeningProcessExecutor(
            ImmutableMultimap.<ProcessExecutorParams, FakeListeningProcessState>builder()
                .putAll(
                    ProcessExecutorParams.ofCommand(exe, "--output-encoding=bser", "get-sockname"),
                    FakeListeningProcessState.ofStdoutBytes(
                        bserSerialized(
                            ImmutableMap.of(
                                "version", "3.7.9",
                                "sockname", "/path/to/sock"))),
                    FakeListeningProcessState.ofExit(0))
                .build(),
            clock);

    WatchmanFactory watchmanFactory =
        createFakeWatchmanFactory(
            Paths.get("/path/to/sock"),
            0,
            ImmutableMap.of(
                VERSION_QUERY,
                ImmutableMap.of("version", "3.7.9"),
                WatchmanQuery.watch(root),
                ImmutableMap.of("version", "3.7.9", "watch", root)));
    Watchman watchman =
        watchmanFactory.build(
            executor,
            rootPaths,
            env,
            finder,
            new TestEventConsole(),
            clock,
            Optional.empty(),
            1_000,
            TimeUnit.SECONDS.toNanos(10),
            TimeUnit.SECONDS.toNanos(1));

    assertTrue(watchman instanceof WatchmanFactory.NullWatchman);
  }

  @Test
  public void shouldFailIfWatchProjectNotAvailable() throws InterruptedException, IOException {
    SettableFakeClock clock = SettableFakeClock.DO_NOT_CARE;
    FakeListeningProcessExecutor executor =
        new FakeListeningProcessExecutor(
            ImmutableMultimap.<ProcessExecutorParams, FakeListeningProcessState>builder()
                .putAll(
                    ProcessExecutorParams.ofCommand(exe, "--output-encoding=bser", "get-sockname"),
                    FakeListeningProcessState.ofStdoutBytes(
                        bserSerialized(
                            ImmutableMap.of(
                                "version", "3.8.0",
                                "sockname", "/path/to/sock"))),
                    FakeListeningProcessState.ofExit(0))
                .build(),
            clock);

    WatchmanFactory watchmanFactory =
        createFakeWatchmanFactory(
            Paths.get("/path/to/sock"),
            0,
            ImmutableMap.of(
                VERSION_QUERY,
                ImmutableMap.of(
                    "version",
                    "3.8.0",
                    "capabilities",
                    ImmutableMap.of(
                        "term-dirname", true,
                        "cmd-watch-project", false,
                        "wildmatch", false,
                        "wildmatch_multislash", false,
                        "glob_generator", false),
                    "error",
                    "client required capabilty `cmd-watch-project` is not supported by this "
                        + "server")));
    Watchman watchman =
        watchmanFactory.build(
            executor,
            rootPaths,
            env,
            finder,
            new TestEventConsole(),
            clock,
            Optional.empty(),
            1_000,
            TimeUnit.SECONDS.toNanos(10),
            TimeUnit.SECONDS.toNanos(1));

    assertTrue(watchman instanceof WatchmanFactory.NullWatchman);
  }

  @Test
  public void watchmanVersionTakingThirtySecondsReturnsEmpty()
      throws InterruptedException, IOException {
    SettableFakeClock clock = SettableFakeClock.DO_NOT_CARE;
    FakeListeningProcessExecutor executor =
        new FakeListeningProcessExecutor(
            ImmutableMultimap.<ProcessExecutorParams, FakeListeningProcessState>builder()
                .putAll(
                    ProcessExecutorParams.ofCommand(exe, "--output-encoding=bser", "get-sockname"),
                    FakeListeningProcessState.ofWaitNanos(TimeUnit.SECONDS.toNanos(30)),
                    FakeListeningProcessState.ofStdoutBytes(
                        bserSerialized(
                            ImmutableMap.of(
                                "version", "3.8.0",
                                "sockname", "/path/to/sock"))),
                    FakeListeningProcessState.ofExit(0))
                .build(),
            clock);
    WatchmanFactory watchmanFactory =
        createFakeWatchmanFactory(
            Paths.get("/path/to/sock"),
            0,
            ImmutableMap.of(
                VERSION_QUERY,
                ImmutableMap.of(
                    "version",
                    "3.8.0",
                    "capabilities",
                    ImmutableMap.of(
                        "term-dirname", true,
                        "cmd-watch-project", false,
                        "wildmatch", false,
                        "wildmatch_multislash", false,
                        "glob_generator", false)),
                WatchmanQuery.watchProject(root),
                ImmutableMap.of("version", "3.8.0", "watch", root)));
    Watchman watchman =
        watchmanFactory.build(
            executor,
            rootPaths,
            env,
            finder,
            new TestEventConsole(),
            clock,
            Optional.of(TimeUnit.SECONDS.toMillis(5)),
            1_000,
            TimeUnit.SECONDS.toNanos(10),
            TimeUnit.SECONDS.toNanos(1));

    assertTrue(watchman instanceof WatchmanFactory.NullWatchman);
  }

  @Test
  public void watchmanWatchProjectTakingThirtySecondsReturnsEmpty()
      throws InterruptedException, IOException {
    SettableFakeClock clock = SettableFakeClock.DO_NOT_CARE;
    FakeListeningProcessExecutor executor =
        new FakeListeningProcessExecutor(
            ImmutableMultimap.<ProcessExecutorParams, FakeListeningProcessState>builder()
                .putAll(
                    ProcessExecutorParams.ofCommand(exe, "--output-encoding=bser", "get-sockname"),
                    FakeListeningProcessState.ofStdoutBytes(
                        bserSerialized(
                            ImmutableMap.of(
                                "version", "3.8.0",
                                "sockname", "/path/to/sock"))))
                .build(),
            clock);
    WatchmanFactory watchmanFactory =
        createFakeWatchmanFactory(
            Paths.get("/path/to/sock"),
            TimeUnit.SECONDS.toNanos(30),
            ImmutableMap.of(
                VERSION_QUERY,
                ImmutableMap.of(
                    "version",
                    "3.8.0",
                    "capabilities",
                    ImmutableMap.<String, Boolean>builder()
                        .put("term-dirname", true)
                        .put("cmd-watch-project", false)
                        .put("wildmatch", false)
                        .put("wildmatch_multislash", false)
                        .put("glob_generator", false)
                        .put("clock-sync-timeout", false)
                        .build()),
                WatchmanQuery.watchProject(root),
                ImmutableMap.of("version", "3.8.0", "watch", root)));
    Watchman watchman =
        watchmanFactory.build(
            executor,
            rootPaths,
            env,
            finder,
            new TestEventConsole(),
            clock,
            Optional.of(TimeUnit.SECONDS.toMillis(5)),
            1_000,
            TimeUnit.SECONDS.toNanos(10),
            TimeUnit.SECONDS.toNanos(1));

    assertTrue(watchman instanceof WatchmanFactory.NullWatchman);
  }

  @Test
  public void capabilitiesDetectedForVersion47AndLater() throws InterruptedException, IOException {
    SettableFakeClock clock = SettableFakeClock.DO_NOT_CARE;
    FakeListeningProcessExecutor executor =
        new FakeListeningProcessExecutor(
            ImmutableMultimap.<ProcessExecutorParams, FakeListeningProcessState>builder()
                .putAll(
                    ProcessExecutorParams.ofCommand(exe, "--output-encoding=bser", "get-sockname"),
                    FakeListeningProcessState.ofStdoutBytes(
                        bserSerialized(
                            ImmutableMap.of(
                                "version", "4.7.0",
                                "sockname", "/path/to/sock"))),
                    FakeListeningProcessState.ofExit(0))
                .build(),
            clock);
    int syncTimeout = 60 * 1_000;
    WatchmanFactory watchmanFactory =
        createFakeWatchmanFactory(
            Paths.get("/path/to/sock"),
            0,
            ImmutableMap.of(
                VERSION_QUERY,
                ImmutableMap.of(
                    "version",
                    "4.7.0",
                    "capabilities",
                    ImmutableMap.<String, Boolean>builder()
                        .put("term-dirname", true)
                        .put("cmd-watch-project", true)
                        .put("wildmatch", true)
                        .put("wildmatch_multislash", true)
                        .put("glob_generator", true)
                        .put("clock-sync-timeout", true)
                        .build()),
                WatchmanQuery.watchProject(root),
                ImmutableMap.of("version", "4.7.0", "watch", root),
                WatchmanQuery.clock(new WatchRoot(root), Optional.of(syncTimeout)),
                ImmutableMap.of("version", "4.7.0", "clock", "c:0:0:1")));
    Watchman watchman =
        watchmanFactory.build(
            executor,
            rootPaths,
            env,
            finder,
            new TestEventConsole(),
            clock,
            Optional.empty(),
            syncTimeout,
            TimeUnit.SECONDS.toNanos(10),
            TimeUnit.SECONDS.toNanos(1));

    assertEquals(
        ImmutableSet.of(
            Capability.DIRNAME,
            Capability.SUPPORTS_PROJECT_WATCH,
            Capability.WILDMATCH_GLOB,
            Capability.WILDMATCH_MULTISLASH,
            Capability.GLOB_GENERATOR,
            Capability.CLOCK_SYNC_TIMEOUT),
        watchman.getCapabilities());

    assertEquals(
        ImmutableMap.of(new WatchRoot(root), "c:0:0:1"), watchman.getClockIdsByWatchRoot());
    assertEquals("4.7.0", watchman.getVersion());
  }

  @Test
  public void emptyClockQueryShouldReturnNullClock() throws InterruptedException, IOException {
    SettableFakeClock clock = SettableFakeClock.DO_NOT_CARE;
    FakeListeningProcessExecutor executor =
        new FakeListeningProcessExecutor(
            ImmutableMultimap.<ProcessExecutorParams, FakeListeningProcessState>builder()
                .putAll(
                    ProcessExecutorParams.ofCommand(exe, "--output-encoding=bser", "get-sockname"),
                    FakeListeningProcessState.ofStdoutBytes(
                        bserSerialized(
                            ImmutableMap.of(
                                "version", "4.7.0",
                                "sockname", "/path/to/sock"))),
                    FakeListeningProcessState.ofExit(0))
                .build(),
            clock);
    int syncTimeout = 60 * 1000;
    WatchmanFactory watchmanFactory =
        createFakeWatchmanFactory(
            Paths.get("/path/to/sock"),
            0,
            ImmutableMap.of(
                VERSION_QUERY,
                ImmutableMap.of(
                    "version",
                    "4.7.0",
                    "capabilities",
                    ImmutableMap.<String, Boolean>builder()
                        .put("term-dirname", true)
                        .put("cmd-watch-project", true)
                        .put("wildmatch", true)
                        .put("wildmatch_multislash", true)
                        .put("glob_generator", true)
                        .put("clock-sync-timeout", true)
                        .build()),
                WatchmanQuery.watchProject(root),
                ImmutableMap.of("version", "4.7.0", "watch", root),
                WatchmanQuery.clock(new WatchRoot(root), Optional.of(syncTimeout)),
                ImmutableMap.of("clock", "123")));
    Watchman watchman =
        watchmanFactory.build(
            executor,
            rootPaths,
            env,
            finder,
            new TestEventConsole(),
            clock,
            Optional.empty(),
            syncTimeout,
            TimeUnit.SECONDS.toNanos(10),
            TimeUnit.SECONDS.toNanos(1));

    assertEquals(ImmutableMap.of(new WatchRoot(root), "123"), watchman.getClockIdsByWatchRoot());
    assertEquals("4.7.0", watchman.getVersion());
  }
}
