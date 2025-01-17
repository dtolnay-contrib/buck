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

package com.facebook.buck.apple.xcode.xcodeproj;

import com.facebook.buck.apple.xcode.XcodeprojSerializer;

/**
 * Reference to another object used by {@link PBXTargetDependency}. Can reference a remote file by
 * specifying the {@link PBXFileReference} to the remote project file, and the GID of the object
 * within that file.
 */
public class PBXContainerItemProxy extends PBXContainerItem {
  public enum ProxyType {
    TARGET_REFERENCE(1);

    private final int intValue;

    ProxyType(int intValue) {
      this.intValue = intValue;
    }

    public int getIntValue() {
      return intValue;
    }
  }

  private final PBXObject containerPortal;
  private final String remoteGlobalIDString;
  private final ProxyType proxyType;

  public PBXContainerItemProxy(
      PBXObject containerPortal, String remoteGlobalIDString, ProxyType proxyType) {
    this.containerPortal = containerPortal;
    this.remoteGlobalIDString = remoteGlobalIDString;
    this.proxyType = proxyType;
  }

  public PBXObject getContainerPortal() {
    return containerPortal;
  }

  public String getRemoteGlobalIDString() {
    return remoteGlobalIDString;
  }

  public ProxyType getProxyType() {
    return proxyType;
  }

  @Override
  public String isa() {
    return "PBXContainerItemProxy";
  }

  @Override
  public int stableHash() {
    return remoteGlobalIDString.hashCode();
  }

  @Override
  public void serializeInto(XcodeprojSerializer s) {
    super.serializeInto(s);

    s.addField("containerPortal", containerPortal.getGlobalID());
    s.addField("remoteGlobalIDString", remoteGlobalIDString);
    s.addField("proxyType", String.valueOf(proxyType.getIntValue()));
  }
}
