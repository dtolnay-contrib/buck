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

package com.facebook.buck.remoteexecution.event;

import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.event.EventKey;
import com.facebook.buck.event.WorkAdvanceEvent;

/** Base class for events about building. */
public abstract class RemoteExecutionSessionEvent extends AbstractBuckEvent
    implements WorkAdvanceEvent {
  protected RemoteExecutionSessionEvent(EventKey eventKey) {
    super(eventKey);
  }

  public static Started started() {
    return new Started();
  }

  public static Finished finished(Started started) {
    return new Finished(started);
  }

  @Override
  protected String getValueString() {
    return "";
  }

  public String getCategory() {
    return "remote_execution_session";
  }

  /** Marks the start of RE session */
  public static class Started extends RemoteExecutionSessionEvent {
    Started() {
      super(EventKey.unique());
    }

    @Override
    public String getEventName() {
      return "RemoteExecutionSessionStarted";
    }
  }

  /** Marks the end of a RE session. */
  public static class Finished extends RemoteExecutionSessionEvent {
    private final Started startedEvent;

    Finished(Started startedEvent) {
      super(EventKey.unique());
      this.startedEvent = startedEvent;
    }

    @Override
    public String getEventName() {
      return "RemoteExecutionSessionFinished";
    }

    public Started getStartedEvent() {
      return startedEvent;
    }
  }
}
