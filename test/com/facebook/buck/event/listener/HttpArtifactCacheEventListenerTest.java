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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.artifact_cache.CacheResult;
import com.facebook.buck.artifact_cache.HttpArtifactCacheEvent;
import com.facebook.buck.artifact_cache.HttpArtifactCacheEvent.Finished;
import com.facebook.buck.artifact_cache.config.ArtifactCacheMode;
import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.support.bgtasks.TaskManagerCommandScope;
import com.facebook.buck.support.bgtasks.TestBackgroundTaskManager;
import com.facebook.buck.util.network.AbstractBatchingLogger;
import com.facebook.buck.util.types.Unit;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableCollection;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;

public class HttpArtifactCacheEventListenerTest {

  private static class TestBatchingLogger extends AbstractBatchingLogger {
    private final List<String> logEntries = new ArrayList<>();

    public TestBatchingLogger(int minBatchSize) {
      super(minBatchSize);
    }

    public List<String> getLogEntries() {
      return logEntries;
    }

    @Override
    public Optional<ListenableFuture<Unit>> log(String logLine) {
      logEntries.add(logLine);
      return super.log(logLine);
    }

    @Override
    protected ListenableFuture<Unit> logMultiple(
        ImmutableCollection<AbstractBatchingLogger.BatchEntry> data) {
      return Futures.immediateFuture(null);
    }

    @Override
    public ListenableFuture<Unit> forceFlush() {
      return Futures.immediateFuture(null);
    }
  }

  private static final BuildId BUILD_ID = new BuildId("My Super ID");

  private TestBatchingLogger fetchLogger;
  private HttpArtifactCacheEventListener listener;
  private TaskManagerCommandScope managerScope;

  @Before
  public void setUp() {
    TestBatchingLogger storeLogger = new TestBatchingLogger(1);
    fetchLogger = new TestBatchingLogger(1);
    managerScope = TestBackgroundTaskManager.of().getNewScope(new BuildId("test"));
    listener = new HttpArtifactCacheEventListener(storeLogger, fetchLogger, managerScope);
  }

  @Test
  public void creatingRowWithoutColumns() {
    String errorMsg = "My super cool error message!!!";

    HttpArtifactCacheEvent.Started startedEvent =
        ArtifactCacheTestUtils.newFetchConfiguredStartedEvent(null, new RuleKey("1234"));
    Finished.Builder builder = HttpArtifactCacheEvent.newFinishedEventBuilder(startedEvent);
    builder
        .getFetchBuilder()
        .setFetchResult(CacheResult.hit("http", ArtifactCacheMode.http))
        .setErrorMessage(errorMsg);
    Finished event = builder.build();
    event.configure(-1, -1, -1, -1, BUILD_ID);
    listener.onHttpArtifactCacheEvent(event);
    listener.close();
    managerScope.close();
    String actualLogLine = fetchLogger.getLogEntries().iterator().next();
    assertFalse(Strings.isNullOrEmpty(actualLogLine));
    assertTrue(actualLogLine.contains(errorMsg));
    assertTrue(actualLogLine.contains(BUILD_ID.toString()));
  }
}
