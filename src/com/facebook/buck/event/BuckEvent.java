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

import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.event.external.events.BuckEventExternalInterface;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.annotations.VisibleForTesting;

public interface BuckEvent extends BuckEventExternalInterface {
  @VisibleForTesting
  void configure(
      long timestamp, long nanoTime, long threadUserNanoTime, long threadId, BuildId buildId);

  @JsonIgnore
  boolean isConfigured();

  long getNanoTime();

  long getThreadUserNanoTime();

  String toLogMessage();

  long getThreadId();

  /** @return an identifier that distinguishes the build with which this event is associated. */
  BuildId getBuildId();

  /**
   * @return Whether or not this event is related to another event. Events are related if they
   *     pertain to the same event, for example if they are measuring the start and stop of some
   *     phase. For example,
   *     <pre>
   *   <code>
   *    (CommandEvent.started("build")).isRelatedTo(CommandEvent.finished("build")) == true
   *    (CommandEvent.started("build")).isRelatedTo(CommandEvent.started("build")) == true
   *    (CommandEvent.started("build")).isRelatedTo(CommandEvent.finished("install")) == false
   *   </code>
   * </pre>
   */
  boolean isRelatedTo(BuckEvent event);

  /** @return key used to determine whether this event is related to another event. */
  EventKey getEventKey();
}
