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

package com.facebook.buck.rules.modern;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.filesystems.PathWrapper;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.attr.SupportsInputBasedRuleKey;
import com.facebook.buck.core.rules.impl.AbstractBuildRule;
import com.facebook.buck.core.sourcepath.BuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.file.MostFiles;
import com.facebook.buck.io.filesystem.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.modern.impl.DefaultClassInfoFactory;
import com.facebook.buck.rules.modern.impl.DefaultInputRuleResolver;
import com.facebook.buck.rules.modern.impl.DepsComputingVisitor;
import com.facebook.buck.rules.modern.impl.OutputPathVisitor;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.RmStep;
import com.facebook.buck.util.MoreSuppliers;
import com.facebook.buck.util.types.Either;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/**
 * ModernBuildRule wraps a Buildable into something that implements BuildRule (and various other
 * interfaces used by the build engine). Most of the overridden methods from
 * BuildRule/AbstractBuildRule are intentionally final to keep users on the safe path.
 *
 * <p>Deps, outputs, inputs and rulekeys are derived from the fields of the {@link Buildable}.
 *
 * <p>For simple ModernBuildRules (e.g. those that don't have any fields that can't be added to the
 * rulekey), the build rule class itself can (and should) implement Buildable. In this case, the
 * constructor taking a {@code Class<T>} should be used.
 *
 * <p>Example:
 *
 * <pre>{@code
 * class WriteData extends ModernBuildRule<WriteData> implements Buildable {
 *   final String data;
 *   public WriteData(
 *       SourcePathRuleFinder ruleFinder,
 *       BuildTarget buildTarget,
 *       ProjectFilesystem projectFilesystem,
 *       String data) {
 *     super(buildTarget, projectFilesystem, ruleFinder, WriteData.class);
 *     this.data = data;
 *   }
 *
 *   ...
 * }
 * }</pre>
 *
 * <p>Some BuildRules contain more information than just that added to the rulekey and used for
 * getBuildSteps(). For these rules, the part used for getBuildSteps should be split out into its
 * own implementation of Buildable.
 *
 * <p>Example:
 *
 * <pre>{@code
 * class CopyData extends ModernBuildRule<CopyData.Impl> implements Buildable {
 *   BuildRule other;
 *   public WriteData(
 *       SourcePathRuleFinder ruleFinder,
 *       BuildTarget buildTarget,
 *       ProjectFilesystem projectFilesystem,
 *       BuildRule other) {
 *     super(buildTarget, projectFilesystem, ruleFinder, new Impl("hello"));
 *     this.other = other;
 *   }
 *
 *   private static class Impl implements Buildable {
 *     ...
 *   }
 *   ...
 * }
 * }</pre>
 */
public class ModernBuildRule<T extends Buildable> extends AbstractBuildRule
    implements SupportsInputBasedRuleKey {

  private OutputPathResolver outputPathResolver;
  private Supplier<ImmutableSortedSet<BuildRule>> deps;
  private T buildable;

  // For cases where the ModernBuildRule is itself the Buildable, we don't want to add it to keys
  // here.
  @AddToRuleKey private T buildableForRuleKey;

  private ClassInfo<T> classInfo;
  private InputRuleResolver inputRuleResolver;

  protected ModernBuildRule(
      BuildTarget buildTarget,
      ProjectFilesystem filesystem,
      SourcePathRuleFinder finder,
      Class<T> clazz) {
    this(buildTarget, filesystem, Either.ofRight(clazz), finder);
  }

  protected ModernBuildRule(
      BuildTarget buildTarget,
      ProjectFilesystem filesystem,
      SourcePathRuleFinder ruleFinder,
      T buildable) {
    this(buildTarget, filesystem, Either.ofLeft(buildable), ruleFinder);
  }

  private ModernBuildRule(
      BuildTarget buildTarget,
      ProjectFilesystem filesystem,
      Either<T, Class<T>> buildableSource,
      SourcePathRuleFinder ruleFinder) {
    super(buildTarget, filesystem);
    initialize(this, buildableSource, ruleFinder, filesystem, buildTarget);
    Objects.requireNonNull(deps);
    Objects.requireNonNull(inputRuleResolver);
    Objects.requireNonNull(outputPathResolver);
    Objects.requireNonNull(buildable);
    Objects.requireNonNull(classInfo);
  }

  private static <T extends Buildable> void initialize(
      ModernBuildRule<T> rule,
      Either<T, Class<T>> buildableSource,
      SourcePathRuleFinder ruleFinder,
      ProjectFilesystem filesystem,
      BuildTarget buildTarget) {
    rule.deps = MoreSuppliers.memoize(rule::computeDeps);
    rule.inputRuleResolver = new DefaultInputRuleResolver(ruleFinder);
    rule.outputPathResolver = new DefaultOutputPathResolver(filesystem, buildTarget);
    T buildable = buildableSource.transform(b -> b, clz -> clz.cast(rule));
    rule.buildable = buildable;
    rule.buildableForRuleKey = rule == buildable ? null : buildable;
    rule.classInfo = DefaultClassInfoFactory.forInstance(buildable);
  }

  private static <T extends Buildable> void injectFields(
      ModernBuildRule<T> rule,
      ProjectFilesystem filesystem,
      BuildTarget target,
      SourcePathRuleFinder ruleFinder) {
    Preconditions.checkState(rule instanceof Buildable);
    AbstractBuildRule.injectFields(rule, filesystem, target);
    @SuppressWarnings("unchecked")
    Either<T, Class<T>> buildableSource = Either.ofRight((Class<T>) rule.getClass());
    initialize(rule, buildableSource, ruleFinder, filesystem, target);
  }

  /** Allows injecting the AbstractBuildRule fields into a Buildable after construction. */
  public static void injectFieldsIfNecessary(
      ProjectFilesystem filesystem,
      BuildTarget target,
      Buildable buildable,
      SourcePathRuleFinder ruleFinder) {
    if (buildable instanceof ModernBuildRule) {
      ModernBuildRule<?> rule = (ModernBuildRule<?>) buildable;
      injectFields(rule, filesystem, target, ruleFinder);
    }
  }

  private ImmutableSortedSet<BuildRule> computeDeps() {
    ImmutableSortedSet.Builder<BuildRule> depsBuilder = ImmutableSortedSet.naturalOrder();
    classInfo.visit(buildable, new DepsComputingVisitor(inputRuleResolver, depsBuilder::add));
    return depsBuilder.build();
  }

  public final T getBuildable() {
    return buildable;
  }

  /**
   * As part of the setup for MBRs, the root output dir and scratch dir are removed and recreated.
   * If an MBR needs to work incrementally, then this method can be overridden. NOTE: This only
   * controls the behavior of the outputs _excluding_ the scratch dir.
   */
  public ImmutableList<OutputPath> getExcludedOutputPathsFromAutomaticSetup() {
    return ImmutableList.of();
  }

  @Nullable
  @Override
  public SourcePath getSourcePathToOutput() {
    return null;
  }

  /**
   * This field could be used unsafely, most ModernBuildRule should never need this directly and it
   * should only be used within the getBuildSteps() call.
   */
  public OutputPathResolver getOutputPathResolver() {
    return outputPathResolver;
  }

  @Override
  public void updateBuildRuleResolver(BuildRuleResolver ruleResolver) {
    this.inputRuleResolver = new DefaultInputRuleResolver(ruleResolver);
  }

  // -----------------------------------------------------------------------------------------------
  // ---------- These function's behaviors can be changed with interfaces on the Buildable ---------
  // -----------------------------------------------------------------------------------------------

  @Override
  public final boolean inputBasedRuleKeyIsEnabled() {
    // Uses instanceof to force this to be non-dynamic.
    return !(buildable instanceof HasBrokenInputBasedRuleKey);
  }

  // -----------------------------------------------------------------------------------------------
  // ---------------------- Everything below here is intentionally final ---------------------------
  // -----------------------------------------------------------------------------------------------

  /**
   * This should only be exposed to implementations of the ModernBuildRule, not of the Buildable.
   */
  protected final BuildTargetSourcePath getSourcePath(OutputPath outputPath) {
    // TODO(cjhopman): enforce that the outputPath is actually from this target somehow.
    return ExplicitBuildTargetSourcePath.of(
        getBuildTarget(), outputPathResolver.resolvePath(outputPath));
  }

  /**
   * Same as {@link #getSourcePath}, but takes multiple {@link OutputPath} instances and returns
   * multiple {@link SourcePath} instances.
   */
  protected final ImmutableSortedSet<SourcePath> getSourcePaths(
      Collection<OutputPath> outputPaths) {
    return outputPaths.stream()
        .map(this::getSourcePath)
        .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural()));
  }

  @Override
  public final ImmutableSortedSet<BuildRule> getBuildDeps() {
    return deps.get();
  }

  @Override
  public final ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    ImmutableList<Path> outputs = deriveAndRecordOutputs(buildableContext);
    return stepsForBuildable(
        context,
        buildable,
        getProjectFilesystem(),
        getBuildTarget(),
        outputs,
        getExcludedOutputPathsFromAutomaticSetup());
  }

  /**
   * Returns the build steps for the Buildable. Unlike getBuildSteps(), this does not record outputs
   * (callers should call recordOutputs() themselves).
   */
  public static <T extends Buildable> ImmutableList<Step> stepsForBuildable(
      BuildContext context,
      T buildable,
      ProjectFilesystem filesystem,
      BuildTarget buildTarget,
      Iterable<Path> outputs,
      ImmutableList<OutputPath> excludedPaths) {
    ImmutableList.Builder<Step> stepBuilder = ImmutableList.builder();
    OutputPathResolver outputPathResolver = new DefaultOutputPathResolver(filesystem, buildTarget);
    appendWithSetupStepsForBuildable(
        context, filesystem, outputs, stepBuilder, outputPathResolver, excludedPaths);

    stepBuilder.addAll(
        buildable.getBuildSteps(
            context,
            filesystem,
            outputPathResolver,
            getBuildCellPathFactory(context, filesystem, outputPathResolver)));

    // TODO(cjhopman): This should probably be handled by the build engine.
    if (context.getShouldDeleteTemporaries()) {
      stepBuilder.add(
          RmStep.of(
              BuildCellRelativePath.fromCellRelativePath(
                  context.getBuildCellRootPath(), filesystem, outputPathResolver.getTempPath()),
              true));
    }

    return stepBuilder.build();
  }

  protected static DefaultBuildCellRelativePathFactory getBuildCellPathFactory(
      BuildContext context, ProjectFilesystem filesystem, OutputPathResolver outputPathResolver) {
    return new DefaultBuildCellRelativePathFactory(
        context.getBuildCellRootPath().getPath(), filesystem, Optional.of(outputPathResolver));
  }

  /**
   * Appends {@code stepBuilder} with the steps for preparing the output directories of the build
   * rule.
   */
  public static void appendWithSetupStepsForBuildable(
      BuildContext context,
      ProjectFilesystem filesystem,
      Iterable<Path> outputs,
      Builder<Step> stepBuilder,
      OutputPathResolver outputPathResolver,
      ImmutableList<OutputPath> excludedOutputPaths) {
    RelPath tempPath = outputPathResolver.getTempPath();
    ImmutableSet<RelPath> excludedRelPaths =
        excludedOutputPaths.stream()
            .map(
                outputPath -> {
                  RelPath relPath = outputPathResolver.resolvePath(outputPath);
                  Preconditions.checkArgument(
                      !relPath.startsWith(tempPath), "Cannot exclude temp paths from cleanup");
                  return relPath;
                })
            .collect(ImmutableSet.toImmutableSet());
    ImmutableSet<Path> excludedFullPaths =
        excludedRelPaths.stream()
            .map(filesystem::resolve)
            .map(PathWrapper::getPath)
            .collect(ImmutableSet.toImmutableSet());

    // TODO(cjhopman): This should probably actually be handled by the build engine.
    for (Path output : outputs) {
      // Don't delete paths that are invalid now; leave it to the Buildable to handle this.
      if (!isValidOutputPath(filesystem, output)
          || (!excludedFullPaths.isEmpty()
              && MostFiles.shouldSkipDeletingPath(filesystem.resolve(output), excludedFullPaths))) {
        continue;
      }

      // Don't bother deleting the root path or anything under it, we're about to delete it and
      // re-create it.
      if (!output.startsWith(outputPathResolver.getRootPath().getPath())) {
        stepBuilder.add(
            RmStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    context.getBuildCellRootPath(), filesystem, output),
                true));
      }
    }

    stepBuilder.addAll(
        MakeCleanDirectoryStep.ofExcludingPaths(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), filesystem, outputPathResolver.getRootPath()),
            excludedRelPaths));
    stepBuilder.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), filesystem, tempPath)));
  }

  /**
   * Helper method for inspecting paths that returns whether or not the given output path is valid
   * in the current state of the filesystem.
   *
   * <p>ModernBuildRule emits deletion steps for output paths as necessary. While this works for
   * most ModernBuildRules, ModernBuildRules that use custom output directories (not the one that
   * MBR provides) can end up in subtle situations where their output directory isn't valid, but
   * will be made valid by the steps emitted in that MBR's Buildable.
   *
   * <p>A concrete example of this is a Genrule whose out parameter changes from build to build such
   * that what used to be a file path is now a component of a directory path. This will become valid
   * later in the build, once GenruleBuildable deletes the full path and re-creates it, but at this
   * point the Genrule's output path is an invalid path since an ancestor path component of the
   * Genrule's output is a file that currently exists.
   *
   * <p>In the case where the path is not valid, MBR must ignore it and rely on the MBR's Buildable
   * to do the right thing.
   *
   * @param filesystem The filesystem of the current build
   * @param path A candidate output path
   * @return True if the output path is valid in the current filesystem, false otherwise.
   */
  private static boolean isValidOutputPath(ProjectFilesystem filesystem, Path path) {
    Path parent = path.getParent();
    while (parent != null) {
      // Paths are definitely not valid if any component of the path refers to a file (not a
      // directory) that exists.
      if (filesystem.exists(parent) && !filesystem.isDirectory(parent)) {
        return false;
      }

      parent = parent.getParent();
    }

    return true;
  }

  /** Return the steps for a buildable. */
  public static <T extends Buildable> ImmutableList<Step> stepsForBuildable(
      BuildContext context,
      T buildable,
      ProjectFilesystem filesystem,
      BuildTarget buildTarget,
      ImmutableList<OutputPath> excludedPaths) {
    ImmutableList.Builder<Path> outputs = ImmutableList.builder();
    deriveOutputs(
        outputs::add,
        new DefaultOutputPathResolver(filesystem, buildTarget),
        DefaultClassInfoFactory.forInstance(buildable),
        buildable);
    return stepsForBuildable(
        context, buildable, filesystem, buildTarget, outputs.build(), excludedPaths);
  }

  /**
   * Derives the outputs of this Buildable. An output will only be passed into {@code consumer} only
   * once (i.e. no duplicates and if a directory is recorded, none of its contents will be).
   */
  public void deriveOutputs(Consumer<Path> consumer) {
    deriveOutputs(consumer, outputPathResolver, classInfo, buildable);
  }

  /**
   * Derives the outputs of this Buildable. An output will only be passed into {@code consumer} only
   * once (i.e. no duplicates and if a directory is consumed, none of its contents will be).
   */
  private static <T extends Buildable> void deriveOutputs(
      Consumer<Path> consumer,
      OutputPathResolver outputPathResolver,
      ClassInfo<T> classInfo,
      T buildable) {

    ImmutableSet.Builder<RelPath> outputsBuilder = ImmutableSet.builder();
    outputsBuilder.add(outputPathResolver.getRootPath());

    RelPath tempPath = outputPathResolver.getTempPath();
    classInfo.visit(
        buildable,
        new OutputPathVisitor(
            path -> {
              // Check that any PublicOutputPath is not specified inside the rule's temporary
              // directory,
              // as the temp directory may be deleted after the rule is run.
              if (path instanceof PublicOutputPath) {
                RelPath relPath = path.getPath();
                if (relPath.startsWith(tempPath)) {
                  throw new IllegalStateException(
                      String.format(
                          "PublicOutputPath %s should not be inside rule temporary directory: %s",
                          relPath, tempPath));
                }
              }
              outputsBuilder.add(outputPathResolver.resolvePath(path));
            }));

    // ImmutableSet guarantees that iteration order is unchanged.
    ImmutableSet<RelPath> outputs = outputsBuilder.build();
    for (RelPath relPath : outputs) {
      if (shouldRecord(outputs, relPath)) {
        consumer.accept(relPath.getPath());
      }
    }
  }

  /**
   * Derives the outputs of this Buildable. An output will only be passed into {@code consumer} only
   * once (i.e. no duplicates and if a directory is consumed, none of its contents will be).
   */
  public static <T extends Buildable> void deriveOutputs(
      Consumer<Path> outputsConsumer, OutputPathResolver outputPathResolver, T buildable) {
    deriveOutputs(
        outputsConsumer,
        outputPathResolver,
        DefaultClassInfoFactory.forInstance(buildable),
        buildable);
  }

  private static boolean shouldRecord(ImmutableSet<RelPath> outputs, RelPath path) {
    RelPath parent = path.getParent();
    while (parent != null) {
      if (outputs.contains(parent)) {
        return false;
      }
      parent = parent.getParent();
    }
    return true;
  }

  @Override
  public final int compareTo(BuildRule that) {
    if (this == that) {
      return 0;
    }

    return this.getBuildTarget().compareTo(that.getBuildTarget());
  }

  /**
   * Derives and records output paths this Buildable {@code this.buildable}. Returns a list of
   * output paths.
   */
  protected ImmutableList<Path> deriveAndRecordOutputs(BuildableContext buildableContext) {
    // derive outputs into a builder
    ImmutableList.Builder<Path> outputsBuilder = ImmutableList.builder();
    deriveOutputs(outputsBuilder::add);
    ImmutableList<Path> outputs = outputsBuilder.build();

    // record outputs
    outputs.forEach(buildableContext::recordArtifact);

    // return outputs
    return outputs;
  }
}
