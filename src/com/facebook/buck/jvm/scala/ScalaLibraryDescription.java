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

package com.facebook.buck.jvm.scala;

import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.description.attr.ImplicitDepsInferringDescription;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorSet;
import com.facebook.buck.core.model.Flavored;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.util.Optionals;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.facebook.buck.downwardapi.config.DownwardApiConfig;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaAbis;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.jvm.java.DefaultJavaLibraryRules;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.jvm.java.JavaLibraryDescription;
import com.facebook.buck.jvm.java.JavaSourceJar;
import com.facebook.buck.jvm.java.JavacFactory;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.jvm.java.JavacOptionsFactory;
import com.facebook.buck.jvm.java.MavenUberJar;
import com.facebook.buck.jvm.java.toolchain.JavacOptionsProvider;
import com.facebook.buck.maven.aether.AetherUtil;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableSet;
import java.util.Optional;

public class ScalaLibraryDescription
    implements DescriptionWithTargetGraph<ScalaLibraryDescriptionArg>,
        Flavored,
        ImplicitDepsInferringDescription<
            ScalaLibraryDescription.AbstractScalaLibraryDescriptionArg> {

  private static final ImmutableSet<Flavor> SUPPORTED_FLAVORS =
      ImmutableSet.of(JavaLibrary.MAVEN_JAR, JavaLibrary.SRC_JAR);

  private final ToolchainProvider toolchainProvider;
  private final ScalaBuckConfig scalaBuckConfig;
  private final JavaBuckConfig javaBuckConfig;
  private final JavacFactory javacFactory;
  private final DownwardApiConfig downwardApiConfig;

  public ScalaLibraryDescription(
      ToolchainProvider toolchainProvider,
      ScalaBuckConfig scalaBuckConfig,
      JavaBuckConfig javaBuckConfig,
      DownwardApiConfig downwardApiConfig) {
    this.toolchainProvider = toolchainProvider;
    this.scalaBuckConfig = scalaBuckConfig;
    this.javaBuckConfig = javaBuckConfig;
    this.javacFactory = JavacFactory.getDefault(toolchainProvider);
    this.downwardApiConfig = downwardApiConfig;
  }

  @Override
  public boolean hasFlavors(
      ImmutableSet<Flavor> flavors, TargetConfiguration toolchainTargetConfiguration) {
    return SUPPORTED_FLAVORS.containsAll(flavors);
  }

  @Override
  public Class<ScalaLibraryDescriptionArg> getConstructorArgType() {
    return ScalaLibraryDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams rawParams,
      ScalaLibraryDescriptionArg args) {
    FlavorSet flavors = buildTarget.getFlavors();

    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();

    BuildTarget buildTargetWithMavenFlavor = buildTarget;
    if (flavors.contains(JavaLibrary.MAVEN_JAR)) {

      // Maven rules will depend upon their vanilla versions, so the latter have to be constructed
      // without the maven flavor to prevent output-path conflict
      buildTarget = buildTarget.withoutFlavors(JavaLibrary.MAVEN_JAR);
    }

    if (flavors.contains(JavaLibrary.SRC_JAR)) {
      Optional<String> mavenCoords =
          args.getMavenCoords()
              .map(input -> AetherUtil.addClassifier(input, AetherUtil.CLASSIFIER_SOURCES));

      if (!flavors.contains(JavaLibrary.MAVEN_JAR)) {
        return new JavaSourceJar(
            buildTarget, projectFilesystem, rawParams, args.getSrcs(), mavenCoords);
      } else {
        return MavenUberJar.SourceJar.create(
            buildTargetWithMavenFlavor, projectFilesystem, rawParams, args.getSrcs(), mavenCoords);
      }
    }

    JavacOptions javacOptions =
        JavacOptionsFactory.create(
            toolchainProvider
                .getByName(
                    JavacOptionsProvider.DEFAULT_NAME,
                    buildTarget.getTargetConfiguration(),
                    JavacOptionsProvider.class)
                .getJavacOptions(),
            buildTarget,
            context.getActionGraphBuilder(),
            projectFilesystem.getRootPath(),
            args);

    DefaultJavaLibraryRules scalaLibraryBuilder =
        ScalaLibraryBuilder.newInstance(
                buildTarget,
                context.getProjectFilesystem(),
                context.getToolchainProvider(),
                rawParams,
                context.getActionGraphBuilder(),
                scalaBuckConfig,
                javaBuckConfig,
                downwardApiConfig,
                args,
                javacFactory,
                context.getCellPathResolver())
            .setJavacOptions(javacOptions)
            .build();

    if (JavaAbis.isAbiTarget(buildTarget)) {
      return scalaLibraryBuilder.buildAbi();
    }

    JavaLibrary defaultScalaLibrary = scalaLibraryBuilder.buildLibrary();

    if (!flavors.contains(JavaLibrary.MAVEN_JAR)) {
      return defaultScalaLibrary;
    } else {
      context.getActionGraphBuilder().addToIndex(defaultScalaLibrary);
      return MavenUberJar.create(
          defaultScalaLibrary,
          buildTargetWithMavenFlavor,
          projectFilesystem,
          rawParams,
          args.getMavenCoords());
    }
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellNameResolver cellRoots,
      AbstractScalaLibraryDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    extraDepsBuilder
        .add(scalaBuckConfig.getScalaLibraryTarget(buildTarget.getTargetConfiguration()))
        .addAll(scalaBuckConfig.getCompilerPlugins(buildTarget.getTargetConfiguration()));
    Optionals.addIfPresent(
        scalaBuckConfig.getScalacTarget(buildTarget.getTargetConfiguration()), extraDepsBuilder);
    javacFactory.addParseTimeDeps(
        targetGraphOnlyDepsBuilder, constructorArg, buildTarget.getTargetConfiguration());
  }

  @RuleArg
  interface AbstractScalaLibraryDescriptionArg extends JavaLibraryDescription.CoreArg {}
}
