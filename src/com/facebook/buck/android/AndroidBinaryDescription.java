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

import static com.facebook.buck.android.AndroidBinaryResourcesGraphEnhancer.PACKAGE_STRING_ASSETS_FLAVOR;

import com.facebook.buck.android.FilterResourcesSteps.ResourceFilter;
import com.facebook.buck.android.dalvik.ZipSplitter.DexSplitStrategy;
import com.facebook.buck.android.exopackage.AdbConfig;
import com.facebook.buck.android.exopackage.ExopackageMode;
import com.facebook.buck.android.toolchain.AndroidTools;
import com.facebook.buck.android.toolchain.ndk.NdkCxxPlatformsProvider;
import com.facebook.buck.command.config.BuildBuckConfig;
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.description.arg.HasDeclaredDeps;
import com.facebook.buck.core.description.arg.HasTests;
import com.facebook.buck.core.description.arg.Hint;
import com.facebook.buck.core.description.attr.ImplicitDepsInferringDescription;
import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.Flavored;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.facebook.buck.cxx.config.CxxBuckConfig;
import com.facebook.buck.downwardapi.config.DownwardApiConfig;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.jvm.java.JavaCDBuckConfig;
import com.facebook.buck.jvm.java.JavaOptions;
import com.facebook.buck.jvm.java.JavacFactory;
import com.facebook.buck.jvm.java.toolchain.JavaOptionsProvider;
import com.facebook.buck.rules.query.Query;
import com.facebook.buck.step.fs.XzStep;
import com.facebook.buck.versions.VersionRoot;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import org.immutables.value.Value;

public class AndroidBinaryDescription
    implements DescriptionWithTargetGraph<AndroidBinaryDescriptionArg>,
        Flavored,
        ImplicitDepsInferringDescription<
            AndroidBinaryDescription.AbstractAndroidBinaryDescriptionArg>,
        VersionRoot<AndroidBinaryDescriptionArg> {

  private static final ImmutableSet<Flavor> FLAVORS =
      ImmutableSet.of(
          PACKAGE_STRING_ASSETS_FLAVOR,
          AndroidBinaryResourcesGraphEnhancer.AAPT2_LINK_FLAVOR,
          AndroidBinaryGraphEnhancer.UNSTRIPPED_NATIVE_LIBRARIES_FLAVOR,
          AndroidBinaryGraphEnhancer.PROGUARD_TEXT_OUTPUT_FLAVOR,
          AndroidBinaryResourcesGraphEnhancer.GENERATE_STRING_RESOURCES_FLAVOR,
          AndroidApkFactory.EXO_SYMLINK_TREE);

  private final JavaBuckConfig javaBuckConfig;
  private final JavaCDBuckConfig javaCDBuckConfig;
  private final AndroidBuckConfig androidBuckConfig;
  private final JavacFactory javacFactory;
  private final Function<TargetConfiguration, JavaOptions> javaOptions;
  private final ProGuardConfig proGuardConfig;
  private final CxxBuckConfig cxxBuckConfig;
  private final AdbConfig adbConfig;
  private final DownwardApiConfig downwardApiConfig;
  private final BuildBuckConfig buildBuckConfig;
  private final ToolchainProvider toolchainProvider;
  private final AndroidBinaryGraphEnhancerFactory androidBinaryGraphEnhancerFactory;
  private final AndroidApkFactory androidApkFactory;

  public AndroidBinaryDescription(
      JavaBuckConfig javaBuckConfig,
      JavaCDBuckConfig javaCDBuckConfig,
      ProGuardConfig proGuardConfig,
      AndroidBuckConfig androidBuckConfig,
      AdbConfig adbConfig,
      CxxBuckConfig cxxBuckConfig,
      DownwardApiConfig downwardApiConfig,
      BuildBuckConfig buildBuckConfig,
      ToolchainProvider toolchainProvider,
      AndroidBinaryGraphEnhancerFactory androidBinaryGraphEnhancerFactory,
      AndroidApkFactory androidApkFactory) {
    this.javaBuckConfig = javaBuckConfig;
    this.javaCDBuckConfig = javaCDBuckConfig;
    this.javacFactory = JavacFactory.getDefault(toolchainProvider);
    this.javaOptions = JavaOptionsProvider.getDefaultJavaOptions(toolchainProvider);
    this.androidBuckConfig = androidBuckConfig;
    this.proGuardConfig = proGuardConfig;
    this.cxxBuckConfig = cxxBuckConfig;
    this.downwardApiConfig = downwardApiConfig;
    this.buildBuckConfig = buildBuckConfig;
    this.adbConfig = adbConfig;
    this.toolchainProvider = toolchainProvider;
    this.androidBinaryGraphEnhancerFactory = androidBinaryGraphEnhancerFactory;
    this.androidApkFactory = androidApkFactory;
  }

  @Override
  public Class<AndroidBinaryDescriptionArg> getConstructorArgType() {
    return AndroidBinaryDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      AndroidBinaryDescriptionArg args) {
    ActionGraphBuilder graphBuilder = context.getActionGraphBuilder();

    params = params.withoutExtraDeps();

    // All of our supported flavors are constructed as side-effects
    // of the main target.
    for (Flavor flavor : FLAVORS) {
      if (buildTarget.getFlavors().contains(flavor)) {
        graphBuilder.requireRule(buildTarget.withoutFlavors(flavor));
        return graphBuilder
            .getRuleOptional(buildTarget)
            .orElseThrow(
                () ->
                    new HumanReadableException(
                        "Rule for target '%s' could not be resolved.", buildTarget));
      }
    }

    // We don't support requiring other flavors right now.
    if (buildTarget.isFlavored()) {
      throw new HumanReadableException(
          "Requested target %s contains an unrecognized flavor", buildTarget);
    }

    EnumSet<ExopackageMode> exopackageModes = ExopackageArgsHelper.detectExopackageModes(args);

    DexSplitMode dexSplitMode = createDexSplitMode(args, exopackageModes);

    Supplier<ImmutableSet<JavaLibrary>> rulesToExcludeFromDex =
        NoDxArgsHelper.createSupplierForRulesToExclude(graphBuilder, buildTarget, args.getNoDx());

    CellPathResolver cellRoots = context.getCellPathResolver();

    ResourceFilter resourceFilter = new ResourceFilter(args.getResourceFilter());

    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();

    // TODO(nga): obtain proper dependency stack
    DependencyStack dependencyStack = DependencyStack.top(buildTarget);
    AndroidBinaryGraphEnhancer graphEnhancer =
        androidBinaryGraphEnhancerFactory.create(
            toolchainProvider,
            javaBuckConfig,
            javaCDBuckConfig,
            androidBuckConfig,
            cxxBuckConfig,
            proGuardConfig,
            downwardApiConfig,
            buildBuckConfig,
            cellRoots,
            context.getTargetGraph(),
            buildTarget,
            dependencyStack,
            projectFilesystem,
            params,
            graphBuilder,
            resourceFilter,
            dexSplitMode,
            exopackageModes,
            rulesToExcludeFromDex,
            args,
            /* useProtoFormat */ false,
            javaOptions.apply(buildTarget.getTargetConfiguration()),
            javacFactory,
            context.getConfigurationRuleRegistry());
    AndroidApk androidApk =
        androidApkFactory.create(
            toolchainProvider,
            projectFilesystem,
            graphBuilder,
            buildTarget,
            params,
            graphEnhancer,
            dexSplitMode,
            exopackageModes,
            resourceFilter,
            rulesToExcludeFromDex,
            args,
            javaOptions.apply(buildTarget.getTargetConfiguration()));
    // The exo installer is always added to the index so that the action graph is the same
    // between build and install calls.
    new AndroidApkInstallGraphEnhancer(adbConfig, projectFilesystem, buildTarget, androidApk)
        .enhance(graphBuilder);
    return androidApk;
  }

  private DexSplitMode createDexSplitMode(
      AndroidBinaryDescriptionArg args, EnumSet<ExopackageMode> exopackageModes) {
    // Exopackage builds default to JAR, otherwise, default to RAW.
    DexStore defaultDexStore =
        ExopackageMode.enabledForSecondaryDexes(exopackageModes) ? DexStore.JAR : DexStore.RAW;
    DexSplitStrategy dexSplitStrategy =
        args.getMinimizePrimaryDexSize()
            ? DexSplitStrategy.MINIMIZE_PRIMARY_DEX_SIZE
            : DexSplitStrategy.MAXIMIZE_PRIMARY_DEX_SIZE;
    return new DexSplitMode(
        args.getUseSplitDex(),
        dexSplitStrategy,
        args.getDexCompression().orElse(defaultDexStore),
        args.getLinearAllocHardLimit(),
        args.getMethodRefCountBufferSpace(),
        args.getFieldRefCountBufferSpace(),
        args.getDexGroupLibLimit(),
        args.getPrimaryDexPatterns(),
        args.isAllowRDotJavaInSecondaryDex());
  }

  @Override
  public boolean hasFlavors(
      ImmutableSet<Flavor> flavors, TargetConfiguration toolchainTargetConfiguration) {
    for (Flavor flavor : flavors) {
      if (!FLAVORS.contains(flavor)) {
        return false;
      }
    }
    return true;
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellNameResolver cellRoots,
      AbstractAndroidBinaryDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    javacFactory.addParseTimeDeps(
        targetGraphOnlyDepsBuilder, null, buildTarget.getTargetConfiguration());
    TargetConfiguration targetConfiguration = buildTarget.getTargetConfiguration();
    extraDepsBuilder.addAll(proGuardConfig.getProguardTargets(targetConfiguration));

    // TODO(cjhopman): we could filter this by the abis that this binary supports.
    toolchainProvider
        .getByNameIfPresent(
            NdkCxxPlatformsProvider.DEFAULT_NAME,
            buildTarget.getTargetConfiguration(),
            NdkCxxPlatformsProvider.class)
        .map(NdkCxxPlatformsProvider::getNdkCxxPlatforms).map(Map::values)
        .orElse(ImmutableList.of()).stream()
        .map(platform -> platform.getParseTimeDeps(targetConfiguration))
        .forEach(extraDepsBuilder::addAll);

    AndroidTools.addParseTimeDepsToAndroidTools(
        toolchainProvider, buildTarget, targetGraphOnlyDepsBuilder);
  }

  @RuleArg
  abstract static class AbstractAndroidBinaryDescriptionArg
      implements BuildRuleArg,
          HasDeclaredDeps,
          HasExopackageArgs,
          HasTests,
          AndroidGraphEnhancerArgs {

    abstract BuildTarget getKeystore();

    @Value.Default
    boolean getUseSplitDex() {
      return false;
    }

    @Value.Default
    boolean getMinimizePrimaryDexSize() {
      return false;
    }

    abstract Optional<DexStore> getDexCompression();

    abstract List<String> getPrimaryDexPatterns();

    @Value.Default
    long getLinearAllocHardLimit() {
      return DexSplitMode.DEFAULT_LINEAR_ALLOC_HARD_LIMIT;
    }

    @Value.Default
    int getDexGroupLibLimit() {
      return DexSplitMode.DEFAULT_DEX_GROUP_LIB_LIMIT;
    }

    abstract List<String> getResourceFilter();

    @Value.NaturalOrder
    abstract ImmutableSortedSet<BuildTarget> getPreprocessJavaClassesDeps();

    @Value.Default
    int getXzCompressionLevel() {
      return XzStep.DEFAULT_COMPRESSION_LEVEL;
    }

    @Value.Default
    boolean isPackageAssetLibraries() {
      return false;
    }

    @Value.Default
    boolean isCompressAssetLibraries() {
      return false;
    }

    abstract Optional<CompressionAlgorithm> getAssetCompressionAlgorithm();

    @Hint(splitConfiguration = true)
    @Override
    @Value.NaturalOrder
    public abstract ImmutableSortedSet<BuildTarget> getDeps();

    @Override
    public AndroidBinaryDescriptionArg withApplicationModuleBlacklist(List<Query> queries) {
      if (getApplicationModuleBlacklist().equals(Optional.of(queries))) {
        return (AndroidBinaryDescriptionArg) this;
      }
      return AndroidBinaryDescriptionArg.builder()
          .from(this)
          .setApplicationModuleBlacklist(queries)
          .build();
    }
  }
}
