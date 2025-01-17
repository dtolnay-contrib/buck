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

import com.facebook.buck.android.FilterResourcesSteps.ResourceFilter;
import com.facebook.buck.android.apkmodule.APKModuleGraph;
import com.facebook.buck.android.exopackage.ExopackageMode;
import com.facebook.buck.android.toolchain.AndroidPlatformTarget;
import com.facebook.buck.android.toolchain.DxToolchain;
import com.facebook.buck.command.config.BuildBuckConfig;
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.config.registry.ConfigurationRuleRegistry;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.cxx.config.CxxBuckConfig;
import com.facebook.buck.downwardapi.config.DownwardApiConfig;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.jvm.java.JavaCDBuckConfig;
import com.facebook.buck.jvm.java.JavaOptions;
import com.facebook.buck.jvm.java.JavacFactory;
import com.facebook.buck.jvm.java.toolchain.JavacOptionsProvider;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.macros.StringWithMacrosConverter;
import com.facebook.buck.step.fs.XzStep;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.util.EnumSet;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AndroidBinaryGraphEnhancerFactory {

  private static final Pattern COUNTRY_LOCALE_PATTERN = Pattern.compile("([a-z]{2})-[A-Z]{2}");

  public AndroidBinaryGraphEnhancer create(
      ToolchainProvider toolchainProvider,
      JavaBuckConfig javaBuckConfig,
      JavaCDBuckConfig javaCDBuckConfig,
      AndroidBuckConfig androidBuckConfig,
      CxxBuckConfig cxxBuckConfig,
      ProGuardConfig proGuardConfig,
      DownwardApiConfig downwardApiConfig,
      BuildBuckConfig buildBuckConfig,
      CellPathResolver cellPathResolver,
      TargetGraph targetGraph,
      BuildTarget buildTarget,
      DependencyStack dependencyStack,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      ActionGraphBuilder graphBuilder,
      ResourceFilter resourceFilter,
      DexSplitMode dexSplitMode,
      EnumSet<ExopackageMode> exopackageModes,
      Supplier<ImmutableSet<JavaLibrary>> rulesToExcludeFromDex,
      AndroidGraphEnhancerArgs args,
      boolean useProtoFormat,
      JavaOptions javaOptions,
      JavacFactory javacFactory,
      ConfigurationRuleRegistry configurationRuleRegistry) {

    AndroidPlatformTarget androidPlatformTarget =
        toolchainProvider.getByName(
            AndroidPlatformTarget.DEFAULT_NAME,
            buildTarget.getTargetConfiguration(),
            AndroidPlatformTarget.class);

    ListeningExecutorService dxExecutorService =
        toolchainProvider
            .getByName(
                DxToolchain.DEFAULT_NAME, buildTarget.getTargetConfiguration(), DxToolchain.class)
            .getDxExecutorService();

    ProGuardObfuscateStep.SdkProguardType androidSdkProguardConfig =
        args.getAndroidSdkProguardConfig().orElse(ProGuardObfuscateStep.SdkProguardType.NONE);

    boolean shouldProguard =
        args.getProguardConfig().isPresent()
            || !ProGuardObfuscateStep.SdkProguardType.NONE.equals(androidSdkProguardConfig);

    boolean shouldPreDex =
        !args.getDisablePreDex()
            && !shouldProguard
            && !args.getPreprocessJavaClassesBash().isPresent()
            && !args.getPreprocessJavaClassesCmd().isPresent();

    boolean shouldSkipCrunchPngs = args.isSkipCrunchPngs().orElse(false);

    int secondaryDexWeightLimit =
        args.getSecondaryDexWeightLimit().orElse(androidBuckConfig.getSecondaryDexWeightLimit());

    APKModuleGraph<BuildTarget> apkModuleGraph;
    if (args.getApplicationModuleConfigs().isEmpty()) {
      apkModuleGraph = new APKModuleGraph<>(targetGraph, buildTarget);
    } else {
      apkModuleGraph =
          new APKModuleGraph<>(
              targetGraph,
              buildTarget,
              Optional.of(args.getApplicationModuleConfigs()),
              args.getApplicationModuleDependencies(),
              APKModuleGraph.extractTargetsFromQueries(args.getApplicationModuleBlacklist()));
    }

    NonPreDexedDexBuildable.NonPredexedDexBuildableArgs nonPreDexedDexBuildableArgs =
        ImmutableNonPredexedDexBuildableArgs.builder()
            .setProguardAgentPath(proGuardConfig.getProguardAgentPath())
            .setProguardJarOverride(
                proGuardConfig.getProguardJarOverride(buildTarget.getTargetConfiguration()))
            .setProguardMaxHeapSize(proGuardConfig.getProguardMaxHeapSize())
            .setSdkProguardConfig(androidSdkProguardConfig)
            .setPreprocessJavaClassesBash(
                getPreprocessJavaClassesBash(args, buildTarget, graphBuilder, cellPathResolver))
            .setPreprocessJavaClassesCmd(
                getPreprocessJavaClassesCmd(args, buildTarget, graphBuilder, cellPathResolver))
            .setDxExecutorService(dxExecutorService)
            .setOptimizationPasses(args.getOptimizationPasses())
            .setProguardJvmArgs(args.getProguardJvmArgs())
            .setSkipProguard(args.isSkipProguard())
            .setJavaRuntimeLauncher(javaOptions.getJavaRuntime())
            .setProguardConfigPath(args.getProguardConfig())
            .setProguardConfigOverride(
                proGuardConfig.getProguardConfigOverride(buildTarget.getTargetConfiguration()))
            .setOptimizedProguardConfigOverride(
                proGuardConfig.getOptimizedProguardConfigOverride(
                    buildTarget.getTargetConfiguration()))
            .setShouldProguard(shouldProguard)
            .build();

    return new AndroidBinaryGraphEnhancer(
        toolchainProvider,
        cellPathResolver,
        buildTarget,
        projectFilesystem,
        androidPlatformTarget,
        params,
        graphBuilder,
        args.getAaptMode(),
        args.getAdditionalAaptParams(),
        args.getResourceCompression(),
        resourceFilter,
        args.getEffectiveBannedDuplicateResourceTypes(),
        args.getDuplicateResourceWhitelist(),
        args.getResourceUnionPackage(),
        addFallbackLocales(args.getLocales()),
        args.getPackagedLocales(),
        args.getManifest(),
        args.getManifestSkeleton(),
        args.getModuleManifestSkeleton(),
        args.getPackageType(),
        ImmutableSet.copyOf(args.getCpuFilters()),
        args.isBuildStringSourceMap(),
        shouldPreDex,
        dexSplitMode,
        args.getNoDx(),
        /* resourcesToExclude */ ImmutableSet.of(),
        /* nativeLibsToExclude */ ImmutableSet.of(),
        /* nativeLinkablesToExclude */ ImmutableSet.of(),
        /* nativeLibAssetsToExclude */ ImmutableSet.of(),
        /* nativeLinkableAssetsToExclude */ ImmutableSet.of(),
        /* nativeLibsForSystemLoaderToExclude */ ImmutableSet.of(),
        /* nativeLinkablesUsedByWrapScriptToExclude */ ImmutableSet.of(),
        shouldSkipCrunchPngs,
        args.isIncludesVectorDrawables(),
        args.isNoAutoVersionResources(),
        args.isNoVersionTransitionsResources(),
        args.isNoAutoAddOverlayResources(),
        androidBuckConfig.getAaptNoResourceRemoval(),
        javaBuckConfig,
        javaCDBuckConfig,
        downwardApiConfig,
        buildBuckConfig,
        javacFactory,
        toolchainProvider
            .getByName(
                JavacOptionsProvider.DEFAULT_NAME,
                buildTarget.getTargetConfiguration(),
                JavacOptionsProvider.class)
            .getJavacOptions(),
        exopackageModes,
        args.getBuildConfigValues(),
        args.getBuildConfigValuesFile(),
        XzStep.DEFAULT_COMPRESSION_LEVEL,
        args.isTrimResourceIds(),
        args.isIgnoreAaptProguardConfig(),
        args.getNativeLibraryMergeMap(),
        args.getNativeLibraryMergeSequence(),
        args.getNativeLibraryMergeSequenceBlocklist(),
        args.getNativeLibraryMergeGlue(),
        args.getNativeLibraryMergeCodeGenerator(),
        args.getNativeLibraryMergeLocalizedSymbols(),
        args.isEnableRelinker() ? RelinkerMode.ENABLED : RelinkerMode.DISABLED,
        args.getRelinkerWhitelist(),
        dxExecutorService,
        args.getManifestEntries(),
        cxxBuckConfig,
        apkModuleGraph,
        getPostFilterResourcesArgs(args, buildTarget, graphBuilder, cellPathResolver),
        nonPreDexedDexBuildableArgs,
        rulesToExcludeFromDex,
        useProtoFormat,
        AndroidNativeTargetConfigurationMatcherFactory.create(
            configurationRuleRegistry, buildTarget, dependencyStack, args.getCpuFilters()),
        args.isAapt2LocaleFiltering(),
        args.isAapt2KeepRawValues(),
        args.getExtraFilteredResources(),
        args.getResourceStableIds(),
        androidBuckConfig.getRDotJavaWeightFactor(),
        secondaryDexWeightLimit,
        ImmutableSet.of());
  }

  private ImmutableSet<String> addFallbackLocales(ImmutableSet<String> locales) {
    ImmutableSet.Builder<String> allLocales = ImmutableSet.builder();
    for (String locale : locales) {
      allLocales.add(locale);
      Matcher matcher = COUNTRY_LOCALE_PATTERN.matcher(locale);
      if (matcher.matches()) {
        allLocales.add(matcher.group(1));
      }
    }
    return allLocales.build();
  }

  private Optional<Arg> getPostFilterResourcesArgs(
      AndroidGraphEnhancerArgs arg,
      BuildTarget buildTarget,
      ActionGraphBuilder graphBuilder,
      CellPathResolver cellRoots) {
    StringWithMacrosConverter macrosConverter =
        StringWithMacrosConverter.of(
            buildTarget,
            cellRoots.getCellNameResolver(),
            graphBuilder,
            MacroExpandersForAndroidRules.MACRO_EXPANDERS);
    return arg.getPostFilterResourcesCmd().map(macrosConverter::convert);
  }

  private Optional<Arg> getPreprocessJavaClassesBash(
      AndroidGraphEnhancerArgs arg,
      BuildTarget buildTarget,
      ActionGraphBuilder graphBuilder,
      CellPathResolver cellRoots) {
    StringWithMacrosConverter macrosConverter =
        StringWithMacrosConverter.of(
            buildTarget,
            cellRoots.getCellNameResolver(),
            graphBuilder,
            MacroExpandersForAndroidRules.MACRO_EXPANDERS);
    return arg.getPreprocessJavaClassesBash().map(macrosConverter::convert);
  }

  private Optional<Arg> getPreprocessJavaClassesCmd(
      AndroidGraphEnhancerArgs arg,
      BuildTarget buildTarget,
      ActionGraphBuilder graphBuilder,
      CellPathResolver cellRoots) {
    StringWithMacrosConverter macrosConverter =
        StringWithMacrosConverter.of(
            buildTarget,
            cellRoots.getCellNameResolver(),
            graphBuilder,
            MacroExpandersForAndroidRules.MACRO_EXPANDERS);
    return arg.getPreprocessJavaClassesCmd().map(macrosConverter::convert);
  }
}
