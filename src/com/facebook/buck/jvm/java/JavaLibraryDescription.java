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

import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.description.arg.HasDeclaredDeps;
import com.facebook.buck.core.description.arg.HasProvidedDeps;
import com.facebook.buck.core.description.arg.HasSrcs;
import com.facebook.buck.core.description.arg.HasTests;
import com.facebook.buck.core.description.arg.Hint;
import com.facebook.buck.core.description.attr.ImplicitDepsInferringDescription;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorSet;
import com.facebook.buck.core.model.Flavored;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.facebook.buck.downwardapi.config.DownwardApiConfig;
import com.facebook.buck.infer.InferConfig;
import com.facebook.buck.infer.InferJava;
import com.facebook.buck.infer.UnresolvedInferPlatform;
import com.facebook.buck.infer.toolchain.InferToolchain;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.HasClasspathEntries;
import com.facebook.buck.jvm.core.HasSources;
import com.facebook.buck.jvm.core.JavaAbis;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.jvm.java.nullsafe.NullsafeConfig;
import com.facebook.buck.jvm.java.toolchain.JavacOptionsProvider;
import com.facebook.buck.maven.aether.AetherUtil;
import com.facebook.buck.versions.VersionPropagator;
import com.google.common.collect.ImmutableCollection.Builder;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Optional;
import org.immutables.value.Value;

public class JavaLibraryDescription
    implements DescriptionWithTargetGraph<JavaLibraryDescriptionArg>,
        Flavored,
        VersionPropagator<JavaLibraryDescriptionArg>,
        ImplicitDepsInferringDescription<JavaLibraryDescriptionArg> {

  private static final ImmutableSet<Flavor> SUPPORTED_FLAVORS =
      ImmutableSet.of(
          InferJava.INFER_NULLSAFE,
          InferJava.INFER_JAVA_CAPTURE,
          Javadoc.DOC_JAR,
          JavaLibrary.SRC_JAR,
          JavaLibrary.MAVEN_JAR,
          JavaAbis.CLASS_ABI_FLAVOR,
          JavaAbis.SOURCE_ABI_FLAVOR,
          JavaAbis.SOURCE_ONLY_ABI_FLAVOR,
          JavaAbis.VERIFIED_SOURCE_ABI_FLAVOR);

  private final ToolchainProvider toolchainProvider;
  private final JavaBuckConfig javaBuckConfig;
  private final DownwardApiConfig downwardApiConfig;
  private final JavacFactory javacFactory;
  private final JavaConfiguredCompilerFactory defaultJavaCompilerFactory;

  public JavaLibraryDescription(
      ToolchainProvider toolchainProvider,
      JavaBuckConfig javaBuckConfig,
      JavaCDBuckConfig javaCDBuckConfig,
      DownwardApiConfig downwardApiConfig) {
    this.toolchainProvider = toolchainProvider;
    this.javaBuckConfig = javaBuckConfig;
    this.javacFactory = JavacFactory.getDefault(toolchainProvider);
    this.downwardApiConfig = downwardApiConfig;
    this.defaultJavaCompilerFactory =
        new JavaConfiguredCompilerFactory(
            javaBuckConfig, javaCDBuckConfig, downwardApiConfig, javacFactory);
  }

  private Optional<UnresolvedInferPlatform> unresolvedInferPlatform(
      ToolchainProvider toolchainProvider, TargetConfiguration toolchainTargetConfiguration) {
    return toolchainProvider
        .getByNameIfPresent(
            InferToolchain.DEFAULT_NAME, toolchainTargetConfiguration, InferToolchain.class)
        .map(InferToolchain::getDefaultPlatform);
  }

  @Override
  public boolean hasFlavors(
      ImmutableSet<Flavor> flavors, TargetConfiguration toolchainTargetConfiguration) {
    return SUPPORTED_FLAVORS.containsAll(flavors) || Nullsafe.hasSupportedFlavor(flavors);
  }

  @Override
  public Class<JavaLibraryDescriptionArg> getConstructorArgType() {
    return JavaLibraryDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      JavaLibraryDescriptionArg args) {
    ActionGraphBuilder graphBuilder = context.getActionGraphBuilder();
    // We know that the flavour we're being asked to create is valid, since the check is done when
    // creating the action graph from the target graph.

    FlavorSet flavors = buildTarget.getFlavors();
    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();
    ToolchainProvider toolchainProvider = context.getToolchainProvider();
    JavacOptions javacOptions =
        JavacOptionsFactory.create(
            context
                .getToolchainProvider()
                .getByName(
                    JavacOptionsProvider.DEFAULT_NAME,
                    buildTarget.getTargetConfiguration(),
                    JavacOptionsProvider.class)
                .getJavacOptions(),
            buildTarget,
            graphBuilder,
            projectFilesystem.getRootPath(),
            args);

    Optional<Flavor> inferFlavor = InferJava.findSupportedFlavor(flavors);
    if (inferFlavor.isPresent()) {
      Flavor flavor = inferFlavor.get();
      return InferJava.create(
          flavor,
          buildTarget,
          projectFilesystem,
          graphBuilder,
          javacOptions,
          defaultJavaCompilerFactory.getExtraClasspathProvider(
              toolchainProvider, buildTarget.getTargetConfiguration()),
          unresolvedInferPlatform(toolchainProvider, buildTarget.getTargetConfiguration())
              .orElseThrow(
                  () ->
                      new HumanReadableException(
                          "Cannot use %s flavor: infer platform not configured", flavor.getName())),
          InferConfig.of(javaBuckConfig.getDelegate()),
          downwardApiConfig);
    }

    if (flavors.contains(Javadoc.DOC_JAR)) {
      BuildTarget unflavored = buildTarget.withoutFlavors();
      BuildRule baseLibrary = graphBuilder.requireRule(unflavored);

      JarShape shape =
          buildTarget.getFlavors().contains(JavaLibrary.MAVEN_JAR)
              ? JarShape.MAVEN
              : JarShape.SINGLE;

      JarShape.Summary summary = shape.gatherDeps(baseLibrary);
      ImmutableSet<SourcePath> sources =
          summary.getPackagedRules().stream()
              .filter(HasSources.class::isInstance)
              .map(rule -> ((HasSources) rule).getSources())
              .flatMap(Collection::stream)
              .collect(ImmutableSet.toImmutableSet());

      // In theory, the only deps we need are the ones that contribute to the sourcepaths. However,
      // javadoc wants to have classes being documented have all their deps be available somewhere.
      // Ideally, we'd not build everything, but then we're not able to document any classes that
      // rely on auto-generated classes, such as those created by the Immutables library. Oh well.
      // Might as well add them as deps. *sigh*
      ImmutableSortedSet.Builder<BuildRule> deps = ImmutableSortedSet.naturalOrder();
      // Sourcepath deps
      deps.addAll(graphBuilder.filterBuildRuleInputs(sources));
      // Classpath deps
      deps.add(baseLibrary);
      deps.addAll(
          summary.getClasspath().stream()
              .filter(rule -> HasClasspathEntries.class.isAssignableFrom(rule.getClass()))
              .flatMap(rule -> rule.getTransitiveClasspathDeps().stream())
              .iterator());
      BuildRuleParams emptyParams = params.withDeclaredDeps(deps.build()).withoutExtraDeps();

      return new Javadoc(
          buildTarget,
          projectFilesystem,
          emptyParams,
          args.getMavenCoords(),
          summary.getMavenDeps(),
          sources,
          downwardApiConfig.isEnabledForJava());
    }

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
            buildTarget, projectFilesystem, params, args.getSrcs(), mavenCoords);
      } else {
        return MavenUberJar.SourceJar.create(
            buildTargetWithMavenFlavor, projectFilesystem, params, args.getSrcs(), mavenCoords);
      }
    }

    if (Nullsafe.hasSupportedFlavor(flavors)) {
      javacOptions =
          Nullsafe.augmentJavacOptions(
              javacOptions,
              buildTarget,
              graphBuilder,
              projectFilesystem,
              NullsafeConfig.of(javaBuckConfig.getDelegate()));
    }

    DefaultJavaLibraryRules defaultJavaLibraryRules =
        DefaultJavaLibrary.rulesBuilder(
                buildTarget,
                projectFilesystem,
                context.getToolchainProvider(),
                params,
                graphBuilder,
                defaultJavaCompilerFactory,
                javaBuckConfig,
                downwardApiConfig,
                args,
                context.getCellPathResolver())
            .setJavacOptions(javacOptions)
            .setToolchainProvider(context.getToolchainProvider())
            .build();

    if (Nullsafe.hasSupportedFlavor(flavors)) {
      return Nullsafe.create(graphBuilder, defaultJavaLibraryRules.buildLibraryForNullsafe());
    }

    if (JavaAbis.isAbiTarget(buildTarget)) {
      return defaultJavaLibraryRules.buildAbi();
    }

    DefaultJavaLibrary defaultJavaLibrary = defaultJavaLibraryRules.buildLibrary();

    if (!flavors.contains(JavaLibrary.MAVEN_JAR)) {
      return defaultJavaLibrary;
    } else {
      graphBuilder.addToIndex(defaultJavaLibrary);
      return MavenUberJar.create(
          defaultJavaLibrary,
          buildTargetWithMavenFlavor,
          projectFilesystem,
          params,
          args.getMavenCoords());
    }
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellNameResolver cellRoots,
      JavaLibraryDescriptionArg constructorArg,
      Builder<BuildTarget> extraDepsBuilder,
      Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    javacFactory.addParseTimeDeps(
        targetGraphOnlyDepsBuilder, constructorArg, buildTarget.getTargetConfiguration());

    if (InferJava.findSupportedFlavor(buildTarget.getFlavors()).isPresent()) {

      unresolvedInferPlatform(toolchainProvider, buildTarget.getTargetConfiguration())
          .ifPresent(
              p -> p.addParseTimeDepsToInferFlavored(targetGraphOnlyDepsBuilder, buildTarget));
    }

    Nullsafe.addParseTimeDeps(
        targetGraphOnlyDepsBuilder, buildTarget, NullsafeConfig.of(javaBuckConfig.getDelegate()));
  }

  public interface CoreArg
      extends JvmLibraryArg, HasDeclaredDeps, HasProvidedDeps, HasSrcs, HasTests {

    @Value.NaturalOrder
    ImmutableSortedSet<SourcePath> getResources();

    Optional<SourcePath> getProguardConfig();

    @Hint(isInput = false)
    Optional<Path> getResourcesRoot();

    Optional<SourcePath> getManifestFile();

    Optional<String> getMavenCoords();

    @Value.NaturalOrder
    ImmutableSortedSet<BuildTarget> getExportedDeps();

    @Value.NaturalOrder
    ImmutableSortedSet<BuildTarget> getSourceOnlyAbiDeps();

    @Value.NaturalOrder
    ImmutableSortedSet<BuildTarget> getRuntimeDeps();
  }

  @RuleArg
  interface AbstractJavaLibraryDescriptionArg extends CoreArg {}
}
