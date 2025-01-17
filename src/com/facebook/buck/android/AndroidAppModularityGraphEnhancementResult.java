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

package com.facebook.buck.android;

import com.facebook.buck.android.apkmodule.APKModule;
import com.facebook.buck.android.apkmodule.APKModuleGraph;
import com.facebook.buck.android.packageable.AndroidPackageableCollection;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.util.immutables.BuckStyleValueWithBuilder;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;

@BuckStyleValueWithBuilder
abstract class AndroidAppModularityGraphEnhancementResult implements AddsToRuleKey {

  public abstract AndroidPackageableCollection getPackageableCollection();

  @AddToRuleKey
  public abstract Optional<ImmutableMultimap<APKModule, String>> getModulesToSharedLibraries();

  @AddToRuleKey
  public abstract ImmutableSortedSet<BuildRule> getFinalDeps();

  @AddToRuleKey
  public abstract APKModuleGraph<BuildTarget> getAPKModuleGraph();
}
