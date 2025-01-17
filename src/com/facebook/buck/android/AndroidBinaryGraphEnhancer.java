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

import com.facebook.buck.android.AndroidNativeLibsPackageableGraphEnhancer.AndroidNativeLibsGraphEnhancementResult;
import com.facebook.buck.android.FilterResourcesSteps.ResourceFilter;
import com.facebook.buck.android.ResourcesFilter.ResourceCompressionMode;
import com.facebook.buck.android.aapt.RDotTxtEntry.RType;
import com.facebook.buck.android.apkmodule.APKModule;
import com.facebook.buck.android.apkmodule.APKModuleGraph;
import com.facebook.buck.android.build_config.BuildConfigFields;
import com.facebook.buck.android.build_config.BuildConfigs;
import com.facebook.buck.android.exopackage.ExopackageMode;
import com.facebook.buck.android.packageable.AndroidPackageableCollection;
import com.facebook.buck.android.packageable.AndroidPackageableCollector;
import com.facebook.buck.android.toolchain.AndroidPlatformTarget;
import com.facebook.buck.android.toolchain.ndk.NdkCxxPlatformsProvider;
import com.facebook.buck.android.toolchain.ndk.TargetCpuType;
import com.facebook.buck.command.config.BuildBuckConfig;
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.sourcepath.BuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.cxx.config.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableGroup;
import com.facebook.buck.downwardapi.config.DownwardApiConfig;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.cd.params.RulesCDParams;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.jvm.java.DefaultJavaLibrary;
import com.facebook.buck.jvm.java.DefaultJavaLibraryRules;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.jvm.java.JavaCDBuckConfig;
import com.facebook.buck.jvm.java.JavaCDParams;
import com.facebook.buck.jvm.java.JavaConfiguredCompilerFactory;
import com.facebook.buck.jvm.java.JavaLibraryDeps;
import com.facebook.buck.jvm.java.Javac;
import com.facebook.buck.jvm.java.JavacFactory;
import com.facebook.buck.jvm.java.JavacLanguageLevelOptions;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.jvm.java.PrebuiltJar;
import com.facebook.buck.jvm.java.RemoveClassesPatternsMatcher;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.coercer.ManifestEntries;
import com.facebook.buck.util.MoreMaps;
import com.facebook.buck.util.stream.RichStream;
import com.facebook.buck.util.types.Pair;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Suppliers;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Ordering;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.SortedSet;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class AndroidBinaryGraphEnhancer {

  static final Flavor D8_FLAVOR = InternalFlavor.of("d8");
  static final Flavor EXTRACT_AND_REDEX_AAB = InternalFlavor.of("extract_and_redex_aab");
  private static final Flavor DEX_MERGE_SPLIT_FLAVOR = InternalFlavor.of("split_dex_merge");
  private static final Flavor DEX_MERGE_SINGLE_FLAVOR = InternalFlavor.of("single_dex_merge");
  private static final Flavor TRIM_UBER_R_DOT_JAVA_FLAVOR =
      InternalFlavor.of("trim_uber_r_dot_java");
  private static final Flavor COMPILE_UBER_R_DOT_JAVA_FLAVOR =
      InternalFlavor.of("compile_uber_r_dot_java");
  private static final Flavor SPLIT_UBER_R_DOT_JAVA_JAR_FLAVOR =
      InternalFlavor.of("split_uber_r_dot_java_jar");
  static final Flavor NATIVE_LIBRARY_PROGUARD_FLAVOR =
      InternalFlavor.of("generate_proguard_config_from_native_libs");
  static final Flavor UNSTRIPPED_NATIVE_LIBRARIES_FLAVOR =
      InternalFlavor.of("unstripped_native_libraries");
  static final Flavor PROGUARD_TEXT_OUTPUT_FLAVOR = InternalFlavor.of("proguard_text_output");
  static final Flavor NON_PREDEXED_DEX_BUILDABLE_FLAVOR =
      InternalFlavor.of("class_file_to_dex_processing");

  private final BuildTarget originalBuildTarget;
  private final SortedSet<BuildRule> originalDeps;
  private final ProjectFilesystem projectFilesystem;
  private final ToolchainProvider toolchainProvider;
  private final AndroidPlatformTarget androidPlatformTarget;
  private final BuildRuleParams buildRuleParams;
  private final boolean trimResourceIds;
  private final CellPathResolver cellPathResolver;
  private final boolean ignoreAaptProguardConfig;
  private final Optional<BuildTarget> nativeLibraryMergeCodeGenerator;
  private final ActionGraphBuilder graphBuilder;
  private final PackageType packageType;
  private final boolean shouldPreDex;
  private final DexSplitMode dexSplitMode;
  private final ImmutableSet<BuildTarget> buildTargetsToExcludeFromDex;
  private final ImmutableSet<BuildTarget> resourcesToExclude;
  private final ImmutableCollection<SourcePath> nativeLibsToExclude;
  private final ImmutableCollection<NativeLinkableGroup> nativeLinkablesToExcludeGroup;
  private final ImmutableCollection<SourcePath> nativeLibAssetsToExclude;
  private final ImmutableCollection<NativeLinkableGroup> nativeLinkablesAssetsToExcludeGroup;
  private final ImmutableCollection<SourcePath> nativeLibsForSystemLoaderToExclude;
  private final ImmutableCollection<NativeLinkableGroup>
      nativeLinkablesUsedByWrapScriptToExcludeGroup;
  private final JavaBuckConfig javaBuckConfig;
  private final JavaCDBuckConfig javaCDBuckConfig;
  private final DownwardApiConfig downwardApiConfig;
  private final Javac javac;
  private final JavacFactory javacFactory;
  private final JavacOptions javacOptions;
  private final EnumSet<ExopackageMode> exopackageModes;
  private final BuildConfigFields buildConfigValues;
  private final Optional<SourcePath> buildConfigValuesFile;
  private final int xzCompressionLevel;
  private final AndroidNativeLibsPackageableGraphEnhancer nativeLibsEnhancer;
  private final APKModuleGraph<BuildTarget> apkModuleGraph;
  private final ListeningExecutorService dxExecutorService;
  private final AndroidBinaryResourcesGraphEnhancer androidBinaryResourcesGraphEnhancer;
  private final NonPreDexedDexBuildable.NonPredexedDexBuildableArgs nonPreDexedDexBuildableArgs;
  private final Supplier<ImmutableSet<JavaLibrary>> rulesToExcludeFromDex;
  private final AndroidNativeTargetConfigurationMatcher androidNativeTargetConfigurationMatcher;
  private final int rDotJavaWeightFactor;
  private final int secondaryDexWeightLimit;
  private final ImmutableSet<String> resourcePackagesToExclude;
  private final Optional<Integer> minSdkVersion;

  AndroidBinaryGraphEnhancer(
      ToolchainProvider toolchainProvider,
      CellPathResolver cellPathResolver,
      BuildTarget originalBuildTarget,
      ProjectFilesystem projectFilesystem,
      AndroidPlatformTarget androidPlatformTarget,
      BuildRuleParams originalParams,
      ActionGraphBuilder graphBuilder,
      AaptMode aaptMode,
      ImmutableList<String> additionalAaptParams,
      ResourceCompressionMode resourceCompressionMode,
      ResourceFilter resourcesFilter,
      EnumSet<RType> bannedDuplicateResourceTypes,
      Optional<SourcePath> duplicateResourceWhitelistPath,
      Optional<String> resourceUnionPackage,
      ImmutableSet<String> locales,
      ImmutableSet<String> packagedLocales,
      Optional<SourcePath> manifest,
      Optional<SourcePath> manifestSkeleton,
      Optional<SourcePath> moduleManifestSkeleton,
      PackageType packageType,
      ImmutableSet<TargetCpuType> cpuFilters,
      boolean shouldBuildStringSourceMap,
      boolean shouldPreDex,
      DexSplitMode dexSplitMode,
      ImmutableSet<BuildTarget> buildTargetsToExcludeFromDex,
      ImmutableSet<BuildTarget> resourcesToExclude,
      ImmutableCollection<SourcePath> nativeLibsToExclude,
      ImmutableCollection<NativeLinkableGroup> nativeLinkablesToExcludeGroup,
      ImmutableCollection<SourcePath> nativeLibAssetsToExclude,
      ImmutableCollection<NativeLinkableGroup> nativeLinkableGroupAssetsToExclude,
      ImmutableCollection<SourcePath> nativeLibsForSystemLoaderToExclude,
      ImmutableCollection<NativeLinkableGroup> nativeLinkablesUsedByWrapScriptToExcludeGroup,
      boolean skipCrunchPngs,
      boolean includesVectorDrawables,
      boolean noAutoVersionResources,
      boolean noVersionTransitionsResources,
      boolean noAutoAddOverlayResources,
      boolean noResourceRemoval,
      JavaBuckConfig javaBuckConfig,
      JavaCDBuckConfig javaCDBuckConfig,
      DownwardApiConfig downwardApiConfig,
      BuildBuckConfig buildBuckConfig,
      JavacFactory javacFactory,
      JavacOptions javacOptions,
      EnumSet<ExopackageMode> exopackageModes,
      BuildConfigFields buildConfigValues,
      Optional<SourcePath> buildConfigValuesFile,
      int xzCompressionLevel,
      boolean trimResourceIds,
      boolean ignoreAaptProguardConfig,
      Optional<ImmutableMap<String, ImmutableList<Pattern>>> nativeLibraryMergeMap,
      Optional<ImmutableList<Pair<String, ImmutableList<Pattern>>>> nativeLibraryMergeSequence,
      Optional<ImmutableList<Pattern>> nativeLibraryMergeSequenceBlocklist,
      Optional<BuildTarget> nativeLibraryMergeGlue,
      Optional<BuildTarget> nativeLibraryMergeCodeGenerator,
      Optional<ImmutableSortedSet<String>> nativeLibraryMergeLocalizedSymbols,
      RelinkerMode relinkerMode,
      ImmutableList<Pattern> relinkerWhitelist,
      ListeningExecutorService dxExecutorService,
      ManifestEntries manifestEntries,
      CxxBuckConfig cxxBuckConfig,
      APKModuleGraph<BuildTarget> apkModuleGraph,
      Optional<Arg> postFilterResourcesCmd,
      NonPreDexedDexBuildable.NonPredexedDexBuildableArgs nonPreDexedDexBuildableArgs,
      Supplier<ImmutableSet<JavaLibrary>> rulesToExcludeFromDex,
      boolean useProtoFormat,
      AndroidNativeTargetConfigurationMatcher androidNativeTargetConfigurationMatcher,
      boolean useAapt2LocaleFiltering,
      boolean shouldAapt2KeepRawValues,
      ImmutableSet<String> extraFilteredResources,
      Optional<SourcePath> resourceStableIds,
      int rDotJavaWeightFactor,
      int secondaryDexWeightLimit,
      ImmutableSet<String> resourcePackagesToExclude) {
    this.cellPathResolver = cellPathResolver;
    this.downwardApiConfig = downwardApiConfig;
    this.ignoreAaptProguardConfig = ignoreAaptProguardConfig;
    this.androidPlatformTarget = androidPlatformTarget;
    this.minSdkVersion = manifestEntries.getMinSdkVersion();
    Preconditions.checkArgument(originalParams.getExtraDeps().get().isEmpty());
    this.projectFilesystem = projectFilesystem;
    this.toolchainProvider = toolchainProvider;
    this.buildRuleParams = originalParams;
    this.originalBuildTarget = originalBuildTarget;
    this.originalDeps = originalParams.getBuildDeps();
    this.graphBuilder = graphBuilder;
    this.packageType = packageType;
    this.shouldPreDex = shouldPreDex;
    this.dexSplitMode = dexSplitMode;
    this.buildTargetsToExcludeFromDex = buildTargetsToExcludeFromDex;
    this.resourcesToExclude = resourcesToExclude;
    this.nativeLibsToExclude = nativeLibsToExclude;
    this.nativeLinkablesToExcludeGroup = nativeLinkablesToExcludeGroup;
    this.nativeLibAssetsToExclude = nativeLibAssetsToExclude;
    this.nativeLinkablesAssetsToExcludeGroup = nativeLinkableGroupAssetsToExclude;
    this.nativeLibsForSystemLoaderToExclude = nativeLibsForSystemLoaderToExclude;
    this.nativeLinkablesUsedByWrapScriptToExcludeGroup =
        nativeLinkablesUsedByWrapScriptToExcludeGroup;
    this.javaBuckConfig = javaBuckConfig;
    this.javaCDBuckConfig = javaCDBuckConfig;
    this.javacOptions = javacOptions;
    this.exopackageModes = exopackageModes;
    this.buildConfigValues = buildConfigValues;
    this.buildConfigValuesFile = buildConfigValuesFile;
    this.dxExecutorService = dxExecutorService;
    this.xzCompressionLevel = xzCompressionLevel;
    this.trimResourceIds = trimResourceIds;
    this.nativeLibraryMergeCodeGenerator = nativeLibraryMergeCodeGenerator;
    this.nativeLibsEnhancer =
        new AndroidNativeLibsPackageableGraphEnhancer(
            toolchainProvider,
            cellPathResolver,
            graphBuilder,
            originalBuildTarget,
            projectFilesystem,
            cpuFilters,
            cxxBuckConfig,
            downwardApiConfig,
            nativeLibraryMergeMap,
            nativeLibraryMergeSequence,
            nativeLibraryMergeSequenceBlocklist,
            nativeLibraryMergeGlue,
            nativeLibraryMergeLocalizedSymbols,
            relinkerMode,
            relinkerWhitelist,
            apkModuleGraph,
            androidNativeTargetConfigurationMatcher);
    this.androidBinaryResourcesGraphEnhancer =
        new AndroidBinaryResourcesGraphEnhancer(
            originalBuildTarget,
            projectFilesystem,
            androidPlatformTarget,
            graphBuilder,
            originalBuildTarget,
            ExopackageMode.enabledForResources(exopackageModes),
            manifest,
            manifestSkeleton,
            moduleManifestSkeleton,
            aaptMode,
            additionalAaptParams,
            resourcesFilter,
            resourceCompressionMode,
            locales,
            packagedLocales,
            resourceUnionPackage,
            shouldBuildStringSourceMap,
            skipCrunchPngs,
            includesVectorDrawables,
            bannedDuplicateResourceTypes,
            duplicateResourceWhitelistPath,
            manifestEntries,
            postFilterResourcesCmd,
            noAutoVersionResources,
            noVersionTransitionsResources,
            noAutoAddOverlayResources,
            noResourceRemoval,
            apkModuleGraph,
            useProtoFormat,
            useAapt2LocaleFiltering,
            shouldAapt2KeepRawValues,
            extraFilteredResources,
            resourceStableIds,
            downwardApiConfig.isEnabledForAndroid(),
            buildBuckConfig.areExternalActionsEnabled());
    this.apkModuleGraph = apkModuleGraph;
    this.nonPreDexedDexBuildableArgs = nonPreDexedDexBuildableArgs;
    this.rulesToExcludeFromDex = rulesToExcludeFromDex;
    this.javacFactory = javacFactory;
    this.javac =
        javacFactory.create(graphBuilder, null, originalBuildTarget.getTargetConfiguration());
    this.androidNativeTargetConfigurationMatcher = androidNativeTargetConfigurationMatcher;
    this.rDotJavaWeightFactor = rDotJavaWeightFactor;
    this.secondaryDexWeightLimit = secondaryDexWeightLimit;
    this.resourcePackagesToExclude = resourcePackagesToExclude;
  }

  AndroidGraphEnhancementResult createAdditionalBuildables() {
    ImmutableList.Builder<BuildRule> additionalJavaLibrariesBuilder = ImmutableList.builder();

    AndroidPackageableCollector collector =
        new AndroidPackageableCollector(
            originalBuildTarget,
            buildTargetsToExcludeFromDex,
            resourcesToExclude,
            nativeLibsToExclude,
            nativeLinkablesToExcludeGroup,
            nativeLibAssetsToExclude,
            nativeLinkablesAssetsToExcludeGroup,
            nativeLibsForSystemLoaderToExclude,
            nativeLinkablesUsedByWrapScriptToExcludeGroup,
            apkModuleGraph,
            AndroidPackageableFilterFactory.createFromConfigurationMatcher(
                originalBuildTarget, androidNativeTargetConfigurationMatcher),
            Suppliers.memoize(
                () -> {
                  NdkCxxPlatformsProvider ndkCxxPlatformsProvider =
                      toolchainProvider.getByName(
                          NdkCxxPlatformsProvider.DEFAULT_NAME,
                          originalBuildTarget.getTargetConfiguration(),
                          NdkCxxPlatformsProvider.class);
                  return ndkCxxPlatformsProvider.getResolvedNdkCxxPlatforms(graphBuilder).values();
                }));
    collector.addPackageables(
        AndroidPackageableCollector.getPackageableRules(originalDeps), graphBuilder);
    AndroidPackageableCollection packageableCollection = collector.build();

    ImmutableList.Builder<SourcePath> proguardConfigsBuilder = ImmutableList.builder();
    proguardConfigsBuilder.addAll(packageableCollection.getProguardConfigs());

    AndroidNativeLibsGraphEnhancementResult nativeLibsEnhancementResult =
        nativeLibsEnhancer.enhance(packageableCollection);
    Optional<ImmutableMap<APKModule, CopyNativeLibraries>> copyNativeLibraries =
        nativeLibsEnhancementResult.getCopyNativeLibraries();
    copyNativeLibraries.ifPresent(
        apkModuleCopyNativeLibrariesImmutableMap ->
            apkModuleCopyNativeLibrariesImmutableMap.values().forEach(graphBuilder::addToIndex));

    nativeLibsEnhancementResult
        .getCopyNativeLibrariesForSystemLibraryLoader()
        .ifPresent(graphBuilder::addToIndex);

    if (nativeLibsEnhancementResult.getUnstrippedLibraries().isPresent()) {
      UnstrippedNativeLibraries unstrippedNativeLibraries =
          new UnstrippedNativeLibraries(
              originalBuildTarget.withAppendedFlavors(UNSTRIPPED_NATIVE_LIBRARIES_FLAVOR),
              projectFilesystem,
              buildRuleParams.withoutDeclaredDeps(),
              graphBuilder,
              nativeLibsEnhancementResult.getUnstrippedLibraries().get());
      graphBuilder.addToIndex(unstrippedNativeLibraries);
    }

    nativeLibsEnhancer.addNativeMergeMapGenCode(
        nativeLibsEnhancementResult,
        nativeLibraryMergeCodeGenerator,
        projectFilesystem,
        buildRuleParams,
        downwardApiConfig,
        additionalJavaLibrariesBuilder,
        javacOptions,
        javaBuckConfig,
        javaCDBuckConfig,
        javacFactory);

    AndroidBinaryResourcesGraphEnhancer.AndroidBinaryResourcesGraphEnhancementResult
        resourcesEnhancementResult =
            androidBinaryResourcesGraphEnhancer.enhance(packageableCollection);

    // BuildConfig deps should not be added for instrumented APKs because BuildConfig.class has
    // already been added to the APK under test.
    if (packageType != PackageType.INSTRUMENTED) {
      ImmutableSortedSet<JavaLibrary> buildConfigDepsRules =
          addBuildConfigDeps(
              originalBuildTarget,
              projectFilesystem,
              packageType,
              exopackageModes,
              buildConfigValues,
              buildConfigValuesFile,
              graphBuilder,
              javac,
              javacOptions,
              packageableCollection,
              downwardApiConfig.isEnabledForAndroid(),
              javaBuckConfig
                  .getDelegate()
                  .getView(BuildBuckConfig.class)
                  .areExternalActionsEnabled(),
              JavaCDParams.get(javaBuckConfig, javaCDBuckConfig));
      additionalJavaLibrariesBuilder.addAll(buildConfigDepsRules);
    }

    ImmutableList<BuildRule> additionalJavaLibraries = additionalJavaLibrariesBuilder.build();
    ImmutableSet<SourcePath> classpathEntriesToDex =
        ImmutableSet.<SourcePath>builder()
            .addAll(packageableCollection.getClasspathEntriesToDex())
            .addAll(
                additionalJavaLibraries.stream()
                    .map(BuildRule::getSourcePathToOutput)
                    .collect(ImmutableList.toImmutableList()))
            .build();
    if (!ignoreAaptProguardConfig) {
      proguardConfigsBuilder.addAll(
          resourcesEnhancementResult.getAaptGeneratedProguardConfigFiles());
    }
    ImmutableList<SourcePath> proguardConfigs = proguardConfigsBuilder.build();

    HasDexFiles dexMergeRule;
    if (shouldPreDex) {
      ImmutableList<DexProducedFromJavaLibrary> preDexedLibrariesExceptRDotJava =
          createPreDexRulesForLibraries(additionalJavaLibraries, packageableCollection);

      if (dexSplitMode.isShouldSplitDex()) {
        dexMergeRule =
            createPreDexMergeSplitDexRule(
                preDexedLibrariesExceptRDotJava, resourcesEnhancementResult);
      } else {
        ImmutableCollection<DexProducedFromJavaLibrary> dexUberRDotJavaParts =
            createUberRDotJavaDexes(resourcesEnhancementResult, preDexedLibrariesExceptRDotJava);
        dexMergeRule =
            createPreDexMergeSingleDexRule(
                ImmutableList.copyOf(
                    Iterables.concat(preDexedLibrariesExceptRDotJava, dexUberRDotJavaParts)));
      }
    } else {
      JavaLibrary compileUberRDotJava =
          createTrimAndCompileUberRDotJava(resourcesEnhancementResult, ImmutableList.of());
      dexMergeRule =
          createNonPredexedDexBuildable(
              dexSplitMode,
              rulesToExcludeFromDex.get(),
              xzCompressionLevel,
              proguardConfigs,
              packageableCollection,
              classpathEntriesToDex,
              compileUberRDotJava,
              javaBuckConfig.shouldDesugarInterfaceMethods(),
              downwardApiConfig.isEnabledForAndroid());
    }

    return ImmutableAndroidGraphEnhancementResult.builder()
        .setPackageableCollection(packageableCollection)
        .setPrimaryResourcesApkPath(resourcesEnhancementResult.getPrimaryResourcesApkPath())
        .setPrimaryApkAssetZips(resourcesEnhancementResult.getPrimaryApkAssetZips())
        .setExoResources(resourcesEnhancementResult.getExoResources())
        .setAndroidManifestPath(resourcesEnhancementResult.getAndroidManifestXml())
        .setCopyNativeLibraries(copyNativeLibraries)
        .setPackageStringAssets(resourcesEnhancementResult.getPackageStringAssets())
        .setDexMergeRule(dexMergeRule)
        .setClasspathEntriesToDex(classpathEntriesToDex)
        .setAPKModuleGraph(apkModuleGraph)
        .setModuleResourceApkPaths(resourcesEnhancementResult.getModuleResourceApkPaths())
        .setCopyNativeLibrariesForSystemLibraryLoader(
            nativeLibsEnhancementResult.getCopyNativeLibrariesForSystemLibraryLoader())
        .build();
  }

  @VisibleForTesting
  public AndroidBinaryResourcesGraphEnhancer getResourcesGraphEnhancer() {
    return androidBinaryResourcesGraphEnhancer;
  }

  private ImmutableList<DexProducedFromJavaLibrary> createUberRDotJavaDexes(
      AndroidBinaryResourcesGraphEnhancer.AndroidBinaryResourcesGraphEnhancementResult
          resourcesEnhancementResult,
      ImmutableList<? extends TrimUberRDotJava.UsesResources> preDexedLibrariesExceptRDotJava) {
    JavaLibrary compileUberRDotJava =
        createTrimAndCompileUberRDotJava(
            resourcesEnhancementResult, preDexedLibrariesExceptRDotJava);
    return createSplitAndDexUberRDotJava(compileUberRDotJava);
  }

  private JavaLibrary createTrimAndCompileUberRDotJava(
      AndroidBinaryResourcesGraphEnhancer.AndroidBinaryResourcesGraphEnhancementResult
          resourcesEnhancementResult,
      ImmutableList<? extends TrimUberRDotJava.UsesResources> preDexedLibrariesExceptRDotJava) {
    // Create rule to trim uber R.java sources.
    Collection<? extends TrimUberRDotJava.UsesResources> preDexedLibrariesForResourceIdFiltering =
        trimResourceIds ? preDexedLibrariesExceptRDotJava : ImmutableList.of();
    BuildRuleParams paramsForTrimUberRDotJava =
        buildRuleParams.withDeclaredDeps(
            ImmutableSortedSet.<BuildRule>naturalOrder()
                .addAll(
                    graphBuilder.filterBuildRuleInputs(
                        resourcesEnhancementResult.getRDotJavaDir().orElse(null)))
                .addAll(preDexedLibrariesForResourceIdFiltering)
                .build());
    TrimUberRDotJava trimUberRDotJava =
        new TrimUberRDotJava(
            originalBuildTarget.withAppendedFlavors(TRIM_UBER_R_DOT_JAVA_FLAVOR),
            projectFilesystem,
            paramsForTrimUberRDotJava,
            resourcesEnhancementResult.getRDotJavaDir(),
            preDexedLibrariesForResourceIdFiltering);
    graphBuilder.addToIndex(trimUberRDotJava);

    // Create rule to compile uber R.java sources.
    BuildRuleParams paramsForCompileUberRDotJava =
        buildRuleParams.withDeclaredDeps(ImmutableSortedSet.of(trimUberRDotJava));

    DefaultJavaLibraryRules.Builder compileUberRDotJavaBuilder =
        DefaultJavaLibrary.rulesBuilder(
                originalBuildTarget.withAppendedFlavors(COMPILE_UBER_R_DOT_JAVA_FLAVOR),
                projectFilesystem,
                toolchainProvider,
                paramsForCompileUberRDotJava,
                graphBuilder,
                new JavaConfiguredCompilerFactory(
                    javaBuckConfig, javaCDBuckConfig, downwardApiConfig, javacFactory),
                javaBuckConfig,
                downwardApiConfig,
                null,
                cellPathResolver)
            .setJavacOptions(
                javacOptions.withLanguageLevelOptions(
                    JavacLanguageLevelOptions.builder()
                        .setSourceLevel(JavacLanguageLevelOptions.TARGETED_JAVA_VERSION)
                        .setTargetLevel(JavacLanguageLevelOptions.TARGETED_JAVA_VERSION)
                        .build()))
            .setSrcs(ImmutableSortedSet.of(trimUberRDotJava.getSourcePathToOutput()))
            .setSourceOnlyAbisAllowed(false)
            .setDeps(
                new JavaLibraryDeps.Builder(graphBuilder)
                    .addAllDepTargets(
                        paramsForCompileUberRDotJava.getDeclaredDeps().get().stream()
                            .map(BuildRule::getBuildTarget)
                            .collect(Collectors.toList()))
                    .build());

    if (!resourcePackagesToExclude.isEmpty()) {
      ImmutableSet<Pattern> excludedResourcePackagePatterns =
          resourcePackagesToExclude.stream()
              .map(pkg -> "^" + pkg.replaceAll("\\.", "[.]") + "[.]R([$].*)?$")
              .map(Pattern::compile)
              .collect(ImmutableSet.toImmutableSet());
      compileUberRDotJavaBuilder.setClassesToRemoveFromJar(
          new RemoveClassesPatternsMatcher(excludedResourcePackagePatterns));
    }
    DefaultJavaLibrary compileUberRDotJava = compileUberRDotJavaBuilder.build().buildLibrary();
    graphBuilder.addToIndex(compileUberRDotJava);
    return compileUberRDotJava;
  }

  private ImmutableList<DexProducedFromJavaLibrary> createSplitAndDexUberRDotJava(
      JavaLibrary compileUberRDotJava) {
    // Create rule to split the compiled R.java into multiple smaller jars.
    BuildTarget splitJarTarget =
        originalBuildTarget.withAppendedFlavors(SPLIT_UBER_R_DOT_JAVA_JAR_FLAVOR);
    SplitUberRDotJavaJar splitJar =
        new SplitUberRDotJavaJar(
            splitJarTarget,
            projectFilesystem,
            graphBuilder,
            compileUberRDotJava.getSourcePathToOutput(),
            dexSplitMode);
    graphBuilder.addToIndex(splitJar);

    // Create rules to dex uber R.java jars.
    ImmutableList.Builder<DexProducedFromJavaLibrary> builder = ImmutableList.builder();
    Flavor prebuiltJarFlavor = InternalFlavor.of("prebuilt_jar");
    Flavor dexFlavor = InternalFlavor.of("dexing");
    for (Entry<String, BuildTargetSourcePath> entry : splitJar.getOutputJars().entrySet()) {
      String rtype = entry.getKey();
      BuildTargetSourcePath jarPath = entry.getValue();
      InternalFlavor rtypeFlavor = InternalFlavor.of("rtype_" + rtype);
      PrebuiltJar prebuiltJar =
          new PrebuiltJar(
              splitJarTarget.withAppendedFlavors(prebuiltJarFlavor, rtypeFlavor),
              projectFilesystem,
              buildRuleParams.withDeclaredDeps(
                  ImmutableSortedSet.of(graphBuilder.getRule(jarPath))),
              graphBuilder.getSourcePathResolver(),
              jarPath,
              Optional.empty(),
              false,
              true,
              false,
              false);
      graphBuilder.addToIndex(prebuiltJar);

      // For the primary dex, don't scale our weight estimate.  Just try to fit it and hope for
      // the best.  For secondary dexes, scale the estimate by a constant factor because R.java
      // classes are relatively small but consume a lot of field-id space.  The constant value
      // was determined empirically and unscientifically.
      int weightFactor =
          !dexSplitMode.isAllowRDotJavaInSecondaryDex() || rtype.equals("_primarydex")
              ? 1
              : rDotJavaWeightFactor;
      DexProducedFromJavaLibrary dexJar =
          new DexProducedFromJavaLibrary(
              createD8Target(splitJarTarget.withAppendedFlavors(dexFlavor, rtypeFlavor)),
              projectFilesystem,
              graphBuilder,
              androidPlatformTarget,
              prebuiltJar,
              weightFactor,
              ImmutableSortedSet.of(),
              downwardApiConfig.isEnabledForAndroid(),
              minSdkVersion);
      graphBuilder.addToIndex(dexJar);
      builder.add(dexJar);
    }

    return builder.build();
  }

  private BuildTarget createD8Target(BuildTarget target) {
    if (minSdkVersion.isPresent()) {
      return target.withAppendedFlavors(
          D8_FLAVOR, InternalFlavor.of("min-api-" + minSdkVersion.get()));
    }
    return target.withAppendedFlavors(D8_FLAVOR);
  }

  /**
   * If the user specified any android_build_config() rules, then we must add some build rules to
   * generate the production {@code BuildConfig.class} files and ensure that they are included in
   * the list of {@link AndroidPackageableCollection#getClasspathEntriesToDex}.
   */
  public static ImmutableSortedSet<JavaLibrary> addBuildConfigDeps(
      BuildTarget originalBuildTarget,
      ProjectFilesystem projectFilesystem,
      PackageType packageType,
      EnumSet<ExopackageMode> exopackageModes,
      BuildConfigFields buildConfigValues,
      Optional<SourcePath> buildConfigValuesFile,
      ActionGraphBuilder graphBuilder,
      Javac javac,
      JavacOptions javacOptions,
      AndroidPackageableCollection packageableCollection,
      boolean withDownwardApi,
      boolean shouldExecuteInSeparateProcess,
      RulesCDParams javaCDParams) {
    ImmutableSortedSet.Builder<JavaLibrary> result = ImmutableSortedSet.naturalOrder();
    BuildConfigFields buildConfigConstants =
        BuildConfigFields.fromFields(
            ImmutableList.of(
                BuildConfigFields.Field.of(
                    "boolean",
                    BuildConfigs.DEBUG_CONSTANT,
                    String.valueOf(packageType != PackageType.RELEASE)),
                BuildConfigFields.Field.of(
                    "boolean",
                    BuildConfigs.IS_EXO_CONSTANT,
                    String.valueOf(!exopackageModes.isEmpty())),
                BuildConfigFields.Field.of(
                    "int",
                    BuildConfigs.EXOPACKAGE_FLAGS,
                    String.valueOf(ExopackageMode.toBitmask(exopackageModes)))));
    for (Entry<String, BuildConfigFields> entry :
        packageableCollection.getBuildConfigs().entrySet()) {
      // Merge the user-defined constants with the APK-specific overrides.
      BuildConfigFields totalBuildConfigValues =
          BuildConfigFields.of()
              .putAll(entry.getValue())
              .putAll(buildConfigValues)
              .putAll(buildConfigConstants);

      // Each enhanced dep needs a unique build target, so we parameterize the build target by the
      // Java package.
      String javaPackage = entry.getKey();
      Flavor flavor = InternalFlavor.of("buildconfig_" + javaPackage.replace('.', '_'));
      BuildTarget buildTargetWithFlavors = originalBuildTarget.withAppendedFlavors(flavor);
      JavaLibrary buildConfigJavaLibrary =
          AndroidBuildConfigDescription.createBuildRule(
              buildTargetWithFlavors,
              projectFilesystem,
              javaPackage,
              totalBuildConfigValues,
              buildConfigValuesFile,
              /* useConstantExpressions */ true,
              javac,
              javacOptions,
              graphBuilder,
              withDownwardApi,
              shouldExecuteInSeparateProcess,
              javaCDParams);
      graphBuilder.addToIndex(buildConfigJavaLibrary);

      Preconditions.checkNotNull(
          buildConfigJavaLibrary.getSourcePathToOutput(),
          "%s must have an output file.",
          buildConfigJavaLibrary);
      result.add(buildConfigJavaLibrary);
    }
    return result.build();
  }

  PreDexSplitDexGroup createPreDexGroupRule(
      APKModule apkModule,
      Collection<DexProducedFromJavaLibrary> dexes,
      Flavor flavor,
      Optional<Integer> group) {
    return new PreDexSplitDexGroup(
        originalBuildTarget.withFlavors(flavor),
        projectFilesystem,
        buildRuleParams.withDeclaredDeps(ImmutableSortedSet.copyOf(dexes)),
        androidPlatformTarget,
        dexSplitMode,
        apkModuleGraph,
        apkModule,
        dexes,
        dxExecutorService,
        xzCompressionLevel,
        group,
        secondaryDexWeightLimit);
  }

  /**
   * Group DexProducedFromJavaLibrary rules by module, into partitions of at most
   * dex_group_lib_limit. Create a single partition per APK module if dex_group_lib_limit is 0
   */
  private ImmutableMultimap<APKModule, List<DexProducedFromJavaLibrary>> groupDexes(
      ImmutableMultimap<APKModule, DexProducedFromJavaLibrary> dexFilesToMerge) {
    ImmutableMultimap.Builder<APKModule, List<DexProducedFromJavaLibrary>> resultBuilder =
        ImmutableMultimap.builder();

    int limit = dexSplitMode.getDexGroupLibLimit();
    for (APKModule module : dexFilesToMerge.keySet()) {
      List<DexProducedFromJavaLibrary> currentDexContents = null;
      for (DexProducedFromJavaLibrary dexWithClasses : dexFilesToMerge.get(module)) {
        if (currentDexContents == null || (limit != 0 && currentDexContents.size() + 1 > limit)) {
          currentDexContents = new ArrayList<>();
          resultBuilder.put(module, currentDexContents);
        }
        currentDexContents.add(dexWithClasses);
      }
    }
    return resultBuilder.build();
  }

  /**
   * Creates/finds the set of build rules that correspond to pre-dex'd artifacts that should be
   * merged to create the final classes.dex for the APK.
   *
   * <p>This method may modify {@code graphBuilder}, inserting new rules into its index.
   */
  @VisibleForTesting
  PreDexSplitDexMerge createPreDexMergeSplitDexRule(
      ImmutableList<DexProducedFromJavaLibrary> preDexedLibrariesExceptRDotJava,
      AndroidBinaryResourcesGraphEnhancer.AndroidBinaryResourcesGraphEnhancementResult
          resourcesEnhancementResult) {

    ImmutableMultimap.Builder<APKModule, DexProducedFromJavaLibrary> moduleDexesBuilder =
        ImmutableMultimap.builder();
    for (DexProducedFromJavaLibrary dex : preDexedLibrariesExceptRDotJava) {
      moduleDexesBuilder.put(
          apkModuleGraph.findModuleForTarget(dex.getJavaLibraryBuildTarget()), dex);
    }

    ImmutableList.Builder<PreDexSplitDexGroup> dexGroupsBuilder = ImmutableList.builder();

    // Dex group partitioning is currently limited to JAR compression mode.
    // XZS (and possibly XZ) aggregate dexes in a way that is not compatible with dex group merging
    // RAW expects dex files to have a single sequential index, and could break on some API levels
    // if more than 100 total dex files are produced, which is more likely to happen with dex
    // groups.
    if (dexSplitMode.getDexStore() != DexStore.JAR || dexSplitMode.getDexGroupLibLimit() == 0) {
      ImmutableCollection<DexProducedFromJavaLibrary> dexUberRDotJavaParts =
          createUberRDotJavaDexes(resourcesEnhancementResult, preDexedLibrariesExceptRDotJava);
      moduleDexesBuilder.putAll(apkModuleGraph.getRootAPKModule(), dexUberRDotJavaParts);

      ImmutableMultimap<APKModule, DexProducedFromJavaLibrary> dexFilesToMerge =
          moduleDexesBuilder.build();
      for (APKModule module : dexFilesToMerge.keySet()) {
        String moduleName = module.isRootModule() ? "" : module.getName() + "_";
        dexGroupsBuilder.add(
            createPreDexGroupRule(
                module,
                dexFilesToMerge.get(module),
                InternalFlavor.of(moduleName + "pre_dex_group"),
                Optional.empty()));
      }
    } else {
      ImmutableMultimap<APKModule, DexProducedFromJavaLibrary> moduleDexes =
          moduleDexesBuilder.build();
      ImmutableMultimap<APKModule, List<DexProducedFromJavaLibrary>> dexGroupMap =
          groupDexes(moduleDexes);
      ImmutableList.Builder<PreDexSplitDexGroup> groupsBuilder = ImmutableList.builder();
      for (APKModule module : dexGroupMap.keySet()) {
        int i = 1;
        String moduleName = module.isRootModule() ? "" : module.getName() + "_";
        String ruleNameFormat = moduleName + "pre_dex_group_%d";
        for (List<DexProducedFromJavaLibrary> dexes : dexGroupMap.get(module)) {
          groupsBuilder.add(
              createPreDexGroupRule(
                  module,
                  dexes,
                  InternalFlavor.of(String.format(ruleNameFormat, i)),
                  Optional.of(i)));
          i += 1;
        }
      }
      // Make trim resources rule depend on dex groups instead of predexed libs so that only
      // groups need to be fetched.
      ImmutableList<PreDexSplitDexGroup> dexGroupsExceptRDotJava = groupsBuilder.build();
      ImmutableCollection<DexProducedFromJavaLibrary> dexUberRDotJavaParts =
          createUberRDotJavaDexes(resourcesEnhancementResult, dexGroupsExceptRDotJava);

      // Make sure this index is larger than the last index used for secondary dexes in the root
      // APK module, so that an existing secondary dex can't be overwritten in the merge step.
      int finalDexGroupIndex = dexGroupMap.get(apkModuleGraph.getRootAPKModule()).size() + 1;
      PreDexSplitDexGroup rDotJavaDex =
          createPreDexGroupRule(
              apkModuleGraph.getRootAPKModule(),
              dexUberRDotJavaParts,
              InternalFlavor.of("r_dot_java_dex"),
              Optional.of(finalDexGroupIndex));
      dexGroupsBuilder.addAll(dexGroupsExceptRDotJava);
      dexGroupsBuilder.add(rDotJavaDex);
    }

    ImmutableList<PreDexSplitDexGroup> dexGroupRules = dexGroupsBuilder.build();
    for (PreDexSplitDexGroup rule : dexGroupRules) {
      graphBuilder.addToIndex(rule);
    }

    PreDexSplitDexMerge superDexMergeRule =
        new PreDexSplitDexMerge(
            originalBuildTarget.withAppendedFlavors(
                DEX_MERGE_SPLIT_FLAVOR, D8_FLAVOR, InternalFlavor.of("split_dex_merge")),
            projectFilesystem,
            buildRuleParams.withDeclaredDeps(ImmutableSortedSet.copyOf(dexGroupRules)),
            androidPlatformTarget,
            dexSplitMode,
            apkModuleGraph,
            dexGroupRules,
            dxExecutorService,
            xzCompressionLevel);
    graphBuilder.addToIndex(superDexMergeRule);

    return superDexMergeRule;
  }

  /**
   * Creates/finds the set of build rules that correspond to pre-dex'd artifacts that should be
   * merged to create the final classes.dex for the APK.
   *
   * <p>This method may modify {@code graphBuilder}, inserting new rules into its index.
   */
  @VisibleForTesting
  PreDexSingleDexMerge createPreDexMergeSingleDexRule(
      Collection<DexProducedFromJavaLibrary> allPreDexDeps) {
    PreDexSingleDexMerge preDexMerge =
        new PreDexSingleDexMerge(
            originalBuildTarget.withAppendedFlavors(DEX_MERGE_SINGLE_FLAVOR, D8_FLAVOR),
            projectFilesystem,
            buildRuleParams.withDeclaredDeps(ImmutableSortedSet.copyOf(allPreDexDeps)),
            androidPlatformTarget,
            allPreDexDeps,
            downwardApiConfig.isEnabledForAndroid());
    graphBuilder.addToIndex(preDexMerge);
    return preDexMerge;
  }

  @VisibleForTesting
  ImmutableList<DexProducedFromJavaLibrary> createPreDexRulesForLibraries(
      Iterable<BuildRule> additionalJavaLibrariesToDex,
      AndroidPackageableCollection packageableCollection) {
    Iterable<BuildTarget> additionalJavaLibraryTargets =
        FluentIterable.from(additionalJavaLibrariesToDex).transform(BuildRule::getBuildTarget);
    ImmutableList.Builder<DexProducedFromJavaLibrary> preDexDeps = ImmutableList.builder();
    for (BuildTarget buildTarget :
        Iterables.concat(
            packageableCollection.getJavaLibrariesToDex(), additionalJavaLibraryTargets)) {
      Preconditions.checkState(
          !buildTargetsToExcludeFromDex.contains(buildTarget),
          "JavaLibrary should have been excluded from target to dex: %s",
          buildTarget);

      BuildRule libraryRule = graphBuilder.getRule(buildTarget);

      Preconditions.checkState(libraryRule instanceof JavaLibrary);
      JavaLibrary javaLibrary = (JavaLibrary) libraryRule;

      // If the rule has no output file (which happens when a java_library has no srcs or
      // resources, but export_deps is true), then there will not be anything to dx.
      if (javaLibrary.getSourcePathToOutput() == null) {
        continue;
      }

      BuildRule preDexRule =
          graphBuilder.computeIfAbsent(
              createD8Target(javaLibrary.getBuildTarget()),
              preDexTarget -> {
                ImmutableSortedSet<SourcePath> desugarDeps =
                    javaLibrary.isDesugarEnabled() && javaLibrary.isInterfaceMethodsDesugarEnabled()
                        ? ImmutableSortedSet.copyOf(javaLibrary.getDesugarDeps())
                        : ImmutableSortedSet.of();

                return new DexProducedFromJavaLibrary(
                    preDexTarget,
                    javaLibrary.getProjectFilesystem(),
                    graphBuilder,
                    androidPlatformTarget,
                    javaLibrary,
                    1,
                    desugarDeps,
                    downwardApiConfig.isEnabledForAndroid(),
                    minSdkVersion);
              });
      preDexDeps.add((DexProducedFromJavaLibrary) preDexRule);
    }
    return preDexDeps.build();
  }

  private NonPreDexedDexBuildable createNonPredexedDexBuildable(
      DexSplitMode dexSplitMode,
      ImmutableSet<JavaLibrary> rulesToExcludeFromDex,
      int xzCompressionLevel,
      ImmutableList<SourcePath> proguardConfigs,
      AndroidPackageableCollection packageableCollection,
      ImmutableSet<SourcePath> classpathEntriesToDex,
      JavaLibrary compiledUberRDotJava,
      boolean desugarInterfaceMethods,
      boolean withDownwardApi) {
    ImmutableSortedMap<APKModule, ImmutableSortedSet<APKModule>> apkModuleMap =
        apkModuleGraph.toOutgoingEdgesMap();
    APKModule rootAPKModule = apkModuleGraph.getRootAPKModule();

    ImmutableSortedSet<SourcePath> additionalJarsForProguardandDesugar =
        rulesToExcludeFromDex.stream()
            .flatMap((javaLibrary) -> javaLibrary.getImmediateClasspaths().stream())
            .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural()));

    ImmutableSet<SourcePath> classpathEntriesToDexSourcePaths =
        RichStream.from(classpathEntriesToDex)
            .concat(RichStream.of(compiledUberRDotJava.getSourcePathToOutput()))
            .collect(ImmutableSet.toImmutableSet());
    Optional<ImmutableSortedMap<APKModule, ImmutableList<SourcePath>>>
        moduleMappedClasspathEntriesToDex =
            Optional.of(
                MoreMaps.convertMultimapToMapOfLists(
                    packageableCollection.getModuleMappedClasspathEntriesToDex()));
    NonPreDexedDexBuildable nonPreDexedDexBuildable =
        new NonPreDexedDexBuildable(
            androidPlatformTarget,
            graphBuilder,
            additionalJarsForProguardandDesugar,
            apkModuleMap,
            classpathEntriesToDexSourcePaths,
            dexSplitMode,
            moduleMappedClasspathEntriesToDex,
            proguardConfigs,
            rootAPKModule,
            xzCompressionLevel,
            dexSplitMode.isShouldSplitDex(),
            nonPreDexedDexBuildableArgs,
            projectFilesystem,
            originalBuildTarget.withFlavors(NON_PREDEXED_DEX_BUILDABLE_FLAVOR),
            desugarInterfaceMethods,
            withDownwardApi,
            minSdkVersion);
    graphBuilder.addToIndex(nonPreDexedDexBuildable);

    if (nonPreDexedDexBuildableArgs.getShouldProguard()) {
      ProguardTextOutput proguardTextOutput =
          new ProguardTextOutput(
              originalBuildTarget.withFlavors(PROGUARD_TEXT_OUTPUT_FLAVOR),
              nonPreDexedDexBuildable,
              graphBuilder);
      graphBuilder.addToIndex(proguardTextOutput);
    }

    return nonPreDexedDexBuildable;
  }
}
