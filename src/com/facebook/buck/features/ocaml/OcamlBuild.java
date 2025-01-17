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

package com.facebook.buck.features.ocaml;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.impl.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.cxx.toolchain.Compiler;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.io.filesystem.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.google.common.collect.ImmutableList;
import java.util.Objects;

/** A build rule which preprocesses, compiles, and assembles an OCaml source. */
public class OcamlBuild extends AbstractBuildRuleWithDeclaredAndExtraDeps {

  @AddToRuleKey private final OcamlBuildContext ocamlContext;
  @AddToRuleKey private final Compiler cCompiler;
  @AddToRuleKey private final Linker cxxLinker;
  @AddToRuleKey private final boolean bytecodeOnly;
  @AddToRuleKey private final boolean withDownwardApi;

  public OcamlBuild(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      OcamlBuildContext ocamlContext,
      Compiler cCompiler,
      Linker cxxLinker,
      boolean bytecodeOnly,
      boolean withDownwardApi) {
    super(buildTarget, projectFilesystem, params);
    this.ocamlContext = ocamlContext;
    this.cCompiler = cCompiler;
    this.cxxLinker = cxxLinker;
    this.bytecodeOnly = bytecodeOnly;
    this.withDownwardApi = withDownwardApi;

    Objects.requireNonNull(ocamlContext.getInput());
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    RelPath baseArtifactDir = ocamlContext.getNativeOutput().getParent();
    buildableContext.recordArtifact(baseArtifactDir.getPath());
    if (!bytecodeOnly) {
      buildableContext.recordArtifact(
          baseArtifactDir.resolve(OcamlBuildContext.OCAML_COMPILED_DIR));
    }
    buildableContext.recordArtifact(
        baseArtifactDir.resolve(OcamlBuildContext.OCAML_COMPILED_BYTECODE_DIR));
    return new ImmutableList.Builder<Step>()
        .addAll(
            MakeCleanDirectoryStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    context.getBuildCellRootPath(),
                    getProjectFilesystem(),
                    ocamlContext.getNativeOutput().getParent())))
        .add(
            new OcamlBuildStep(
                context,
                getProjectFilesystem(),
                ocamlContext,
                getBuildTarget(),
                cCompiler.getEnvironment(context.getSourcePathResolver()),
                cCompiler.getCommandPrefix(context.getSourcePathResolver()),
                cxxLinker.getEnvironment(context.getSourcePathResolver()),
                cxxLinker.getCommandPrefix(context.getSourcePathResolver()),
                bytecodeOnly,
                withDownwardApi))
        .build();
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(
        getBuildTarget(),
        bytecodeOnly ? ocamlContext.getBytecodeOutput() : ocamlContext.getNativeOutput());
  }

  @Override
  public boolean isCacheable() {
    // Intermediate OCaml rules are not cacheable because the compiler is not deterministic.
    return false;
  }

  public OcamlBuildContext getOcamlContext() {
    return ocamlContext;
  }
}
