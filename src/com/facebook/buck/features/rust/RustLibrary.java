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

package com.facebook.buck.features.rust;

import com.facebook.buck.android.packageable.AndroidPackageable;
import com.facebook.buck.android.packageable.AndroidPackageableCollector;
import com.facebook.buck.android.toolchain.ndk.NdkCxxPlatform;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.impl.NoopBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableGroup;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.collect.ImmutableList;
import java.util.function.Supplier;

public abstract class RustLibrary extends NoopBuildRuleWithDeclaredAndExtraDeps
    implements RustLinkable, NativeLinkableGroup, AndroidPackageable {
  public RustLibrary(
      BuildTarget buildTarget, ProjectFilesystem projectFilesystem, BuildRuleParams params) {
    super(buildTarget, projectFilesystem, params);
  }

  @Override
  public Iterable<AndroidPackageable> getRequiredPackageables(
      BuildRuleResolver ruleResolver, Supplier<Iterable<NdkCxxPlatform>> ndkCxxPlatforms) {
    if (!isProcMacro()) {
      return AndroidPackageableCollector.getPackageableRules(getBuildDeps());
    } else {
      return ImmutableList.of();
    }
  }

  @Override
  public void addToCollector(
      ActionGraphBuilder graphBuilder, AndroidPackageableCollector collector) {
    if (!isProcMacro()) {
      collector.addNativeLinkableAsset(this);
    }
  }
}
