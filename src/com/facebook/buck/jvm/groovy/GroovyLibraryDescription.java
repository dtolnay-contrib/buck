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

package com.facebook.buck.jvm.groovy;

import com.facebook.buck.cd.model.java.AbiGenerationMode;
import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.description.attr.ImplicitDepsInferringDescription;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.facebook.buck.downwardapi.config.DownwardApiConfig;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaAbis;
import com.facebook.buck.jvm.groovy.GroovyLibraryDescription.AbstractGroovyLibraryDescriptionArg;
import com.facebook.buck.jvm.java.DefaultJavaLibraryRules;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.jvm.java.JavaLibraryDescription;
import com.facebook.buck.jvm.java.JavacFactory;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.jvm.java.JavacOptionsFactory;
import com.facebook.buck.jvm.java.toolchain.JavacOptionsProvider;
import com.google.common.collect.ImmutableCollection.Builder;
import com.google.common.collect.ImmutableList;
import java.util.Optional;

public class GroovyLibraryDescription
    implements DescriptionWithTargetGraph<GroovyLibraryDescriptionArg>,
        ImplicitDepsInferringDescription<AbstractGroovyLibraryDescriptionArg> {

  private final ToolchainProvider toolchainProvider;
  private final JavaBuckConfig javaBuckConfig;
  private final DownwardApiConfig downwardApiConfig;
  private final GroovyConfiguredCompilerFactory compilerFactory;

  public GroovyLibraryDescription(
      ToolchainProvider toolchainProvider,
      GroovyBuckConfig groovyBuckConfig,
      JavaBuckConfig javaBuckConfig,
      DownwardApiConfig downwardApiConfig) {
    this.toolchainProvider = toolchainProvider;
    this.javaBuckConfig = javaBuckConfig;
    this.downwardApiConfig = downwardApiConfig;
    this.compilerFactory =
        new GroovyConfiguredCompilerFactory(
            groovyBuckConfig, downwardApiConfig, JavacFactory.getDefault(toolchainProvider));
  }

  @Override
  public Class<GroovyLibraryDescriptionArg> getConstructorArgType() {
    return GroovyLibraryDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      GroovyLibraryDescriptionArg args) {
    ActionGraphBuilder graphBuilder = context.getActionGraphBuilder();
    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();
    JavacOptions javacOptions =
        JavacOptionsFactory.create(
            toolchainProvider
                .getByName(
                    JavacOptionsProvider.DEFAULT_NAME,
                    buildTarget.getTargetConfiguration(),
                    JavacOptionsProvider.class)
                .getJavacOptions(),
            buildTarget,
            graphBuilder,
            projectFilesystem.getRootPath(),
            args);
    DefaultJavaLibraryRules defaultJavaLibraryRules =
        new DefaultJavaLibraryRules.Builder(
                buildTarget,
                projectFilesystem,
                context.getToolchainProvider(),
                params,
                graphBuilder,
                compilerFactory,
                javaBuckConfig,
                downwardApiConfig,
                args,
                context.getCellPathResolver())
            .setJavacOptions(javacOptions)
            .build();

    return JavaAbis.isAbiTarget(buildTarget)
        ? defaultJavaLibraryRules.buildAbi()
        : graphBuilder.addToIndex(defaultJavaLibraryRules.buildLibrary());
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellNameResolver cellRoots,
      AbstractGroovyLibraryDescriptionArg constructorArg,
      Builder<BuildTarget> extraDepsBuilder,
      Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    compilerFactory.addTargetDeps(
        buildTarget.getTargetConfiguration(), extraDepsBuilder, targetGraphOnlyDepsBuilder);
  }

  public interface CoreArg extends JavaLibraryDescription.CoreArg {

    // Groovyc may not play nice with source ABIs, so turning it off
    @Override
    default Optional<AbiGenerationMode> getAbiGenerationMode() {
      return Optional.of(AbiGenerationMode.CLASS);
    }

    ImmutableList<String> getExtraGroovycArguments();
  }

  @RuleArg
  interface AbstractGroovyLibraryDescriptionArg extends CoreArg {}
}
