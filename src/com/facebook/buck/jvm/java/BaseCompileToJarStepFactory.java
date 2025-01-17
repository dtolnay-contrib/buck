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

import com.facebook.buck.cd.model.java.FilesystemParams;
import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.cd.CompileStepsBuilderFactory;
import com.facebook.buck.jvm.cd.DefaultCompileStepsBuilderFactory;
import com.facebook.buck.jvm.cd.params.CDParams;
import com.facebook.buck.jvm.core.BuildTargetValue;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import com.facebook.buck.step.isolatedsteps.common.MakeCleanDirectoryIsolatedStep;
import com.facebook.buck.step.isolatedsteps.common.MkdirIsolatedStep;
import com.facebook.buck.step.isolatedsteps.java.JarDirectoryStep;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.ImmutableMap;
import java.util.Optional;
import javax.annotation.Nullable;

/** Provides a base implementation for post compile steps. */
public abstract class BaseCompileToJarStepFactory<T extends CompileToJarStepFactory.ExtraParams>
    implements AddsToRuleKey, CompileToJarStepFactory<T>, CompileStepsBuilderFactory.Creator {

  @AddToRuleKey private final boolean hasAnnotationProcessing;
  @AddToRuleKey protected final boolean withDownwardApi;

  protected BaseCompileToJarStepFactory(boolean hasAnnotationProcessing, boolean withDownwardApi) {
    this.withDownwardApi = withDownwardApi;
    this.hasAnnotationProcessing = hasAnnotationProcessing;
  }

  @Override
  public final void createCompileToJarStep(
      FilesystemParams filesystemParams,
      BuildTargetValue buildTargetValue,
      CompilerOutputPathsValue compilerOutputPathsValue,
      CompilerParameters compilerParameters,
      @Nullable JarParameters abiJarParameters,
      @Nullable JarParameters libraryJarParameters,
      Builder<IsolatedStep> steps,
      BuildableContext buildableContext,
      ImmutableMap<CanonicalCellName, RelPath> cellToPathMappings,
      ImmutableMap<RelPath, RelPath> resourcesMap,
      ResolvedJavac resolvedJavac,
      T extraParams) {
    Preconditions.checkArgument(libraryJarParameters != null || abiJarParameters == null);

    steps.addAll(
        getCompilerSetupIsolatedSteps(
            resourcesMap,
            compilerParameters.getOutputPaths(),
            compilerParameters.getSourceFilePaths().isEmpty()));

    JarParameters jarParameters =
        abiJarParameters != null ? abiJarParameters : libraryJarParameters;
    if (jarParameters != null) {
      addJarSetupSteps(jarParameters, steps);
    }

    // Only run javac if there are .java or .kt files to compile or we need to shovel the manifest
    // file
    // into the built jar.
    if (!compilerParameters.getSourceFilePaths().isEmpty()) {
      recordDepFileIfNecessary(
          compilerOutputPathsValue, buildTargetValue, compilerParameters, buildableContext);

      // This adds the javac command, along with any supporting commands.
      createCompileToJarStepImpl(
          filesystemParams,
          cellToPathMappings,
          buildTargetValue,
          compilerOutputPathsValue,
          compilerParameters,
          abiJarParameters,
          libraryJarParameters,
          steps,
          buildableContext,
          resolvedJavac,
          extraParams);
    }

    if (jarParameters != null) {
      addJarCreationSteps(compilerParameters, steps, buildableContext, jarParameters);
    }
  }

  /** Returns Compiler Setup steps */
  public ImmutableList<IsolatedStep> getCompilerSetupIsolatedSteps(
      ImmutableMap<RelPath, RelPath> resourcesMap,
      CompilerOutputPaths outputPaths,
      boolean emptySources) {
    // Always create the output directory, even if there are no .java files to compile because there
    // might be resources that need to be copied there.

    Builder<IsolatedStep> steps = ImmutableList.builder();

    steps.addAll(MakeCleanDirectoryIsolatedStep.of(outputPaths.getClassesDir()));
    steps.addAll(MakeCleanDirectoryIsolatedStep.of(outputPaths.getAnnotationPath()));
    steps.add(MkdirIsolatedStep.of(outputPaths.getOutputJarDirPath()));

    // If there are resources, then link them to the appropriate place in the classes directory.
    steps.addAll(CopyResourcesStep.of(resourcesMap));

    if (!emptySources) {
      steps.add(MkdirIsolatedStep.of(outputPaths.getPathToSourcesList().getParent()));
      steps.addAll(MakeCleanDirectoryIsolatedStep.of(outputPaths.getWorkingDirectory()));
    }

    return steps.build();
  }

  protected void addJarSetupSteps(JarParameters jarParameters, Builder<IsolatedStep> steps) {
    steps.addAll(MakeCleanDirectoryIsolatedStep.of(jarParameters.getJarPath().getParent()));
  }

  protected void recordDepFileIfNecessary(
      CompilerOutputPathsValue compilerOutputPathsValue,
      BuildTargetValue buildTargetValue,
      CompilerParameters compilerParameters,
      BuildableContext buildableContext) {
    if (compilerParameters.shouldTrackClassUsage()) {
      CompilerOutputPaths outputPath =
          compilerOutputPathsValue.getByType(buildTargetValue.getType());
      RelPath depFilePath =
          CompilerOutputPaths.getJavaDepFilePath(outputPath.getOutputJarDirPath());
      buildableContext.recordArtifact(depFilePath.getPath());
    }
  }

  void addJarCreationSteps(
      CompilerParameters compilerParameters,
      Builder<IsolatedStep> steps,
      BuildableContext buildableContext,
      JarParameters jarParameters) {
    // No source files, only resources
    if (compilerParameters.getSourceFilePaths().isEmpty()) {
      steps.add(new JarDirectoryStep(jarParameters));
    }
    buildableContext.recordArtifact(jarParameters.getJarPath().getPath());
  }

  protected void createCompileToJarStepImpl(
      FilesystemParams filesystemParams,
      ImmutableMap<CanonicalCellName, RelPath> cellToPathMappings,
      BuildTargetValue target,
      CompilerOutputPathsValue compilerOutputPathsValue,
      CompilerParameters compilerParameters,
      @Nullable JarParameters abiJarParameters,
      @Nullable JarParameters libraryJarParameters,
      Builder<IsolatedStep> steps,
      BuildableContext buildableContext,
      ResolvedJavac resolvedJavac,
      T extraParams) {
    Preconditions.checkArgument(abiJarParameters == null);
    Preconditions.checkArgument(
        libraryJarParameters != null
            && libraryJarParameters
                .getEntriesToJar()
                .contains(compilerParameters.getOutputPaths().getClassesDir()));

    createCompileStep(
        filesystemParams,
        cellToPathMappings,
        target,
        compilerOutputPathsValue,
        compilerParameters,
        steps,
        buildableContext,
        resolvedJavac,
        extraParams);

    steps.add(new JarDirectoryStep(libraryJarParameters));
  }

  /**
   * This can be used make the bootclasspath if available, to the postprocess classes commands.
   *
   * @return the bootclasspath.
   */
  protected Optional<String> getBootClasspath() {
    return Optional.empty();
  }

  public abstract void createCompileStep(
      FilesystemParams filesystemParams,
      ImmutableMap<CanonicalCellName, RelPath> cellToPathMappings,
      BuildTargetValue invokingRule,
      CompilerOutputPathsValue compilerOutputPathsValue,
      CompilerParameters parameters,
      Builder<IsolatedStep> steps,
      BuildableContext buildableContext,
      ResolvedJavac resolvedJavac,
      T extraParams);

  public boolean hasAnnotationProcessing() {
    return hasAnnotationProcessing;
  }

  protected static boolean hasAnnotationProcessing(JavacOptions javacOptions) {
    return !javacOptions.getJavaAnnotationProcessorParams().isEmpty();
  }

  public boolean supportsCompilationDaemon() {
    return false;
  }

  public ImmutableList<RelPath> getDepFilePaths(
      @SuppressWarnings("unused") ProjectFilesystem filesystem,
      @SuppressWarnings("unused") BuildTarget buildTarget) {
    return ImmutableList.of();
  }

  @Override
  public CompileStepsBuilderFactory createStepsBuilderFactory(CDParams params) {
    return new DefaultCompileStepsBuilderFactory<T>(this);
  }

  protected AbsPath getRootPath(FilesystemParams filesystemParams) {
    return AbsPath.get(filesystemParams.getRootPath().getPath());
  }
}
