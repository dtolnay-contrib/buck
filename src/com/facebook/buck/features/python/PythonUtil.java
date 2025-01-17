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

package com.facebook.buck.features.python;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.util.Optionals;
import com.facebook.buck.core.util.graph.AbstractBreadthFirstTraversal;
import com.facebook.buck.cxx.CxxGenruleDescription;
import com.facebook.buck.cxx.CxxResourceUtils;
import com.facebook.buck.cxx.CxxResourcesProvider;
import com.facebook.buck.cxx.Omnibus;
import com.facebook.buck.cxx.OmnibusRoots;
import com.facebook.buck.cxx.config.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkStrategy;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkTarget;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkTargetMode;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableGroup;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkables;
import com.facebook.buck.downwardapi.config.DownwardApiConfig;
import com.facebook.buck.features.python.toolchain.PythonPlatform;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.pathformat.PathFormatter;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.coercer.SourceSortedSet;
import com.facebook.buck.rules.coercer.VersionMatchedCollection;
import com.facebook.buck.rules.macros.AbsoluteOutputMacroExpander;
import com.facebook.buck.rules.macros.ExecutableMacro;
import com.facebook.buck.rules.macros.ExecutableMacroExpander;
import com.facebook.buck.rules.macros.ExecutableTargetMacro;
import com.facebook.buck.rules.macros.LocationMacroExpander;
import com.facebook.buck.rules.macros.Macro;
import com.facebook.buck.rules.macros.MacroExpander;
import com.facebook.buck.rules.macros.QueryTargetsAndOutputsMacroExpander;
import com.facebook.buck.rules.macros.QueryTargetsMacroExpander;
import com.facebook.buck.util.MoreMaps;
import com.facebook.buck.util.function.TriConsumer;
import com.facebook.buck.util.stream.RichStream;
import com.facebook.buck.versions.Version;
import com.google.common.base.CaseFormat;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class PythonUtil {

  static final String SOURCE_EXT = "py";

  static final String INIT_PY = "__init__.py";

  static ImmutableList<MacroExpander<? extends Macro, ?>> macroExpanders(TargetGraph targetGraph) {
    return ImmutableList.of(
        LocationMacroExpander.INSTANCE,
        new ExecutableMacroExpander<>(ExecutableMacro.class),
        new ExecutableMacroExpander<>(ExecutableTargetMacro.class),
        AbsoluteOutputMacroExpander.INSTANCE,
        new QueryTargetsMacroExpander(targetGraph),
        new QueryTargetsAndOutputsMacroExpander(targetGraph));
  }

  private PythonUtil() {}

  public static boolean isNativeExt(String ext) {
    return ext.equals("so") || ext.equals("dll") || ext.equals("pyd");
  }

  public static boolean isSourceExt(String ext) {
    return ext.equals(SOURCE_EXT);
  }

  public static boolean isModuleExt(String ext) {
    return isNativeExt(ext) || isSourceExt(ext);
  }

  public static void checkSrcExt(
      PythonBuckConfig.SrcExtCheckStyle srcExtCheckStyle,
      ActionGraphBuilder graphBuilder,
      String parameter,
      Path name,
      SourcePath path) {
    switch (srcExtCheckStyle) {
      case NONE:
        break;
      case GENERATED:
        if (!graphBuilder.getRule(path).isPresent()) {
          break;
        }
        // $FALL-THROUGH$
      case ALL:
        String ext = MorePaths.getFileExtension(name);
        // TODO(agallagher): Ideally, we wouldn't pass `.pyi` files via `srcs`.
        if (!isSourceExt(ext) && !ext.equals("pyi")) {
          throw new HumanReadableException(
              "parameter `%s`: invalid source extension for %s", parameter, name);
        }
    }
  }

  /**
   * @return the merged list of all items for the given platform, from the given platform-agnostic
   *     and platform-specific params.
   */
  public static <T, C extends Collection<T>> ImmutableList<T> getParamForPlatform(
      PythonPlatform pythonPlatform,
      CxxPlatform cxxPlatform,
      C param,
      PatternMatchedCollection<C> platformParam) {
    return RichStream.from(param)
        .concat(
            platformParam.getMatchingValues(pythonPlatform.getFlavor().toString()).stream()
                .flatMap(Collection::stream))
        .concat(
            platformParam.getMatchingValues(cxxPlatform.getFlavor().toString()).stream()
                .flatMap(Collection::stream))
        .toImmutableList();
  }

  private static void forEachModule(
      BuildTarget target,
      ActionGraphBuilder graphBuilder,
      PythonPlatform pythonPlatform,
      CxxPlatform cxxPlatform,
      String parameter,
      Path baseModule,
      SourceSortedSet items,
      PatternMatchedCollection<SourceSortedSet> platformItems,
      Optional<VersionMatchedCollection<SourceSortedSet>> versionItems,
      Optional<ImmutableMap<BuildTarget, Version>> versions,
      TriConsumer<String, Path, SourcePath> consumer) {
    forEachModuleParam(
        target,
        graphBuilder,
        cxxPlatform,
        parameter,
        baseModule,
        ImmutableList.of(items),
        (name, src) -> consumer.accept(parameter, name, src));
    forEachModuleParam(
        target,
        graphBuilder,
        cxxPlatform,
        "platform" + CaseFormat.LOWER_HYPHEN.to(CaseFormat.UPPER_CAMEL, parameter),
        baseModule,
        Iterables.concat(
            platformItems.getMatchingValues(pythonPlatform.getFlavor().toString()),
            platformItems.getMatchingValues(cxxPlatform.getFlavor().toString())),
        (name, src) -> consumer.accept(parameter, name, src));
    forEachModuleParam(
        target,
        graphBuilder,
        cxxPlatform,
        "versioned" + CaseFormat.LOWER_HYPHEN.to(CaseFormat.UPPER_CAMEL, parameter),
        baseModule,
        versions.isPresent() && versionItems.isPresent()
            ? versionItems.get().getMatchingValues(versions.get())
            : ImmutableList.of(),
        (name, src) -> consumer.accept(parameter, name, src));
  }

  private static void forEachSrc(
      BuildTarget target,
      ActionGraphBuilder graphBuilder,
      PythonPlatform pythonPlatform,
      CxxPlatform cxxPlatform,
      Optional<ImmutableMap<BuildTarget, Version>> versions,
      PythonLibraryDescription.CoreArg args,
      TriConsumer<String, Path, SourcePath> consumer) {
    forEachModule(
        target,
        graphBuilder,
        pythonPlatform,
        cxxPlatform,
        "srcs",
        PythonUtil.getBasePath(target, args.getBaseModule()),
        args.getSrcs(),
        args.getPlatformSrcs(),
        args.getVersionedSrcs(),
        versions,
        consumer);
  }

  public static ImmutableSortedMap<Path, SourcePath> parseSources(
      BuildTarget target,
      ActionGraphBuilder graphBuilder,
      PythonPlatform pythonPlatform,
      CxxPlatform cxxPlatform,
      Optional<ImmutableMap<BuildTarget, Version>> versions,
      PythonLibraryDescription.CoreArg args) {
    ImmutableSortedMap.Builder<Path, SourcePath> builder = ImmutableSortedMap.naturalOrder();
    forEachSrc(
        target,
        graphBuilder,
        pythonPlatform,
        cxxPlatform,
        versions,
        args,
        (parameter, name, src) -> {
          if (isSourceExt(MorePaths.getFileExtension(name))) {
            builder.put(name, src);
          }
        });
    return builder.build();
  }

  public static ImmutableSortedMap<Path, SourcePath> parseModules(
      BuildTarget target,
      ActionGraphBuilder graphBuilder,
      PythonPlatform pythonPlatform,
      CxxPlatform cxxPlatform,
      Optional<ImmutableMap<BuildTarget, Version>> versions,
      PythonBuckConfig.SrcExtCheckStyle srcExtCheckStyle,
      PythonLibraryDescription.CoreArg args) {
    ImmutableSortedMap.Builder<Path, SourcePath> builder = ImmutableSortedMap.naturalOrder();
    forEachSrc(
        target,
        graphBuilder,
        pythonPlatform,
        cxxPlatform,
        versions,
        args,
        (parameter, name, src) -> {
          checkSrcExt(srcExtCheckStyle, graphBuilder, parameter, name, src);
          builder.put(name, src);
        });
    return builder.build();
  }

  public static ImmutableSortedMap<Path, SourcePath> parseResources(
      BuildTarget target,
      ActionGraphBuilder graphBuilder,
      PythonPlatform pythonPlatform,
      CxxPlatform cxxPlatform,
      Optional<ImmutableMap<BuildTarget, Version>> versions,
      PythonLibraryDescription.CoreArg args) {
    ImmutableSortedMap.Builder<Path, SourcePath> builder = ImmutableSortedMap.naturalOrder();
    forEachModule(
        target,
        graphBuilder,
        pythonPlatform,
        cxxPlatform,
        "resources",
        PythonUtil.getBasePath(target, args.getBaseModule()),
        args.getResources(),
        args.getPlatformResources(),
        args.getVersionedResources(),
        versions,
        (parameter, name, src) -> builder.put(name, src));
    return builder.build();
  }

  static void forEachModuleParam(
      BuildTarget target,
      ActionGraphBuilder actionGraphBuilder,
      CxxPlatform cxxPlatform,
      String parameter,
      Path baseModule,
      Iterable<SourceSortedSet> inputs,
      BiConsumer<Path, SourcePath> consumer) {

    for (SourceSortedSet input : inputs) {
      ImmutableMap<String, SourcePath> namesAndSourcePaths =
          input.match(
              new SourceSortedSet.Matcher<ImmutableMap<String, SourcePath>>() {
                @Override
                public ImmutableMap<String, SourcePath> named(
                    ImmutableSortedMap<String, SourcePath> named) {
                  return named;
                }

                @Override
                public ImmutableMap<String, SourcePath> unnamed(
                    ImmutableSortedSet<SourcePath> unnamed) {
                  return actionGraphBuilder
                      .getSourcePathResolver()
                      .getSourcePathNames(target, parameter, unnamed);
                }
              });
      for (ImmutableMap.Entry<String, SourcePath> entry : namesAndSourcePaths.entrySet()) {
        consumer.accept(
            baseModule.resolve(baseModule.getFileSystem().getPath(entry.getKey()).normalize()),
            CxxGenruleDescription.fixupSourcePath(
                actionGraphBuilder, cxxPlatform.getFlavor(), entry.getValue()));
      }
    }
  }

  /** Convert a path to a module to it's module name as referenced in import statements. */
  static String toModuleName(BuildTarget target, String name) {
    int ext = name.lastIndexOf('.');
    if (ext == -1) {
      throw new HumanReadableException("%s: missing extension for module path: %s", target, name);
    }
    return toModuleName(name);
  }

  /** Convert a path to a module to it's module name as referenced in import statements. */
  static String toModuleName(String name) {
    int ext = name.lastIndexOf('.');
    Preconditions.checkState(ext != -1);
    name = name.substring(0, ext);
    return PathFormatter.pathWithUnixSeparators(name).replace('/', '.');
  }

  static void forEachDependency(
      ActionGraphBuilder graphBuilder,
      PythonPlatform pythonPlatform,
      CxxPlatform cxxPlatform,
      Iterable<?> roots,
      Consumer<PythonPackagable> packagableConsumer,
      Consumer<CxxPythonExtension> extensionConsumer,
      Consumer<NativeLinkableGroup> nativeLinkableConsumer,
      Consumer<CxxResourcesProvider> cxxResourcesProviderConsumer) {

    // Walk all our transitive deps to build our complete package that we'll
    // turn into an executable.
    new AbstractBreadthFirstTraversal<Object>(roots) {
      private final ImmutableList<BuildRule> empty = ImmutableList.of();

      @Override
      public Iterable<?> visit(Object node) {
        Iterable<?> deps = empty;

        if (node instanceof CxxPythonExtension) {
          CxxPythonExtension extension = (CxxPythonExtension) node;
          extensionConsumer.accept(extension);
          List<BuildRule> cxxpydeps = new ArrayList<>();
          for (BuildRule dep :
              extension.getPythonPackageDeps(pythonPlatform, cxxPlatform, graphBuilder)) {
            if (dep instanceof PythonPackagable) {
              cxxpydeps.add(dep);
            }
            if (dep instanceof CxxResourcesProvider) {
              cxxResourcesProviderConsumer.accept((CxxResourcesProvider) dep);
            }
          }
          deps = cxxpydeps;
        } else if (node instanceof PythonPackagable) {
          PythonPackagable packagable = (PythonPackagable) node;
          packagableConsumer.accept(packagable);
          deps = packagable.getPythonPackageDeps(pythonPlatform, cxxPlatform, graphBuilder);
        } else if (node instanceof NativeLinkableGroup) {
          nativeLinkableConsumer.accept((NativeLinkableGroup) node);
          if (node instanceof CxxResourcesProvider) {
            cxxResourcesProviderConsumer.accept((CxxResourcesProvider) node);
          }
        }

        return deps;
      }
    }.start();
  }

  static PythonPackageComponents getAllComponents(
      CellPathResolver cellPathResolver,
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      ActionGraphBuilder graphBuilder,
      PythonPackagable binary,
      PythonPlatform pythonPlatform,
      CxxBuckConfig cxxBuckConfig,
      DownwardApiConfig downwardApiConfig,
      CxxPlatform cxxPlatform,
      ImmutableList<? extends Arg> extraLdflags,
      NativeLinkStrategy nativeLinkStrategy,
      ImmutableSet<BuildTarget> preloadDeps,
      boolean compile,
      boolean preferStrippedNativeObjects,
      Optional<Boolean> deduplicateOmnibusRoots,
      Optional<BuildTarget> dummyOmnibus) {

    PythonPackageComponents.Builder allComponents = new PythonPackageComponents.Builder();

    Map<BuildTarget, CxxPythonExtension> extensions = new LinkedHashMap<>();
    Map<BuildTarget, NativeLinkable> nativeLinkableRoots = new LinkedHashMap<>();
    Set<CxxResourcesProvider> cxxResourceProviders = new LinkedHashSet<>();

    OmnibusRoots.Builder omnibusRoots = OmnibusRoots.builder(preloadDeps, graphBuilder);

    // Walk all our transitive deps to build our complete package that we'll
    // turn into an executable.
    forEachDependency(
        graphBuilder,
        pythonPlatform,
        cxxPlatform,
        Iterables.concat(ImmutableList.of(binary), graphBuilder.getAllRules(preloadDeps)),
        (PythonPackagable packagable) -> {
          packagable
              .getPythonModules(pythonPlatform, cxxPlatform, graphBuilder)
              .ifPresent(modules -> allComponents.putModules(packagable.getBuildTarget(), modules));
          if (compile) {
            packagable
                .getPythonBytecode(pythonPlatform, cxxPlatform, graphBuilder)
                .ifPresent(
                    bytecode -> allComponents.putModules(packagable.getBuildTarget(), bytecode));
          }
          packagable
              .getPythonResources(pythonPlatform, cxxPlatform, graphBuilder)
              .ifPresent(
                  resources -> allComponents.putResources(packagable.getBuildTarget(), resources));
          allComponents.addZipSafe(packagable.isPythonZipSafe());
          if (nativeLinkStrategy == NativeLinkStrategy.MERGED
              && packagable.doesPythonPackageDisallowOmnibus(
                  pythonPlatform, cxxPlatform, graphBuilder)) {
            Iterable<BuildRule> packagableDeps =
                packagable.getPythonPackageDeps(pythonPlatform, cxxPlatform, graphBuilder);
            for (BuildRule dep : packagableDeps) {
              if (dep instanceof NativeLinkableGroup) {
                NativeLinkable linkable =
                    ((NativeLinkableGroup) dep).getNativeLinkable(cxxPlatform, graphBuilder);
                nativeLinkableRoots.put(linkable.getBuildTarget(), linkable);
                omnibusRoots.addExcludedRoot(linkable);
              }
            }
          }
        },
        extension -> {
          NativeLinkTarget target =
              extension.getNativeLinkTarget(
                  pythonPlatform, cxxPlatform, graphBuilder, false, preferStrippedNativeObjects);
          extensions.put(target.getBuildTarget(), extension);
          omnibusRoots.addIncludedRoot(target);
        },
        linkableGroup -> {
          NativeLinkable linkable = linkableGroup.getNativeLinkable(cxxPlatform, graphBuilder);
          nativeLinkableRoots.put(linkable.getBuildTarget(), linkable);
          omnibusRoots.addPotentialRoot(linkable, false, preferStrippedNativeObjects);
        },
        cxxResourceProviders::add);

    // Add C++ resources.
    Path cxxResourceBase = projectFilesystem.getPath("__cxx_resources__");
    ImmutableMap<Path, SourcePath> cxxResources =
        MoreMaps.transformKeys(
            CxxResourceUtils.gatherResources(graphBuilder, ImmutableMap.of(), cxxResourceProviders),
            name -> cxxResourceBase.resolve(name.getNameAsPath().toString()));
    if (!cxxResources.isEmpty()) {
      allComponents.putResources(
          buildTarget, PythonMappedComponents.of(ImmutableSortedMap.copyOf(cxxResources)));
    }

    // For the merged strategy, build up the lists of included native linkable roots, and the
    // excluded native linkable roots.
    if (nativeLinkStrategy == NativeLinkStrategy.MERGED) {
      OmnibusRoots roots = omnibusRoots.build();
      Omnibus.OmnibusLibraries libraries =
          Omnibus.getSharedLibraries(
              buildTarget,
              projectFilesystem,
              params,
              cellPathResolver,
              graphBuilder,
              cxxBuckConfig,
              downwardApiConfig,
              cxxPlatform,
              extraLdflags,
              roots.getIncludedRoots().values(),
              roots.getExcludedRoots().values(),
              preferStrippedNativeObjects,
              deduplicateOmnibusRoots,
              Optionals.firstOf(
                  dummyOmnibus,
                  cxxBuckConfig.getDummyOmnibusTarget(buildTarget.getTargetConfiguration())));

      // Add all the roots from the omnibus link.  If it's an extension, add it as a module.
      // Otherwise, add it as a native library.
      for (Map.Entry<BuildTarget, Omnibus.OmnibusRoot> root : libraries.getRoots().entrySet()) {
        CxxPythonExtension extension = extensions.get(root.getKey());
        if (extension != null) {
          allComponents.putModules(
              root.getKey(),
              PythonMappedComponents.of(
                  ImmutableSortedMap.of(
                      extension.getModule(cxxPlatform), root.getValue().getPath())));
        } else {
          NativeLinkTarget target =
              Preconditions.checkNotNull(
                  roots.getIncludedRoots().get(root.getKey()),
                  "%s: linked unexpected omnibus root: %s",
                  buildTarget,
                  root.getKey());
          NativeLinkTargetMode mode = target.getNativeLinkTargetMode();
          String soname =
              Preconditions.checkNotNull(
                  mode.getLibraryName().orElse(null),
                  "%s: omnibus library for %s was built without soname",
                  buildTarget,
                  root.getKey());
          allComponents.putNativeLibraries(
              root.getKey(),
              PythonMappedComponents.of(
                  ImmutableSortedMap.of(Paths.get(soname), root.getValue().getPath())));
        }
      }

      // Add all remaining libraries as native libraries.
      if (!libraries.getLibraries().isEmpty()) {
        libraries.getLibraries().stream()
            .forEach(
                lib ->
                    allComponents.putNativeLibraries(
                        buildTarget,
                        PythonMappedComponents.of(
                            ImmutableSortedMap.of(Paths.get(lib.getSoname()), lib.getPath()))));
      }
    } else {

      // For regular linking, add all extensions via the package components interface.
      Map<BuildTarget, NativeLinkable> extensionNativeDeps = new LinkedHashMap<>();
      for (Map.Entry<BuildTarget, CxxPythonExtension> entry : extensions.entrySet()) {
        entry
            .getValue()
            .getPythonModules(pythonPlatform, cxxPlatform, graphBuilder)
            .ifPresent(
                modules -> allComponents.putModules(entry.getValue().getBuildTarget(), modules));
        entry
            .getValue()
            .getPythonResources(pythonPlatform, cxxPlatform, graphBuilder)
            .ifPresent(
                resources ->
                    allComponents.putResources(entry.getValue().getBuildTarget(), resources));
        allComponents.addZipSafe(entry.getValue().isPythonZipSafe());

        extensionNativeDeps.putAll(
            Maps.uniqueIndex(
                entry
                    .getValue()
                    .getNativeLinkTarget(
                        pythonPlatform,
                        cxxPlatform,
                        graphBuilder,
                        false,
                        preferStrippedNativeObjects)
                    .getNativeLinkTargetDeps(graphBuilder),
                NativeLinkable::getBuildTarget));
      }

      // Add all the native libraries.
      ImmutableList<? extends NativeLinkable> nativeLinkables =
          NativeLinkables.getTransitiveNativeLinkables(
              graphBuilder,
              Iterables.concat(nativeLinkableRoots.values(), extensionNativeDeps.values()));
      graphBuilder
          .getParallelizer()
          .maybeParallelizeTransform(
              nativeLinkables.stream()
                  .filter(
                      nativeLinkable -> {
                        NativeLinkableGroup.Linkage linkage = nativeLinkable.getPreferredLinkage();
                        return nativeLinkableRoots.containsKey(nativeLinkable.getBuildTarget())
                            || linkage != NativeLinkableGroup.Linkage.STATIC;
                      })
                  .collect(Collectors.toList()),
              linkable ->
                  new AbstractMap.SimpleEntry<BuildTarget, PythonComponents>(
                      linkable.getBuildTarget(),
                      PythonMappedComponents.of(
                          ImmutableSortedMap.copyOf(
                              MoreMaps.transformKeys(
                                  linkable.getSharedLibraries(graphBuilder), Paths::get)))))
          .forEach(e -> allComponents.putNativeLibraries(e.getKey(), e.getValue()));
    }

    return allComponents.build();
  }

  public static Path getBasePath(BuildTarget target, Optional<String> override) {
    return override.isPresent()
        ? Paths.get(override.get().replace('.', '/'))
        : target.getCellRelativeBasePath().getPath().toPathDefaultFileSystem();
  }

  static ImmutableSet<String> getPreloadNames(
      ActionGraphBuilder graphBuilder, CxxPlatform cxxPlatform, Iterable<BuildTarget> preloadDeps) {
    ImmutableSet.Builder<String> builder = ImmutableSet.builder();
    for (NativeLinkableGroup nativeLinkableGroup :
        FluentIterable.from(preloadDeps)
            .transform(graphBuilder::getRule)
            .filter(NativeLinkableGroup.class)) {
      builder.addAll(
          nativeLinkableGroup
              .getNativeLinkable(cxxPlatform, graphBuilder)
              .getSharedLibraries(graphBuilder)
              .keySet());
    }
    return builder.build();
  }
}
