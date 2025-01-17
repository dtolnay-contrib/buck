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

package com.facebook.buck.cxx.toolchain;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.toolchain.toolprovider.ToolProvider;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.google.common.collect.ImmutableList;

/** Represents the params needed to create scrubbed dylib stubs. */
@BuckStyleValue
public abstract class MachoDylibStubParams implements SharedLibraryInterfaceParams {

  public static MachoDylibStubParams of(ToolProvider strip) {
    return ImmutableMachoDylibStubParams.ofImpl(strip);
  }

  public abstract ToolProvider getStrip();

  @Override
  public Iterable<BuildTarget> getParseTimeDeps(TargetConfiguration targetConfiguration) {
    return ImmutableList.of();
  }

  @Override
  public Kind getKind() {
    return Kind.MACHO;
  }

  @Override
  public ImmutableList<String> getLdflags() {
    return ImmutableList.of();
  }
}
