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

package com.facebook.buck.apple;

import com.facebook.buck.apple.toolchain.ApplePlatform;
import com.facebook.buck.apple.toolchain.CodeSignIdentityStore;
import com.facebook.buck.apple.toolchain.ProvisioningProfileStore;
import com.facebook.buck.apple.toolchain.UnresolvedAppleCxxPlatform;
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.description.attr.ImplicitDepsInferringDescription;
import com.facebook.buck.core.description.attr.ImplicitFlavorsInferringDescription;
import com.facebook.buck.core.description.impl.DescriptionCache;
import com.facebook.buck.core.description.metadata.MetadataProvidingDescription;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.FlavorSet;
import com.facebook.buck.core.model.Flavored;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.facebook.buck.cxx.CxxBinaryDescription;
import com.facebook.buck.cxx.CxxBinaryDescriptionArg;
import com.facebook.buck.cxx.CxxBinaryFactory;
import com.facebook.buck.cxx.CxxBinaryFlavored;
import com.facebook.buck.cxx.CxxBinaryImplicitFlavors;
import com.facebook.buck.cxx.CxxBinaryMetadataFactory;
import com.facebook.buck.cxx.CxxCompilationDatabase;
import com.facebook.buck.cxx.CxxFocusedDebugTargets;
import com.facebook.buck.cxx.CxxInferEnhancer;
import com.facebook.buck.cxx.CxxLinkGroupMapDatabase;
import com.facebook.buck.cxx.FrameworkDependencies;
import com.facebook.buck.cxx.HasAppleDebugSymbolDeps;
import com.facebook.buck.cxx.config.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.facebook.buck.cxx.toolchain.LinkerMapMode;
import com.facebook.buck.cxx.toolchain.StripStyle;
import com.facebook.buck.cxx.toolchain.impl.CxxPlatforms;
import com.facebook.buck.downwardapi.config.DownwardApiConfig;
import com.facebook.buck.file.CopyFile;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.rules.modern.OutputPath;
import com.facebook.buck.swift.SwiftBuckConfig;
import com.facebook.buck.swift.SwiftLibraryDescription;
import com.facebook.buck.util.types.Either;
import com.facebook.buck.versions.Version;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicates;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import java.util.Optional;
import java.util.Set;

public class AppleBinaryDescription
    implements DescriptionWithTargetGraph<AppleBinaryDescriptionArg>,
        Flavored,
        ImplicitDepsInferringDescription<AppleBinaryDescription.AbstractAppleBinaryDescriptionArg>,
        ImplicitFlavorsInferringDescription,
        MetadataProvidingDescription<AppleBinaryDescriptionArg> {

  public static final Flavor APP_FLAVOR = InternalFlavor.of("app");
  public static final Sets.SetView<Flavor> NON_DELEGATE_FLAVORS =
      Sets.union(AppleDebugFormat.FLAVOR_DOMAIN.getFlavors(), ImmutableSet.of(APP_FLAVOR));

  @SuppressWarnings("PMD") // PMD doesn't understand method references
  private static final Set<Flavor> SUPPORTED_FLAVORS =
      ImmutableSet.of(
          APP_FLAVOR,
          CxxLinkGroupMapDatabase.LINK_GROUP_MAP_DATABASE,
          CxxFocusedDebugTargets.FOCUSED_DEBUG_TARGETS,
          CxxCompilationDatabase.COMPILATION_DATABASE,
          CxxCompilationDatabase.UBER_COMPILATION_DATABASE,
          AppleDebugFormat.DWARF_AND_DSYM.getFlavor(),
          AppleDebugFormat.DWARF.getFlavor(),
          AppleDebugFormat.NONE.getFlavor(),
          LinkerMapMode.NO_LINKER_MAP.getFlavor());

  private final ToolchainProvider toolchainProvider;
  private final XCodeDescriptions xcodeDescriptions;
  private final Optional<SwiftLibraryDescription> swiftDelegate;
  private final AppleConfig appleConfig;
  private final CxxBuckConfig cxxBuckConfig;
  private final SwiftBuckConfig swiftBuckConfig;
  private final DownwardApiConfig downwardApiConfig;
  private final CxxBinaryImplicitFlavors cxxBinaryImplicitFlavors;
  private final CxxBinaryFactory cxxBinaryFactory;
  private final CxxBinaryMetadataFactory cxxBinaryMetadataFactory;
  private final CxxBinaryFlavored cxxBinaryFlavored;

  public AppleBinaryDescription(
      ToolchainProvider toolchainProvider,
      XCodeDescriptions xcodeDescriptions,
      SwiftLibraryDescription swiftDelegate,
      AppleConfig appleConfig,
      CxxBuckConfig cxxBuckConfig,
      SwiftBuckConfig swiftBuckConfig,
      DownwardApiConfig downwardApiConfig,
      CxxBinaryImplicitFlavors cxxBinaryImplicitFlavors,
      CxxBinaryFactory cxxBinaryFactory,
      CxxBinaryMetadataFactory cxxBinaryMetadataFactory,
      CxxBinaryFlavored cxxBinaryFlavored) {
    this.toolchainProvider = toolchainProvider;
    this.xcodeDescriptions = xcodeDescriptions;
    // TODO(T22135033): Make apple_binary not use a Swift delegate
    this.swiftDelegate = Optional.of(swiftDelegate);
    this.appleConfig = appleConfig;
    this.cxxBuckConfig = cxxBuckConfig;
    this.swiftBuckConfig = swiftBuckConfig;
    this.downwardApiConfig = downwardApiConfig;
    this.cxxBinaryImplicitFlavors = cxxBinaryImplicitFlavors;
    this.cxxBinaryFactory = cxxBinaryFactory;
    this.cxxBinaryMetadataFactory = cxxBinaryMetadataFactory;
    this.cxxBinaryFlavored = cxxBinaryFlavored;
  }

  @Override
  public Class<AppleBinaryDescriptionArg> getConstructorArgType() {
    return AppleBinaryDescriptionArg.class;
  }

  @Override
  public Optional<ImmutableSet<FlavorDomain<?>>> flavorDomains(
      TargetConfiguration toolchainTargetConfiguration) {
    ImmutableSet.Builder<FlavorDomain<?>> builder = ImmutableSet.builder();

    ImmutableSet<FlavorDomain<?>> localDomains = ImmutableSet.of(AppleDebugFormat.FLAVOR_DOMAIN);

    builder.addAll(localDomains);
    cxxBinaryFlavored
        .flavorDomains(toolchainTargetConfiguration)
        .ifPresent(domains -> builder.addAll(domains));
    swiftDelegate
        .flatMap(swift -> swift.flavorDomains(toolchainTargetConfiguration))
        .ifPresent(domains -> builder.addAll(domains));

    ImmutableSet<FlavorDomain<?>> result = builder.build();

    // Drop StripStyle because it's overridden by AppleDebugFormat
    result =
        result.stream()
            .filter(domain -> !domain.equals(StripStyle.FLAVOR_DOMAIN))
            .collect(ImmutableSet.toImmutableSet());

    return Optional.of(result);
  }

  @Override
  public boolean hasFlavors(
      ImmutableSet<Flavor> flavors, TargetConfiguration toolchainTargetConfiguration) {
    if (FluentIterable.from(flavors).allMatch(SUPPORTED_FLAVORS::contains)) {
      return true;
    }
    ImmutableSet<Flavor> delegateFlavors =
        ImmutableSet.copyOf(Sets.difference(flavors, NON_DELEGATE_FLAVORS));
    if (swiftDelegate
        .map(swift -> swift.hasFlavors(delegateFlavors, toolchainTargetConfiguration))
        .orElse(false)) {
      return true;
    }
    ImmutableList<ImmutableSortedSet<Flavor>> thinFlavorSets =
        generateThinDelegateFlavors(delegateFlavors);
    if (thinFlavorSets.size() > 0) {
      return Iterables.all(
          thinFlavorSets,
          inputFlavors -> cxxBinaryFlavored.hasFlavors(inputFlavors, toolchainTargetConfiguration));
    } else {
      return cxxBinaryFlavored.hasFlavors(delegateFlavors, toolchainTargetConfiguration);
    }
  }

  private ImmutableList<ImmutableSortedSet<Flavor>> generateThinDelegateFlavors(
      ImmutableSet<Flavor> delegateFlavors) {
    return MultiarchFileInfos.generateThinFlavors(ImmutableSortedSet.copyOf(delegateFlavors));
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      AppleBinaryDescriptionArg args) {
    FlavorDomain<UnresolvedAppleCxxPlatform> appleCxxPlatformsFlavorDomain =
        AppleDescriptions.getAppleCxxPlatformsFlavorDomain(
            toolchainProvider, buildTarget.getTargetConfiguration());
    ActionGraphBuilder actionGraphBuilder = context.getActionGraphBuilder();
    args.checkDuplicateSources(actionGraphBuilder.getSourcePathResolver());
    if (buildTarget.getFlavors().contains(APP_FLAVOR)) {
      return createBundleBuildRule(
          context.getTargetGraph(),
          buildTarget,
          context.getProjectFilesystem(),
          params,
          actionGraphBuilder,
          appleCxxPlatformsFlavorDomain,
          args);
    } else {
      return createBinaryBuildRule(
          context,
          buildTarget,
          context.getProjectFilesystem(),
          params,
          actionGraphBuilder,
          context.getCellPathResolver(),
          appleCxxPlatformsFlavorDomain,
          args);
    }
  }

  // We want to wrap only if we have explicit debug flavor. This is because we don't want to
  // force dSYM generation in case if its enabled by default in config. We just want the binary,
  // so unless flavor is explicitly set, lets just produce binary!
  private boolean shouldWrapIntoAppleDebuggableBinary(
      BuildTarget buildTarget, BuildRule binaryBuildRule) {
    Optional<AppleDebugFormat> explicitDebugInfoFormat =
        AppleDebugFormat.FLAVOR_DOMAIN.getValue(buildTarget);
    boolean binaryIsWrappable = AppleDebuggableBinary.canWrapBinaryBuildRule(binaryBuildRule);
    return explicitDebugInfoFormat.isPresent() && binaryIsWrappable;
  }

  private BuildRule createBinaryBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      ActionGraphBuilder graphBuilder,
      CellPathResolver cellRoots,
      FlavorDomain<UnresolvedAppleCxxPlatform> appleCxxPlatformsFlavorDomain,
      AppleBinaryDescriptionArg args) {
    args.checkDuplicateSources(graphBuilder.getSourcePathResolver());
    // remove some flavors so binary will have the same output regardless their values
    BuildTarget unstrippedBinaryBuildTarget =
        buildTarget
            .withoutFlavors(AppleDebugFormat.FLAVOR_DOMAIN.getFlavors())
            .withoutFlavors(StripStyle.FLAVOR_DOMAIN.getFlavors());

    BuildRule unstrippedBinaryRule =
        createBinary(
            context,
            unstrippedBinaryBuildTarget,
            projectFilesystem,
            params,
            graphBuilder,
            cellRoots,
            appleCxxPlatformsFlavorDomain,
            args);

    if (shouldWrapIntoAppleDebuggableBinary(buildTarget, unstrippedBinaryRule)) {
      return createAppleDebuggableBinary(
          context,
          buildTarget,
          projectFilesystem,
          params,
          graphBuilder,
          cellRoots,
          appleCxxPlatformsFlavorDomain,
          args,
          unstrippedBinaryBuildTarget,
          (HasAppleDebugSymbolDeps) unstrippedBinaryRule);
    } else {
      return unstrippedBinaryRule;
    }
  }

  private BuildRule createAppleDebuggableBinary(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      ActionGraphBuilder graphBuilder,
      CellPathResolver cellRoots,
      FlavorDomain<UnresolvedAppleCxxPlatform> appleCxxPlatformsFlavorDomain,
      AppleBinaryDescriptionArg args,
      BuildTarget unstrippedBinaryBuildTarget,
      HasAppleDebugSymbolDeps unstrippedBinaryRule) {
    CxxPlatformsProvider cxxPlatformsProvider =
        getCxxPlatformsProvider(buildTarget.getTargetConfiguration());
    BuildTarget strippedBinaryBuildTarget =
        unstrippedBinaryBuildTarget.withAppendedFlavors(
            StripStyle.FLAVOR_DOMAIN
                .getFlavor(buildTarget.getFlavors())
                .orElse(StripStyle.NON_GLOBAL_SYMBOLS.getFlavor()));
    BuildRule strippedBinaryRule =
        createBinary(
            context,
            strippedBinaryBuildTarget,
            projectFilesystem,
            params,
            graphBuilder,
            cellRoots,
            appleCxxPlatformsFlavorDomain,
            args);
    return AppleDescriptions.createAppleDebuggableBinary(
        unstrippedBinaryBuildTarget,
        projectFilesystem,
        graphBuilder,
        strippedBinaryRule,
        unstrippedBinaryRule,
        AppleDebugFormat.FLAVOR_DOMAIN.getRequiredValue(buildTarget),
        appleConfig.getDsymutilExtraFlags(),
        appleConfig.getVerifyDsym(),
        appleConfig.getDwarfdumpFailsDsymVerification(),
        cxxPlatformsProvider,
        appleCxxPlatformsFlavorDomain,
        cxxBuckConfig.shouldCacheStrip(),
        downwardApiConfig.isEnabledForApple());
  }

  private BuildRule createBundleBuildRule(
      TargetGraph targetGraph,
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      ActionGraphBuilder graphBuilder,
      FlavorDomain<UnresolvedAppleCxxPlatform> appleCxxPlatformsFlavorDomain,
      AppleBinaryDescriptionArg args) {
    if (!args.getInfoPlist().isPresent()) {
      throw new HumanReadableException(
          "Cannot create application for apple_binary '%s':\n",
          "No value specified for 'info_plist' attribute.", buildTarget.getUnflavoredBuildTarget());
    }
    AppleDebugFormat flavoredDebugFormat =
        AppleDebugFormat.FLAVOR_DOMAIN
            .getValue(buildTarget)
            .orElse(appleConfig.getDefaultDebugInfoFormatForBinaries());
    if (!buildTarget.getFlavors().contains(flavoredDebugFormat.getFlavor())) {
      return graphBuilder.requireRule(
          buildTarget.withAppendedFlavors(flavoredDebugFormat.getFlavor()));
    }
    CxxPlatformsProvider cxxPlatformsProvider =
        getCxxPlatformsProvider(buildTarget.getTargetConfiguration());
    if (!AppleDescriptions.INCLUDE_FRAMEWORKS.getValue(buildTarget).isPresent()) {
      CxxPlatform cxxPlatform =
          ApplePlatforms.getCxxPlatformForBuildTarget(
                  cxxPlatformsProvider, buildTarget, Optional.empty())
              .resolve(graphBuilder, buildTarget.getTargetConfiguration());
      ApplePlatform applePlatform =
          appleCxxPlatformsFlavorDomain
              .getValue(cxxPlatform.getFlavor())
              .resolve(graphBuilder)
              .getAppleSdk()
              .getApplePlatform();
      if (applePlatform.getAppIncludesFrameworks()) {
        return graphBuilder.requireRule(
            buildTarget.withAppendedFlavors(AppleDescriptions.INCLUDE_FRAMEWORKS_FLAVOR));
      }
      return graphBuilder.requireRule(
          buildTarget.withAppendedFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR));
    }
    BuildTarget binaryTarget = buildTarget.withoutFlavors(APP_FLAVOR);
    return AppleDescriptions.createAppleBundle(
        xcodeDescriptions,
        getCxxPlatformsProvider(buildTarget.getTargetConfiguration()),
        appleCxxPlatformsFlavorDomain,
        targetGraph,
        buildTarget,
        projectFilesystem,
        params,
        graphBuilder,
        toolchainProvider.getByName(
            CodeSignIdentityStore.DEFAULT_NAME,
            buildTarget.getTargetConfiguration(),
            CodeSignIdentityStore.class),
        toolchainProvider.getByName(
            ProvisioningProfileStore.DEFAULT_NAME,
            buildTarget.getTargetConfiguration(),
            ProvisioningProfileStore.class),
        Optional.of(binaryTarget),
        Optional.empty(),
        args.getDefaultPlatform(),
        Either.ofLeft(AppleBundleExtension.APP),
        Optional.empty(),
        args.getInfoPlist().get(),
        args.getInfoPlistSubstitutions(),
        args.getDeps(),
        args.getTests(),
        flavoredDebugFormat,
        appleConfig.getDsymutilExtraFlags(),
        appleConfig.getVerifyDsym(),
        appleConfig.getDwarfdumpFailsDsymVerification(),
        appleConfig.useDryRunCodeSigning(),
        Optional.empty(),
        appleConfig.cacheBundlesAndPackages(),
        appleConfig.shouldVerifyBundleResources(),
        appleConfig.assetCatalogValidation(),
        AppleAssetCatalogsCompilationOptions.builder().build(),
        ImmutableList.of(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        appleConfig.getCodesignTimeout(),
        swiftBuckConfig.getCopyStdlibToFrameworks(),
        Optional.empty(),
        cxxBuckConfig.shouldCacheStrip(),
        appleConfig.useEntitlementsWhenAdhocCodeSigning(),
        Predicates.alwaysTrue(),
        swiftBuckConfig.getSliceAppPackageSwiftRuntime(),
        swiftBuckConfig.getSliceAppBundleSwiftRuntime(),
        downwardApiConfig.isEnabledForApple(),
        args.getTargetSdkVersion(),
        appleConfig.getIncrementalBundlingEnabled(),
        appleConfig.getCodeSignTypeOverride(),
        appleConfig.getBundleInputBasedRulekeyEnabled(),
        appleConfig.getIncrementalHashCacheEnabled(),
        false,
        false,
        Optional.empty());
  }

  private BuildRule createBinary(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      ActionGraphBuilder graphBuilder,
      CellPathResolver cellRoots,
      FlavorDomain<UnresolvedAppleCxxPlatform> appleCxxPlatformsFlavorDomain,
      AppleBinaryDescriptionArg args) {

    if (AppleDescriptions.flavorsDoNotAllowLinkerMapMode(buildTarget)) {
      buildTarget = buildTarget.withoutFlavors(LinkerMapMode.NO_LINKER_MAP.getFlavor());
    }

    Optional<MultiarchFileInfo> fatBinaryInfo =
        MultiarchFileInfos.create(appleCxxPlatformsFlavorDomain, buildTarget);
    if (fatBinaryInfo.isPresent()) {
      if (shouldUseWatchKitStubBinary(buildTarget, args)) {
        BuildTarget thinTarget = Iterables.getFirst(fatBinaryInfo.get().getThinTargets(), null);
        return requireThinBinary(
            context,
            thinTarget,
            projectFilesystem,
            params,
            graphBuilder,
            cellRoots,
            appleCxxPlatformsFlavorDomain,
            args);
      }

      ImmutableSortedSet.Builder<BuildRule> thinRules = ImmutableSortedSet.naturalOrder();
      for (BuildTarget thinTarget : fatBinaryInfo.get().getThinTargets()) {
        thinRules.add(
            requireThinBinary(
                context,
                thinTarget,
                projectFilesystem,
                params,
                graphBuilder,
                cellRoots,
                appleCxxPlatformsFlavorDomain,
                args));
      }
      return MultiarchFileInfos.requireMultiarchRule(
          buildTarget,
          projectFilesystem,
          params,
          graphBuilder,
          fatBinaryInfo.get(),
          thinRules.build(),
          cxxBuckConfig,
          downwardApiConfig,
          appleCxxPlatformsFlavorDomain);
    } else {
      return requireThinBinary(
          context,
          buildTarget,
          projectFilesystem,
          params,
          graphBuilder,
          cellRoots,
          appleCxxPlatformsFlavorDomain,
          args);
    }
  }

  private BuildRule requireThinBinary(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      ActionGraphBuilder graphBuilder,
      CellPathResolver cellRoots,
      FlavorDomain<UnresolvedAppleCxxPlatform> appleCxxPlatformsFlavorDomain,
      AppleBinaryDescriptionArg args) {

    return graphBuilder.computeIfAbsent(
        buildTarget,
        ignored -> {
          ImmutableSortedSet<BuildTarget> extraCxxDeps;
          Optional<BuildRule> swiftCompanionBuildRule =
              swiftDelegate.flatMap(
                  swift ->
                      swift.createCompanionBuildRule(
                          context,
                          buildTarget,
                          params,
                          graphBuilder,
                          args,
                          args.getTargetSdkVersion()));
          if (swiftCompanionBuildRule.isPresent()
              && SwiftLibraryDescription.isSwiftTarget(buildTarget)) {
            // when creating a swift target, there is no need to proceed with apple binary rules,
            return swiftCompanionBuildRule.get();
          } else if (swiftCompanionBuildRule.isPresent()) {
            // otherwise, add this swift rule as a dependency.
            extraCxxDeps = ImmutableSortedSet.of(swiftCompanionBuildRule.get().getBuildTarget());
          } else {
            extraCxxDeps = ImmutableSortedSet.of();
          }

          Optional<SourcePath> watchKitStubBinaryPath =
              getWatchKitStubBinaryPath(
                  buildTarget, appleCxxPlatformsFlavorDomain, args, graphBuilder);
          if (shouldUseWatchKitStubBinary(buildTarget, args)
              && watchKitStubBinaryPath.isPresent()) {
            return new CopyFile(
                buildTarget,
                projectFilesystem,
                graphBuilder,
                new OutputPath("WK"),
                watchKitStubBinaryPath.get());
          } else {
            CxxBinaryDescriptionArg.Builder delegateArg =
                CxxBinaryDescriptionArg.builder().from(args);
            Optional<UnresolvedAppleCxxPlatform> appleCxxPlatform =
                getAppleCxxPlatform(
                    graphBuilder,
                    appleCxxPlatformsFlavorDomain,
                    buildTarget,
                    args.getDefaultPlatform());
            AppleDescriptions.populateCxxBinaryDescriptionArg(
                graphBuilder,
                delegateArg,
                appleCxxPlatform,
                args,
                buildTarget,
                appleConfig.getUseTargetSpecificSDKVersionLinkerFlag());

            Optional<ApplePlatform> applePlatform =
                getApplePlatformForTarget(
                    buildTarget,
                    args.getDefaultPlatform(),
                    appleCxxPlatformsFlavorDomain,
                    graphBuilder);
            if (applePlatform.isPresent()
                && ApplePlatform.needsEntitlementsInBinary(applePlatform.get().getName())) {
              Optional<SourcePath> entitlements = args.getEntitlementsFile();
              if (entitlements.isPresent()) {
                ImmutableList<String> flags =
                    ImmutableList.of(
                        "-Xlinker",
                        "-sectcreate",
                        "-Xlinker",
                        "__TEXT",
                        "-Xlinker",
                        "__entitlements",
                        "-Xlinker",
                        graphBuilder
                            .getSourcePathResolver()
                            .getAbsolutePath(entitlements.get())
                            .toString());
                delegateArg.addAllLinkerFlags(
                    Iterables.transform(flags, StringWithMacros::ofConstantString));
              }
            }

            return cxxBinaryFactory.createBuildRule(
                context.getTargetGraph(),
                buildTarget,
                projectFilesystem,
                graphBuilder,
                cellRoots,
                delegateArg.build(),
                extraCxxDeps,
                AppleCxxRelinkStrategyFactory.getConfiguredStrategy(appleConfig),
                AppleCxxDebugSymbolLinkStrategyFactory.getDebugStrategyFactory(cxxBuckConfig));
          }
        });
  }

  private boolean shouldUseWatchKitStubBinary(
      BuildTarget buildTarget, AppleBinaryDescriptionArg args) {
    // If the target has sources, it's not a watch app, it might be a watch extension instead.
    // In this case, we don't need to add a watch kit stub.
    if (!args.getSrcs().isEmpty()) {
      return false;
    }
    FlavorSet flavors = buildTarget.getFlavors();
    return (flavors.contains(AppleBundleDescription.WATCH_OS_FLAVOR)
        || flavors.contains(AppleBundleDescription.WATCH_OS_64_32_FLAVOR)
        || flavors.contains(AppleBundleDescription.WATCH_SIMULATOR_FLAVOR)
        || flavors.contains(AppleBundleDescription.WATCH_SIMULATOR_X86_64_FLAVOR)
        || flavors.contains(AppleBundleDescription.WATCH_SIMULATOR_ARM64_FLAVOR));
  }

  private Optional<SourcePath> getWatchKitStubBinaryPath(
      BuildTarget buildTarget,
      FlavorDomain<UnresolvedAppleCxxPlatform> appleCxxPlatformsFlavorDomain,
      AppleBinaryDescriptionArg args,
      ActionGraphBuilder graphBuilder) {
    Optional<SourcePath> watchKitStubBinaryPath = Optional.empty();
    Optional<UnresolvedAppleCxxPlatform> appleCxxPlatform =
        getAppleCxxPlatform(
            graphBuilder, appleCxxPlatformsFlavorDomain, buildTarget, args.getDefaultPlatform());
    if (appleCxxPlatform.isPresent() && args.getSrcs().isEmpty()) {
      watchKitStubBinaryPath = appleCxxPlatform.get().resolve(graphBuilder).getWatchKitStubBinary();
    }
    return watchKitStubBinaryPath;
  }

  private Optional<ApplePlatform> getApplePlatformForTarget(
      BuildTarget buildTarget,
      Optional<Flavor> defaultPlatform,
      FlavorDomain<UnresolvedAppleCxxPlatform> appleCxxPlatformsFlavorDomain,
      BuildRuleResolver ruleResolver) {
    CxxPlatformsProvider cxxPlatformsProvider =
        getCxxPlatformsProvider(buildTarget.getTargetConfiguration());
    CxxPlatform cxxPlatform =
        ApplePlatforms.getCxxPlatformForBuildTarget(
                cxxPlatformsProvider, buildTarget, defaultPlatform)
            .resolve(ruleResolver, buildTarget.getTargetConfiguration());

    if (!appleCxxPlatformsFlavorDomain.contains(cxxPlatform.getFlavor())) {
      return Optional.empty();
    }
    return Optional.of(
        appleCxxPlatformsFlavorDomain
            .getValue(cxxPlatform.getFlavor())
            .resolve(ruleResolver)
            .getAppleSdk()
            .getApplePlatform());
  }

  private Optional<UnresolvedAppleCxxPlatform> getAppleCxxPlatform(
      BuildRuleResolver ruleResolver,
      FlavorDomain<UnresolvedAppleCxxPlatform> appleCxxPlatformsFlavorDomain,
      BuildTarget buildTarget,
      Optional<Flavor> defaultPlatform) {
    if (appleConfig.getBinaryUsesFallbackPlatform()) {
      UnresolvedAppleCxxPlatform unresolvedPlatform =
          ApplePlatforms.getUnresolvedAppleCxxPlatformForBuildTarget(
              ruleResolver,
              getCxxPlatformsProvider(buildTarget.getTargetConfiguration()),
              appleCxxPlatformsFlavorDomain,
              buildTarget,
              defaultPlatform);
      return Optional.of(unresolvedPlatform);
    }

    return getAppleCxxPlatformFromExplicitBuildTargetFlavor(
        appleCxxPlatformsFlavorDomain, buildTarget);
  }

  /**
   * This method inspects _just_ the build target to determine a platform without any fallbacks.
   * Note that this behavior is different than how any sources will be built because source
   * compilation will use a fallback platform.
   */
  private Optional<UnresolvedAppleCxxPlatform> getAppleCxxPlatformFromExplicitBuildTargetFlavor(
      FlavorDomain<UnresolvedAppleCxxPlatform> appleCxxPlatformsFlavorDomain,
      BuildTarget buildTarget) {
    return appleCxxPlatformsFlavorDomain.getValue(buildTarget);
  }

  @Override
  public <U> Optional<U> createMetadata(
      BuildTarget buildTarget,
      ActionGraphBuilder graphBuilder,
      CellPathResolver cellRoots,
      AppleBinaryDescriptionArg args,
      Optional<ImmutableMap<BuildTarget, Version>> selectedVersions,
      Class<U> metadataClass) {
    if (!metadataClass.isAssignableFrom(FrameworkDependencies.class)) {
      CxxBinaryDescriptionArg.Builder delegateArg = CxxBinaryDescriptionArg.builder().from(args);
      Optional<UnresolvedAppleCxxPlatform> appleCxxPlatform =
          getAppleCxxPlatform(
              graphBuilder,
              AppleDescriptions.getAppleCxxPlatformsFlavorDomain(
                  toolchainProvider, buildTarget.getTargetConfiguration()),
              buildTarget,
              args.getDefaultPlatform());
      AppleDescriptions.populateCxxBinaryDescriptionArg(
          graphBuilder,
          delegateArg,
          appleCxxPlatform,
          args,
          buildTarget,
          appleConfig.getUseTargetSpecificSDKVersionLinkerFlag());
      return cxxBinaryMetadataFactory.createMetadata(
          buildTarget, graphBuilder, delegateArg.build().getDeps(), metadataClass);
    }

    if (metadataClass.isAssignableFrom(HasEntitlementsFile.class)) {
      return Optional.of(metadataClass.cast(args));
    }

    Optional<Flavor> cxxPlatformFlavor =
        getCxxPlatformsProvider(buildTarget.getTargetConfiguration())
            .getUnresolvedCxxPlatforms()
            .getFlavor(buildTarget);
    Preconditions.checkState(
        cxxPlatformFlavor.isPresent(),
        "Could not find cxx platform in:\n%s",
        Joiner.on(", ").join(buildTarget.getFlavors().getSet()));
    ImmutableSet.Builder<SourcePath> sourcePaths = ImmutableSet.builder();
    for (BuildTarget dep : args.getDeps()) {
      Optional<FrameworkDependencies> frameworks =
          graphBuilder.requireMetadata(
              dep.withAppendedFlavors(
                  AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR, cxxPlatformFlavor.get()),
              FrameworkDependencies.class);
      if (frameworks.isPresent()) {
        sourcePaths.addAll(frameworks.get().getSourcePaths());
      }
    }

    return Optional.of(metadataClass.cast(FrameworkDependencies.of(sourcePaths.build())));
  }

  @Override
  public ImmutableSortedSet<Flavor> addImplicitFlavors(
      ImmutableSortedSet<Flavor> argDefaultFlavors,
      TargetConfiguration toolchainTargetConfiguration) {
    // Use defaults.apple_binary if present, but fall back to defaults.cxx_binary otherwise.
    return cxxBinaryImplicitFlavors.addImplicitFlavorsForRuleTypes(
        argDefaultFlavors,
        toolchainTargetConfiguration,
        DescriptionCache.getRuleType(this),
        DescriptionCache.getRuleType(CxxBinaryDescription.class));
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellNameResolver cellRoots,
      AbstractAppleBinaryDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    ImmutableList<ImmutableSortedSet<Flavor>> thinFlavorSets =
        generateThinDelegateFlavors(buildTarget.getFlavors().getSet());
    CxxPlatformsProvider cxxPlatformsProvider =
        getCxxPlatformsProvider(buildTarget.getTargetConfiguration());
    if (thinFlavorSets.size() > 0) {
      for (ImmutableSortedSet<Flavor> flavors : thinFlavorSets) {
        extraDepsBuilder.addAll(
            CxxPlatforms.findDepsForTargetFromConstructorArgs(
                cxxPlatformsProvider, buildTarget.withFlavors(flavors), Optional.empty()));
      }
    } else {
      extraDepsBuilder.addAll(
          CxxPlatforms.findDepsForTargetFromConstructorArgs(
              cxxPlatformsProvider, buildTarget, Optional.empty()));
    }
    AppleDescriptions.findToolchainDeps(buildTarget, targetGraphOnlyDepsBuilder, toolchainProvider);

    // Infer target/binary added as a parse-time dep to flavoured ObjCBinary
    if (CxxInferEnhancer.INFER_FLAVOR_DOMAIN.containsAnyOf(buildTarget.getFlavors())) {
      cxxBinaryFactory
          .getUnresolvedInferPlatform(buildTarget.getTargetConfiguration())
          .addParseTimeDepsToInferFlavored(targetGraphOnlyDepsBuilder, buildTarget);
    }
  }

  private CxxPlatformsProvider getCxxPlatformsProvider(
      TargetConfiguration toolchainTargetConfiguration) {
    return toolchainProvider.getByName(
        CxxPlatformsProvider.DEFAULT_NAME,
        toolchainTargetConfiguration,
        CxxPlatformsProvider.class);
  }

  @RuleArg
  interface AbstractAppleBinaryDescriptionArg
      extends AppleNativeTargetDescriptionArg, HasEntitlementsFile {
    Optional<SourcePath> getInfoPlist();

    ImmutableMap<String, String> getInfoPlistSubstitutions();
  }
}
