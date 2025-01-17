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

package com.facebook.buck.slb;

import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.core.util.immutables.BuckStyleValueWithBuilder;
import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.event.EventKey;
import com.google.common.collect.ImmutableList;
import java.net.URI;
import java.util.Optional;

public class LoadBalancerPingEvent extends AbstractBuckEvent {
  private final LoadBalancerPingEventData data;

  protected LoadBalancerPingEvent(LoadBalancerPingEventData data) {
    super(EventKey.unique());
    this.data = data;
  }

  public LoadBalancerPingEventData getData() {
    return data;
  }

  @Override
  protected String getValueString() {
    return getEventName();
  }

  @Override
  public String getEventName() {
    return this.getClass().getName();
  }

  @BuckStyleValueWithBuilder
  public interface PerServerPingData {
    URI getServer();

    Optional<Exception> getException();

    Optional<Long> getPingRequestLatencyMillis();
  }

  @BuckStyleValue
  public interface LoadBalancerPingEventData {
    ImmutableList<PerServerPingData> getPerServerData();
  }
}
