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

package com.facebook.buck.features.js;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.FlavorSet;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.impl.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.io.filesystem.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.shell.WorkerTool;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.util.json.JsonBuilder;
import com.facebook.buck.util.json.JsonBuilder.ObjectBuilder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;

public class JsDependenciesFile extends AbstractBuildRuleWithDeclaredAndExtraDeps
    implements JsDependenciesOutputs {

  @AddToRuleKey private final ImmutableSet<String> entryPoints;
  @AddToRuleKey private final ImmutableSortedSet<SourcePath> libraries;
  @AddToRuleKey private final Optional<Arg> extraJson;
  @AddToRuleKey private final WorkerTool worker;
  @AddToRuleKey private final boolean withDownwardApi;

  protected JsDependenciesFile(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams buildRuleParams,
      ImmutableSortedSet<SourcePath> libraries,
      ImmutableSet<String> entryPoints,
      Optional<Arg> extraJson,
      WorkerTool worker,
      boolean withDownwardApi) {
    super(buildTarget, projectFilesystem, buildRuleParams);
    this.entryPoints = entryPoints;
    this.libraries = libraries;
    this.extraJson = extraJson;
    this.worker = worker;
    this.withDownwardApi = withDownwardApi;
  }

  @Override
  public ImmutableList<? extends Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    SourcePathResolverAdapter sourcePathResolverAdapter = context.getSourcePathResolver();

    SourcePath outputFile = getSourcePathToOutput();
    ObjectBuilder jobArgs = getJobArgs(sourcePathResolverAdapter, outputFile);

    buildableContext.recordArtifact(
        sourcePathResolverAdapter.getCellUnsafeRelPath(outputFile).getPath());

    return ImmutableList.<Step>builder()
        .add(
            MkdirStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    context.getBuildCellRootPath(),
                    getProjectFilesystem(),
                    sourcePathResolverAdapter.getCellUnsafeRelPath(outputFile).getParent())),
            JsUtil.jsonWorkerShellStepAddingFlavors(
                worker,
                jobArgs,
                getBuildTarget(),
                sourcePathResolverAdapter,
                getProjectFilesystem(),
                withDownwardApi))
        .build();
  }

  private ObjectBuilder getJobArgs(
      SourcePathResolverAdapter sourcePathResolverAdapter, SourcePath outputFilePath) {

    FlavorSet flavors = getBuildTarget().getFlavors();

    return JsonBuilder.object()
        .addString(
            "outputFilePath", sourcePathResolverAdapter.getAbsolutePath(outputFilePath).toString())
        .addString("command", "dependencies")
        .addArray("entryPoints", entryPoints.stream().collect(JsonBuilder.toArrayOfStrings()))
        .addArray(
            "libraries",
            libraries.stream()
                .map(sourcePathResolverAdapter::getAbsolutePath)
                .map(AbsPath::toString)
                .collect(JsonBuilder.toArrayOfStrings()))
        .addString("platform", JsUtil.getPlatformString(flavors))
        .addBoolean("release", flavors.contains(JsFlavors.RELEASE))
        .addString("rootPath", getProjectFilesystem().getRootPath().toString())
        .addRaw("extraData", extraJson.map(a -> Arg.stringify(a, sourcePathResolverAdapter)));
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return JsDependenciesOutputs.super.getSourcePathToDepsFile();
  }
}
