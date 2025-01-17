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

package com.facebook.buck.jvm.java;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.impl.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.io.filesystem.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.HasClasspathEntries;
import com.facebook.buck.jvm.core.HasMavenCoordinates;
import com.facebook.buck.jvm.core.HasSources;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.isolatedsteps.java.JarDirectoryStep;
import com.facebook.buck.util.stream.RichStream;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A {@link BuildRule} used to have the provided {@link JavaLibrary} published to a maven repository
 *
 * @see #create
 */
public class MavenUberJar extends AbstractBuildRuleWithDeclaredAndExtraDeps
    implements MavenPublishable {

  private final Optional<String> mavenCoords;
  private final TraversedDeps traversedDeps;

  private MavenUberJar(
      TraversedDeps traversedDeps,
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      Optional<String> mavenCoords) {
    super(buildTarget, projectFilesystem, params);
    this.traversedDeps = traversedDeps;
    this.mavenCoords = mavenCoords;
  }

  private static BuildRuleParams adjustParams(BuildRuleParams params, TraversedDeps traversedDeps) {
    return params
        .withDeclaredDeps(ImmutableSortedSet.copyOf(Ordering.natural(), traversedDeps.packagedDeps))
        .withoutExtraDeps();
  }

  /**
   * Will traverse transitive dependencies of {@code rootRule}, separating those that do and don't
   * have maven coordinates. Those that do will be considered maven-external dependencies. They will
   * be returned by {@link #getMavenDeps} and will end up being specified as dependencies in
   * pom.xml. Others will be packaged in the same jar as if they are just a part of the one
   * published item.
   */
  public static MavenUberJar create(
      JavaLibrary rootRule,
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      Optional<String> mavenCoords) {
    TraversedDeps traversedDeps = TraversedDeps.traverse(ImmutableSet.of(rootRule));
    return new MavenUberJar(
        traversedDeps,
        buildTarget,
        projectFilesystem,
        adjustParams(params, traversedDeps),
        mavenCoords);
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    SourcePathResolverAdapter sourcePathResolver = context.getSourcePathResolver();
    ProjectFilesystem filesystem = getProjectFilesystem();
    RelPath pathToOutput = sourcePathResolver.getRelativePath(filesystem, getSourcePathToOutput());
    MkdirStep mkOutputDirStep =
        MkdirStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), filesystem, pathToOutput.getParent()));
    JarDirectoryStep mergeOutputsStep =
        new JarDirectoryStep(
            JarParameters.builder()
                .setJarPath(pathToOutput)
                .setEntriesToJar(
                    toOutputPaths(sourcePathResolver, filesystem, traversedDeps.packagedDeps))
                .setMergeManifests(true)
                .build());
    return ImmutableList.of(mkOutputDirStep, mergeOutputsStep);
  }

  private ImmutableSortedSet<RelPath> toOutputPaths(
      SourcePathResolverAdapter sourcePathResolver,
      ProjectFilesystem filesystem,
      Iterable<? extends BuildRule> rules) {
    return RichStream.from(rules)
        .map(BuildRule::getSourcePathToOutput)
        .filter(Objects::nonNull)
        .map(s -> sourcePathResolver.getRelativePath(filesystem, s))
        .collect(ImmutableSortedSet.toImmutableSortedSet(RelPath.comparator()));
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(
        getBuildTarget(),
        CompilerOutputPaths.of(getBuildTarget(), getProjectFilesystem().getBuckPaths())
            .getOutputJarPath()
            .get());
  }

  @Override
  public Optional<String> getMavenCoords() {
    return mavenCoords;
  }

  @Override
  public Iterable<HasMavenCoordinates> getMavenDeps() {
    return traversedDeps.mavenDeps;
  }

  @Override
  public Iterable<BuildRule> getPackagedDependencies() {
    return traversedDeps.packagedDeps;
  }

  public static class SourceJar extends JavaSourceJar implements MavenPublishable {

    private final TraversedDeps traversedDeps;

    public SourceJar(
        BuildTarget buildTarget,
        ProjectFilesystem projectFilesystem,
        BuildRuleParams params,
        ImmutableSortedSet<SourcePath> srcs,
        Optional<String> mavenCoords,
        TraversedDeps traversedDeps) {
      super(buildTarget, projectFilesystem, params, srcs, mavenCoords);
      this.traversedDeps = traversedDeps;
    }

    public static SourceJar create(
        BuildTarget buildTarget,
        ProjectFilesystem projectFilesystem,
        BuildRuleParams params,
        ImmutableSortedSet<SourcePath> topLevelSrcs,
        Optional<String> mavenCoords) {
      // Do not package deps by default.
      TraversedDeps traversedDeps = TraversedDeps.traverse(params.getBuildDeps(), false);

      params = adjustParams(params, traversedDeps);

      ImmutableSortedSet<SourcePath> sourcePaths =
          FluentIterable.from(traversedDeps.packagedDeps)
              .filter(HasSources.class)
              .transformAndConcat(HasSources::getSources)
              .append(topLevelSrcs)
              .toSortedSet(Ordering.natural());
      return new SourceJar(
          buildTarget, projectFilesystem, params, sourcePaths, mavenCoords, traversedDeps);
    }

    @Override
    public Iterable<HasMavenCoordinates> getMavenDeps() {
      return traversedDeps.mavenDeps;
    }

    @Override
    public Iterable<BuildRule> getPackagedDependencies() {
      return traversedDeps.packagedDeps;
    }
  }

  private static class TraversedDeps {
    public final Iterable<HasMavenCoordinates> mavenDeps;
    public final Iterable<BuildRule> packagedDeps;

    private TraversedDeps(
        Iterable<HasMavenCoordinates> mavenDeps, Iterable<BuildRule> packagedDeps) {
      this.mavenDeps = mavenDeps;
      this.packagedDeps = packagedDeps;
    }

    private static TraversedDeps traverse(Set<? extends BuildRule> roots) {
      return traverse(roots, true);
    }

    private static TraversedDeps traverse(
        Set<? extends BuildRule> roots, boolean alwaysPackageRoots) {
      ImmutableSortedSet.Builder<HasMavenCoordinates> depsCollector =
          ImmutableSortedSet.naturalOrder();

      ImmutableSortedSet.Builder<JavaLibrary> candidates = ImmutableSortedSet.naturalOrder();
      for (BuildRule root : roots) {
        Preconditions.checkState(root instanceof HasClasspathEntries);
        candidates.addAll(
            ((HasClasspathEntries) root)
                .getTransitiveClasspathDeps().stream()
                    .filter(buildRule -> !(alwaysPackageRoots && root.equals(buildRule)))
                    .iterator());
      }
      ImmutableSortedSet.Builder<JavaLibrary> removals = ImmutableSortedSet.naturalOrder();
      for (JavaLibrary javaLibrary : candidates.build()) {
        if (HasMavenCoordinates.isMavenCoordsPresent(javaLibrary)) {
          depsCollector.add(javaLibrary);
          removals.addAll(javaLibrary.getTransitiveClasspathDeps());
        }
      }

      Set<JavaLibrary> difference = Sets.difference(candidates.build(), removals.build());
      Set<? extends BuildRule> mandatoryRules = alwaysPackageRoots ? roots : Collections.emptySet();
      return new TraversedDeps(
          /* mavenDeps */ depsCollector.build(),
          /* packagedDeps */ Sets.union(mandatoryRules, difference));
    }
  }
}
