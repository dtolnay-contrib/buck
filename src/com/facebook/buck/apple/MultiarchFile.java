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

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.OutputLabel;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.impl.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.rules.tool.BinaryBuildRule;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.tool.impl.CommandTool;
import com.facebook.buck.cxx.CxxBinary;
import com.facebook.buck.cxx.CxxLink;
import com.facebook.buck.cxx.HasAppleDebugSymbolDeps;
import com.facebook.buck.io.filesystem.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.shell.DefaultShellStep;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.util.stream.RichStream;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.stream.Stream;

/** Puts together multiple thin library/binaries into a multi-arch file. */
public class MultiarchFile extends AbstractBuildRuleWithDeclaredAndExtraDeps
    implements HasAppleDebugSymbolDeps, BinaryBuildRule {

  private final SourcePathRuleFinder ruleFinder;
  @AddToRuleKey private final Tool lipo;

  @AddToRuleKey private final ImmutableSortedSet<SourcePath> thinBinaries;

  private final boolean isCacheable;

  @AddToRuleKey(stringify = true)
  private final Path output;

  @AddToRuleKey private final boolean withDownwardApi;

  public MultiarchFile(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams buildRuleParams,
      SourcePathRuleFinder ruleFinder,
      Tool lipo,
      ImmutableSortedSet<SourcePath> thinBinaries,
      boolean isCacheable,
      Path output,
      boolean withDownwardApi) {
    super(buildTarget, projectFilesystem, buildRuleParams);
    this.ruleFinder = ruleFinder;
    this.lipo = lipo;
    this.thinBinaries = ImmutableSortedSet.copyOf(thinBinaries);
    this.isCacheable = isCacheable;
    this.output = output;
    this.withDownwardApi = withDownwardApi;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    buildableContext.recordArtifact(output);

    ImmutableList.Builder<Step> steps = ImmutableList.builder();
    steps.add(
        MkdirStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), output.getParent())));

    lipoBinaries(context, steps);
    copyLinkMaps(buildableContext, context, steps);

    return steps.build();
  }

  private void copyLinkMaps(
      BuildableContext buildableContext,
      BuildContext buildContext,
      ImmutableList.Builder<Step> steps) {
    Path linkMapDir = Paths.get(output + "-LinkMap");
    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                buildContext.getBuildCellRootPath(), getProjectFilesystem(), linkMapDir)));

    for (SourcePath thinBinary : thinBinaries) {
      Optional<BuildRule> maybeRule = ruleFinder.getRule(thinBinary);
      if (maybeRule.isPresent()) {
        BuildRule rule = maybeRule.get();
        if (rule instanceof CxxBinary) {
          rule = ((CxxBinary) rule).getLinkRule();
        }
        if (rule instanceof CxxLink) {
          CxxLink cxxLink = ((CxxLink) rule);
          if (cxxLink.isLinkerMapEnabled()) {
            Optional<Path> maybeLinkerMapPath = cxxLink.getLinkerMapPath();
            Path source = maybeLinkerMapPath.get();
            Path dest = linkMapDir.resolve(source.getFileName());
            steps.add(CopyStep.forFile(source, dest));
            buildableContext.recordArtifact(dest);
          }
        }
      }
    }
  }

  private void lipoBinaries(BuildContext context, ImmutableList.Builder<Step> steps) {
    ImmutableList.Builder<String> commandBuilder = ImmutableList.builder();
    commandBuilder.addAll(lipo.getCommandPrefix(context.getSourcePathResolver()));
    commandBuilder.add("-create", "-output", getProjectFilesystem().resolve(output).toString());
    for (SourcePath thinBinary : thinBinaries) {
      commandBuilder.add(context.getSourcePathResolver().getAbsolutePath(thinBinary).toString());
    }
    steps.add(
        new DefaultShellStep(
            getProjectFilesystem().getRootPath(),
            withDownwardApi,
            commandBuilder.build(),
            lipo.getEnvironment(context.getSourcePathResolver())));
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), output);
  }

  @Override
  public Stream<BuildRule> getAppleDebugSymbolDeps() {
    return RichStream.from(getBuildDeps())
        .filter(HasAppleDebugSymbolDeps.class)
        .flatMap(HasAppleDebugSymbolDeps::getAppleDebugSymbolDeps)
        // Include the build deps themselves, which are the rules generating the thin binary.
        // These rules may generate supplemental object files that are linked into the binary, and
        // must be materialized in order for dsymutil to find them.
        .concat(getBuildDeps().stream());
  }

  @Override
  public Optional<String> getPathNormalizationPrefix() {
    ImmutableSet<String> prefixes =
        RichStream.from(getBuildDeps())
            .filter(HasAppleDebugSymbolDeps.class)
            .map(HasAppleDebugSymbolDeps::getPathNormalizationPrefix)
            .filter(Optional::isPresent)
            .map(Optional::get)
            .collect(ImmutableSet.toImmutableSet());

    if (!prefixes.isEmpty()) {
      if (prefixes.size() > 1) {
        throw new HumanReadableException(
            "Combining multiarch ('universal') files which have different OSO prefix, this case is impossible to hit");
      }
      return Optional.of(prefixes.iterator().next());
    }

    return Optional.empty();
  }

  @Override
  public boolean isCacheable() {
    return isCacheable;
  }

  @Override
  public Tool getExecutableCommand(OutputLabel outputLabel) {
    return new CommandTool.Builder().addArg(SourcePathArg.of(getSourcePathToOutput())).build();
  }
}
