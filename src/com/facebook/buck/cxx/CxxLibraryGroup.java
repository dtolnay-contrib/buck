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

package com.facebook.buck.cxx;

import com.facebook.buck.android.packageable.AndroidPackageable;
import com.facebook.buck.android.packageable.AndroidPackageableCollector;
import com.facebook.buck.android.toolchain.ndk.NdkCxxPlatform;
import com.facebook.buck.core.filesystems.ForwardRelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.attr.HasRuntimeDeps;
import com.facebook.buck.core.rules.impl.NoopBuildRule;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.HeaderVisibility;
import com.facebook.buck.cxx.toolchain.PicType;
import com.facebook.buck.cxx.toolchain.StripStyle;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.nativelink.LegacyNativeLinkTargetGroup;
import com.facebook.buck.cxx.toolchain.nativelink.LegacyNativeLinkableGroup;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkTargetMode;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableCacheKey;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableGroup;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableGroups;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableInput;
import com.facebook.buck.cxx.toolchain.nativelink.PlatformLockedNativeLinkableGroup;
import com.facebook.buck.file.CopyTree;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.FileListableLinkerInputArg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.util.MoreMaps;
import com.facebook.buck.util.function.QuintFunction;
import com.facebook.buck.util.stream.RichStream;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Streams;
import com.google.common.util.concurrent.UncheckedExecutionException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedSet;
import java.util.concurrent.ExecutionException;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * An action graph representation of a C/C++ library from the target graph, providing the various
 * interfaces to make it consumable by C/C++ preprocessing and native linkable rules.
 */
public class CxxLibraryGroup extends NoopBuildRule
    implements AbstractCxxLibraryGroup,
        HasRuntimeDeps,
        NativeTestable,
        LegacyNativeLinkableGroup,
        LegacyNativeLinkTargetGroup,
        CxxResourcesProvider {

  private static final Logger LOG = Logger.get(CxxLibraryGroup.class);

  private final Supplier<? extends SortedSet<BuildRule>> declaredDeps;
  private final CxxDeps deps;
  private final CxxDeps exportedDeps;
  private final Predicate<CxxPlatform> headerOnly;
  private final BiFunction<? super CxxPlatform, ActionGraphBuilder, Iterable<? extends Arg>>
      exportedLinkerFlags;
  private final BiFunction<? super CxxPlatform, ActionGraphBuilder, Iterable<? extends Arg>>
      postExportedLinkerFlags;
  private final QuintFunction<
          ? super CxxPlatform,
          ActionGraphBuilder,
          SourcePathResolverAdapter,
          Boolean,
          Boolean,
          NativeLinkableInput>
      linkTargetInput;
  private final Optional<Pattern> supportedPlatformsRegex;
  private final ImmutableSet<FrameworkPath> frameworks;
  private final ImmutableSet<FrameworkPath> libraries;
  private final Linkage linkage;
  private final boolean linkWhole;
  private final boolean includeInAndroidMergeMapOutput;
  private final boolean usedByWrapScript;
  private final Optional<String> soname;
  private final ImmutableSortedSet<BuildTarget> tests;
  private final boolean canBeAsset;
  private final boolean reexportAllHeaderDependencies;
  private final boolean supportsOmnibusLinking;
  private final Optional<Boolean> useArchive;
  private final ImmutableMap<CxxResourceName, SourcePath> resources;

  private final CxxLibraryDescriptionDelegate delegate;

  private final PlatformLockedNativeLinkableGroup.Cache linkableCache;

  /**
   * Whether Native Linkable dependencies should be propagated for the purpose of computing objects
   * to link at link time. Setting this to false makes this library invisible to linking, so it and
   * its link-time dependencies are ignored.
   */
  private final boolean propagateLinkables;

  private final Cache<NativeLinkableCacheKey, NativeLinkableInput> nativeLinkableCache =
      CacheBuilder.newBuilder().build();

  private final TransitiveCxxPreprocessorInputCache transitiveCxxPreprocessorInputCache;

  public CxxLibraryGroup(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      Supplier<? extends SortedSet<BuildRule>> declaredDeps,
      CxxDeps deps,
      CxxDeps exportedDeps,
      Predicate<CxxPlatform> headerOnly,
      BiFunction<? super CxxPlatform, ActionGraphBuilder, Iterable<? extends Arg>>
          exportedLinkerFlags,
      BiFunction<? super CxxPlatform, ActionGraphBuilder, Iterable<? extends Arg>>
          postExportedLinkerFlags,
      QuintFunction<
              ? super CxxPlatform,
              ActionGraphBuilder,
              SourcePathResolverAdapter,
              Boolean,
              Boolean,
              NativeLinkableInput>
          linkTargetInput,
      Optional<Pattern> supportedPlatformsRegex,
      ImmutableSet<FrameworkPath> frameworks,
      ImmutableSet<FrameworkPath> libraries,
      Linkage linkage,
      boolean includeInAndroidMergeMapOutput,
      boolean usedByWrapScript,
      boolean linkWhole,
      Optional<String> soname,
      ImmutableSortedSet<BuildTarget> tests,
      boolean canBeAsset,
      boolean propagateLinkables,
      boolean reexportAllHeaderDependencies,
      boolean supportsOmnibusLinking,
      Optional<Boolean> useArchive,
      ImmutableMap<CxxResourceName, SourcePath> resources,
      CxxLibraryDescriptionDelegate delegate) {
    super(buildTarget, projectFilesystem);
    this.declaredDeps = declaredDeps;
    this.deps = deps;
    this.exportedDeps = exportedDeps;
    this.headerOnly = headerOnly;
    this.exportedLinkerFlags = exportedLinkerFlags;
    this.postExportedLinkerFlags = postExportedLinkerFlags;
    this.linkTargetInput = linkTargetInput;
    this.supportedPlatformsRegex = supportedPlatformsRegex;
    this.frameworks = frameworks;
    this.libraries = libraries;
    this.linkage = linkage;
    this.includeInAndroidMergeMapOutput = includeInAndroidMergeMapOutput;
    this.usedByWrapScript = usedByWrapScript;
    this.linkWhole = linkWhole;
    this.soname = soname;
    this.tests = tests;
    this.canBeAsset = canBeAsset;
    this.propagateLinkables = propagateLinkables;
    this.reexportAllHeaderDependencies = reexportAllHeaderDependencies;
    this.supportsOmnibusLinking = supportsOmnibusLinking;
    this.useArchive = useArchive;
    this.delegate = delegate;
    this.transitiveCxxPreprocessorInputCache = new TransitiveCxxPreprocessorInputCache(this);
    this.linkableCache = LegacyNativeLinkableGroup.getNativeLinkableCache(this);
    this.resources = resources;
  }

  private boolean isPlatformSupported(CxxPlatform cxxPlatform) {
    return !supportedPlatformsRegex.isPresent()
        || supportedPlatformsRegex.get().matcher(cxxPlatform.getFlavor().toString()).find();
  }

  public Iterable<CxxPreprocessorDep> getDirectCxxDeps(
      CxxPlatform cxxPlatform, BuildRuleResolver ruleResolver) {
    if (!isPlatformSupported(cxxPlatform)) {
      return ImmutableList.of();
    }
    return RichStream.from(exportedDeps.get(ruleResolver, cxxPlatform))
        .concat(RichStream.from(deps.get(ruleResolver, cxxPlatform)))
        .filter(CxxPreprocessorDep.class)
        .toImmutableList();
  }

  @Override
  public ImmutableMap<CxxResourceName, SourcePath> getCxxResources() {
    return resources;
  }

  @Override
  public void forEachCxxResourcesDep(
      BuildRuleResolver resolver, Consumer<CxxResourcesProvider> consumer) {
    deps.forEachForAllPlatforms(CxxResourceUtils.filterConsumer(resolver, consumer));
    exportedDeps.forEachForAllPlatforms(CxxResourceUtils.filterConsumer(resolver, consumer));
  }

  @Override
  public Iterable<CxxPreprocessorDep> getCxxPreprocessorDeps(
      CxxPlatform cxxPlatform, BuildRuleResolver ruleResolver) {
    if (!isPlatformSupported(cxxPlatform)) {
      return ImmutableList.of();
    }
    return RichStream.from(exportedDeps.get(ruleResolver, cxxPlatform))
        .concat(
            this.reexportAllHeaderDependencies
                ? RichStream.from(deps.get(ruleResolver, cxxPlatform))
                : Stream.empty())
        .filter(CxxPreprocessorDep.class)
        .toImmutableList();
  }

  private CxxPreprocessorInput getCxxPreprocessorInput(
      CxxPlatform cxxPlatform, HeaderVisibility headerVisibility, ActionGraphBuilder graphBuilder) {
    // Handle via metadata query.
    return CxxLibraryDescription.queryMetadataCxxPreprocessorInput(
            graphBuilder, getBuildTarget(), cxxPlatform, headerVisibility)
        .orElseThrow(IllegalStateException::new);
  }

  @Override
  public CxxPreprocessorInput getCxxPreprocessorInput(
      CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    CxxPreprocessorInput publicHeaders =
        getPublicCxxPreprocessorInputExcludingDelegate(cxxPlatform, graphBuilder);
    Optional<CxxPreprocessorInput> pluginHeaders =
        delegate
            .requireDelegate(getBuildTarget(), cxxPlatform, graphBuilder)
            .flatMap(p -> p.getPreprocessorInput());

    if (pluginHeaders.isPresent()) {
      return CxxPreprocessorInput.concat(ImmutableList.of(publicHeaders, pluginHeaders.get()));
    }

    return publicHeaders;
  }

  /**
   * Returns public headers excluding contribution from any {@link CxxLibraryDescriptionDelegate}.
   */
  public CxxPreprocessorInput getPublicCxxPreprocessorInputExcludingDelegate(
      CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    return getCxxPreprocessorInput(cxxPlatform, HeaderVisibility.PUBLIC, graphBuilder);
  }

  @Override
  public CxxPreprocessorInput getPrivateCxxPreprocessorInput(
      CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    CxxPreprocessorInput privateInput =
        getCxxPreprocessorInput(cxxPlatform, HeaderVisibility.PRIVATE, graphBuilder);
    Optional<CxxPreprocessorInput> delegateInput =
        delegate
            .requireDelegate(getBuildTarget(), cxxPlatform, graphBuilder)
            .flatMap(p -> p.getPrivatePreprocessorInput());

    if (delegateInput.isPresent()) {
      return CxxPreprocessorInput.concat(ImmutableList.of(privateInput, delegateInput.get()));
    }

    return privateInput;
  }

  @Override
  public ImmutableMap<BuildTarget, CxxPreprocessorInput> getTransitiveCxxPreprocessorInput(
      CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    return transitiveCxxPreprocessorInputCache.getUnchecked(cxxPlatform, graphBuilder);
  }

  private void forEachNativeLinkableDep(
      BuildRuleResolver ruleResolver, Consumer<? super NativeLinkableGroup> consumer) {
    if (!propagateLinkables) {
      return;
    }
    deps.forEachForAllPlatforms(NativeLinkableGroups.filterConsumer(ruleResolver, consumer));
  }

  @Override
  public Iterable<NativeLinkableGroup> getNativeLinkableDeps(BuildRuleResolver ruleResolver) {
    // Repeated from `consume*` to avoid allocation.  Remove once relevant callers have been
    // migrated.
    if (!propagateLinkables) {
      return ImmutableList.of();
    }
    ImmutableList.Builder<NativeLinkableGroup> builder = ImmutableList.builder();
    forEachNativeLinkableDep(ruleResolver, builder::add);
    return builder.build();
  }

  private void forEachNativeLinkableDepForPlatform(
      CxxPlatform cxxPlatform,
      BuildRuleResolver ruleResolver,
      Consumer<? super NativeLinkableGroup> consumer) {
    if (!propagateLinkables) {
      return;
    }
    if (!isPlatformSupported(cxxPlatform)) {
      return;
    }
    deps.forEach(cxxPlatform, NativeLinkableGroups.filterConsumer(ruleResolver, consumer));
  }

  @Override
  public Iterable<NativeLinkableGroup> getNativeLinkableDepsForPlatform(
      CxxPlatform cxxPlatform, BuildRuleResolver ruleResolver) {
    // Repeated from `consume*` to avoid allocation.  Remove once relevant callers have been
    // migrated.
    if (!propagateLinkables) {
      return ImmutableList.of();
    }
    if (!isPlatformSupported(cxxPlatform)) {
      return ImmutableList.of();
    }
    ImmutableList.Builder<NativeLinkableGroup> builder = ImmutableList.builder();
    forEachNativeLinkableDepForPlatform(cxxPlatform, ruleResolver, builder::add);
    return builder.build();
  }

  private void forEachNativeLinkableExportedDep(
      BuildRuleResolver ruleResolver, Consumer<? super NativeLinkableGroup> consumer) {
    if (!propagateLinkables) {
      return;
    }
    exportedDeps.forEachForAllPlatforms(
        NativeLinkableGroups.filterConsumer(ruleResolver, consumer));
  }

  @Override
  public Iterable<? extends NativeLinkableGroup> getNativeLinkableExportedDeps(
      BuildRuleResolver ruleResolver) {
    // Repeated from `consume*` to avoid allocation.  Remove once relevant callers have been
    // migrated.
    if (!propagateLinkables) {
      return ImmutableList.of();
    }
    ImmutableList.Builder<NativeLinkableGroup> builder = ImmutableList.builder();
    forEachNativeLinkableExportedDep(ruleResolver, builder::add);
    return builder.build();
  }

  private void forEachNativeLinkableExportedDepForPlatform(
      CxxPlatform cxxPlatform,
      ActionGraphBuilder graphBuilder,
      Consumer<? super NativeLinkableGroup> consumer) {
    if (!propagateLinkables) {
      return;
    }
    if (!isPlatformSupported(cxxPlatform)) {
      return;
    }
    exportedDeps.forEach(cxxPlatform, NativeLinkableGroups.filterConsumer(graphBuilder, consumer));
    delegate
        .requireDelegate(getBuildTarget(), cxxPlatform, graphBuilder)
        .ifPresent(
            d -> d.getNativeLinkableExportedDeps().ifPresent(deps -> deps.forEach(consumer)));
  }

  @Override
  public Iterable<? extends NativeLinkableGroup> getNativeLinkableExportedDepsForPlatform(
      CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    // Repeated from `consume*` to avoid allocation.  Remove once relevant callers have been
    // migrated.
    if (!propagateLinkables) {
      return ImmutableList.of();
    }
    if (!isPlatformSupported(cxxPlatform)) {
      return ImmutableList.of();
    }
    ImmutableList.Builder<NativeLinkableGroup> builder = ImmutableList.builder();
    forEachNativeLinkableExportedDepForPlatform(cxxPlatform, graphBuilder, builder::add);
    return builder.build();
  }

  @VisibleForTesting
  ImmutableList<SourcePath> getObjects(
      ActionGraphBuilder graphBuilder, CxxPlatform cxxPlatform, PicType picType, boolean stripped) {
    return CxxLibraryMetadataFactory.requireObjects(
        getBuildTarget(),
        graphBuilder,
        cxxPlatform,
        picType,
        stripped ? Optional.of(StripStyle.DEBUGGING_SYMBOLS) : Optional.empty());
  }

  private NativeLinkableInput computeNativeLinkableInputUncached(
      NativeLinkableCacheKey key, ActionGraphBuilder graphBuilder) {
    CxxPlatform cxxPlatform = key.getCxxPlatform();

    if (!isPlatformSupported(cxxPlatform)) {
      LOG.verbose("Skipping library %s on platform %s", this, cxxPlatform.getFlavor());
      return NativeLinkableInput.of();
    }

    Linker.LinkableDepType type = key.getType();
    boolean forceLinkWhole = key.getForceLinkWhole();
    boolean preferStripped = key.getPreferStripped();

    // Build up the arguments used to link this library.  If we're linking the
    // whole archive, wrap the library argument in the necessary "ld" flags.
    ImmutableList.Builder<Arg> linkerArgsBuilder = ImmutableList.builder();
    linkerArgsBuilder.addAll(
        Objects.requireNonNull(exportedLinkerFlags.apply(cxxPlatform, graphBuilder)));

    boolean delegateWantsArtifact =
        delegate
            .requireDelegate(getBuildTarget(), cxxPlatform, graphBuilder)
            .map(d -> d.getShouldProduceLibraryArtifact())
            .orElse(false);
    boolean headersOnly = headerOnly.test(cxxPlatform);
    boolean shouldProduceArtifact = (!headersOnly || delegateWantsArtifact) && propagateLinkables;

    if (shouldProduceArtifact) {
      boolean isStatic;
      switch (linkage) {
        case STATIC:
          isStatic = true;
          break;
        case SHARED:
          isStatic = false;
          break;
        case ANY:
          isStatic = type != Linker.LinkableDepType.SHARED;
          break;
        default:
          throw new IllegalStateException("unhandled linkage type: " + linkage);
      }
      if (isStatic) {
        if (useArchive.orElse(true) || cxxPlatform.getRequiresArchives()) {
          List<Flavor> archiveFlavors =
              Lists.newArrayList(
                  cxxPlatform.getFlavor(),
                  type == Linker.LinkableDepType.STATIC
                      ? CxxDescriptionEnhancer.STATIC_FLAVOR
                      : CxxDescriptionEnhancer.STATIC_PIC_FLAVOR);
          if (preferStripped) {
            archiveFlavors.add(StripStyle.DEBUGGING_SYMBOLS.getFlavor());
          }
          Archive archive =
              (Archive) requireBuildRule(graphBuilder, archiveFlavors.toArray(new Flavor[0]));
          if (linkWhole || forceLinkWhole) {
            Linker linker =
                cxxPlatform
                    .getLd()
                    .resolve(graphBuilder, getBuildTarget().getTargetConfiguration());
            linkerArgsBuilder.addAll(
                linker.linkWhole(archive.toArg(), graphBuilder.getSourcePathResolver()));
          } else {
            Arg libraryArg = archive.toArg();
            if (libraryArg instanceof SourcePathArg) {
              linkerArgsBuilder.add(
                  FileListableLinkerInputArg.withSourcePathArg((SourcePathArg) libraryArg));
            } else {
              linkerArgsBuilder.add(libraryArg);
            }
          }
        } else {

          // Archive-less
          ImmutableList<SourcePath> objects =
              getObjects(
                  graphBuilder,
                  cxxPlatform,
                  type == Linker.LinkableDepType.STATIC ? PicType.PDC : PicType.PIC,
                  preferStripped);
          Iterable<Arg> objectArgs =
              objects.stream().map(SourcePathArg::of).collect(Collectors.toList());

          if (!(linkWhole || forceLinkWhole)) {
            Linker linker =
                cxxPlatform
                    .getLd()
                    .resolve(graphBuilder, getBuildTarget().getTargetConfiguration());
            linkerArgsBuilder.addAll(linker.asLibrary(objectArgs));
          } else {
            linkerArgsBuilder.addAll(objectArgs);
          }
        }
      } else {
        BuildRule rule =
            requireBuildRule(
                graphBuilder,
                cxxPlatform.getFlavor(),
                cxxPlatform.getSharedLibraryInterfaceParams().isPresent()
                    ? CxxLibraryDescription.Type.SHARED_INTERFACE.getFlavor()
                    : CxxLibraryDescription.Type.SHARED.getFlavor());
        SourcePath sourcePathForLinking =
            rule instanceof CxxLink
                ? ((CxxLink) rule).getSourcePathToOutputForLinking()
                : rule.getSourcePathToOutput();
        linkerArgsBuilder.add(SourcePathArg.of(Objects.requireNonNull(sourcePathForLinking)));
      }
    }

    // Add the postExportedLinkerFlags.
    linkerArgsBuilder.addAll(
        Objects.requireNonNull(postExportedLinkerFlags.apply(cxxPlatform, graphBuilder)));

    ImmutableList<Arg> linkerArgs = linkerArgsBuilder.build();
    ImmutableSet<SourcePath> swiftmodulePaths =
        delegate
            .requireDelegate(getBuildTarget(), cxxPlatform, graphBuilder)
            .map(d -> d.getSwiftmodulePaths())
            .orElse(ImmutableSet.of());
    ImmutableSet<Arg> deduplicatedLinkerArgs =
        delegate
            .requireDelegate(getBuildTarget(), cxxPlatform, graphBuilder)
            .map(d -> d.getDeduplicatedLinkerArgs())
            .orElse(ImmutableSet.of());

    return NativeLinkableInput.of(
        linkerArgs,
        Objects.requireNonNull(frameworks),
        Objects.requireNonNull(libraries),
        swiftmodulePaths,
        deduplicatedLinkerArgs);
  }

  @Override
  public NativeLinkableInput getNativeLinkableInput(
      CxxPlatform cxxPlatform,
      Linker.LinkableDepType type,
      boolean forceLinkWhole,
      ActionGraphBuilder graphBuilder,
      TargetConfiguration targetConfiguration,
      boolean preferStripped) {
    NativeLinkableCacheKey key =
        NativeLinkableCacheKey.of(
            cxxPlatform.getFlavor(), type, forceLinkWhole, cxxPlatform, preferStripped);
    try {
      return nativeLinkableCache.get(
          key, () -> computeNativeLinkableInputUncached(key, graphBuilder));
    } catch (ExecutionException e) {
      throw new UncheckedExecutionException(e.getCause());
    }
  }

  /** Require a flavored version of this build rule */
  public BuildRule requireBuildRule(ActionGraphBuilder graphBuilder, Flavor... flavors) {
    return graphBuilder.requireRule(getBuildTarget().withAppendedFlavors(flavors));
  }

  @Override
  public NativeLinkableGroup.Linkage getPreferredLinkage(CxxPlatform cxxPlatform) {
    return linkage;
  }

  @Override
  public boolean getIncludeInAndroidMergeMapOutput(CxxPlatform cxxPlatform) {
    return includeInAndroidMergeMapOutput;
  }

  @Override
  public Iterable<AndroidPackageable> getRequiredPackageables(
      BuildRuleResolver ruleResolver, Supplier<Iterable<NdkCxxPlatform>> ndkCxxPlatforms) {
    // Both iterables we are concating are ImmutableSets, so the returned iterator does not support
    // remove
    return AndroidPackageableCollector.getPackageableRules(
        Iterables.concat(
            Streams.stream(ndkCxxPlatforms.get())
                .map(
                    platform ->
                        Iterables.concat(
                            deps.get(ruleResolver, platform.getCxxPlatform()),
                            exportedDeps.get(ruleResolver, platform.getCxxPlatform())))
                .collect(ImmutableList.toImmutableList())));
  }

  @Override
  public void addToCollector(
      ActionGraphBuilder graphBuilder, AndroidPackageableCollector collector) {
    if (canBeAsset) {
      collector.addNativeLinkableAsset(this);
    } else if (usedByWrapScript) {
      collector.addNativeLinkableUsedByWrapScript(this);
    } else {
      collector.addNativeLinkable(this);
    }
    if (!resources.isEmpty()) {
      ForwardRelPath cxxResRoot = ForwardRelPath.of("cxx-resources");
      collector.addAssetsDirectory(
          getBuildTarget(),
          graphBuilder
              .computeIfAbsent(
                  getBuildTarget().withAppendedFlavors(InternalFlavor.of("android-resources")),
                  target ->
                      new CopyTree(
                          target,
                          getProjectFilesystem(),
                          graphBuilder,
                          ImmutableSortedMap.copyOf(
                              MoreMaps.transformKeys(
                                  resources, name -> cxxResRoot.resolve(name.getNameAsPath())))))
              .getSourcePathToOutput());
    }
  }

  @Override
  public ImmutableMap<String, SourcePath> getSharedLibraries(
      CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    if (headerOnly.test(cxxPlatform)) {
      return ImmutableMap.of();
    }
    if (!isPlatformSupported(cxxPlatform)) {
      LOG.verbose("Skipping library %s on platform %s", this, cxxPlatform.getFlavor());
      return ImmutableMap.of();
    }
    String sharedLibrarySoname =
        CxxDescriptionEnhancer.getSharedLibrarySoname(
            soname, getBuildTarget(), cxxPlatform, getProjectFilesystem());
    BuildRule sharedLibraryBuildRule =
        requireBuildRule(
            graphBuilder, cxxPlatform.getFlavor(), CxxDescriptionEnhancer.SHARED_FLAVOR);
    SourcePath sourcePathToOutput = sharedLibraryBuildRule.getSourcePathToOutput();
    Preconditions.checkNotNull(
        sourcePathToOutput,
        "rule output is null: %s; for so: %s",
        sharedLibraryBuildRule,
        sharedLibrarySoname);
    return ImmutableMap.of(sharedLibrarySoname, sourcePathToOutput);
  }

  @Override
  public boolean isTestedBy(BuildTarget testTarget) {
    return tests.contains(testTarget);
  }

  @Override
  public NativeLinkTargetMode getNativeLinkTargetMode(CxxPlatform cxxPlatform) {
    return NativeLinkTargetMode.library(
        CxxDescriptionEnhancer.getSharedLibrarySoname(
            soname, getBuildTarget(), cxxPlatform, getProjectFilesystem()));
  }

  @Override
  public Iterable<? extends NativeLinkableGroup> getNativeLinkTargetDeps(
      CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    return Iterables.concat(
        getNativeLinkableDepsForPlatform(cxxPlatform, graphBuilder),
        getNativeLinkableExportedDepsForPlatform(cxxPlatform, graphBuilder));
  }

  @Override
  public NativeLinkableInput getNativeLinkTargetInput(
      CxxPlatform cxxPlatform,
      ActionGraphBuilder graphBuilder,
      SourcePathResolverAdapter pathResolver,
      boolean includePrivateLinkerFlags,
      boolean preferStripped) {
    if (!isPlatformSupported(cxxPlatform)) {
      LOG.verbose("Skipping library %s on platform %s", this, cxxPlatform.getFlavor());
      return NativeLinkableInput.of();
    }
    return linkTargetInput.apply(
        cxxPlatform, graphBuilder, pathResolver, includePrivateLinkerFlags, preferStripped);
  }

  @Override
  public Optional<Path> getNativeLinkTargetOutputPath() {
    return Optional.empty();
  }

  @Override
  public boolean supportsOmnibusLinking(CxxPlatform cxxPlatform) {
    return supportsOmnibusLinking;
  }

  @Override
  public boolean supportsOmnibusLinkingForHaskell(CxxPlatform cxxPlatform) {
    // TODO(agallagher): This should use supportsOmnibusLinking.
    return true;
  }

  @Override
  public Stream<BuildTarget> getRuntimeDeps(BuildRuleResolver buildRuleResolver) {
    // We export all declared deps as runtime deps, to setup a transitive runtime dep chain which
    // will pull in runtime deps (e.g. other binaries) or transitive C/C++ libraries.  Since the
    // `CxxLibrary` rules themselves are noop meta rules, they shouldn't add any unnecessary
    // overhead.
    return RichStream.from(declaredDeps.get().stream())
        // Make sure to use the rule ruleFinder that's passed in rather than the field we're
        // holding, since we need to access nodes already created by the previous build rule
        // ruleFinder in the incremental action graph scenario, and {@see #updateBuildRuleResolver}
        // may already have been called.
        .concat(exportedDeps.getForAllPlatforms(buildRuleResolver).stream())
        .map(BuildRule::getBuildTarget);
  }

  @Override
  public Iterable<? extends Arg> getExportedLinkerFlags(
      CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    return exportedLinkerFlags.apply(cxxPlatform, graphBuilder);
  }

  @Override
  public Iterable<? extends Arg> getExportedPostLinkerFlags(
      CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    return postExportedLinkerFlags.apply(cxxPlatform, graphBuilder);
  }

  @Override
  public boolean forceLinkWholeForHaskellOmnibus() {
    // We link C/C++ libraries whole...
    return true;
  }

  @Override
  public PlatformLockedNativeLinkableGroup.Cache getNativeLinkableCompatibilityCache() {
    return linkableCache;
  }
}
