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

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.event.external.events.InstallFinishedEventExternalInterface;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableMap;
import java.util.Optional;

public abstract class InstallEvent extends AbstractBuckEvent
    implements LeafEvent, WorkAdvanceEvent {

  private final String buildTarget;

  protected InstallEvent(EventKey eventKey, String buildTarget) {
    super(eventKey);
    this.buildTarget = buildTarget;
  }

  public String getBuildTarget() {
    return buildTarget;
  }

  @Override
  public String getCategory() {
    return "install_apk";
  }

  @Override
  protected String getValueString() {
    return buildTarget;
  }

  public static Started started(BuildTarget buildTarget) {
    return started(buildTarget.getFullyQualifiedName());
  }

  public static Started started(String buildTarget) {
    return new Started(buildTarget);
  }

  public static Finished finished(
      Started started,
      boolean success,
      Optional<Long> pid,
      Optional<String> packageName,
      ImmutableMap<String, String> deviceInfos,
      Optional<Integer> adbPort) {
    return new Finished(started, success, pid, packageName, deviceInfos, adbPort);
  }

  public static Finished finished(
      Started started, boolean success, Optional<Long> pid, Optional<String> packageName) {
    return finished(started, success, pid, packageName, ImmutableMap.of(), Optional.empty());
  }

  public static class Started extends InstallEvent {

    protected Started(String buildTarget) {
      super(EventKey.unique(), buildTarget);
    }

    @Override
    public String getEventName() {
      return INSTALL_STARTED;
    }
  }

  public static class Finished extends InstallEvent
      implements InstallFinishedEventExternalInterface {

    public static final String DEVICE_INFO_LOCALES = "locales";

    private static long invalidPid = -1;

    private final boolean success;
    private final long pid;
    private final String packageName;
    private final ImmutableMap<String, String> deviceInfo;
    private final Integer adbPort;

    protected Finished(
        Started started,
        boolean success,
        Optional<Long> pid,
        Optional<String> packageName,
        ImmutableMap<String, String> deviceInfo,
        Optional<Integer> adbPort) {
      super(started.getEventKey(), started.getValueString());
      this.success = success;
      this.pid = pid.orElse(invalidPid);
      this.packageName = packageName.orElse("");
      this.deviceInfo = deviceInfo;
      this.adbPort = adbPort.orElse(0);
    }

    @Override
    public boolean isSuccess() {
      return success;
    }

    public long getPid() {
      return pid;
    }

    public ImmutableMap<String, String> getInstallDeviceInfo() {
      return deviceInfo;
    }

    @Override
    public String getPackageName() {
      return packageName;
    }

    @Override
    public String getEventName() {
      return INSTALL_FINISHED;
    }

    public int getAdbPort() {
      return adbPort;
    }

    @Override
    public boolean equals(Object o) {
      if (!super.equals(o)) {
        return false;
      }
      // Because super.equals compares the EventKey, getting here means that we've somehow managed
      // to create 2 Finished events for the same Started event.
      throw new UnsupportedOperationException("Multiple conflicting Finished events detected.");
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(super.hashCode(), isSuccess());
    }
  }
}
