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

import com.google.common.collect.ImmutableMap;

/** Events for sending Annotation Processing perf stats from the compilation step to be logged. */
public abstract class AnnotationProcessorStatsEvent extends SimplePerfEvent {
  public static class Started extends AnnotationProcessorStatsEvent {
    public Started(String invokingRule, AnnotationProcessorPerfStats data) {
      super(invokingRule, data);
    }

    public AnnotationProcessorPerfStats getData() {
      return data;
    }

    public String getInvokingRule() {
      return invokingRule;
    }

    @Override
    public Type getEventType() {
      return Type.STARTED;
    }
  }

  public static class Finished extends AnnotationProcessorStatsEvent {
    public Finished(AnnotationProcessorStatsEvent.Started startedEvent) {
      super(startedEvent.invokingRule, startedEvent.data);
    }

    @Override
    protected String getValueString() {
      return "apStats";
    }

    @Override
    public Type getEventType() {
      return Type.FINISHED;
    }
  }

  protected final String invokingRule;
  protected final AnnotationProcessorPerfStats data;

  public AnnotationProcessorStatsEvent(String invokingRule, AnnotationProcessorPerfStats data) {
    super(EventKey.unique());
    this.invokingRule = invokingRule;
    this.data = data;
  }

  @Override
  protected String getValueString() {
    return "apStats";
  }

  @Override
  public String getEventName() {
    return "AnnotationProcessorStatsEvents." + getCategory() + "." + getEventType().getValue();
  }

  @Override
  public String toString() {
    return getEventName() + "{invokingRule=" + invokingRule + ", data=" + data + "}";
  }

  @Override
  public PerfEventTitle getTitle() {
    return PerfEventTitle.of(getEventName());
  }

  @Override
  public ImmutableMap<String, Object> getEventInfo() {
    return ImmutableMap.of();
  }

  @Override
  public String getCategory() {
    return data.getProcessorName();
  }

  @Override
  public boolean isLogToChromeTrace() {
    return false;
  }
}
