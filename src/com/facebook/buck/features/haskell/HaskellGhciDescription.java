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

package com.facebook.buck.features.haskell;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.description.arg.HasDepsQuery;
import com.facebook.buck.core.description.attr.ImplicitDepsInferringDescription;
import com.facebook.buck.core.exceptions.BuckUncheckedExecutionException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.util.graph.AbstractBreadthFirstTraversal;
import com.facebook.buck.core.util.immutables.BuckStyleValueWithBuilder;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.cxx.CxxLinkableEnhancer;
import com.facebook.buck.cxx.config.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.linker.Linker.LinkableDepType;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableGroup;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableInput;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkables;
import com.facebook.buck.downwardapi.config.DownwardApiConfig;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.coercer.SourceSortedSet;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.rules.query.Query;
import com.facebook.buck.rules.query.QueryUtils;
import com.facebook.buck.util.stream.RichStream;
import com.facebook.buck.versions.VersionRoot;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.immutables.value.Value;

public class HaskellGhciDescription
    implements DescriptionWithTargetGraph<HaskellGhciDescriptionArg>,
        ImplicitDepsInferringDescription<HaskellGhciDescription.AbstractHaskellGhciDescriptionArg>,
        VersionRoot<HaskellGhciDescriptionArg> {

  private static final Logger LOG = Logger.get(HaskellGhciDescription.class);

  private final ToolchainProvider toolchainProvider;
  private final CxxBuckConfig cxxBuckConfig;
  private final DownwardApiConfig downwardApiConfig;

  public HaskellGhciDescription(
      ToolchainProvider toolchainProvider,
      CxxBuckConfig cxxBuckConfig,
      DownwardApiConfig downwardApiConfig) {
    this.toolchainProvider = toolchainProvider;
    this.cxxBuckConfig = cxxBuckConfig;
    this.downwardApiConfig = downwardApiConfig;
  }

  @Override
  public Class<HaskellGhciDescriptionArg> getConstructorArgType() {
    return HaskellGhciDescriptionArg.class;
  }

  /**
   * @param omnibusRoots roots of the graph of nodes (including transitive deps) to include in the
   *     omnibus link.
   * @param excludedRoots roots of a the graph of nodes (including transitive deps) that cannot be
   *     included in the omnibus link.
   * @return the {@link HaskellGhciOmnibusSpec} describing the omnibus link.
   */
  public static HaskellGhciOmnibusSpec getOmnibusSpec(
      BuildTarget baseTarget,
      ActionGraphBuilder graphBuilder,
      ImmutableList<? extends NativeLinkable> omnibusRoots,
      ImmutableList<? extends NativeLinkable> excludedRoots) {

    LOG.verbose("%s: omnibus roots: %s", baseTarget, omnibusRoots);
    LOG.verbose("%s: excluded roots: %s", baseTarget, excludedRoots);

    ImmutableHaskellGhciOmnibusSpec.Builder builder = ImmutableHaskellGhciOmnibusSpec.builder();

    // Calculate excluded roots/deps, and add them to the link.
    ImmutableList<? extends NativeLinkable> transitiveExcludedLinkables =
        NativeLinkables.getTransitiveNativeLinkables(graphBuilder, excludedRoots);
    builder.setExcludedRoots(Iterables.transform(excludedRoots, r -> r.getBuildTarget()));
    ImmutableMap<BuildTarget, ? extends NativeLinkable> mappedExcludedLinkables =
        Maps.uniqueIndex(transitiveExcludedLinkables, r -> r.getBuildTarget());
    builder.setExcludedTransitiveDeps(mappedExcludedLinkables);

    // Calculate the body and first-order deps of omnibus.
    new AbstractBreadthFirstTraversal<NativeLinkable>(omnibusRoots) {
      @Override
      public Iterable<? extends NativeLinkable> visit(NativeLinkable nativeLinkable) {

        // Excluded linkables can't be included in omnibus.
        if (mappedExcludedLinkables.containsKey(nativeLinkable.getBuildTarget())) {
          LOG.verbose(
              "%s: skipping excluded linkable %s", baseTarget, nativeLinkable.getBuildTarget());
          return ImmutableSet.of();
        }

        // We cannot include prebuilt SOs in omnibus.
        if (nativeLinkable.isPrebuiltSOForHaskellOmnibus(graphBuilder)) {
          builder.addDeps(nativeLinkable);
          LOG.verbose("%s: skipping prebuilt SO %s", baseTarget, nativeLinkable.getBuildTarget());
          return ImmutableSet.of();
        }

        // Include C/C++ libs capable of static linking in omnibus.
        if (nativeLinkable.supportsOmnibusLinkingForHaskell()) {
          builder.addBody(nativeLinkable);
          LOG.verbose(
              "%s: including C/C++ library %s", baseTarget, nativeLinkable.getBuildTarget());
          return Iterables.concat(
              nativeLinkable.getNativeLinkableDeps(graphBuilder),
              nativeLinkable.getNativeLinkableExportedDeps(graphBuilder));
        }

        // Unexpected node.  Can this actually happen?
        //
        // TODO(agallagher): This should probably be an internal error/assertion, as silently
        // dropping libraries at this point will likely result in we're user errors.
        return ImmutableSet.of();
      }
    }.start();

    HaskellGhciOmnibusSpec spec = builder.build();
    LOG.verbose("%s: built omnibus spec %s", baseTarget, spec);
    return spec;
  }

  private static NativeLinkableInput getOmnibusNativeLinkableInput(
      BuildTarget baseTarget,
      ActionGraphBuilder graphBuilder,
      Iterable<NativeLinkable> body,
      Iterable<NativeLinkable> deps) {

    List<NativeLinkableInput> nativeLinkableInputs = new ArrayList<>();

    // Topologically sort the body nodes, so that they're ready to add to the link line.
    ImmutableSet<BuildTarget> bodyTargets =
        RichStream.from(body).map(NativeLinkable::getBuildTarget).toImmutableSet();
    ImmutableList<? extends NativeLinkable> topoSortedBody =
        NativeLinkables.getTopoSortedNativeLinkables(
            body,
            nativeLinkable ->
                FluentIterable.concat(
                        nativeLinkable.getNativeLinkableExportedDeps(graphBuilder),
                        nativeLinkable.getNativeLinkableDeps(graphBuilder))
                    .filter(l -> bodyTargets.contains(l.getBuildTarget()))
                    .iterator());

    // Add the link inputs for all omnibus nodes.
    for (NativeLinkable nativeLinkable : topoSortedBody) {
      try {
        boolean forceLinkWhole = nativeLinkable.forceLinkWholeForHaskellOmnibus();

        TargetConfiguration targetConfiguration = baseTarget.getTargetConfiguration();
        LinkableDepType linkStyle = LinkableDepType.STATIC_PIC;
        NativeLinkableGroup.Linkage link = nativeLinkable.getPreferredLinkage();

        NativeLinkableInput linkableInput =
            nativeLinkable.getNativeLinkableInput(
                NativeLinkables.getLinkStyle(link, linkStyle),
                forceLinkWhole,
                graphBuilder,
                targetConfiguration,
                false);
        nativeLinkableInputs.add(linkableInput);

        LOG.verbose(
            "%s: linking C/C++ library %s into omnibus (forced_link_whole=%s)",
            baseTarget, nativeLinkable.getBuildTarget(), forceLinkWhole);
      } catch (RuntimeException e) {
        throw new BuckUncheckedExecutionException(
            e, "When creating omnibus rule for %s.", baseTarget);
      }
    }

    // Link in omnibus deps dynamically.
    ImmutableList<? extends NativeLinkable> depLinkables =
        NativeLinkables.getNativeLinkables(graphBuilder, deps, LinkableDepType.SHARED);
    for (NativeLinkable linkable : depLinkables) {
      nativeLinkableInputs.add(
          NativeLinkables.getNativeLinkableInput(
              LinkableDepType.SHARED,
              linkable,
              graphBuilder,
              baseTarget.getTargetConfiguration(),
              false));
    }

    return NativeLinkableInput.concat(nativeLinkableInputs);
  }

  /**
   * Give the relative path from the omnibus to its shared library directory. Expose this to enable
   * setting -rpath.
   */
  public static Path getSoLibsRelDir(BuildTarget baseTarget) {
    return Paths.get(baseTarget.getShortName() + ".so-symlinks");
  }

  /** Give a rule for an omnibus object to be loaded into a ghci session */
  public static synchronized BuildRule requireOmnibusSharedObject(
      CellPathResolver cellPathResolver,
      BuildTarget baseTarget,
      ProjectFilesystem projectFilesystem,
      ActionGraphBuilder graphBuilder,
      CxxPlatform cxxPlatform,
      CxxBuckConfig cxxBuckConfig,
      DownwardApiConfig downwardApiConfig,
      Iterable<NativeLinkable> body,
      Iterable<NativeLinkable> deps,
      ImmutableList<Arg> extraLdFlags) {
    return graphBuilder.computeIfAbsent(
        baseTarget.withShortName(baseTarget.getShortName() + ".omnibus-shared-object"),
        ruleTarget -> {
          ImmutableList.Builder<Arg> linkFlagsBuilder = ImmutableList.builder();
          linkFlagsBuilder.addAll(extraLdFlags);
          linkFlagsBuilder.addAll(
              getOmnibusNativeLinkableInput(baseTarget, graphBuilder, body, deps).getArgs());

          // ----------------------------------------------------------------
          // Add to graphBuilder
          return CxxLinkableEnhancer.createCxxLinkableSharedBuildRule(
              graphBuilder,
              downwardApiConfig,
              cxxPlatform,
              projectFilesystem,
              graphBuilder,
              ruleTarget,
              BuildTargetPaths.getGenPath(projectFilesystem.getBuckPaths(), ruleTarget, "%s")
                  .resolve("libghci_dependencies.so"),
              ImmutableMap.of(),
              Optional.of("libghci_dependencies.so"),
              linkFlagsBuilder.build(),
              cellPathResolver,
              cxxBuckConfig.getLinkScheduleInfo(),
              cxxBuckConfig.getLinkerMapEnabled(),
              cxxBuckConfig.shouldCacheLinks());
        });
  }

  // Return the C/C++ platform to build against.
  private UnresolvedHaskellPlatform getPlatform(
      BuildTarget target, AbstractHaskellGhciDescriptionArg arg) {
    HaskellPlatformsProvider haskellPlatformsProvider =
        getHaskellPlatformsProvider(target.getTargetConfiguration());
    FlavorDomain<UnresolvedHaskellPlatform> platforms =
        haskellPlatformsProvider.getHaskellPlatforms();

    Optional<UnresolvedHaskellPlatform> flavorPlatform = platforms.getValue(target);
    if (flavorPlatform.isPresent()) {
      return flavorPlatform.get();
    }

    if (arg.getPlatform().isPresent()) {
      return platforms.getValue(arg.getPlatform().get());
    }

    return haskellPlatformsProvider.getDefaultHaskellPlatform();
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      HaskellGhciDescriptionArg args) {

    HaskellPlatform platform =
        getPlatform(buildTarget, args)
            .resolve(context.getActionGraphBuilder(), buildTarget.getTargetConfiguration());
    return HaskellDescriptionUtils.requireGhciRule(
        buildTarget,
        context.getProjectFilesystem(),
        params,
        context.getCellPathResolver(),
        context.getActionGraphBuilder(),
        platform,
        cxxBuckConfig,
        downwardApiConfig,
        args.getDeps(),
        args.getPlatformDeps(),
        args.getSrcs(),
        args.getPreloadDeps(),
        args.getPlatformPreloadDeps(),
        args.getCompilerFlags(),
        args.getGhciBinDep(),
        args.getGhciInit(),
        args.getExtraScriptTemplates(),
        args.isEnableProfiling());
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellNameResolver cellRoots,
      AbstractHaskellGhciDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {

    targetGraphOnlyDepsBuilder.addAll(
        getPlatform(buildTarget, constructorArg)
            .getParseTimeDeps(buildTarget.getTargetConfiguration()));

    constructorArg
        .getDepsQuery()
        .ifPresent(
            depsQuery ->
                QueryUtils.extractParseTimeTargets(buildTarget, cellRoots, depsQuery)
                    .forEach(targetGraphOnlyDepsBuilder::add));
  }

  private HaskellPlatformsProvider getHaskellPlatformsProvider(
      TargetConfiguration toolchainTargetConfiguration) {
    return toolchainProvider.getByName(
        HaskellPlatformsProvider.DEFAULT_NAME,
        toolchainTargetConfiguration,
        HaskellPlatformsProvider.class);
  }

  /** Composition of {@link NativeLinkableGroup}s in the omnibus link. */
  @BuckStyleValueWithBuilder
  interface HaskellGhciOmnibusSpec {

    // All native nodes which are to be statically linked into the giant combined shared library.
    ImmutableSet<NativeLinkable> getBody();

    // The subset of excluded nodes which are first-order deps of any root or body nodes.
    ImmutableSet<NativeLinkable> getDeps();

    // Native root nodes which are to be excluded from omnibus linking.
    ImmutableSet<BuildTarget> getExcludedRoots();

    // Transitive native nodes which are to be excluded from omnibus linking.
    ImmutableMap<BuildTarget, NativeLinkable> getExcludedTransitiveDeps();
  }

  @RuleArg
  interface AbstractHaskellGhciDescriptionArg extends BuildRuleArg, HasDepsQuery {
    @Value.Default
    default SourceSortedSet getSrcs() {
      return SourceSortedSet.EMPTY;
    }

    ImmutableList<String> getCompilerFlags();

    ImmutableList<StringWithMacros> getLinkerFlags();

    @Value.Default
    default PatternMatchedCollection<ImmutableSortedSet<BuildTarget>> getPlatformDeps() {
      return PatternMatchedCollection.of();
    }

    @Value.Default
    default boolean isEnableProfiling() {
      return false;
    }

    Optional<BuildTarget> getGhciBinDep();

    Optional<SourcePath> getGhciInit();

    Optional<Flavor> getPlatform();

    @Value.Default
    default ImmutableList<SourcePath> getExtraScriptTemplates() {
      return ImmutableList.of();
    }

    @Value.Default
    default ImmutableSortedSet<BuildTarget> getPreloadDeps() {
      return ImmutableSortedSet.of();
    }

    @Value.Default
    default PatternMatchedCollection<ImmutableSortedSet<BuildTarget>> getPlatformPreloadDeps() {
      return PatternMatchedCollection.of();
    }

    @Override
    default HaskellGhciDescriptionArg withDepsQuery(Query query) {
      if (getDepsQuery().equals(Optional.of(query))) {
        return (HaskellGhciDescriptionArg) this;
      }
      return HaskellGhciDescriptionArg.builder().from(this).setDepsQuery(query).build();
    }
  }
}
