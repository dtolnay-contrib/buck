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

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.sourcepath.BuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.util.graph.AbstractBreadthFirstTraversal;
import com.facebook.buck.core.util.graph.DirectedAcyclicGraph;
import com.facebook.buck.core.util.graph.TopologicalSort;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.core.util.immutables.BuckStyleValueWithBuilder;
import com.facebook.buck.cxx.config.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkTarget;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkTargetMode;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableGroup;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableGroups;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableInput;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkables;
import com.facebook.buck.downwardapi.config.DownwardApiConfig;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.SanitizedArg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.util.stream.RichStream;
import com.google.common.base.Functions;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.collect.Streams;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.google.common.io.BaseEncoding;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.immutables.value.Value;

public class Omnibus {

  public static final Flavor OMNIBUS_FLAVOR = InternalFlavor.of("omnibus");
  private static final Flavor DUMMY_OMNIBUS_FLAVOR = InternalFlavor.of("dummy-omnibus");

  private Omnibus() {}

  private static String getOmnibusSoname(CxxPlatform cxxPlatform) {
    return String.format("libomnibus.%s", cxxPlatform.getSharedLibraryExtension());
  }

  /**
   * @return a {@link BuildTarget} to use for a root link rule, composed by concatenating the
   *     top-level omnibus target with the root target.
   */
  private static BuildTarget getRootLinkTarget(BuildTarget base, BuildTarget root) {
    StringBuilder builder = new StringBuilder();

    // If the root target is really long, shorten it via hashing to avoid excessively long
    // path names from concatenating it with the base target.
    String fullRootName = root.getFullyQualifiedName();
    if (fullRootName.length() > 20) {
      builder.append(Flavor.replaceInvalidCharacters(root.getShortName()));
      builder.append("-");
      builder.append(
          BaseEncoding.base64Url()
              .omitPadding()
              .encode(Hashing.sha1().hashString(root.toString(), StandardCharsets.UTF_8).asBytes()),
          0,
          10);
    } else {
      builder.append(Flavor.replaceInvalidCharacters(root.toString()));
    }
    return base.withAppendedFlavors(
        InternalFlavor.of("omnibus-root"), InternalFlavor.of(builder.toString()));
  }

  /**
   * @return a {@link BuildTarget} to use for a root link rule, which may be shared by several
   *     different omnibus links (and so includes all inputs that may affect the rule key).
   */
  private static BuildTarget getDeduplicatedRootLinkTarget(
      BuildTarget root,
      BuildTargetSourcePath dummyOmnibus,
      ImmutableSet<BuildTarget> transitiveRootDeps,
      ImmutableSet<BuildTarget> transitiveExcludedDeps,
      ImmutableList<? extends Arg> ldflags,
      boolean preferStrippedObjects) {
    Hasher hasher = Hashing.md5().newHasher();

    ldflags.forEach(flag -> hasher.putInt(flag.hashCode()));

    hasher.putString(dummyOmnibus.getTarget().getFullyQualifiedName(), StandardCharsets.UTF_8);

    // The transitive root deps of this root can affect how this links, so we need to inlcude it in
    // the rule target name.
    transitiveRootDeps.forEach(
        target -> hasher.putString(target.getFullyQualifiedName(), StandardCharsets.UTF_8));

    // The transitive excluded deps of this root can affect how this links, so we need to include it
    // in the rule target name.
    transitiveExcludedDeps.forEach(
        target -> hasher.putString(target.getFullyQualifiedName(), StandardCharsets.UTF_8));

    hasher.putBoolean(preferStrippedObjects);

    BuildTarget finalTargetWithoutConfigFlavor = getRootLinkTarget(dummyOmnibus.getTarget(), root);

    return finalTargetWithoutConfigFlavor.withAppendedFlavors(
        InternalFlavor.of("omnibus-config-" + hasher.hash().toString().substring(0, 9)));
  }

  private static BuildTarget getDummyRootTarget(BuildTarget root) {
    return root.withAppendedFlavors(InternalFlavor.of("dummy"));
  }

  private static boolean shouldCreateDummyRoot(NativeLinkTarget target) {
    return target.getNativeLinkTargetMode().getType() == Linker.LinkType.EXECUTABLE;
  }

  private static Iterable<NativeLinkable> getDeps(
      NativeLinkable nativeLinkable, ActionGraphBuilder graphBuilder) {
    return Iterables.concat(
        nativeLinkable.getNativeLinkableDeps(graphBuilder),
        nativeLinkable.getNativeLinkableExportedDeps(graphBuilder));
  }

  // Returned the dependencies for the given node, which can either be a `NativeLinkable` or a
  // `NativeLinkTarget`.
  private static Iterable<? extends NativeLinkable> getDeps(
      BuildTarget target,
      Map<BuildTarget, ? extends NativeLinkTarget> nativeLinkTargets,
      Map<BuildTarget, ? extends NativeLinkable> nativeLinkables,
      ActionGraphBuilder graphBuilder) {
    if (nativeLinkables.containsKey(target)) {
      NativeLinkable nativeLinkable = Objects.requireNonNull(nativeLinkables.get(target));
      return getDeps(nativeLinkable, graphBuilder);
    } else {
      NativeLinkTarget nativeLinkTarget = Objects.requireNonNull(nativeLinkTargets.get(target));
      return nativeLinkTarget.getNativeLinkTargetDeps(graphBuilder);
    }
  }

  // Build the data structure containing bookkeeping which describing the omnibus link for the
  // given included and excluded roots.
  static OmnibusSpec buildSpec(
      Iterable<? extends NativeLinkTarget> includedRoots,
      Iterable<? extends NativeLinkable> excludedRoots,
      ActionGraphBuilder actionGraphBuilder) {

    // A map of targets to native linkable objects.  We maintain this, so that we index our
    // bookkeeping around `BuildTarget` and avoid having to guarantee that all other types are
    // hashable.
    Map<BuildTarget, NativeLinkable> nativeLinkables = new LinkedHashMap<>();

    // The nodes which should *not* be included in the omnibus link.
    Set<BuildTarget> excluded = new LinkedHashSet<>();

    // Process all the roots included in the omnibus link.
    Map<BuildTarget, NativeLinkTarget> roots = new LinkedHashMap<>();
    for (NativeLinkTarget root : includedRoots) {
      roots.put(root.getBuildTarget(), root);
    }

    // Find all transitive root deps.
    Map<BuildTarget, NativeLinkable> rootDeps = new LinkedHashMap<>();
    for (NativeLinkable dep :
        NativeLinkables.getNativeLinkables(
            actionGraphBuilder,
            Streams.stream(includedRoots)
                .flatMap(root -> Streams.stream(root.getNativeLinkTargetDeps(actionGraphBuilder)))
                .collect(ImmutableList.toImmutableList()),
            Linker.LinkableDepType.SHARED)) {
      Linker.LinkableDepType linkStyle =
          NativeLinkableGroups.getLinkStyle(
              dep.getPreferredLinkage(), Linker.LinkableDepType.SHARED);
      Preconditions.checkState(linkStyle != Linker.LinkableDepType.STATIC);

      // We only consider deps which aren't *only* statically linked.
      if (linkStyle == Linker.LinkableDepType.SHARED) {
        rootDeps.put(dep.getBuildTarget(), dep);
        nativeLinkables.put(dep.getBuildTarget(), dep);
      }
    }

    // Process all roots excluded from the omnibus link, and add them to our running list of
    // excluded nodes.
    for (NativeLinkable root : excludedRoots) {
      nativeLinkables.put(root.getBuildTarget(), root);
      excluded.add(root.getBuildTarget());
    }

    // Perform the first walk starting from the native linkable nodes immediately reachable via the
    // included roots.  We'll accomplish two things here:
    // 1. Build up the map of node names to their native linkable objects.
    // 2. Perform an initial discovery of dependency nodes to exclude from the omnibus link.
    new AbstractBreadthFirstTraversal<BuildTarget>(rootDeps.keySet()) {
      @Override
      public Iterable<BuildTarget> visit(BuildTarget target) {
        NativeLinkable nativeLinkable = Objects.requireNonNull(nativeLinkables.get(target));
        ImmutableSet.Builder<BuildTarget> deps = ImmutableSet.builder();
        for (NativeLinkable linkable : getDeps(nativeLinkable, actionGraphBuilder)) {
          nativeLinkables.put(linkable.getBuildTarget(), linkable);
          deps.add(linkable.getBuildTarget());
        }
        if (!nativeLinkable.supportsOmnibusLinking()) {
          excluded.add(target);
        }
        return deps.build();
      }
    }.start();

    // Do another walk to flesh out the transitively excluded nodes.
    new AbstractBreadthFirstTraversal<BuildTarget>(excluded) {
      @Override
      public Iterable<BuildTarget> visit(BuildTarget target) {
        NativeLinkable nativeLinkable = Objects.requireNonNull(nativeLinkables.get(target));
        ImmutableSet.Builder<BuildTarget> deps = ImmutableSet.builder();
        for (NativeLinkable linkable : getDeps(nativeLinkable, actionGraphBuilder)) {
          nativeLinkables.put(linkable.getBuildTarget(), linkable);
          deps.add(linkable.getBuildTarget());
        }
        excluded.add(target);
        return deps.build();
      }
    }.start();

    // And then we can do one last walk to create the actual graph which contain only root and body
    // nodes to include in the omnibus link.
    DirectedAcyclicGraph.Builder<BuildTarget> graphBuilder = DirectedAcyclicGraph.serialBuilder();
    Set<BuildTarget> deps = new LinkedHashSet<>();
    new AbstractBreadthFirstTraversal<BuildTarget>(Sets.difference(rootDeps.keySet(), excluded)) {
      @Override
      public Iterable<BuildTarget> visit(BuildTarget target) {
        graphBuilder.addNode(target);
        Set<BuildTarget> keep = new LinkedHashSet<>();
        for (BuildTarget dep :
            Iterables.transform(
                getDeps(target, roots, nativeLinkables, actionGraphBuilder),
                NativeLinkable::getBuildTarget)) {
          if (excluded.contains(dep)) {
            deps.add(dep);
          } else {
            keep.add(dep);
            graphBuilder.addEdge(target, dep);
          }
        }
        return keep;
      }
    }.start();
    DirectedAcyclicGraph<BuildTarget> graph = graphBuilder.build();

    // Since we add all undefined root symbols into the omnibus library, we also need to include
    // any excluded root deps as deps of omnibus, as they may fulfill these undefined symbols.
    // Also add any excluded nodes that are also root dependencies.
    deps.addAll(Sets.intersection(rootDeps.keySet(), excluded));

    return ImmutableOmnibusSpec.ofImpl(
        graph,
        roots,
        graph.getNodes().stream()
            .filter(n -> !roots.containsKey(n))
            .collect(ImmutableMap.toImmutableMap(k -> k, Functions.forMap(nativeLinkables))),
        RichStream.from(excludedRoots).map(NativeLinkable::getBuildTarget).toImmutableSet(),
        Maps.asMap(excluded, Functions.forMap(nativeLinkables)),
        Maps.asMap(deps, Functions.forMap(nativeLinkables)));
  }

  // Build a dummy library with the omnibus SONAME.  We'll need this to break any dep cycle between
  // the omnibus roots and the merged omnibus body, by first linking the roots against this
  // dummy lib (ignoring missing symbols), then linking the omnibus body with the roots.
  private static BuildTargetSourcePath requireDummyOmnibus(
      BuildTarget baseTarget,
      ProjectFilesystem projectFilesystem,
      CellPathResolver cellPathResolver,
      ActionGraphBuilder graphBuilder,
      CxxBuckConfig cxxBuckConfig,
      DownwardApiConfig downwardApiConfig,
      CxxPlatform cxxPlatform,
      ImmutableList<? extends Arg> extraLdflags,
      Optional<BuildTarget> configutedDummyOmnibus) {
    return ((CxxLink)
            configutedDummyOmnibus
                .map(
                    dummyTarget ->
                        graphBuilder.requireRule(
                            dummyTarget.withAppendedFlavors(
                                cxxPlatform.getFlavor(),
                                cxxPlatform.getSharedLibraryInterfaceParams().isPresent()
                                    ? CxxLibraryDescription.Type.SHARED_INTERFACE.getFlavor()
                                    : CxxLibraryDescription.Type.SHARED.getFlavor())))
                .orElseGet(
                    () -> {
                      String omnibusSoname = getOmnibusSoname(cxxPlatform);
                      return graphBuilder.computeIfAbsent(
                          baseTarget.withAppendedFlavors(DUMMY_OMNIBUS_FLAVOR),
                          dummyOmnibusTarget ->
                              CxxLinkableEnhancer.createCxxLinkableSharedBuildRule(
                                  graphBuilder,
                                  downwardApiConfig,
                                  cxxPlatform,
                                  projectFilesystem,
                                  graphBuilder,
                                  dummyOmnibusTarget,
                                  BuildTargetPaths.getGenPath(
                                          projectFilesystem.getBuckPaths(),
                                          dummyOmnibusTarget,
                                          "%s")
                                      .resolve(omnibusSoname),
                                  ImmutableMap.of(),
                                  Optional.of(omnibusSoname),
                                  extraLdflags,
                                  cellPathResolver,
                                  cxxBuckConfig.getOmnibusRootLinkScheduleInfo(),
                                  cxxBuckConfig.getLinkerMapEnabled(),
                                  cxxBuckConfig.shouldCacheOmnibusRootLinks()));
                    }))
        .getSourcePathToOutput();
  }

  // Create a build rule which links the given root node against the merged omnibus library
  // described by the given spec file.
  private static CxxLink createRootRule(
      BuildTarget target,
      ProjectFilesystem projectFilesystem,
      CellPathResolver cellPathResolver,
      ActionGraphBuilder graphBuilder,
      CxxBuckConfig cxxBuckConfig,
      DownwardApiConfig downwardApiConfig,
      CxxPlatform cxxPlatform,
      ImmutableList<? extends Arg> extraLdFlags,
      OmnibusSpec spec,
      SourcePath omnibus,
      Function<BuildTarget, BuildTarget> rootLinkTargetFn,
      NativeLinkTarget root,
      Optional<Path> output,
      boolean preferStrippedObjects) {

    ImmutableList.Builder<Arg> argsBuilder = ImmutableList.builder();

    // Add any extra flags to the link.
    argsBuilder.addAll(extraLdFlags);

    // Since the dummy omnibus library doesn't actually contain any symbols, make sure the linker
    // won't drop its runtime reference to it.
    argsBuilder.addAll(
        StringArg.from(
            cxxPlatform
                .getLd()
                .resolve(graphBuilder, target.getTargetConfiguration())
                .getNoAsNeededSharedLibsFlags()));

    // Since we're linking against a dummy libomnibus, ignore undefined symbols.
    argsBuilder.addAll(
        StringArg.from(
            cxxPlatform
                .getLd()
                .resolve(graphBuilder, target.getTargetConfiguration())
                .getIgnoreUndefinedSymbolsFlags()));

    // Add the args for the root link target first.
    NativeLinkableInput input =
        root.getNativeLinkTargetInput(graphBuilder, graphBuilder.getSourcePathResolver());
    argsBuilder.addAll(input.getArgs());

    // Grab a topologically sorted mapping of all the root's deps.
    ImmutableList<? extends NativeLinkable> deps =
        NativeLinkables.getNativeLinkables(
            graphBuilder,
            root.getNativeLinkTargetDeps(graphBuilder),
            Linker.LinkableDepType.SHARED);

    // Now process the dependencies in topological order, to assemble the link line.
    boolean alreadyAddedOmnibusToArgs = false;
    for (NativeLinkable nativeLinkable : deps) {
      BuildTarget linkableTarget = nativeLinkable.getBuildTarget();
      Linker.LinkableDepType linkStyle =
          NativeLinkableGroups.getLinkStyle(
              nativeLinkable.getPreferredLinkage(), Linker.LinkableDepType.SHARED);

      // If this dep needs to be linked statically, then we always link it directly.
      if (linkStyle != Linker.LinkableDepType.SHARED) {
        Preconditions.checkState(linkStyle == Linker.LinkableDepType.STATIC_PIC);
        argsBuilder.addAll(
            nativeLinkable
                .getNativeLinkableInput(
                    linkStyle,
                    false,
                    graphBuilder,
                    target.getTargetConfiguration(),
                    preferStrippedObjects)
                .getArgs());
        continue;
      }

      // If this dep is another root node, substitute in the custom linked library we built for it.
      if (spec.getRoots().containsKey(linkableTarget)) {
        argsBuilder.add(
            SourcePathArg.of(
                DefaultBuildTargetSourcePath.of(rootLinkTargetFn.apply(linkableTarget))));
        continue;
      }

      // If we're linking this dep from the body, then we need to link via the giant merged
      // libomnibus instead.
      if (spec.getBody()
          .containsKey(linkableTarget)) { // && linkStyle == Linker.LinkableDepType.SHARED) {
        if (!alreadyAddedOmnibusToArgs) {
          argsBuilder.add(SourcePathArg.of(omnibus));
          alreadyAddedOmnibusToArgs = true;
        }
        continue;
      }

      // Otherwise, this is either an explicitly statically linked or excluded node, so link it
      // normally.
      Preconditions.checkState(spec.getExcluded().containsKey(linkableTarget));
      argsBuilder.addAll(
          nativeLinkable
              .getNativeLinkableInput(
                  linkStyle,
                  false,
                  graphBuilder,
                  target.getTargetConfiguration(),
                  preferStrippedObjects)
              .getArgs());
    }

    // Create the root library rule using the arguments assembled above.
    NativeLinkTargetMode rootTargetMode = root.getNativeLinkTargetMode();
    CxxLink rootLinkRule;
    switch (rootTargetMode.getType()) {

        // Link the root as a shared library.
      case SHARED:
        {
          Optional<String> rootSoname = rootTargetMode.getLibraryName();
          rootLinkRule =
              CxxLinkableEnhancer.createCxxLinkableSharedBuildRule(
                  graphBuilder,
                  downwardApiConfig,
                  cxxPlatform,
                  projectFilesystem,
                  graphBuilder,
                  target,
                  output.orElse(
                      BuildTargetPaths.getGenPath(projectFilesystem.getBuckPaths(), target, "%s")
                          .resolve(
                              rootSoname.orElse(
                                  String.format(
                                      "%s.%s",
                                      target.getShortName(),
                                      cxxPlatform.getSharedLibraryExtension())))),
                  ImmutableMap.of(),
                  rootSoname,
                  argsBuilder.build(),
                  cellPathResolver,
                  cxxBuckConfig.getOmnibusRootLinkScheduleInfo(),
                  cxxBuckConfig.getLinkerMapEnabled(),
                  cxxBuckConfig.shouldCacheOmnibusRootLinks());
          break;
        }

        // Link the root as an executable.
      case EXECUTABLE:
        {
          rootLinkRule =
              CxxLinkableEnhancer.createCxxLinkableBuildRule(
                  graphBuilder,
                  cellPathResolver,
                  cxxBuckConfig,
                  downwardApiConfig,
                  cxxPlatform,
                  projectFilesystem,
                  graphBuilder,
                  target,
                  output.orElse(
                      BuildTargetPaths.getGenPath(projectFilesystem.getBuckPaths(), target, "%s")
                          .resolve(target.getShortName())),
                  ImmutableMap.of(),
                  argsBuilder.build(),
                  Linker.LinkableDepType.SHARED,
                  CxxLinkOptions.of(),
                  Optional.empty());
          break;
        }

        // $CASES-OMITTED$
      default:
        throw new IllegalStateException(
            String.format(
                "%s: unexpected omnibus root type: %s", target, rootTargetMode.getType()));
    }

    return rootLinkRule;
  }

  private static OmnibusRoot createRoot(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      CellPathResolver cellPathResolver,
      ActionGraphBuilder graphBuilder,
      CxxBuckConfig cxxBuckConfig,
      DownwardApiConfig downwardApiConfig,
      CxxPlatform cxxPlatform,
      ImmutableList<? extends Arg> extraLdFlags,
      OmnibusSpec spec,
      SourcePath omnibus,
      Function<BuildTarget, BuildTarget> rootLinkTargetFn,
      NativeLinkTarget root,
      boolean preferStrippedObjects) {
    CxxLink link =
        (CxxLink)
            graphBuilder.computeIfAbsent(
                buildTarget,
                target ->
                    createRootRule(
                        target,
                        projectFilesystem,
                        cellPathResolver,
                        graphBuilder,
                        cxxBuckConfig,
                        downwardApiConfig,
                        cxxPlatform,
                        extraLdFlags,
                        spec,
                        omnibus,
                        rootLinkTargetFn,
                        root,
                        root.getNativeLinkTargetOutputPath(),
                        preferStrippedObjects));
    return ImmutableOmnibusRoot.ofImpl(Preconditions.checkNotNull(link.getSourcePathToOutput()));
  }

  private static OmnibusRoot createDummyRoot(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      CellPathResolver cellPathResolver,
      ActionGraphBuilder graphBuilder,
      CxxBuckConfig cxxBuckConfig,
      DownwardApiConfig downwardApiConfig,
      CxxPlatform cxxPlatform,
      ImmutableList<? extends Arg> extraLdFlags,
      OmnibusSpec spec,
      SourcePath omnibus,
      Function<BuildTarget, BuildTarget> rootLinkTargetFn,
      NativeLinkTarget root,
      boolean preferStrippedObjects) {
    CxxLink link =
        (CxxLink)
            graphBuilder.computeIfAbsent(
                buildTarget,
                target ->
                    createRootRule(
                        target,
                        projectFilesystem,
                        cellPathResolver,
                        graphBuilder,
                        cxxBuckConfig,
                        downwardApiConfig,
                        cxxPlatform,
                        extraLdFlags,
                        spec,
                        omnibus,
                        rootLinkTargetFn,
                        root,
                        Optional.empty(),
                        preferStrippedObjects));
    return ImmutableOmnibusRoot.ofImpl(Preconditions.checkNotNull(link.getSourcePathToOutput()));
  }

  private static ImmutableList<Arg> createUndefinedSymbolsArgs(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      ActionGraphBuilder graphBuilder,
      CxxPlatform cxxPlatform,
      Iterable<? extends SourcePath> linkerInputs) {
    SourcePath undefinedSymbolsFile =
        cxxPlatform
            .getSymbolNameTool()
            .createUndefinedSymbolsFile(
                projectFilesystem,
                params,
                graphBuilder,
                buildTarget.getTargetConfiguration(),
                buildTarget.withAppendedFlavors(
                    InternalFlavor.of("omnibus-undefined-symbols-file")),
                linkerInputs);
    return cxxPlatform
        .getLd()
        .resolve(graphBuilder, buildTarget.getTargetConfiguration())
        .createUndefinedSymbolsLinkerArgs(
            projectFilesystem,
            params,
            graphBuilder,
            buildTarget.withAppendedFlavors(InternalFlavor.of("omnibus-undefined-symbols-args")),
            ImmutableList.of(undefinedSymbolsFile));
  }

  private static ImmutableList<Arg> createGlobalSymbolsArgs(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      ActionGraphBuilder graphBuilder,
      CxxPlatform cxxPlatform,
      Iterable<? extends SourcePath> linkerInputs,
      ImmutableList<String> extraGlobals) {
    SourcePath globalSymbolsFile =
        cxxPlatform
            .getSymbolNameTool()
            .creatGlobalSymbolsFile(
                projectFilesystem,
                params,
                graphBuilder,
                buildTarget.getTargetConfiguration(),
                buildTarget.withAppendedFlavors(InternalFlavor.of("omnibus-global-symbols-file")),
                linkerInputs);
    return cxxPlatform
        .getLd()
        .resolve(graphBuilder, buildTarget.getTargetConfiguration())
        .createGlobalSymbolsLinkerArgs(
            projectFilesystem,
            params,
            graphBuilder,
            buildTarget.withAppendedFlavors(InternalFlavor.of("omnibus-global-symbols-args")),
            ImmutableList.of(globalSymbolsFile),
            extraGlobals);
  }

  private static Pattern globalSymbolPattern;

  private static ImmutableList<String> parseGlobalSymbols(ImmutableList<? extends Arg> flags) {

    assert globalSymbolPattern != null;

    ImmutableList.Builder<String> globalSymbols = ImmutableList.builder();
    for (Arg flag : flags) {
      if (flag instanceof SanitizedArg) {
        Matcher matcher = globalSymbolPattern.matcher(flag.toString());
        if (matcher.matches()) {
          String symbol = matcher.group("symbol");
          globalSymbols.add(symbol);
        }
      }
    }
    return globalSymbols.build();
  }

  // Create a build rule to link the giant merged omnibus library described by the given spec.
  private static OmnibusLibrary createOmnibus(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      CellPathResolver cellPathResolver,
      BuildRuleParams params,
      ActionGraphBuilder graphBuilder,
      CxxBuckConfig cxxBuckConfig,
      DownwardApiConfig downwardApiConfig,
      CxxPlatform cxxPlatform,
      ImmutableList<? extends Arg> extraLdflags,
      OmnibusSpec spec,
      Function<BuildTarget, BuildTarget> rootLinkTargetFn,
      boolean preferStrippedObjects) {

    ImmutableList.Builder<Arg> argsBuilder = ImmutableList.builder();

    // Add extra ldflags to the beginning of the link.
    argsBuilder.addAll(extraLdflags);

    // For roots that aren't dependencies of nodes in the body, we extract their undefined symbols
    // to add to the link so that required symbols get pulled into the merged library.
    List<SourcePath> undefinedSymbolsOnlyRoots = new ArrayList<>();
    for (BuildTarget target :
        Sets.difference(spec.getRoots().keySet(), spec.getGraph().getNodes())) {
      NativeLinkTarget linkTarget = Objects.requireNonNull(spec.getRoots().get(target));
      undefinedSymbolsOnlyRoots.add(
          graphBuilder
              .requireRule(
                  rootLinkTargetFn.apply(
                      shouldCreateDummyRoot(linkTarget) ? getDummyRootTarget(target) : target))
              .getSourcePathToOutput());
    }
    // For roots that aren't dependencies of nodes in the body,
    // we extract their global (defined and undefined) symbols to add to the link,
    // so that they can be exported in the merged library.
    List<SourcePath> globalSymbolSources = new ArrayList<>();
    for (BuildTarget target : spec.getRoots().keySet()) {
      NativeLinkTarget linkTarget = Objects.requireNonNull(spec.getRoots().get(target));
      globalSymbolSources.add(
          graphBuilder
              .requireRule(
                  rootLinkTargetFn.apply(
                      shouldCreateDummyRoot(linkTarget) ? getDummyRootTarget(target) : target))
              .getSourcePathToOutput());
    }
    // Similarly, extract global symbols from excluded nodes,
    // omitting static libraries.
    for (NativeLinkable nativeLinkable : spec.getExcluded().values()) {
      if (nativeLinkable.getPreferredLinkage() != NativeLinkableGroup.Linkage.STATIC) {
        for (Map.Entry<String, SourcePath> ent :
            nativeLinkable.getSharedLibraries(graphBuilder).entrySet()) {
          globalSymbolSources.add(ent.getValue());
        }
      }
    }

    argsBuilder.addAll(
        createUndefinedSymbolsArgs(
            buildTarget,
            projectFilesystem,
            params,
            graphBuilder,
            cxxPlatform,
            undefinedSymbolsOnlyRoots));

    String exportDynamicSymbolFlag =
        cxxPlatform
            .getLd()
            .resolve(graphBuilder, buildTarget.getTargetConfiguration())
            .getExportDynamicSymbolFlag();

    // linker args will be in one of these two forms:
    // 1. --export-dynamic-symbol=some_symbol
    // 2. -Wl,--export-dynamic-symbol,some_symbol
    globalSymbolPattern =
        Pattern.compile(
            String.format("(-Wl,)?%s[,=](?<symbol>[_a-zA-Z0-9]+)", exportDynamicSymbolFlag));

    ImmutableList.Builder<String> globalSymbols = ImmutableList.builder();

    // Resolve all `NativeLinkableInput`s in parallel, before using them below.
    ImmutableList<? extends NativeLinkable> deps =
        NativeLinkables.getNativeLinkables(
            graphBuilder, spec.getDeps().values(), Linker.LinkableDepType.SHARED);
    ImmutableMap<BuildTarget, NativeLinkableInput> inputs =
        ImmutableMap.copyOf(
            graphBuilder
                .getParallelizer()
                .maybeParallelizeTransform(
                    ImmutableList.<NativeLinkable>builder()
                        .addAll(spec.getBody().values())
                        .addAll(deps)
                        .build(),
                    nativeLinkable ->
                        new AbstractMap.SimpleEntry<>(
                            Objects.requireNonNull(nativeLinkable).getBuildTarget(),
                            NativeLinkables.getNativeLinkableInput(
                                spec.getBody().containsKey(nativeLinkable.getBuildTarget())
                                    ? Linker.LinkableDepType.STATIC_PIC
                                    : Linker.LinkableDepType.SHARED,
                                nativeLinkable,
                                graphBuilder,
                                buildTarget.getTargetConfiguration(),
                                preferStrippedObjects))));

    // Walk the graph in topological order, appending each nodes contributions to the link.
    ImmutableList<BuildTarget> targets = TopologicalSort.sort(spec.getGraph()).reverse();
    for (BuildTarget target : targets) {

      // If this is a root, just place the shared library we've linked above onto the link line.
      // We need this so that the linker can grab any undefined symbols from it, and therefore
      // know which symbols to pull in from the body nodes.
      NativeLinkTarget root = spec.getRoots().get(target);
      if (root != null) {
        argsBuilder.add(
            SourcePathArg.of(
                graphBuilder
                    .requireRule(rootLinkTargetFn.apply(root.getBuildTarget()))
                    .getSourcePathToOutput()));
        continue;
      }

      // Otherwise, this is a body node, and we need to add its static library to the link line,
      // so that the linker can discard unused object files from it.
      ImmutableList<Arg> args = inputs.get(target).getArgs();
      argsBuilder.addAll(args);
      globalSymbols.addAll(parseGlobalSymbols(args));
    }

    // We process all excluded omnibus deps last, and just add their components as if this were a
    // normal shared link.
    for (NativeLinkable nativeLinkable : deps) {
      argsBuilder.addAll(inputs.get(nativeLinkable.getBuildTarget()).getArgs());
    }

    argsBuilder.addAll(
        createGlobalSymbolsArgs(
            buildTarget,
            projectFilesystem,
            params,
            graphBuilder,
            cxxPlatform,
            globalSymbolSources,
            globalSymbols.build()));

    // Create the merged omnibus library using the arguments assembled above.
    BuildTarget omnibusTarget = buildTarget.withAppendedFlavors(OMNIBUS_FLAVOR);
    String omnibusSoname = getOmnibusSoname(cxxPlatform);
    CxxLink omnibusRule =
        graphBuilder.addToIndex(
            CxxLinkableEnhancer.createCxxLinkableSharedBuildRule(
                graphBuilder,
                downwardApiConfig,
                cxxPlatform,
                projectFilesystem,
                graphBuilder,
                omnibusTarget,
                BuildTargetPaths.getGenPath(projectFilesystem.getBuckPaths(), omnibusTarget, "%s")
                    .resolve(omnibusSoname),
                ImmutableMap.of(),
                Optional.of(omnibusSoname),
                argsBuilder.build(),
                cellPathResolver,
                cxxBuckConfig.getLinkScheduleInfo(),
                cxxBuckConfig.getLinkerMapEnabled(),
                cxxBuckConfig.shouldCacheLinks()));

    return ImmutableOmnibusLibrary.ofImpl(omnibusSoname, omnibusRule.getSourcePathToOutput());
  }

  /** @return transitive roots and excluded deps for all root nodes in the omnibus graph. */
  private static ImmutableMap<BuildTarget, TransitiveRootAndExcludedNodes>
      getTransitiveRootAndExcludedNodes(ActionGraphBuilder graphBuilder, OmnibusSpec spec) {

    /** Helper to walk and cache the omnibus graph. */
    class Processor {

      private final Map<BuildTarget, TransitiveRootAndExcludedNodes> seen = new HashMap<>();

      TransitiveRootAndExcludedNodes processDeps(Iterable<? extends NativeLinkable> deps) {
        ImmutableTransitiveRootAndExcludedNodes.Builder builder =
            ImmutableTransitiveRootAndExcludedNodes.builder();
        for (NativeLinkable dep : deps) {
          if (spec.getRoots().containsKey(dep.getBuildTarget())) {
            builder.addRoots(dep.getBuildTarget());
          }
          if (spec.getExcluded().containsKey(dep.getBuildTarget())) {
            builder.addExcluded(dep.getBuildTarget());
          }
          TransitiveRootAndExcludedNodes depNodes = processLinkable(dep);
          builder.addAllRoots(depNodes.getRoots());
          builder.addAllExcluded(depNodes.getExcluded());
        }
        return builder.build();
      }

      TransitiveRootAndExcludedNodes processLinkable(NativeLinkable linkable) {
        TransitiveRootAndExcludedNodes transitiveRoots = seen.get(linkable.getBuildTarget());
        if (transitiveRoots == null) {
          transitiveRoots =
              processDeps(
                  NativeLinkables.getDepsForLink(
                      graphBuilder, linkable, Linker.LinkableDepType.SHARED));
          seen.put(linkable.getBuildTarget(), transitiveRoots);
        }
        return transitiveRoots;
      }

      TransitiveRootAndExcludedNodes processRoot(NativeLinkTarget root) {
        TransitiveRootAndExcludedNodes transitiveRoots = seen.get(root.getBuildTarget());
        if (transitiveRoots == null) {
          transitiveRoots = processDeps(root.getNativeLinkTargetDeps(graphBuilder));
          seen.put(root.getBuildTarget(), transitiveRoots);
        }
        return transitiveRoots;
      }
    }

    Processor builder = new Processor();

    ImmutableMap.Builder<BuildTarget, TransitiveRootAndExcludedNodes> allTransitiveRoots =
        ImmutableMap.builderWithExpectedSize(spec.getRoots().size());
    for (NativeLinkTarget root : spec.getRoots().values()) {
      allTransitiveRoots.put(root.getBuildTarget(), builder.processRoot(root));
    }
    return allTransitiveRoots.build();
  }

  /**
   * @return a function which maps a root target graph name to a action graph name that's
   *     deduplicated across all omnibus links in the same build.
   */
  private static Function<BuildTarget, BuildTarget> getDeduplicatedRootLinkTargetFn(
      ActionGraphBuilder graphBuilder,
      CxxPlatform cxxPlatform,
      ImmutableList<? extends Arg> extraLdflags,
      OmnibusSpec spec,
      BuildTargetSourcePath dummyOmnibus,
      boolean preferStrippedObjects) {
    ImmutableMap<BuildTarget, TransitiveRootAndExcludedNodes> transitiveRoots =
        getTransitiveRootAndExcludedNodes(graphBuilder, spec);
    return target -> {
      TransitiveRootAndExcludedNodes nodes =
          Preconditions.checkNotNull(transitiveRoots.get(target));
      return getDeduplicatedRootLinkTarget(
          target.withAppendedFlavors(cxxPlatform.getFlavor()),
          dummyOmnibus,
          nodes.getRoots(),
          nodes.getExcluded(),
          extraLdflags,
          preferStrippedObjects);
    };
  }

  /**
   * An alternate link strategy for languages which need to package native deps up as shared
   * libraries, which only links native nodes which have an explicit edge from non-native code as
   * separate, and statically linking all other native nodes into a single giant shared library.
   * This reduces the number of shared libraries considerably and also allows the linker to throw
   * away a lot of unused object files.
   *
   * @param cellPathResolver
   * @param nativeLinkTargetRoots root nodes which will be included in the omnibus link.
   * @param nativeLinkableRoots root nodes which are to be excluded from the omnibus link.
   * @param deduplicateRoots whether to deduplicate root links which are identical across multiple
   *     independent omnibus links.
   * @return a map of shared library names to their containing {@link SourcePath}s.
   */
  public static OmnibusLibraries getSharedLibraries(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      CellPathResolver cellPathResolver,
      ActionGraphBuilder graphBuilder,
      CxxBuckConfig cxxBuckConfig,
      DownwardApiConfig downwardApiConfig,
      CxxPlatform cxxPlatform,
      ImmutableList<? extends Arg> extraLdflags,
      Iterable<? extends NativeLinkTarget> nativeLinkTargetRoots,
      Iterable<? extends NativeLinkable> nativeLinkableRoots,
      boolean preferStrippedObjects,
      Optional<Boolean> deduplicateRoots,
      Optional<BuildTarget> configuredDummyOmnibus) {

    ImmutableOmnibusLibraries.Builder libs = ImmutableOmnibusLibraries.builder();

    OmnibusSpec spec = buildSpec(nativeLinkTargetRoots, nativeLinkableRoots, graphBuilder);

    // Create an empty dummy omnibus library, to give the roots something to link against before
    // we have the actual omnibus library available.  Note that this requires that the linker
    // supports linking shared libraries with undefined references.
    SourcePath dummyOmnibus =
        requireDummyOmnibus(
            buildTarget,
            projectFilesystem,
            cellPathResolver,
            graphBuilder,
            cxxBuckConfig,
            downwardApiConfig,
            cxxPlatform,
            extraLdflags,
            configuredDummyOmnibus);

    // The root target names to use.
    Function<BuildTarget, BuildTarget> independentRootLinkTargetFn =
        target -> getRootLinkTarget(buildTarget, target);
    Function<BuildTarget, BuildTarget> rootLinkTargetFn =
        deduplicateRoots.orElse(cxxBuckConfig.getOmnibusDeduplicateRoots())
            ? getDeduplicatedRootLinkTargetFn(
                graphBuilder,
                cxxPlatform,
                extraLdflags,
                spec,
                (BuildTargetSourcePath) dummyOmnibus,
                preferStrippedObjects)
            : independentRootLinkTargetFn;

    // Create rule for each of the root nodes, linking against the dummy omnibus library above.
    graphBuilder
        .getParallelizer()
        .maybeParallelizeTransform(
            spec.getRoots().values().stream()
                .filter(target -> !shouldCreateDummyRoot(target))
                .collect(Collectors.toList()),
            target -> {
              OmnibusRoot root =
                  createRoot(
                      rootLinkTargetFn.apply(target.getBuildTarget()),
                      projectFilesystem,
                      cellPathResolver,
                      graphBuilder,
                      cxxBuckConfig,
                      downwardApiConfig,
                      cxxPlatform,
                      extraLdflags,
                      spec,
                      dummyOmnibus,
                      rootLinkTargetFn,
                      target,
                      preferStrippedObjects);
              return new AbstractMap.SimpleEntry<>(target.getBuildTarget(), root);
            })
        .forEach(libs::putRoots);

    // For executable roots, some platforms can't properly build them when there are any
    // unresolved symbols, so we initially link a dummy root just to provide a way to grab the
    // undefined symbol list we need to build the real omnibus library.
    for (NativeLinkTarget target : spec.getRoots().values()) {
      if (shouldCreateDummyRoot(target)) {
        createDummyRoot(
            rootLinkTargetFn.apply(getDummyRootTarget(target.getBuildTarget())),
            projectFilesystem,
            cellPathResolver,
            graphBuilder,
            cxxBuckConfig,
            downwardApiConfig,
            cxxPlatform,
            extraLdflags,
            spec,
            dummyOmnibus,
            rootLinkTargetFn,
            target,
            preferStrippedObjects);
      }
    }

    // If there are any body nodes, generate the giant merged omnibus library.
    Optional<SourcePath> realOmnibus = Optional.empty();
    if (!spec.getBody().isEmpty()) {
      OmnibusLibrary omnibus =
          createOmnibus(
              buildTarget,
              projectFilesystem,
              cellPathResolver,
              params,
              graphBuilder,
              cxxBuckConfig,
              downwardApiConfig,
              cxxPlatform,
              extraLdflags,
              spec,
              rootLinkTargetFn,
              preferStrippedObjects);
      libs.addLibraries(omnibus);
      realOmnibus = Optional.of(omnibus.getPath());
    }

    // Do another pass over executable roots, building the real DSO which links to the real omnibus.
    // See the comment above in the first pass for more details.
    for (NativeLinkTarget target : spec.getRoots().values()) {
      if (shouldCreateDummyRoot(target)) {
        OmnibusRoot root =
            createRoot(
                independentRootLinkTargetFn.apply(target.getBuildTarget()),
                projectFilesystem,
                cellPathResolver,
                graphBuilder,
                cxxBuckConfig,
                downwardApiConfig,
                cxxPlatform,
                extraLdflags,
                spec,
                realOmnibus.orElse(dummyOmnibus),
                rootLinkTargetFn,
                target,
                preferStrippedObjects);
        libs.putRoots(target.getBuildTarget(), root);
      }
    }

    // Lastly, add in any shared libraries from excluded nodes the normal way, omitting non-root
    // static libraries.
    for (NativeLinkable nativeLinkable : spec.getExcluded().values()) {
      if (spec.getExcludedRoots().contains(nativeLinkable.getBuildTarget())
          || nativeLinkable.getPreferredLinkage() != NativeLinkableGroup.Linkage.STATIC) {
        for (Map.Entry<String, SourcePath> ent :
            nativeLinkable.getSharedLibraries(graphBuilder).entrySet()) {
          libs.addLibraries(ImmutableOmnibusLibrary.ofImpl(ent.getKey(), ent.getValue()));
        }
      }
    }

    return libs.build();
  }

  @BuckStyleValue
  abstract static class OmnibusSpec {

    // The graph containing all root and body nodes that are to be included in the omnibus link.
    public abstract DirectedAcyclicGraph<BuildTarget> getGraph();

    // All native roots included in the omnibus.  These will get linked into separate shared
    // libraries which depend on the giant statically linked omnibus body.
    public abstract ImmutableMap<BuildTarget, NativeLinkTarget> getRoots();

    // All native nodes which are to be statically linked into the giant combined shared library.
    public abstract ImmutableMap<BuildTarget, NativeLinkable> getBody();

    // All root native nodes which are not included in the omnibus link.
    public abstract ImmutableSet<BuildTarget> getExcludedRoots();

    // All native nodes which are not included in the omnibus link, as either a root or a body node.
    public abstract ImmutableMap<BuildTarget, NativeLinkable> getExcluded();

    // The subset of excluded nodes which are first-order deps of any root or body nodes.
    public abstract ImmutableMap<BuildTarget, NativeLinkable> getDeps();

    @Value.Check
    public void verify() {

      // Verify that all the graph is composed entirely off root and body nodes.
      Preconditions.checkState(
          ImmutableSet.<BuildTarget>builder()
              .addAll(getRoots().keySet())
              .addAll(getBody().keySet())
              .build()
              .containsAll(getGraph().getNodes()));

      // Verify that the root, body, and excluded nodes are distinct and that deps are a subset
      // of the excluded nodes.
      Preconditions.checkState(
          Sets.intersection(getRoots().keySet(), getBody().keySet()).isEmpty());
      Preconditions.checkState(
          Sets.intersection(getRoots().keySet(), getExcluded().keySet()).isEmpty());
      Preconditions.checkState(
          Sets.intersection(getBody().keySet(), getExcluded().keySet()).isEmpty());
      Preconditions.checkState(getExcluded().keySet().containsAll(getDeps().keySet()));
    }
  }

  @BuckStyleValue
  public interface OmnibusRoot {

    SourcePath getPath();
  }

  @BuckStyleValue
  public interface OmnibusLibrary {

    String getSoname();

    SourcePath getPath();
  }

  @BuckStyleValueWithBuilder
  public abstract static class OmnibusLibraries {

    public abstract ImmutableMap<BuildTarget, OmnibusRoot> getRoots();

    public abstract ImmutableList<OmnibusLibrary> getLibraries();
  }

  /** Pair of transitive omnibus roots and excluded nodes. */
  @BuckStyleValueWithBuilder
  interface TransitiveRootAndExcludedNodes {
    ImmutableSet<BuildTarget> getRoots();

    ImmutableSet<BuildTarget> getExcluded();
  }
}
