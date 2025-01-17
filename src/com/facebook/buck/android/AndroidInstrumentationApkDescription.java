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

import com.facebook.buck.android.ResourcesFilter.ResourceCompressionMode;
import com.facebook.buck.android.aapt.RDotTxtEntry.RType;
import com.facebook.buck.android.apkmodule.APKModule;
import com.facebook.buck.android.apkmodule.APKModuleGraph;
import com.facebook.buck.android.build_config.BuildConfigFields;
import com.facebook.buck.android.exopackage.ExopackageMode;
import com.facebook.buck.android.packageable.AndroidPackageableCollection;
import com.facebook.buck.android.toolchain.AndroidPlatformTarget;
import com.facebook.buck.android.toolchain.AndroidSdkLocation;
import com.facebook.buck.android.toolchain.AndroidTools;
import com.facebook.buck.android.toolchain.DxToolchain;
import com.facebook.buck.command.config.BuildBuckConfig;
import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.description.arg.HasDeclaredDeps;
import com.facebook.buck.core.description.attr.ImplicitDepsInferringDescription;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.facebook.buck.cxx.config.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableGroup;
import com.facebook.buck.downwardapi.config.DownwardApiConfig;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.jvm.java.JavaCDBuckConfig;
import com.facebook.buck.jvm.java.JavacFactory;
import com.facebook.buck.jvm.java.toolchain.JavacOptionsProvider;
import com.facebook.buck.step.fs.XzStep;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableCollection.Builder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.util.EnumSet;
import java.util.Optional;
import java.util.function.Supplier;
import org.immutables.value.Value;

public class AndroidInstrumentationApkDescription
    implements DescriptionWithTargetGraph<AndroidInstrumentationApkDescriptionArg>,
        ImplicitDepsInferringDescription<AndroidInstrumentationApkDescriptionArg> {

  private final JavaBuckConfig javaBuckConfig;
  private final JavaCDBuckConfig javaCDBuckConfig;
  private final ProGuardConfig proGuardConfig;
  private final CxxBuckConfig cxxBuckConfig;
  private final ToolchainProvider toolchainProvider;
  private final AndroidBuckConfig androidBuckConfig;
  private final JavacFactory javacFactory;
  private final DownwardApiConfig downwardApiConfig;
  private final BuildBuckConfig buildBuckConfig;

  public AndroidInstrumentationApkDescription(
      JavaBuckConfig javaBuckConfig,
      JavaCDBuckConfig javaCDBuckConfig,
      ProGuardConfig proGuardConfig,
      CxxBuckConfig cxxBuckConfig,
      ToolchainProvider toolchainProvider,
      AndroidBuckConfig androidBuckConfig,
      DownwardApiConfig downwardApiConfig,
      BuildBuckConfig buildBuckConfig) {
    this.javaBuckConfig = javaBuckConfig;
    this.javaCDBuckConfig = javaCDBuckConfig;
    this.proGuardConfig = proGuardConfig;
    this.cxxBuckConfig = cxxBuckConfig;
    this.toolchainProvider = toolchainProvider;
    this.javacFactory = JavacFactory.getDefault(toolchainProvider);
    this.androidBuckConfig = androidBuckConfig;
    this.downwardApiConfig = downwardApiConfig;
    this.buildBuckConfig = buildBuckConfig;
  }

  @Override
  public Class<AndroidInstrumentationApkDescriptionArg> getConstructorArgType() {
    return AndroidInstrumentationApkDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      AndroidInstrumentationApkDescriptionArg args) {
    ActionGraphBuilder graphBuilder = context.getActionGraphBuilder();
    params = params.withoutExtraDeps();
    BuildRule installableApk = graphBuilder.getRule(args.getApk());
    if (!(installableApk instanceof HasInstallableApk)
        || (installableApk instanceof AndroidInstrumentationApk)) {
      throw new HumanReadableException(
          "In %s, apk='%s' must be an android_binary() or apk_genrule() but was %s().",
          buildTarget, installableApk.getFullyQualifiedName(), installableApk.getType());
    }
    AndroidApk apkUnderTest =
        ApkGenruleDescription.getUnderlyingApk((HasInstallableApk) installableApk);

    ImmutableSet<JavaLibrary> apkUnderTestTransitiveClasspathDeps =
        apkUnderTest.getTransitiveClasspathDeps();

    ImmutableSet.Builder<BuildTarget> buildTargetsToExclude = ImmutableSet.builder();
    apkUnderTest.getRulesToExcludeFromDex().get().stream()
        .map(BuildRule::getBuildTarget)
        .forEach(buildTargetsToExclude::add);
    apkUnderTestTransitiveClasspathDeps.stream()
        .map(BuildRule::getBuildTarget)
        .forEach(buildTargetsToExclude::add);

    APKModule rootAPKModule = APKModule.of(APKModule.ROOT_APKMODULE_NAME);
    AndroidPackageableCollection.ResourceDetails resourceDetails =
        apkUnderTest.getAndroidPackageableCollection().getResourceDetails().get(rootAPKModule);
    ImmutableSet<BuildTarget> resourcesToExclude =
        ImmutableSet.copyOf(
            Iterables.concat(
                resourceDetails.getResourcesWithNonEmptyResDir(),
                resourceDetails.getResourcesWithEmptyResButNonEmptyAssetsDir()));

    // Exclude package names used by the APK under test to avoid creating conflicting R classes
    ImmutableSet<String> resourcePackagesToExclude = resourceDetails.getResourcePackages();

    ImmutableCollection<SourcePath> nativeLibsToExclude =
        apkUnderTest
            .getAndroidPackageableCollection()
            .getNativeLibsDirectories()
            .get(rootAPKModule);

    ImmutableCollection<NativeLinkableGroup> nativeLinkablesToExcludeGroup =
        apkUnderTest.getAndroidPackageableCollection().getNativeLinkables().get(rootAPKModule);

    ImmutableCollection<SourcePath> nativeLibAssetsToExclude =
        apkUnderTest
            .getAndroidPackageableCollection()
            .getNativeLibAssetsDirectories()
            .get(rootAPKModule);

    ImmutableCollection<NativeLinkableGroup> nativeLinkableGroupAssetsToExclude =
        apkUnderTest
            .getAndroidPackageableCollection()
            .getNativeLinkablesAssets()
            .get(rootAPKModule);

    ImmutableCollection<SourcePath> nativeLibsForSystemLoaderToExclude =
        apkUnderTest.getAndroidPackageableCollection().getNativeLibsDirectoriesForSystemLoader();

    ImmutableCollection<NativeLinkableGroup> nativeLinkablesUsedByWrapScriptToExcludeGroup =
        apkUnderTest.getAndroidPackageableCollection().getNativeLinkablesUsedByWrapScript();

    ListeningExecutorService dxExecutorService =
        toolchainProvider
            .getByName(
                DxToolchain.DEFAULT_NAME, buildTarget.getTargetConfiguration(), DxToolchain.class)
            .getDxExecutorService();

    boolean shouldProguard =
        apkUnderTest.getProguardConfig().isPresent()
            || !ProGuardObfuscateStep.SdkProguardType.NONE.equals(
                apkUnderTest.getSdkProguardConfig());
    NonPreDexedDexBuildable.NonPredexedDexBuildableArgs nonPreDexedDexBuildableArgs =
        ImmutableNonPredexedDexBuildableArgs.builder()
            .setProguardAgentPath(proGuardConfig.getProguardAgentPath())
            .setProguardJarOverride(
                proGuardConfig.getProguardJarOverride(buildTarget.getTargetConfiguration()))
            .setProguardMaxHeapSize(proGuardConfig.getProguardMaxHeapSize())
            .setSdkProguardConfig(apkUnderTest.getSdkProguardConfig())
            .setPreprocessJavaClassesBash(Optional.empty())
            .setPreprocessJavaClassesCmd(Optional.empty())
            .setDxExecutorService(dxExecutorService)
            .setOptimizationPasses(apkUnderTest.getOptimizationPasses())
            .setProguardJvmArgs(apkUnderTest.getProguardJvmArgs())
            .setSkipProguard(apkUnderTest.getSkipProguard())
            .setJavaRuntimeLauncher(apkUnderTest.getJavaRuntimeLauncher())
            .setProguardConfigPath(apkUnderTest.getProguardConfig())
            .setProguardConfigOverride(
                proGuardConfig.getProguardConfigOverride(buildTarget.getTargetConfiguration()))
            .setOptimizedProguardConfigOverride(
                proGuardConfig.getOptimizedProguardConfigOverride(
                    buildTarget.getTargetConfiguration()))
            .setShouldProguard(shouldProguard)
            .build();

    AndroidPlatformTarget androidPlatformTarget =
        toolchainProvider.getByName(
            AndroidPlatformTarget.DEFAULT_NAME,
            buildTarget.getTargetConfiguration(),
            AndroidPlatformTarget.class);

    AndroidBinaryGraphEnhancer graphEnhancer =
        new AndroidBinaryGraphEnhancer(
            toolchainProvider,
            context.getCellPathResolver(),
            buildTarget,
            context.getProjectFilesystem(),
            androidPlatformTarget,
            params,
            graphBuilder,
            args.getAaptMode(),
            ImmutableList.of(),
            ResourceCompressionMode.DISABLED,
            FilterResourcesSteps.ResourceFilter.EMPTY_FILTER,
            /* bannedDuplicateResourceTypes */ EnumSet.noneOf(RType.class),
            Optional.empty(),
            /* resourceUnionPackage */ Optional.empty(),
            /* locales */ ImmutableSet.of(),
            /* packagedLocales */ ImmutableSet.of(),
            args.getManifest(),
            args.getManifestSkeleton(),
            /* moduleManifestSkeleton */ Optional.empty(),
            PackageType.INSTRUMENTED,
            apkUnderTest.getCpuFilters(),
            /* shouldBuildStringSourceMap */ false,
            /* shouldPreDex */ true,
            DexSplitMode.NO_SPLIT,
            buildTargetsToExclude.build(),
            resourcesToExclude,
            nativeLibsToExclude,
            nativeLinkablesToExcludeGroup,
            nativeLibAssetsToExclude,
            nativeLinkableGroupAssetsToExclude,
            nativeLibsForSystemLoaderToExclude,
            nativeLinkablesUsedByWrapScriptToExcludeGroup,
            /* skipCrunchPngs */ false,
            args.getIncludesVectorDrawables(),
            /* noAutoVersionResources */ false,
            /* noVersionTransitionsResources */ false,
            /* noAutoAddOverlayResources */ false,
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
            EnumSet.noneOf(ExopackageMode.class),
            /* buildConfigValues */ BuildConfigFields.of(),
            /* buildConfigValuesFile */ Optional.empty(),
            /* xzCompressionLevel */ XzStep.DEFAULT_COMPRESSION_LEVEL,
            /* trimResourceIds */ false,
            false,
            /* nativeLibraryMergeMap */ Optional.empty(),
            /* nativeLibraryMergeSequence */ Optional.empty(),
            /* nativeLibraryMergeSequenceBlocklist */ Optional.empty(),
            /* nativeLibraryMergeGlue */ Optional.empty(),
            /* nativeLibraryMergeCodeGenerator */ Optional.empty(),
            Optional.empty(),
            RelinkerMode.DISABLED,
            ImmutableList.of(),
            dxExecutorService,
            apkUnderTest.getManifestEntries(),
            cxxBuckConfig,
            new APKModuleGraph<>(context.getTargetGraph(), buildTarget),
            /* postFilterResourcesCommands */ Optional.empty(),
            nonPreDexedDexBuildableArgs,
            createRulesToExcludeFromDexSupplier(
                apkUnderTest.getRulesToExcludeFromDex(), apkUnderTestTransitiveClasspathDeps),
            false,
            new NoopAndroidNativeTargetConfigurationMatcher(),
            /* useAapt2LocaleFiltering= */ false,
            /* shouldAapt2KeepRawValues= */ false,
            /* extraFilteredResources= */ ImmutableSet.of(),
            /* resourceStableIds= */ Optional.empty(),
            androidBuckConfig.getRDotJavaWeightFactor(),
            androidBuckConfig.getSecondaryDexWeightLimit(),
            resourcePackagesToExclude);

    AndroidGraphEnhancementResult enhancementResult = graphEnhancer.createAdditionalBuildables();
    AndroidApkFilesInfo filesInfo =
        new AndroidApkFilesInfo(enhancementResult, EnumSet.noneOf(ExopackageMode.class), false);
    return new AndroidInstrumentationApk(
        buildTarget,
        context.getProjectFilesystem(),
        toolchainProvider.getByName(
            AndroidSdkLocation.DEFAULT_NAME,
            buildTarget.getTargetConfiguration(),
            AndroidSdkLocation.class),
        params,
        graphBuilder,
        apkUnderTest,
        enhancementResult,
        filesInfo.getDexFilesInfo(),
        filesInfo.getNativeFilesInfo(),
        filesInfo.getResourceFilesInfo(),
        filesInfo.getExopackageInfo(),
        downwardApiConfig.isEnabledForAndroid());
  }

  private static Supplier<ImmutableSet<JavaLibrary>> createRulesToExcludeFromDexSupplier(
      Supplier<ImmutableSet<JavaLibrary>> apkUnderTestRulesToExcludeFromDex,
      ImmutableSet<JavaLibrary> apkUnderTestTransitiveClasspathDeps) {
    return Suppliers.memoize(
        () ->
            ImmutableSet.<JavaLibrary>builder()
                .addAll(apkUnderTestRulesToExcludeFromDex.get())
                .addAll(apkUnderTestTransitiveClasspathDeps)
                .build());
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellNameResolver cellRoots,
      AndroidInstrumentationApkDescriptionArg constructorArg,
      Builder<BuildTarget> extraDepsBuilder,
      Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    javacFactory.addParseTimeDeps(
        targetGraphOnlyDepsBuilder, null, buildTarget.getTargetConfiguration());
    AndroidTools.addParseTimeDepsToAndroidTools(
        toolchainProvider, buildTarget, targetGraphOnlyDepsBuilder);
  }

  @RuleArg
  interface AbstractAndroidInstrumentationApkDescriptionArg extends BuildRuleArg, HasDeclaredDeps {
    Optional<SourcePath> getManifest();

    Optional<SourcePath> getManifestSkeleton();

    BuildTarget getApk();

    @Value.Default
    default AaptMode getAaptMode() {
      return AaptMode.AAPT1;
    }

    @Value.Default
    default boolean getIncludesVectorDrawables() {
      return false;
    }

    @Value.Default
    default String getDexTool() {
      return "d8";
    };
  }
}
