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

package com.facebook.buck.cxx.toolchain.linker.impl;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.DefaultFieldInputs;
import com.facebook.buck.core.rulekey.DefaultFieldSerialization;
import com.facebook.buck.core.rulekey.ExcludeFromRuleKey;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.impl.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.toolchain.tool.DelegatingTool;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.cxx.toolchain.linker.HasIncrementalThinLTO;
import com.facebook.buck.cxx.toolchain.linker.HasLTO;
import com.facebook.buck.cxx.toolchain.linker.HasLinkerMap;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.objectfile.OsoSymbolsContentsScrubber;
import com.facebook.buck.io.file.FileScrubber;
import com.facebook.buck.io.filesystem.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.isolatedsteps.common.WriteFileIsolatedStep;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * A specialization of {@link Linker} containing information specific to the Darwin implementation.
 */
public class DarwinLinker extends DelegatingTool
    implements Linker, HasLinkerMap, HasIncrementalThinLTO, HasLTO {

  @ExcludeFromRuleKey(
      reason =
          "We do want the get cache hits with normalised paths even when we would not build normalised paths locally.",
      serialization = DefaultFieldSerialization.class,
      inputs = DefaultFieldInputs.class)
  private final boolean shouldCreateHermeticLinkOutput;

  @ExcludeFromRuleKey(
      reason = "So that we can read from the remote cache and not write to it.",
      serialization = DefaultFieldSerialization.class,
      inputs = DefaultFieldInputs.class)
  private final boolean shouldUploadToCache;

  @ExcludeFromRuleKey(
      reason =
          "We want to get cache hits on binaries with no focused targets regardless whether we're using focused debugging",
      serialization = DefaultFieldSerialization.class,
      inputs = DefaultFieldInputs.class)
  private final boolean useFocusedDebugging;

  @AddToRuleKey private final boolean usePathNormalizationArgs;

  public DarwinLinker(
      Tool tool,
      boolean shouldCacheLinks,
      boolean shouldUploadToCache,
      boolean useFocusedDebugging,
      boolean usePathNormalizationArgs) {
    super(tool);
    this.shouldCreateHermeticLinkOutput = shouldCacheLinks;
    this.shouldUploadToCache = shouldUploadToCache;
    this.useFocusedDebugging = useFocusedDebugging;
    this.usePathNormalizationArgs = usePathNormalizationArgs;
  }

  @Override
  public ImmutableList<FileScrubber> getScrubbers(
      ImmutableMap<Path, Path> cellRootMap,
      Optional<ImmutableMultimap<String, AbsPath>> targetToOutputPathMap,
      Optional<AbsPath> focusedTargetsPath) {
    if (shouldCreateHermeticLinkOutput) {
      if (shouldUploadToCache || !useFocusedDebugging) {
        // If we aren't using focused debugging, scrub all absolute OSO paths into relative paths.
        if (usePathNormalizationArgs) {
          // Path normalization would happen via pathNormalizationArgs()
          return ImmutableList.of();
        }
        return ImmutableList.of(new OsoSymbolsContentsScrubber(cellRootMap));
      } else {
        // If we're using focused debugging and  don't plan to upload to remote cache, e.g. during
        // local builds,
        // scrub the output based on given focused targets. Since we don't plan to upload link
        // outputs to remote
        // cache, it's ok to leave absolute paths in the outputs.
        return ImmutableList.of(
            new OsoSymbolsContentsScrubber(focusedTargetsPath, targetToOutputPathMap));
      }
    } else {
      // If we aren't uploading linked output to remote cache, scrub the linked binaries based
      // on the focused targets info available.
      if (focusedTargetsPath.isPresent()) {
        return ImmutableList.of(
            new OsoSymbolsContentsScrubber(focusedTargetsPath, targetToOutputPathMap));
      } else {
        return ImmutableList.of();
      }
    }
  }

  @Override
  public Iterable<Arg> pathNormalizationArgs(ImmutableMap<Path, Path> cellRootMap) {
    if (shouldCreateHermeticLinkOutput && usePathNormalizationArgs && !useFocusedDebugging) {
      Optional<String> maybeOsoPrefix =
          OsoSymbolsContentsScrubber.computeOsoPrefixForCellRootMap(cellRootMap);
      return maybeOsoPrefix
          .map(
              osoPrefix ->
                  ImmutableList.<Arg>of(
                      StringArg.of("-Xlinker"), StringArg.of("-oso_prefix"),
                      StringArg.of("-Xlinker"), StringArg.of(osoPrefix)))
          .orElse(ImmutableList.of());
    }

    return ImmutableList.of();
  }

  @Override
  public Optional<String> pathNormalizationPrefix(ImmutableMap<Path, Path> cellRootMap) {
    if (shouldCreateHermeticLinkOutput && usePathNormalizationArgs && !useFocusedDebugging) {
      return OsoSymbolsContentsScrubber.computeOsoPrefixForCellRootMap(cellRootMap);
    }
    return Optional.empty();
  }

  @Override
  public Iterable<Arg> linkWhole(Arg input, SourcePathResolverAdapter resolver) {
    return ImmutableList.of(
        StringArg.of("-Xlinker"), StringArg.of("-force_load"), StringArg.of("-Xlinker"), input);
  }

  @Override
  public Iterable<Arg> asLibrary(Iterable<Arg> objects) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Iterable<Arg> linkerMap(Path output) {
    // Build up the arguments to pass to the linker.
    return StringArg.from("-Xlinker", "-map", "-Xlinker", linkerMapPath(output).toString());
  }

  @Override
  public Path linkerMapPath(Path output) {
    return Paths.get(output + "-LinkMap.txt");
  }

  @Override
  public Iterable<Arg> thinLTO(Path output) {
    return StringArg.from(
        "-flto=thin", "-Xlinker", "-object_path_lto", "-Xlinker", ltoPath(output).toString());
  }

  @Override
  public Iterable<Arg> incrementalThinLTOFlags(Path output) {
    return StringArg.from(
        "-Wl,-thinlto_emit_indexes",
        "-Wl,-thinlto_emit_imports",
        "-Xlinker",
        "-thinlto_new_prefix",
        "-Xlinker",
        output.toString());
  }

  @Override
  public Iterable<Arg> fatLTO(Path output) {
    // For fat LTO, the object path should be a file.
    return StringArg.from(
        "-flto", "-Xlinker", "-object_path_lto", "-Xlinker", ltoPath(output).toString());
  }

  @Override
  public Path ltoPath(Path output) {
    return Paths.get(output + "-lto");
  }

  @Override
  public Iterable<String> soname(String arg) {
    return Linkers.iXlinker("-install_name", "@rpath/" + arg);
  }

  @Override
  public Iterable<Arg> fileList(Path fileListPath) {
    return ImmutableList.of(
        StringArg.of("-Xlinker"),
        StringArg.of("-filelist"),
        StringArg.of("-Xlinker"),
        StringArg.of(fileListPath.toString()));
  }

  @Override
  public String origin() {
    return "@executable_path";
  }

  @Override
  public String libOrigin() {
    return "@loader_path";
  }

  @Override
  public String searchPathEnvVar() {
    return "DYLD_LIBRARY_PATH";
  }

  @Override
  public String preloadEnvVar() {
    return "DYLD_INSERT_LIBRARIES";
  }

  @Override
  public ImmutableList<Arg> createUndefinedSymbolsLinkerArgs(
      ProjectFilesystem projectFilesystem,
      BuildRuleParams baseParams,
      ActionGraphBuilder graphBuilder,
      BuildTarget target,
      ImmutableList<? extends SourcePath> symbolFiles) {
    return ImmutableList.of(new UndefinedSymbolsArg(symbolFiles));
  }

  @Override
  public ImmutableList<Arg> createGlobalSymbolsLinkerArgs(
      ProjectFilesystem projectFilesystem,
      BuildRuleParams baseParams,
      ActionGraphBuilder graphBuilder,
      BuildTarget target,
      ImmutableList<? extends SourcePath> symbolFiles,
      ImmutableList<String> extraGlobals) {
    ExportedSymbolsList rule =
        graphBuilder.addToIndex(
            new ExportedSymbolsList(
                target,
                projectFilesystem,
                baseParams
                    .withDeclaredDeps(
                        ImmutableSortedSet.copyOf(graphBuilder.filterBuildRuleInputs(symbolFiles)))
                    .withoutExtraDeps(),
                symbolFiles,
                extraGlobals));
    return ImmutableList.of(
        StringArg.of("-Xlinker"),
        StringArg.of("-exported_symbols_list"),
        StringArg.of("-Xlinker"),
        SourcePathArg.of(rule.getSourcePathToOutput()));
  }

  @Override
  public String getExportDynamicSymbolFlag() {
    return "-exported_symbol";
  }

  @Override
  public Iterable<String> getNoAsNeededSharedLibsFlags() {
    return ImmutableList.of();
  }

  @Override
  public Iterable<String> getIgnoreUndefinedSymbolsFlags() {
    return Linkers.iXlinker("-flat_namespace", "-undefined", "suppress");
  }

  @Override
  public Iterable<Arg> getSharedLibFlag() {
    return ImmutableList.of(StringArg.of("-shared"));
  }

  @Override
  public Iterable<String> outputArgs(String path) {
    return ImmutableList.of("-o", path);
  }

  @Override
  public SharedLibraryLoadingType getSharedLibraryLoadingType() {
    return SharedLibraryLoadingType.RPATH;
  }

  @Override
  public Optional<ExtraOutputsDeriver> getExtraOutputsDeriver() {
    return Optional.empty();
  }

  @Override
  public boolean getUseUnixPathSeparator() {
    return true;
  }

  /**
   * An {@link Arg} which reads undefined symbols from files and propagates them to the Darwin
   * linker via the `-u` argument.
   *
   * <p>NOTE: this is prone to overrunning command line argument limits, but it's not clear of
   * another way to do this (perhaps other than creating a dummy object file whose symbol table only
   * contains the undefined symbols listed in the symbol files).
   */
  private static class UndefinedSymbolsArg implements Arg {
    @AddToRuleKey private final ImmutableList<? extends SourcePath> symbolFiles;

    public UndefinedSymbolsArg(ImmutableList<? extends SourcePath> symbolFiles) {
      this.symbolFiles = symbolFiles;
    }

    // Open all the symbol files and read in all undefined symbols, passing them to linker using the
    // `-u` command line option.
    @Override
    public void appendToCommandLine(
        Consumer<String> consumer, SourcePathResolverAdapter pathResolver) {
      Set<String> symbols = new LinkedHashSet<>();
      try {
        for (SourcePath path : symbolFiles) {
          symbols.addAll(
              Files.readAllLines(
                  pathResolver.getAbsolutePath(path).getPath(), StandardCharsets.UTF_8));
        }
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
      for (String symbol : symbols) {
        Linkers.iXlinker("-u", symbol).forEach(consumer);
      }
    }

    @Override
    public String toString() {
      return "symbols(" + Joiner.on(',').join(symbolFiles) + ")";
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }
      if (!(other instanceof UndefinedSymbolsArg)) {
        return false;
      }
      UndefinedSymbolsArg symbolsArg = (UndefinedSymbolsArg) other;
      return Objects.equals(symbolFiles, symbolsArg.symbolFiles);
    }

    @Override
    public int hashCode() {
      return Objects.hash(symbolFiles);
    }
  }

  /**
   * * Write all symbols to a exported symbols list.
   *
   * <p>NOTE: `-exported_symbols_list` fails with an undefined symbol error when it is passed a
   * symbol that does not exist. Using a wildcard prefix * fixes this. But, it may over-export some
   * symbols, especially with C linkage.
   */
  private static class ExportedSymbolsList extends AbstractBuildRuleWithDeclaredAndExtraDeps {

    @AddToRuleKey private final Iterable<? extends SourcePath> symbolFiles;
    @AddToRuleKey private final ImmutableList<String> extraGlobals;

    public ExportedSymbolsList(
        BuildTarget buildTarget,
        ProjectFilesystem projectFilesystem,
        BuildRuleParams buildRuleParams,
        Iterable<? extends SourcePath> symbolFiles,
        ImmutableList<String> extraGlobals) {
      super(buildTarget, projectFilesystem, buildRuleParams);
      this.symbolFiles = symbolFiles;
      this.extraGlobals = extraGlobals;
    }

    private RelPath getExportedSymbolsList() {
      return BuildTargetPaths.getGenPath(
          getProjectFilesystem().getBuckPaths(), getBuildTarget(), "%s/exported_symbols_list.txt");
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext context, BuildableContext buildableContext) {
      RelPath linkerScript = getExportedSymbolsList();
      buildableContext.recordArtifact(linkerScript.getPath());
      return ImmutableList.of(
          MkdirStep.of(
              BuildCellRelativePath.fromCellRelativePath(
                  context.getBuildCellRootPath(),
                  getProjectFilesystem(),
                  linkerScript.getParent())),
          WriteFileIsolatedStep.of(
              () -> {
                Set<String> symbols = new LinkedHashSet<>();
                for (SourcePath path : symbolFiles) {
                  try {
                    symbols.addAll(
                        Files.readAllLines(
                            context.getSourcePathResolver().getAbsolutePath(path).getPath(),
                            StandardCharsets.UTF_8));
                  } catch (IOException e) {
                    throw new RuntimeException(e);
                  }
                }
                symbols.addAll(extraGlobals);
                List<String> lines = new ArrayList<>();
                for (String symbol : symbols) {
                  lines.add(String.format("*%s", symbol));
                }
                return Joiner.on(System.lineSeparator()).join(lines);
              },
              linkerScript.getPath(),
              /* executable */ false));
    }

    @Override
    public SourcePath getSourcePathToOutput() {
      return ExplicitBuildTargetSourcePath.of(getBuildTarget(), getExportedSymbolsList());
    }
  }
}
