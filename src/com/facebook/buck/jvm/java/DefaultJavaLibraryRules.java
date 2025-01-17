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

import static com.facebook.buck.step.isolatedsteps.java.UnusedDependenciesFinder.isActionableUnusedDependenciesAction;

import com.facebook.buck.cd.model.java.AbiGenerationMode;
import com.facebook.buck.cd.model.java.UnusedDependenciesParams.UnusedDependenciesAction;
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.util.immutables.BuckStyleValueWithBuilder;
import com.facebook.buck.downwardapi.config.DownwardApiConfig;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.common.ResourceValidator;
import com.facebook.buck.jvm.core.CalculateAbi;
import com.facebook.buck.jvm.core.JavaAbis;
import com.facebook.buck.jvm.java.JavaBuckConfig.SourceAbiVerificationMode;
import com.facebook.buck.jvm.java.JavaBuckConfig.UnusedDependenciesConfig;
import com.facebook.buck.jvm.java.JavaLibraryDescription.CoreArg;
import com.facebook.buck.jvm.java.abi.AbiGenerationModeUtils;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import org.immutables.value.Value;

@BuckStyleValueWithBuilder
public abstract class DefaultJavaLibraryRules {

  /** Default java library constructor interface */
  @FunctionalInterface
  public interface DefaultJavaLibraryConstructor {

    DefaultJavaLibrary newInstance(
        BuildTarget buildTarget,
        ProjectFilesystem projectFilesystem,
        JarBuildStepsFactory<?> jarBuildStepsFactory,
        SourcePathRuleFinder ruleFinder,
        Optional<SourcePath> proguardConfig,
        SortedSet<BuildRule> firstOrderPackageableDeps,
        ImmutableSortedSet<BuildRule> fullJarExportedDeps,
        ImmutableSortedSet<BuildRule> fullJarProvidedDeps,
        ImmutableSortedSet<BuildRule> fullJarExportedProvidedDeps,
        ImmutableSortedSet<BuildRule> runtimeDeps,
        @Nullable BuildTarget abiJar,
        @Nullable BuildTarget sourceOnlyAbiJar,
        Optional<String> mavenCoords,
        ImmutableSortedSet<BuildTarget> tests,
        boolean requiredForSourceOnlyAbi,
        UnusedDependenciesAction unusedDependenciesAction,
        Optional<UnusedDependenciesFinderFactory> unusedDependenciesFinderFactory,
        @Nullable CalculateSourceAbi previousRuleInPipeline,
        boolean isDesugarEnabled,
        boolean isInterfaceMethodsDesugarEnabled,
        boolean neverMarkAsUnusedDependency);
  }

  @org.immutables.builder.Builder.Parameter
  abstract BuildTarget getInitialBuildTarget();

  @Value.Lazy
  BuildTarget getLibraryTarget() {
    BuildTarget initialBuildTarget = getInitialBuildTarget();
    return JavaAbis.isLibraryTarget(initialBuildTarget)
        ? initialBuildTarget
        : JavaAbis.getLibraryTarget(initialBuildTarget);
  }

  @org.immutables.builder.Builder.Parameter
  abstract ProjectFilesystem getProjectFilesystem();

  @org.immutables.builder.Builder.Parameter
  abstract ToolchainProvider getToolchainProvider();

  @org.immutables.builder.Builder.Parameter
  abstract BuildRuleParams getInitialParams();

  @org.immutables.builder.Builder.Parameter
  abstract ActionGraphBuilder getActionGraphBuilder();

  @Value.Lazy
  SourcePathResolverAdapter getSourcePathResolver() {
    return getActionGraphBuilder().getSourcePathResolver();
  }

  @org.immutables.builder.Builder.Parameter
  abstract ConfiguredCompilerFactory getConfiguredCompilerFactory();

  @org.immutables.builder.Builder.Parameter
  abstract UnusedDependenciesAction getUnusedDependenciesAction();

  @org.immutables.builder.Builder.Parameter
  @Nullable
  abstract JavaBuckConfig getJavaBuckConfig();

  @org.immutables.builder.Builder.Parameter
  abstract CellPathResolver getCellPathResolver();

  @org.immutables.builder.Builder.Parameter
  abstract DownwardApiConfig getDownwardApiConfig();

  @Value.Default
  DefaultJavaLibraryConstructor getConstructor() {
    return DefaultJavaLibrary::new;
  }

  @Value.NaturalOrder
  abstract ImmutableSortedSet<SourcePath> getSrcs();

  @Value.NaturalOrder
  abstract ImmutableSortedSet<SourcePath> getResources();

  @Value.Check
  void validateResources() {
    ResourceValidator.validateResources(
        getSourcePathResolver(), getProjectFilesystem(), getResources());
  }

  abstract Optional<SourcePath> getProguardConfig();

  abstract Optional<Path> getResourcesRoot();

  abstract Optional<SourcePath> getManifestFile();

  abstract Optional<String> getMavenCoords();

  @Value.NaturalOrder
  abstract ImmutableSortedSet<BuildTarget> getTests();

  @Value.Default
  RemoveClassesPatternsMatcher getClassesToRemoveFromJar() {
    return RemoveClassesPatternsMatcher.EMPTY;
  }

  @Value.Default
  boolean getSourceOnlyAbisAllowed() {
    return true;
  }

  abstract JavacOptions getJavacOptions();

  @Nullable
  abstract JavaLibraryDeps getDeps();

  @org.immutables.builder.Builder.Parameter
  @Nullable
  abstract JavaLibraryDescription.CoreArg getArgs();

  public DefaultJavaLibrary buildLibrary() {
    buildAllRules();

    return (DefaultJavaLibrary) getActionGraphBuilder().getRule(getLibraryTarget());
  }

  public BuildRule buildAbi() {
    buildAllRules();

    return getActionGraphBuilder().getRule(getInitialBuildTarget());
  }

  private void buildAllRules() {
    // To guarantee that all rules in a source-ABI pipeline are working off of the same settings,
    // we want to create them all from the same instance of this builder. To ensure this, we force
    // a request for whichever rule is closest to the root of the graph (regardless of which rule
    // was actually requested) and then create all of the rules inside that request. We're
    // requesting the rootmost rule because the rules are created from leafmost to rootmost and
    // we want any requests to block until all of the rules are built.
    BuildTarget rootmostTarget = getLibraryTarget();
    if (willProduceCompareAbis()) {
      rootmostTarget = JavaAbis.getVerifiedSourceAbiJar(rootmostTarget);
    } else if (willProduceClassAbi()) {
      rootmostTarget = JavaAbis.getClassAbiJar(rootmostTarget);
    }

    ActionGraphBuilder graphBuilder = getActionGraphBuilder();
    graphBuilder.computeIfAbsent(
        rootmostTarget,
        target -> {
          CalculateSourceAbi sourceOnlyAbiRule = buildSourceOnlyAbiRule();
          CalculateSourceAbi sourceAbiRule = buildSourceAbiRule();
          DefaultJavaLibrary libraryRule = buildLibraryRule(sourceAbiRule);
          CalculateClassAbi classAbiRule = buildClassAbiRule(libraryRule);
          CompareAbis compareAbisRule;
          if (sourceOnlyAbiRule != null) {
            compareAbisRule = buildCompareAbisRule(sourceAbiRule, sourceOnlyAbiRule);
          } else {
            compareAbisRule = buildCompareAbisRule(classAbiRule, sourceAbiRule);
          }

          if (JavaAbis.isLibraryTarget(target)) {
            return libraryRule;
          } else if (JavaAbis.isClassAbiTarget(target)) {
            return classAbiRule;
          } else if (JavaAbis.isVerifiedSourceAbiTarget(target)) {
            return compareAbisRule;
          }

          throw new AssertionError();
        });
  }

  @Nullable
  private <T extends BuildRule & CalculateAbi, U extends BuildRule & CalculateAbi>
      CompareAbis buildCompareAbisRule(@Nullable T correctAbi, @Nullable U experimentalAbi) {
    if (!willProduceCompareAbis()) {
      return null;
    }
    Objects.requireNonNull(correctAbi);
    Objects.requireNonNull(experimentalAbi);

    BuildTarget compareAbisTarget = JavaAbis.getVerifiedSourceAbiJar(getLibraryTarget());
    return getActionGraphBuilder()
        .addToIndex(
            new CompareAbis(
                compareAbisTarget,
                getProjectFilesystem(),
                getInitialParams()
                    .withDeclaredDeps(ImmutableSortedSet.of(correctAbi, experimentalAbi))
                    .withoutExtraDeps(),
                correctAbi.getSourcePathToOutput(),
                experimentalAbi.getSourcePathToOutput(),
                Objects.requireNonNull(getJavaBuckConfig()).getSourceAbiVerificationMode()));
  }

  @Value.Lazy
  @Nullable
  BuildTarget getAbiJar() {
    if (willProduceCompareAbis()) {
      return JavaAbis.getVerifiedSourceAbiJar(getLibraryTarget());
    } else if (willProduceSourceAbi() || willProduceSourceOnlyAbi()) {
      return JavaAbis.getSourceAbiJar(getLibraryTarget());
    } else if (willProduceClassAbi()) {
      return JavaAbis.getClassAbiJar(getLibraryTarget());
    }

    return null;
  }

  @Value.Lazy
  @Nullable
  BuildTarget getSourceOnlyAbiJar() {
    if (willProduceSourceOnlyAbi()) {
      return JavaAbis.getSourceOnlyAbiJar(getLibraryTarget());
    }

    return null;
  }

  private boolean willProduceAbiJar() {
    return !getSrcs().isEmpty() || !getResources().isEmpty() || getManifestFile().isPresent();
  }

  // regex pattern to extract java version from both "7" and "1.7" notations.
  private static final Pattern JAVA_VERSION_PATTERN = Pattern.compile("^(1\\.)*(?<version>\\d)$");

  private boolean isDesugarRequired() {
    String rawJavaSourceLevel =
        getJavacOptions().getLanguageLevelOptions().getSourceLevelValue().getVersion();
    String rawKotlinSourceLevel =
        Optional.ofNullable(getArgs()).flatMap(JvmLibraryArg::getTarget).orElse(null);
    Integer javaSourceLevel = extractSourceLevel(rawJavaSourceLevel);
    Integer kotlinSourceLevel = extractSourceLevel(rawKotlinSourceLevel);
    return shouldApplyDesugaringToSourceLevel(javaSourceLevel)
        || shouldApplyDesugaringToSourceLevel(kotlinSourceLevel);
  }

  private static boolean shouldApplyDesugaringToSourceLevel(@Nullable Integer sourceLevel) {
    // Currently only java 8+ requires desugaring on Android
    return sourceLevel != null && sourceLevel > 7;
  }

  private static @Nullable Integer extractSourceLevel(@Nullable String rawSourceLevel) {
    if (rawSourceLevel == null) {
      return null;
    }

    Matcher matcher = JAVA_VERSION_PATTERN.matcher(rawSourceLevel);
    if (!matcher.find()) {
      return null;
    }
    return Integer.parseInt(matcher.group("version"));
  }

  @Value.Lazy
  AbiGenerationMode getAbiGenerationMode() {
    AbiGenerationMode result = null;

    CoreArg args = getArgs();
    if (args != null) {
      result = args.getAbiGenerationMode().orElse(null);
    }
    if (result == null) {
      result = Objects.requireNonNull(getConfiguredCompilerFactory()).getAbiGenerationMode();
    }

    if (result == AbiGenerationMode.CLASS) {
      return result;
    }

    if (!shouldBuildSourceAbi() && !shouldBuildSourceOnlyAbi()) {
      return AbiGenerationMode.CLASS;
    }

    if (result != AbiGenerationMode.SOURCE
        && (!getSourceOnlyAbisAllowed() || !pluginsSupportSourceOnlyAbis())) {
      return AbiGenerationMode.SOURCE;
    }

    if (result == AbiGenerationMode.MIGRATING_TO_SOURCE_ONLY
        && !getConfiguredCompilerFactory().shouldMigrateToSourceOnlyAbi()) {
      return AbiGenerationMode.SOURCE;
    }

    if (result == AbiGenerationMode.SOURCE_ONLY
        && !getConfiguredCompilerFactory().shouldGenerateSourceOnlyAbi()) {
      return AbiGenerationMode.SOURCE;
    }

    return result;
  }

  @Value.Lazy
  SourceAbiVerificationMode getSourceAbiVerificationMode() {
    JavaBuckConfig javaBuckConfig = getJavaBuckConfig();
    CoreArg args = getArgs();
    SourceAbiVerificationMode result = null;

    if (args != null) {
      result = args.getSourceAbiVerificationMode().orElse(null);
    }
    if (result == null) {
      result =
          javaBuckConfig != null
              ? javaBuckConfig.getSourceAbiVerificationMode()
              : SourceAbiVerificationMode.OFF;
    }

    return result;
  }

  private boolean willProduceSourceAbi() {
    return willProduceAbiJar() && AbiGenerationModeUtils.isSourceAbi(getAbiGenerationMode())
        || willProduceSourceOnlyAbi() && sourceAbiIsAvailableIfSourceOnlyAbiIsAvailable();
  }

  private boolean willProduceSourceOnlyAbi() {
    return willProduceAbiJar() && AbiGenerationModeUtils.isSourceOnlyAbi(getAbiGenerationMode());
  }

  private boolean willProduceClassAbi() {
    return willProduceAbiJar()
        && ((!willProduceSourceAbi() && !willProduceSourceOnlyAbi()) || willProduceCompareAbis());
  }

  private boolean willProduceClassAbiPartFromLibraryTarget() {
    return willProduceClassAbi()
        && getConfiguredCompilerFactory().shouldProduceClassAbiPartFromLibraryTarget();
  }

  private boolean willProduceCompareAbis() {
    return willProduceSourceAbi()
        && getSourceAbiVerificationMode() != JavaBuckConfig.SourceAbiVerificationMode.OFF;
  }

  private boolean sourceAbiIsAvailableIfSourceOnlyAbiIsAvailable() {
    return getConfiguredCompilerFactory().sourceAbiIsAvailableIfSourceOnlyAbiIsAvailable();
  }

  private boolean shouldBuildSourceAbi() {
    return getConfiguredCompilerFactory().shouldGenerateSourceAbi() && !getSrcs().isEmpty();
  }

  private boolean shouldBuildSourceOnlyAbi() {
    return getConfiguredCompilerFactory().shouldGenerateSourceOnlyAbi() && !getSrcs().isEmpty();
  }

  private boolean pluginsSupportSourceOnlyAbis() {
    ImmutableList<ResolvedJavacPluginProperties> annotationProcessors =
        Objects.requireNonNull(getJavacOptions())
            .getJavaAnnotationProcessorParams()
            .getPluginProperties();

    for (ResolvedJavacPluginProperties annotationProcessor : annotationProcessors) {
      if (!annotationProcessor.getDoesNotAffectAbi()
          && !annotationProcessor.getSupportAbiGenerationFromSource()) {
        // Processor is ABI-affecting but cannot run during ABI generation from source; disallow
        return false;
      }
    }

    return true;
  }

  /**
   * Build a {@link DefaultJavaLibrary} similar to {@link #buildLibraryRule(CalculateSourceAbi)} but
   * with several important differences:
   *
   * <ul>
   *   <li>The built rule DOES NOT get added to the build graph proactively. This is crucial for
   *       ensuring that flavored targets are associated with Nullsafe build rule.
   *   <li>No ABI rules get created. Nullsafe flavored targets are used for analysis and reporting
   *       only.
   * </ul>
   *
   * See {@link Nullsafe} for more details.
   */
  public DefaultJavaLibrary buildLibraryForNullsafe() {
    UnusedDependenciesAction unusedDependenciesAction = getUnusedDependenciesAction();

    BuildTarget buildTarget = getLibraryTarget();
    ProjectFilesystem projectFilesystem = getProjectFilesystem();
    ActionGraphBuilder actionGraphBuilder = getActionGraphBuilder();
    JavaLibraryDeps javaLibraryDeps = Objects.requireNonNull(getDeps());
    ConfiguredCompilerFactory configuredCompilerFactory = getConfiguredCompilerFactory();

    Optional<UnusedDependenciesFinderFactory> unusedDependenciesFinderFactory = Optional.empty();

    CoreArg args = getArgs();

    return getConstructor()
        .newInstance(
            buildTarget,
            projectFilesystem,
            getJarBuildStepsFactory(),
            actionGraphBuilder,
            getProguardConfig(),
            javaLibraryDeps.getDeps(),
            javaLibraryDeps.getExportedDeps(),
            javaLibraryDeps.getProvidedDeps(),
            javaLibraryDeps.getExportedProvidedDeps(),
            javaLibraryDeps.getRuntimeDeps(),
            null,
            null,
            getMavenCoords(),
            getTests(),
            getRequiredForSourceOnlyAbi(),
            unusedDependenciesAction,
            unusedDependenciesFinderFactory,
            null,
            isDesugarRequired(),
            configuredCompilerFactory.shouldDesugarInterfaceMethods(),
            args != null && args.getNeverMarkAsUnusedDependency().orElse(false));
  }

  private DefaultJavaLibrary buildLibraryRule(@Nullable CalculateSourceAbi sourceAbiRule) {
    UnusedDependenciesAction unusedDependenciesAction = getUnusedDependenciesAction();

    BuildTarget buildTarget = getLibraryTarget();
    ProjectFilesystem projectFilesystem = getProjectFilesystem();
    ActionGraphBuilder actionGraphBuilder = getActionGraphBuilder();
    JavaLibraryDeps javaLibraryDeps = Objects.requireNonNull(getDeps());
    ConfiguredCompilerFactory configuredCompilerFactory = getConfiguredCompilerFactory();

    Optional<UnusedDependenciesFinderFactory> unusedDependenciesFinderFactory =
        getUnusedDependenciesFinderFactory(
            unusedDependenciesAction,
            actionGraphBuilder,
            javaLibraryDeps,
            configuredCompilerFactory);

    CoreArg args = getArgs();
    DefaultJavaLibrary libraryRule =
        getConstructor()
            .newInstance(
                buildTarget,
                projectFilesystem,
                getJarBuildStepsFactory(),
                actionGraphBuilder,
                getProguardConfig(),
                javaLibraryDeps.getDeps(),
                javaLibraryDeps.getExportedDeps(),
                javaLibraryDeps.getProvidedDeps(),
                javaLibraryDeps.getExportedProvidedDeps(),
                javaLibraryDeps.getRuntimeDeps(),
                getAbiJar(),
                getSourceOnlyAbiJar(),
                getMavenCoords(),
                getTests(),
                getRequiredForSourceOnlyAbi(),
                unusedDependenciesAction,
                unusedDependenciesFinderFactory,
                sourceAbiRule,
                isDesugarRequired(),
                configuredCompilerFactory.shouldDesugarInterfaceMethods(),
                args != null && args.getNeverMarkAsUnusedDependency().orElse(false));

    actionGraphBuilder.addToIndex(libraryRule);
    return libraryRule;
  }

  private Optional<UnusedDependenciesFinderFactory> getUnusedDependenciesFinderFactory(
      UnusedDependenciesAction unusedDependenciesAction,
      ActionGraphBuilder actionGraphBuilder,
      JavaLibraryDeps javaLibraryDeps,
      ConfiguredCompilerFactory configuredCompilerFactory) {
    if (isActionableUnusedDependenciesAction(unusedDependenciesAction)
        && configuredCompilerFactory.trackClassUsage(getJavacOptions())) {
      JavaBuckConfig javaBuckConfig = Objects.requireNonNull(getJavaBuckConfig());
      return Optional.of(
          new UnusedDependenciesFinderFactory(
              javaBuckConfig.getUnusedDependenciesBuildozerString(),
              javaBuckConfig.isUnusedDependenciesOnlyPrintCommands(),
              javaBuckConfig.isUnusedDependenciesUltralightChecking(),
              actionGraphBuilder,
              javaLibraryDeps.getDepTargets(),
              javaLibraryDeps.getProvidedDepTargets(),
              Objects.requireNonNull(javaLibraryDeps.getExportedDepTargets()).stream()
                  .map(bt -> bt.getUnconfiguredBuildTarget().toString())
                  .collect(ImmutableList.toImmutableList())));
    }
    return Optional.empty();
  }

  private boolean getRequiredForSourceOnlyAbi() {
    return getArgs() != null && getArgs().getRequiredForSourceOnlyAbi();
  }

  @Nullable
  private CalculateSourceAbi buildSourceOnlyAbiRule() {
    if (!willProduceSourceOnlyAbi()) {
      return null;
    }

    JarBuildStepsFactory<?> jarBuildStepsFactory = getJarBuildStepsFactoryForSourceOnlyAbi();

    BuildTarget libraryTarget = getLibraryTarget();
    BuildTarget sourceOnlyAbiTarget = JavaAbis.getSourceOnlyAbiJar(libraryTarget);
    ActionGraphBuilder graphBuilder = getActionGraphBuilder();
    ProjectFilesystem projectFilesystem = getProjectFilesystem();
    return graphBuilder.addToIndex(
        new CalculateSourceAbi(
            sourceOnlyAbiTarget, projectFilesystem, jarBuildStepsFactory, graphBuilder));
  }

  @Nullable
  private CalculateSourceAbi buildSourceAbiRule() {
    if (!willProduceSourceAbi()) {
      return null;
    }

    JarBuildStepsFactory<?> jarBuildStepsFactory = getJarBuildStepsFactory();

    BuildTarget libraryTarget = getLibraryTarget();
    BuildTarget sourceAbiTarget = JavaAbis.getSourceAbiJar(libraryTarget);

    ActionGraphBuilder graphBuilder = getActionGraphBuilder();
    ProjectFilesystem projectFilesystem = getProjectFilesystem();
    return graphBuilder.addToIndex(
        new CalculateSourceAbi(
            sourceAbiTarget, projectFilesystem, jarBuildStepsFactory, graphBuilder));
  }

  @Nullable
  private CalculateClassAbi buildClassAbiRule(DefaultJavaLibrary libraryRule) {
    if (!willProduceClassAbi()) {
      return null;
    }

    BuildTarget classAbiTarget = JavaAbis.getClassAbiJar(getLibraryTarget());
    return getActionGraphBuilder()
        .addToIndex(
            CalculateClassAbi.of(
                classAbiTarget,
                getActionGraphBuilder(),
                getProjectFilesystem(),
                libraryRule.getSourcePathToOutput(),
                getAbiCompatibilityMode(),
                willProduceClassAbiPartFromLibraryTarget()));
  }

  @Value.Lazy
  AbiGenerationMode getAbiCompatibilityMode() {
    return getJavaBuckConfig() == null
            || getJavaBuckConfig().getSourceAbiVerificationMode() == SourceAbiVerificationMode.OFF
        ? AbiGenerationMode.CLASS
        // Use the BuckConfig version (rather than the inferred one) because if any
        // targets are using source_only it can affect the output of other targets
        // in ways that are hard to simulate
        : getConfiguredCompilerFactory().getAbiGenerationMode();
  }

  @Value.Lazy
  DefaultJavaLibraryClasspaths getClasspaths() {
    return ImmutableDefaultJavaLibraryClasspaths.builder(getActionGraphBuilder())
        .setBuildRuleParams(getInitialParams())
        .setDeps(Objects.requireNonNull(getDeps()))
        .setCompileAgainstLibraryType(getCompileAgainstLibraryType())
        .build();
  }

  @Value.Lazy
  BaseCompileToJarStepFactory<?> getConfiguredCompiler() {
    return getConfiguredCompilerFactory()
        .configure(
            getArgs(),
            getJavacOptions(),
            getActionGraphBuilder(),
            getInitialBuildTarget().getTargetConfiguration(),
            getToolchainProvider());
  }

  @Value.Lazy
  Javac getJavac() {
    return getConfiguredCompilerFactory()
        .getJavac(
            getActionGraphBuilder(), getArgs(), getInitialBuildTarget().getTargetConfiguration());
  }

  @Value.Lazy
  BaseCompileToJarStepFactory<?> getConfiguredCompilerForSourceOnlyAbi(
      SourcePathResolverAdapter resolver, AbsPath ruleCellRoot) {
    return getConfiguredCompilerFactory()
        .configure(
            getArgs(),
            getJavacOptionsForSourceOnlyAbi(resolver, ruleCellRoot),
            getActionGraphBuilder(),
            getInitialBuildTarget().getTargetConfiguration(),
            getToolchainProvider());
  }

  @Value.Lazy
  JavacOptions getJavacOptionsForSourceOnlyAbi(
      SourcePathResolverAdapter resolver, AbsPath ruleCellRoot) {
    JavacOptions javacOptions = getJavacOptions();
    return javacOptions.withJavaAnnotationProcessorParams(
        abiProcessorsOnly(javacOptions.getJavaAnnotationProcessorParams(), resolver, ruleCellRoot));
  }

  private JavacPluginParams abiProcessorsOnly(
      JavacPluginParams annotationProcessingParams,
      SourcePathResolverAdapter resolver,
      AbsPath ruleCellRoot) {
    return annotationProcessingParams.withAbiProcessorsOnly(resolver, ruleCellRoot);
  }

  @Value.Lazy
  CompileAgainstLibraryType getCompileAgainstLibraryType() {
    return getConfiguredCompilerFactory().shouldCompileAgainstAbis()
        ? CompileAgainstLibraryType.ABI
        : CompileAgainstLibraryType.FULL;
  }

  @Value.Lazy
  JarBuildStepsFactory<?> getJarBuildStepsFactory() {
    JavacOptions javacOptions = getJavacOptions();

    DefaultJavaLibraryClasspaths classpaths = getClasspaths();
    return JarBuildStepsFactory.of(
        getLibraryTarget(),
        getConfiguredCompiler(),
        getJavac(),
        getSrcs(),
        getResources(),
        getResourcesParameters(),
        getManifestFile(),
        getConfiguredCompilerFactory().trackClassUsage(javacOptions),
        javacOptions.trackJavacPhaseEvents(),
        getClassesToRemoveFromJar(),
        getAbiGenerationMode(),
        getAbiCompatibilityMode(),
        classpaths.getDependencyInfos(),
        getRequiredForSourceOnlyAbi(),
        getDownwardApiConfig().isEnabledForJava(),
        getConfiguredCompilerFactory().getCDParams());
  }

  @Value.Lazy
  JarBuildStepsFactory<?> getJarBuildStepsFactoryForSourceOnlyAbi() {
    ProjectFilesystem projectFilesystem = getProjectFilesystem();
    JavacOptions javacOptions = getJavacOptions();
    return JarBuildStepsFactory.of(
        getLibraryTarget(),
        getConfiguredCompilerForSourceOnlyAbi(
            getSourcePathResolver(), projectFilesystem.getRootPath()),
        getJavac(),
        getSrcs(),
        getResources(),
        getResourcesParameters(),
        getManifestFile(),
        getConfiguredCompilerFactory().trackClassUsage(javacOptions),
        javacOptions.trackJavacPhaseEvents(),
        getClassesToRemoveFromJar(),
        getAbiGenerationMode(),
        getAbiCompatibilityMode(),
        getClasspaths().getDependencyInfosForSourceOnlyAbi(),
        getRequiredForSourceOnlyAbi(),
        getDownwardApiConfig().isEnabledForJava(),
        getConfiguredCompilerFactory().getCDParams());
  }

  private ResourcesParameters getResourcesParameters() {
    return ResourcesParameters.create(
        getProjectFilesystem(), getActionGraphBuilder(), getResources(), getResourcesRoot());
  }

  /**
   * This is a little complicated, but goes along the lines of: 1. If the buck config value is
   * "ignore_always", then ignore. 2. If the buck config value is "warn_if_fail", then downgrade a
   * local "fail" to "warn". 3. Use the local action if available. 4. Use the buck config value if
   * available. 5. Default to ignore.
   */
  private static UnusedDependenciesAction getUnusedDependenciesAction(
      @Nullable UnusedDependenciesConfig configAction,
      @Nullable JavaLibraryDescription.CoreArg args) {
    UnusedDependenciesAction localAction =
        args == null ? null : args.getOnUnusedDependencies().orElse(null);

    if (configAction == UnusedDependenciesConfig.IGNORE_ALWAYS) {
      return UnusedDependenciesAction.IGNORE;
    }

    if (configAction == UnusedDependenciesConfig.WARN_IF_FAIL
        && localAction == UnusedDependenciesAction.FAIL) {
      return UnusedDependenciesAction.WARN;
    }

    if (localAction != null) {
      return localAction;
    }

    if (configAction == UnusedDependenciesConfig.FAIL) {
      return UnusedDependenciesAction.FAIL;
    } else if (configAction == UnusedDependenciesConfig.WARN) {
      return UnusedDependenciesAction.WARN;
    } else {
      return UnusedDependenciesAction.IGNORE;
    }
  }

  @org.immutables.builder.Builder.AccessibleFields
  public static class Builder extends ImmutableDefaultJavaLibraryRules.Builder {

    public Builder(
        BuildTarget initialBuildTarget,
        ProjectFilesystem projectFilesystem,
        ToolchainProvider toolchainProvider,
        BuildRuleParams initialParams,
        ActionGraphBuilder graphBuilder,
        ConfiguredCompilerFactory configuredCompilerFactory,
        @Nullable JavaBuckConfig javaBuckConfig,
        DownwardApiConfig downwardApiConfig,
        @Nullable JavaLibraryDescription.CoreArg args,
        CellPathResolver cellPathResolver) {
      super(
          initialBuildTarget,
          projectFilesystem,
          toolchainProvider,
          initialParams,
          graphBuilder,
          configuredCompilerFactory,
          getUnusedDependenciesAction(
              configuredCompilerFactory.getUnusedDependenciesAction(), args),
          javaBuckConfig,
          cellPathResolver,
          downwardApiConfig,
          args);

      this.actionGraphBuilder = graphBuilder;

      if (args != null) {
        setSrcs(args.getSrcs())
            .setResources(args.getResources())
            .setResourcesRoot(args.getResourcesRoot())
            .setProguardConfig(args.getProguardConfig())
            .setDeps(
                JavaLibraryDeps.newInstance(
                    args,
                    graphBuilder,
                    initialBuildTarget.getTargetConfiguration(),
                    configuredCompilerFactory,
                    javaBuckConfig.isAddRuntimeDepsAsDeps()))
            .setTests(args.getTests())
            .setManifestFile(args.getManifestFile())
            .setMavenCoords(args.getMavenCoords())
            .setClassesToRemoveFromJar(new RemoveClassesPatternsMatcher(args.getRemoveClasses()));
      }
    }

    Builder() {
      throw new UnsupportedOperationException();
    }

    @Nullable
    public JavaLibraryDeps getDeps() {
      return deps;
    }
  }
}
