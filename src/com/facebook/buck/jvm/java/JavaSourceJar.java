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
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.impl.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.filesystem.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.HasMavenCoordinates;
import com.facebook.buck.jvm.core.HasSources;
import com.facebook.buck.jvm.core.JavaPackageFinder;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.RmStep;
import com.facebook.buck.step.fs.ZipStep;
import com.facebook.buck.util.zip.ZipCompressionLevel;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

public class JavaSourceJar extends AbstractBuildRuleWithDeclaredAndExtraDeps
    implements HasMavenCoordinates, HasSources {

  @AddToRuleKey private final ImmutableSortedSet<SourcePath> sources;
  private final RelPath output;
  private final RelPath temp;
  private final Optional<String> mavenCoords;

  public JavaSourceJar(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      ImmutableSortedSet<SourcePath> sources,
      Optional<String> mavenCoords) {
    super(buildTarget, projectFilesystem, params);
    this.sources = sources;
    this.output =
        BuildTargetPaths.getGenPath(
            getProjectFilesystem().getBuckPaths(), buildTarget, "%s" + JavaPaths.SRC_JAR);
    this.temp = BuildTargetPaths.getScratchPath(getProjectFilesystem(), buildTarget, "%s-srcs");
    this.mavenCoords = mavenCoords;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    JavaPackageFinder packageFinder = context.getJavaPackageFinder();

    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    steps.add(
        MkdirStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), output.getParent())));
    steps.add(
        RmStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), output)));

    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), temp)));

    Set<Path> seenPackages = new HashSet<>();

    // We only want to consider raw source files, since the java package finder doesn't have the
    // smarts to read the "package" line from a source file.

    for (Path source : context.getSourcePathResolver().filterInputsToCompareToOutput(sources)) {
      Path packageFolder = packageFinder.findJavaPackageFolder(source);
      Path packageDir = temp.resolve(packageFolder);
      if (seenPackages.add(packageDir)) {
        steps.add(
            MkdirStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    context.getBuildCellRootPath(), getProjectFilesystem(), packageDir)));
      }
      steps.add(CopyStep.forFile(source, packageDir.resolve(source.getFileName())));
    }
    steps.add(
        ZipStep.of(
            getProjectFilesystem(),
            output.getPath(),
            ImmutableSet.of(),
            false,
            ZipCompressionLevel.DEFAULT,
            temp.getPath()));

    buildableContext.recordArtifact(output.getPath());

    return steps.build();
  }

  @Override
  public ImmutableSortedSet<SourcePath> getSources() {
    return sources;
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), output);
  }

  @Override
  public Optional<String> getMavenCoords() {
    return mavenCoords;
  }
}
