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

import static com.facebook.buck.jvm.java.JavaCompilationConstants.DEFAULT_JAVACD_CONFIG;
import static com.facebook.buck.jvm.java.JavaCompilationConstants.DEFAULT_JAVAC_OPTIONS;
import static com.facebook.buck.jvm.java.JavaCompilationConstants.DEFAULT_JAVA_CONFIG;
import static com.facebook.buck.jvm.java.JavaCompilationConstants.DEFAULT_JAVA_OPTIONS;

import com.facebook.buck.android.AndroidBinaryDescription.AbstractAndroidBinaryDescriptionArg;
import com.facebook.buck.android.FilterResourcesSteps.ResourceFilter;
import com.facebook.buck.android.ResourcesFilter.ResourceCompressionMode;
import com.facebook.buck.android.aapt.RDotTxtEntry;
import com.facebook.buck.android.toolchain.AndroidPlatformTarget;
import com.facebook.buck.android.toolchain.DxToolchain;
import com.facebook.buck.android.toolchain.ndk.impl.TestNdkCxxPlatformsProviderFactory;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.AbstractNodeBuilder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.toolchain.impl.ToolchainProviderBuilder;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.jvm.java.JavaCompilationConstants;
import com.facebook.buck.jvm.java.toolchain.JavaOptionsProvider;
import com.facebook.buck.jvm.java.toolchain.JavaToolchain;
import com.facebook.buck.jvm.java.toolchain.JavacOptionsProvider;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.util.concurrent.MoreExecutors;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public class AndroidBundleBuilder
    extends AbstractNodeBuilder<
        AndroidBundleDescriptionArg.Builder,
        AndroidBundleDescriptionArg,
        AndroidBundleDescription,
        AndroidBundle> {

  private AndroidBundleBuilder(BuildTarget target) {
    this(FakeBuckConfig.empty(), target);
  }

  private AndroidBundleBuilder(BuckConfig buckConfig, BuildTarget target) {
    super(
        new AndroidBundleDescription(
            DEFAULT_JAVA_CONFIG,
            DEFAULT_JAVACD_CONFIG,
            new ProGuardConfig(buckConfig),
            new AndroidBuckConfig(buckConfig, Platform.detect()),
            CxxPlatformUtils.DEFAULT_CONFIG,
            CxxPlatformUtils.DEFAULT_DOWNWARD_API_CONFIG,
            CxxPlatformUtils.DEFAULT_EXTERNAL_ACTIONS_CONFIG,
            createToolchainProviderForAndroidBundle(),
            new AndroidBinaryGraphEnhancerFactory(),
            new AndroidBundleFactory(CxxPlatformUtils.DEFAULT_DOWNWARD_API_CONFIG)),
        target,
        new FakeProjectFilesystem(),
        createToolchainProviderForAndroidBundle());
  }

  public static ToolchainProvider createToolchainProviderForAndroidBundle() {
    return new ToolchainProviderBuilder()
        .withToolchain(
            AndroidPlatformTarget.DEFAULT_NAME, TestAndroidPlatformTargetFactory.create())
        .withToolchain(TestNdkCxxPlatformsProviderFactory.createDefaultNdkPlatformsProvider())
        .withToolchain(
            DxToolchain.DEFAULT_NAME, DxToolchain.of(MoreExecutors.newDirectExecutorService()))
        .withToolchain(
            JavaOptionsProvider.DEFAULT_NAME,
            JavaOptionsProvider.of(
                DEFAULT_JAVA_OPTIONS, DEFAULT_JAVA_OPTIONS, DEFAULT_JAVA_OPTIONS))
        .withToolchain(
            JavacOptionsProvider.DEFAULT_NAME, JavacOptionsProvider.of(DEFAULT_JAVAC_OPTIONS))
        .withToolchain(JavaToolchain.DEFAULT_NAME, JavaCompilationConstants.DEFAULT_JAVA_TOOLCHAIN)
        .build();
  }

  public static AndroidBundleBuilder createBuilder(BuildTarget buildTarget) {
    return new AndroidBundleBuilder(buildTarget);
  }

  public AndroidBundleBuilder setManifest(SourcePath manifest) {
    getArgForPopulating().setManifest(manifest);
    return this;
  }

  public AndroidBundleBuilder setOriginalDeps(ImmutableSortedSet<BuildTarget> originalDeps) {
    getArgForPopulating().setDeps(originalDeps);
    return this;
  }

  public AndroidBundleBuilder setKeystore(BuildTarget keystore) {
    getArgForPopulating().setKeystore(keystore);
    getArgForPopulating().addDeps(keystore);
    return this;
  }

  public AndroidBundleBuilder setShouldSplitDex(boolean shouldSplitDex) {
    getArgForPopulating().setUseSplitDex(shouldSplitDex);
    return this;
  }

  public AndroidBundleBuilder setDexCompression(DexStore dexStore) {
    getArgForPopulating().setDexCompression(Optional.of(dexStore));
    return this;
  }

  public AndroidBundleBuilder setLinearAllocHardLimit(long limit) {
    getArgForPopulating().setLinearAllocHardLimit(limit);
    return this;
  }

  public AndroidBundleBuilder setBuildTargetsToExcludeFromDex(
      Set<BuildTarget> buildTargetsToExcludeFromDex) {
    getArgForPopulating().setNoDx(buildTargetsToExcludeFromDex);
    return this;
  }

  public AndroidBundleBuilder setResourceCompressionMode(
      ResourceCompressionMode resourceCompressionMode) {
    getArgForPopulating().setResourceCompression(resourceCompressionMode);
    return this;
  }

  public AndroidBundleBuilder setResourceFilter(ResourceFilter resourceFilter) {
    List<String> rawFilters = ImmutableList.copyOf(resourceFilter.getFilter());
    getArgForPopulating().setResourceFilter(rawFilters);
    return this;
  }

  public AndroidBundleBuilder setNoDx(Set<BuildTarget> noDx) {
    getArgForPopulating().setNoDx(noDx);
    return this;
  }

  public AndroidBundleBuilder setDuplicateResourceBehavior(
      AbstractAndroidBinaryDescriptionArg.DuplicateResourceBehaviour value) {
    getArgForPopulating().setDuplicateResourceBehavior(value);
    return this;
  }

  public AndroidBundleBuilder setBannedDuplicateResourceTypes(Set<RDotTxtEntry.RType> value) {
    getArgForPopulating().setBannedDuplicateResourceTypes(value);
    return this;
  }

  public AndroidBundleBuilder setAllowedDuplicateResourceTypes(Set<RDotTxtEntry.RType> value) {
    getArgForPopulating().setAllowedDuplicateResourceTypes(value);
    return this;
  }

  public AndroidBundleBuilder setPostFilterResourcesCmd(Optional<StringWithMacros> command) {
    getArgForPopulating().setPostFilterResourcesCmd(command);
    return this;
  }

  public AndroidBundleBuilder setPreprocessJavaClassesBash(StringWithMacros command) {
    getArgForPopulating().setPreprocessJavaClassesBash(command);
    return this;
  }

  public AndroidBundleBuilder setProguardConfig(SourcePath path) {
    getArgForPopulating().setProguardConfig(path);
    return this;
  }
}
