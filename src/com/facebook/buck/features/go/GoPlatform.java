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

package com.facebook.buck.features.go;

import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorConvertible;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.util.immutables.BuckStyleValueWithBuilder;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.util.Optional;

/** Abstracting the tooling/flags/libraries used to build Go rules. */
@BuckStyleValueWithBuilder
abstract class GoPlatform implements FlavorConvertible, AddsToRuleKey {

  @AddToRuleKey
  public abstract GoOs getGoOs();

  @AddToRuleKey
  public abstract GoArch getGoArch();

  @Override
  public abstract Flavor getFlavor();

  public abstract Path getGoRoot();

  public abstract ImmutableList<Path> getAssemblerIncludeDirs();

  public abstract Tool getCompiler();

  public abstract Tool getAssembler();

  public abstract Tool getCGo();

  public abstract Tool getPacker();

  public abstract Tool getLinker();

  public abstract Tool getCover();

  public abstract Optional<Tool> getTestMainGen();

  public abstract CxxPlatform getCxxPlatform();

  public abstract ImmutableList<String> getExternalLinkerFlags();

  public GoPlatform withGoOs(GoOs goOs) {
    if (getGoOs().equals(goOs)) {
      return this;
    }
    return ImmutableGoPlatform.builder().from(this).setGoOs(goOs).build();
  }

  public GoPlatform withGoArch(GoArch goArch) {
    if (getGoArch().equals(goArch)) {
      return this;
    }
    return ImmutableGoPlatform.builder().from(this).setGoArch(goArch).build();
  }
}
