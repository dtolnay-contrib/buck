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

package com.facebook.buck.apple.toolchain.impl;

import com.facebook.buck.apple.AppleToolchainSetBuildRule;
import com.facebook.buck.apple.toolchain.AppleCxxPlatform;
import com.facebook.buck.apple.toolchain.UnresolvedAppleCxxPlatform;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.ProviderBackedCxxPlatform;
import com.facebook.buck.cxx.toolchain.UnresolvedCxxPlatform;
import com.facebook.buck.swift.toolchain.SwiftPlatform;
import com.facebook.buck.swift.toolchain.UnresolvedSwiftPlatform;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableList;
import java.util.Optional;

/**
 * Used to provide a {@link AppleCxxPlatform} that is specified as a apple_toolchain_set build
 * target.
 */
public class ProviderBackedUnresolvedAppleCxxPlatform implements UnresolvedAppleCxxPlatform {
  private final BuildTarget toolchainSetTarget;
  private final Flavor flavor;
  private final UnresolvedCxxPlatform cxxPlatformProvider;
  private final UnresolvedSwiftPlatform swiftPlatformProvider;
  private final Optional<AppleCxxPlatform> fallbackPlatform;

  public ProviderBackedUnresolvedAppleCxxPlatform(
      BuildTarget toolchainSetTarget, Flavor flavor, Optional<AppleCxxPlatform> fallbackPlatform) {
    this.toolchainSetTarget = toolchainSetTarget;
    this.flavor = flavor;
    this.cxxPlatformProvider = new AppleUnresolvedCxxPlatform();
    this.swiftPlatformProvider = new AppleUnresolvedSwiftPlatform();
    this.fallbackPlatform = fallbackPlatform;
  }

  @Override
  public Iterable<BuildTarget> getParseTimeDeps(TargetConfiguration targetConfiguration) {
    ImmutableList.Builder<BuildTarget> deps = ImmutableList.builder();
    deps.add(toolchainSetTarget);
    if (fallbackPlatform.isPresent()) {
      deps.addAll(
          fallbackPlatform.get().getCodesignProvider().getParseTimeDeps(targetConfiguration));
    }
    return deps.build();
  }

  @Override
  public AppleCxxPlatform resolve(BuildRuleResolver ruleResolver) {
    BuildRule rule = ruleResolver.getRule(toolchainSetTarget);
    Verify.verify(rule instanceof AppleToolchainSetBuildRule);
    AppleToolchainSetBuildRule toolchainSetBuildRule = (AppleToolchainSetBuildRule) rule;
    if (!toolchainSetBuildRule.hasApplePlatforms() && fallbackPlatform.isPresent()) {
      // The toolchain_set is empty but we have a target defined. This can happen when a select
      // evaluates to []. In this case we should fall back to a static platform if available.
      return fallbackPlatform.get();
    }
    return toolchainSetBuildRule.getAppleCxxPlatform(flavor);
  }

  @Override
  public UnresolvedCxxPlatform getUnresolvedCxxPlatform() {
    return cxxPlatformProvider;
  }

  @Override
  public UnresolvedSwiftPlatform getUnresolvedSwiftPlatform() {
    return swiftPlatformProvider;
  }

  @Override
  public Flavor getFlavor() {
    return flavor;
  }

  private class AppleUnresolvedCxxPlatform
      implements UnresolvedCxxPlatform, ProviderBackedCxxPlatform {

    @Override
    public CxxPlatform resolve(
        BuildRuleResolver resolver, TargetConfiguration targetConfiguration) {
      return ProviderBackedUnresolvedAppleCxxPlatform.this.resolve(resolver).getCxxPlatform();
    }

    @Override
    public Flavor getFlavor() {
      return flavor;
    }

    @Override
    public UnresolvedCxxPlatform withFlavor(Flavor hostFlavor) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Iterable<BuildTarget> getParseTimeDeps(TargetConfiguration targetConfiguration) {
      return ImmutableList.of(toolchainSetTarget);
    }

    @Override
    public Iterable<? extends BuildTarget> getLinkerParseTimeDeps(
        TargetConfiguration targetConfiguration) {
      return getParseTimeDeps(targetConfiguration);
    }
  }

  private class AppleUnresolvedSwiftPlatform implements UnresolvedSwiftPlatform {

    @Override
    public Iterable<BuildTarget> getParseTimeDeps(TargetConfiguration targetConfiguration) {
      return ImmutableList.of(toolchainSetTarget);
    }

    @Override
    public Optional<SwiftPlatform> resolve(BuildRuleResolver ruleResolver) {
      return ProviderBackedUnresolvedAppleCxxPlatform.this.resolve(ruleResolver).getSwiftPlatform();
    }

    @Override
    public Flavor getFlavor() {
      return flavor;
    }
  }
}
