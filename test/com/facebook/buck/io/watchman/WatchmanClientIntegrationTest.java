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

import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.filesystems.ForwardRelPath;
import com.facebook.buck.event.console.TestEventConsole;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.ListeningProcessExecutor;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.timing.DefaultClock;
import com.facebook.buck.util.types.Either;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class WatchmanClientIntegrationTest {

  private static final long timeoutMillis = 5000L;
  private static final long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
  private static final long pollingTimeNanos = TimeUnit.MILLISECONDS.toNanos(1);
  private ProjectWorkspace workspace;

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Rule public TemporaryPaths watchmanBaseDir = new TemporaryPaths();

  private ListeningProcessExecutor executor;
  private WatchmanTestDaemon watchmanDaemon;

  private void startWatchman() throws IOException, InterruptedException {
    executor = new ListeningProcessExecutor();
    try {
      watchmanDaemon = WatchmanTestDaemon.start(watchmanBaseDir.getRoot(), executor);
    } catch (WatchmanNotFoundException e) {
      Assume.assumeNoException(e);
    }
  }

  @Before
  public void setUp() throws IOException, InterruptedException {
    Assume.assumeTrue("Platform should be supported", isSupportedPlatform());
    startWatchman();
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "watchman", tmp);
    workspace.setUp();
  }

  @After
  public void tearDown() throws IOException {
    if (watchmanDaemon != null) {
      watchmanDaemon.close();
    }
  }

  @Test
  public void testWatchmanGlob() throws Exception {
    WatchmanClient client =
        WatchmanFactory.createWatchmanClient(
            watchmanDaemon.getTransportPath(), new TestEventConsole(), new DefaultClock());

    Either<WatchmanQueryResp.Generic, WatchmanClient.Timeout> versionResponse =
        client.queryWithTimeout(
            timeoutNanos,
            pollingTimeNanos,
            WatchmanQuery.version(
                WatchmanFactory.REQUIRED_CAPABILITIES.asList(),
                WatchmanFactory.ALL_CAPABILITIES.keySet().asList()));
    assertTrue(versionResponse.isLeft());

    Path rootPath = workspace.getDestPath();

    Either<WatchmanQueryResp.WatchProjectResp, WatchmanClient.Timeout> watch =
        client.queryWithTimeout(
            timeoutNanos, pollingTimeNanos, WatchmanQuery.watchProject(rootPath.toString()));

    assertTrue(watch.isLeft());

    WatchmanQueryResp.WatchProjectResp map = watch.getLeft();
    WatchRoot watchRoot = map.getWatch();

    Either<WatchmanQueryResp.Generic, WatchmanClient.Timeout> queryResponse =
        client.queryWithTimeout(
            timeoutNanos,
            pollingTimeNanos,
            WatchmanQuery.query(
                watchRoot,
                ForwardRelPath.EMPTY,
                Optional.empty(),
                Optional.of(ImmutableList.of("**/X")),
                ImmutableList.of("name")));

    assertTrue(queryResponse.isLeft());

    Set<?> actualFileSet =
        ImmutableSet.copyOf((List<?>) queryResponse.getLeft().getResp().get("files"));
    Set<?> expectedFileSet = ImmutableSet.of("X", "f1/X", "f2/X");

    Assert.assertEquals(expectedFileSet, actualFileSet);
    client.close();
  }

  private static boolean isSupportedPlatform() {
    switch (Platform.detect()) {
      case LINUX:
      case MACOS:
      case WINDOWS:
        return true;
        // $CASES-OMITTED$
      default:
        return false;
    }
  }
}
