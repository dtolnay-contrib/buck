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

package com.facebook.buck.util.monitoring;

import com.facebook.buck.event.WorkAdvanceEvent;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.eventbus.Subscribe;
import com.google.common.util.concurrent.AbstractScheduledService;
import com.google.common.util.concurrent.ServiceManager;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Hand monitor service. */
public class HangMonitor extends AbstractScheduledService {

  private final Consumer<String> hangReportConsumer;
  private final AtomicInteger eventsSeenSinceLastCheck;
  private final Duration hangCheckTimeout;
  private volatile String mostRecentReport;

  public HangMonitor(Consumer<String> hangReportConsumer, Duration hangCheckTimeout) {
    this.hangReportConsumer = hangReportConsumer;
    this.eventsSeenSinceLastCheck = new AtomicInteger(0);
    this.hangCheckTimeout = hangCheckTimeout;
    this.mostRecentReport = "";
  }

  @Subscribe
  @SuppressWarnings("unused")
  public void onWorkAdvance(WorkAdvanceEvent event) {
    workAdvance();
  }

  /**
   * Tell hang monitor that there was some work done towards completing the current task. Absence of
   * those calls can be used to detect a possible deadlock.
   */
  public void workAdvance() {
    eventsSeenSinceLastCheck.incrementAndGet();
  }

  @Override
  protected void runOneIteration() {
    if (eventsSeenSinceLastCheck.get() > 0) {
      eventsSeenSinceLastCheck.set(0);
      return;
    }

    Map<Thread, StackTraceElement[]> allStackTraces = Thread.getAllStackTraces();
    Thread hangMonitorThread = Thread.currentThread();
    StringBuilder hangReportBuilder = new StringBuilder();
    for (Map.Entry<Thread, StackTraceElement[]> entry : allStackTraces.entrySet()) {
      Thread thread = entry.getKey();
      if (thread == hangMonitorThread) {
        continue;
      }
      StackTraceElement[] stack = entry.getValue();

      hangReportBuilder.append("Thread [");
      hangReportBuilder.append(thread.getName());
      hangReportBuilder.append("],stack:[");
      Joiner.on(", ").appendTo(hangReportBuilder, stack);
      hangReportBuilder.append("],");
    }
    String currentReport = hangReportBuilder.toString();
    if (!currentReport.equals(mostRecentReport)) {
      mostRecentReport = currentReport;
      hangReportConsumer.accept(currentReport);
    }
  }

  @Override
  protected Scheduler scheduler() {
    return Scheduler.newFixedRateSchedule(
        hangCheckTimeout.toMillis(), hangCheckTimeout.toMillis(), TimeUnit.MILLISECONDS);
  }

  /** Auto start wrapper around hang monitor service */
  public static class AutoStartInstance {

    private final HangMonitor hangMonitor;

    public AutoStartInstance(Consumer<String> hangReportConsumer, Duration hangCheckTimeout) {
      hangMonitor = new HangMonitor(hangReportConsumer, hangCheckTimeout);
      ServiceManager serviceManager = new ServiceManager(ImmutableList.of(hangMonitor));
      serviceManager.startAsync();
    }

    public HangMonitor getHangMonitor() {
      return hangMonitor;
    }
  }
}
