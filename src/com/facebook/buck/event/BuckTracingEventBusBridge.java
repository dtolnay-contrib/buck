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

package com.facebook.buck.event;

import com.facebook.buck.core.build.execution.context.actionid.ActionId;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.api.BuckTracing;
import com.facebook.buck.event.api.BuckTracingInterface;
import com.google.common.collect.ImmutableMap;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

/** Bridges the {@link BuckTracing} API (in the system ClassLoader) with {@link BuckEventBus}. */
public class BuckTracingEventBusBridge implements BuckTracingInterface {

  private static final Logger LOG = Logger.get(BuckTracingEventBusBridge.class);

  private final IsolatedEventBus eventBus;
  private final ActionId actionId;
  private final String buildTargetName;
  private final Deque<CompilerPluginDurationEvent.Started> eventStack = new ArrayDeque<>();

  public BuckTracingEventBusBridge(
      IsolatedEventBus eventBus, ActionId actionId, String buildTargetName) {
    this.eventBus = eventBus;
    this.actionId = actionId;
    this.buildTargetName = buildTargetName;
  }

  @Override
  public void begin(String pluginName, String eventName, Map<String, String> args) {
    CompilerPluginDurationEvent.Started startedEvent =
        CompilerPluginDurationEvent.started(
            buildTargetName, pluginName, eventName, ImmutableMap.copyOf(args));

    eventStack.push(startedEvent);

    eventBus.post(startedEvent, actionId);
  }

  @Override
  public void end(Map<String, String> args) {
    if (eventStack.isEmpty()) {
      LOG.warn(new Throwable(), "Compiler plugin event stack underflow.");
      return;
    }

    CompilerPluginDurationEvent.Finished finishedEvent =
        CompilerPluginDurationEvent.finished(eventStack.pop(), ImmutableMap.copyOf(args));

    eventBus.post(finishedEvent, actionId);
  }
}
