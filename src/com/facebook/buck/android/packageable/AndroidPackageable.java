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

package com.facebook.buck.android.packageable;

import com.facebook.buck.android.toolchain.ndk.NdkCxxPlatform;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import java.util.function.Supplier;

/**
 * Something (usually a {@link BuildRule}) that can be included in an Android package
 * (android_binary, cxx_library or android_prebuilt_aar).
 */
public interface AndroidPackageable {

  /**
   * Get the set of packagables that need to be included in any package that includes this object.
   *
   * <p>For example, an android_library will need all of its Java deps (except provided_deps), its
   * resource deps, and its native library deps (even though it doesn't need the native library as a
   * build-time dependency). An android_resource might need an android_library that declares a
   * custom view that it references, as well as other android_resource rules that it references
   * directly.
   *
   * <p>TODO(natthu): Once build rules and buildables are merged, replace this method with another
   * interface that lets an {@link AndroidPackageable} override the default set which is all deps of
   * the type {@link AndroidPackageable}.
   *
   * <p>TODO(agallagher): We pass in the list of all NDK C++ platforms so that native linkable deps
   * can trim deps which aren't relevant for the given platform, but ideally, we'd handle this a bit
   * more elegantly (e.g. collect the first-order native linkables, then use the native linkable
   * helpers to do platform-specific traversals in each case).
   *
   * @return All {@link AndroidPackageable}s that must be included along with this one.
   */
  Iterable<AndroidPackageable> getRequiredPackageables(
      BuildRuleResolver ruleResolver, Supplier<Iterable<NdkCxxPlatform>> ndkCxxPlatforms);

  /**
   * Add concrete resources to the given collector.
   *
   * <p>Implementations should call methods on the collector specify what concrete content must be
   * included in an Android package that includes this object. For example, an android_library will
   * add Java classes, an ndk_library will add native libraries, and android_resource will add
   * resource directories.
   *
   * @param graphBuilder
   * @param collector The {@link AndroidPackageableCollector} that will receive the content.
   */
  void addToCollector(ActionGraphBuilder graphBuilder, AndroidPackageableCollector collector);
}
