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

package com.facebook.buck.remoteexecution.event.listener;

import com.facebook.buck.remoteexecution.event.LocalFallbackStats;
import com.facebook.buck.remoteexecution.event.RemoteExecutionActionEvent.State;
import java.util.List;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class RemoteExecutionConsoleLineProviderTest {
  private final String SESSION_ID_INFO = "super cool info about the session";

  private TestRemoteExecutionStatsProvider statsProvider;

  @Before
  public void setUp() {
    this.statsProvider = new TestRemoteExecutionStatsProvider();
  }

  @Test
  public void testNoLocalFallback() {
    statsProvider.casDownladedBytes = 42;
    statsProvider.casDownloads = 21;
    statsProvider.actionsPerState.put(State.ACTION_SUCCEEDED, 84);
    statsProvider.localFallbackStats =
        LocalFallbackStats.builder()
            .from(statsProvider.localFallbackStats)
            .setLocallyExecutedRules(0)
            .build();
    RemoteExecutionConsoleLineProvider provider =
        new RemoteExecutionConsoleLineProvider(statsProvider, SESSION_ID_INFO, true);
    List<String> lines = provider.createConsoleLinesAtTime(0);
    Assert.assertEquals(6, lines.size());
    for (String line : lines) {
      Assert.assertFalse(line.contains("LocalFallback"));
    }
  }

  @Test
  public void testNoDebug() {
    statsProvider.casDownladedBytes = 42;
    statsProvider.casDownloads = 21;
    final int succeeded = 84, loadedFromCache = 20;
    statsProvider.actionsPerState.put(State.ACTION_SUCCEEDED, succeeded);
    statsProvider.actionsPerState.put(State.LOADED_FROM_CACHE, loadedFromCache);
    statsProvider.localFallbackStats =
        LocalFallbackStats.builder()
            .from(statsProvider.localFallbackStats)
            .setLocallyExecutedRules(0)
            .build();
    RemoteExecutionConsoleLineProvider provider =
        new RemoteExecutionConsoleLineProvider(statsProvider, SESSION_ID_INFO, false);
    List<String> lines = provider.createConsoleLinesAtTime(0);
    Assert.assertEquals(2, lines.size());
    Assert.assertEquals(
        "[RE] Metadata: Session ID=[super cool info about the session]", lines.get(0));
    Assert.assertEquals(
        lines.get(1),
        String.format(
            "[RE] Waiting on 0 remote actions. Completed %d actions remotely, action cache hit rate: %.2f%%.",
            succeeded + loadedFromCache, loadedFromCache / 1. / succeeded * 100));

    for (String line : lines) {
      Assert.assertFalse(line.contains("LocalFallback"));
    }
  }

  @Test
  public void testDebugFormatConsoleOutput() {
    statsProvider.casDownladedBytes = 42;
    statsProvider.casDownloads = 21;
    statsProvider.actionsPerState.put(State.ACTION_SUCCEEDED, 84);

    RemoteExecutionConsoleLineProvider provider =
        new RemoteExecutionConsoleLineProvider(statsProvider, SESSION_ID_INFO, true);
    List<String> lines = provider.createConsoleLinesAtTime(0);
    Assert.assertEquals(7, lines.size());
    Assert.assertEquals(
        "[RE] Actions: Local=0 Remote=[wait=0 comp=0 upl_in=0 upl_act=0 exec=0 del=0 dwl=0 suc=84 fail=0 cncl=0 cache=0]",
        lines.get(1));
    Assert.assertEquals(
        "[RE] CAS: Upl=[Count:0 Size=0.00 bytes] Dwl=[Count:21 Size=42.00 bytes]", lines.get(2));
    Assert.assertEquals("[RE] Metrics: CPU 1:05 minutes", lines.get(3));
    Assert.assertEquals("[RE] Metrics: Used 3:20 minutes of total time.", lines.get(4));

    Assert.assertEquals(
        "[RE] Some actions failed remotely, retrying locally. LocalFallback: [fallback_rate=50.00% remote=42 local=21]",
        lines.get(6));
  }
}
