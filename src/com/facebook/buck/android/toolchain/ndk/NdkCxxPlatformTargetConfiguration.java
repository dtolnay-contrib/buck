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

package com.facebook.buck.android.toolchain.ndk;

import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.google.common.collect.ImmutableList;
import org.immutables.value.Value;

/** A container for all configuration settings needed to define a build target. */
@BuckStyleValue
public abstract class NdkCxxPlatformTargetConfiguration {

  public static NdkCxxPlatformTargetConfiguration of(
      TargetCpuType targetCpuType, String targetAppPlatform, NdkCxxPlatformCompiler compiler) {
    return ImmutableNdkCxxPlatformTargetConfiguration.ofImpl(
        targetCpuType, targetAppPlatform, compiler);
  }

  public abstract TargetCpuType getTargetCpuType();

  public abstract String getTargetAppPlatform();

  @Value.Derived
  public int getTargetAppPlatformLevel() {
    return Integer.parseInt(getTargetAppPlatform().substring("android-".length()));
  }

  public abstract NdkCxxPlatformCompiler getCompiler();

  @Value.Derived
  public NdkToolchain getToolchain() {
    return getTargetCpuType().getToolchain();
  }

  @Value.Derived
  public NdkToolchainTarget getToolchainTarget() {
    return getTargetCpuType().getToolchainTarget();
  }

  @Value.Derived
  public NdkTargetArch getTargetArch() {
    return getTargetCpuType().getTargetArch();
  }

  @Value.Derived
  public NdkTargetArchAbi getTargetArchAbi() {
    return getTargetCpuType().getTargetArchAbi();
  }

  public ImmutableList<String> getAssemblerFlags(NdkCompilerType type) {
    return getTargetCpuType().getAssemblerFlags(type);
  }

  public ImmutableList<String> getCompilerFlags(NdkCompilerType type) {
    return getTargetCpuType().getCompilerFlags(type);
  }

  public ImmutableList<String> getLinkerFlags(NdkCompilerType type) {
    return getTargetCpuType().getLinkerFlags(type);
  }
}
