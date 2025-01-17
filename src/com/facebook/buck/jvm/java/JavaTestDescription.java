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

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.description.arg.HasDepsQuery;
import com.facebook.buck.core.description.arg.HasTestTimeout;
import com.facebook.buck.core.description.attr.ImplicitDepsInferringDescription;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.core.rules.impl.MappedSymlinkTree;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.test.rule.HasTestRunner;
import com.facebook.buck.core.test.rule.TestRunnerSpec;
import com.facebook.buck.core.test.rule.coercer.TestRunnerSpecCoercer;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.facebook.buck.cxx.toolchain.UnresolvedCxxPlatform;
import com.facebook.buck.downwardapi.config.DownwardApiConfig;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaAbis;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.jvm.java.toolchain.JavaCxxPlatformProvider;
import com.facebook.buck.jvm.java.toolchain.JavaOptionsProvider;
import com.facebook.buck.jvm.java.toolchain.JavacOptionsProvider;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.macros.AbsoluteOutputMacroExpander;
import com.facebook.buck.rules.macros.LocationMacroExpander;
import com.facebook.buck.rules.macros.Macro;
import com.facebook.buck.rules.macros.MacroExpander;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.rules.macros.StringWithMacrosConverter;
import com.facebook.buck.rules.query.Query;
import com.facebook.buck.test.config.TestBuckConfig;
import com.facebook.buck.util.stream.RichStream;
import com.facebook.buck.versions.VersionRoot;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Maps;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.stream.Stream;
import org.immutables.value.Value;

public class JavaTestDescription
    implements DescriptionWithTargetGraph<JavaTestDescriptionArg>,
        ImplicitDepsInferringDescription<JavaTestDescription.AbstractJavaTestDescriptionArg>,
        VersionRoot<JavaTestDescriptionArg> {

  public static final ImmutableList<MacroExpander<? extends Macro, ?>> MACRO_EXPANDERS =
      ImmutableList.of(LocationMacroExpander.INSTANCE, AbsoluteOutputMacroExpander.INSTANCE);

  public static final String SYMLINK_TREE_ENV_VAR = "BUCK_LD_SYMLINK_TREE";

  private final ToolchainProvider toolchainProvider;
  private final JavaBuckConfig javaBuckConfig;
  private final JavaCDBuckConfig javaCDBuckConfig;
  private final DownwardApiConfig downwardApiConfig;
  private final Function<TargetConfiguration, JavaOptions> javaOptionsForTests;
  private final Function<TargetConfiguration, JavaOptions> java11OptionsForTests;
  private final JavacFactory javacFactory;

  public JavaTestDescription(
      ToolchainProvider toolchainProvider,
      JavaBuckConfig javaBuckConfig,
      JavaCDBuckConfig javaCDBuckConfig,
      DownwardApiConfig downwardApiConfig) {
    this.toolchainProvider = toolchainProvider;
    this.javaBuckConfig = javaBuckConfig;
    this.javaCDBuckConfig = javaCDBuckConfig;
    this.downwardApiConfig = downwardApiConfig;
    this.javaOptionsForTests = JavaOptionsProvider.getDefaultJavaOptionsForTests(toolchainProvider);
    this.java11OptionsForTests =
        JavaOptionsProvider.getDefaultJava11OptionsForTests(toolchainProvider);
    this.javacFactory = JavacFactory.getDefault(toolchainProvider);
  }

  @Override
  public Class<JavaTestDescriptionArg> getConstructorArgType() {
    return JavaTestDescriptionArg.class;
  }

  private UnresolvedCxxPlatform getUnresolvedCxxPlatform(
      AbstractJavaTestDescriptionArg args, TargetConfiguration toolchainTargetConfiguration) {
    return args.getDefaultCxxPlatform()
        .map(
            toolchainProvider
                    .getByName(
                        CxxPlatformsProvider.DEFAULT_NAME,
                        toolchainTargetConfiguration,
                        CxxPlatformsProvider.class)
                    .getUnresolvedCxxPlatforms()
                ::getValue)
        .orElse(
            toolchainProvider
                .getByName(
                    JavaCxxPlatformProvider.DEFAULT_NAME,
                    toolchainTargetConfiguration,
                    JavaCxxPlatformProvider.class)
                .getDefaultJavaCxxPlatform());
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      JavaTestDescriptionArg args) {
    ActionGraphBuilder graphBuilder = context.getActionGraphBuilder();
    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();
    CellPathResolver cellRoots = context.getCellPathResolver();
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

    CxxLibraryEnhancement cxxLibraryEnhancement =
        new CxxLibraryEnhancement(
            buildTarget,
            projectFilesystem,
            params,
            args.getUseCxxLibraries(),
            args.getCxxLibraryWhitelist(),
            graphBuilder,
            getUnresolvedCxxPlatform(args, buildTarget.getTargetConfiguration())
                .resolve(graphBuilder, buildTarget.getTargetConfiguration()),
            javaBuckConfig.shouldAddBuckLDSymlinkTree());
    params = cxxLibraryEnhancement.updatedParams;

    DefaultJavaLibraryRules defaultJavaLibraryRules =
        DefaultJavaLibrary.rulesBuilder(
                buildTarget.withAppendedFlavors(JavaTest.COMPILED_TESTS_LIBRARY_FLAVOR),
                projectFilesystem,
                context.getToolchainProvider(),
                params,
                graphBuilder,
                new JavaConfiguredCompilerFactory(
                    javaBuckConfig, javaCDBuckConfig, downwardApiConfig, javacFactory),
                javaBuckConfig,
                downwardApiConfig,
                args,
                context.getCellPathResolver())
            .setJavacOptions(javacOptions)
            .setToolchainProvider(context.getToolchainProvider())
            .build();

    if (JavaAbis.isAbiTarget(buildTarget)) {
      return defaultJavaLibraryRules.buildAbi();
    }

    JavaLibrary testsLibrary = graphBuilder.addToIndex(defaultJavaLibraryRules.buildLibrary());
    params = params.copyAppendingExtraDeps(testsLibrary);

    StringWithMacrosConverter macrosConverter =
        StringWithMacrosConverter.of(
            buildTarget, cellRoots.getCellNameResolver(), graphBuilder, MACRO_EXPANDERS);
    List<Arg> vmArgs =
        args.getVmArgs().stream()
            .map(macrosConverter::convert)
            .collect(ImmutableList.toImmutableList());

    Function<TargetConfiguration, JavaOptions> javaRuntimeConfig =
        javacOptions.getLanguageLevelOptions().getTargetLevel().equals("11")
            ? java11OptionsForTests
            : javaOptionsForTests;
    JavaOptions javaOptions = javaRuntimeConfig.apply(buildTarget.getTargetConfiguration());

    Optional<BuildTarget> runner = args.getRunner();
    Optional<TestRunnerSpec> runnerSpecs = args.getSpecs();
    if (runnerSpecs.isPresent()) {
      JavaTestRunner testRunner;
      if (runner.isPresent()) {
        BuildRule runnerRule = graphBuilder.requireRule(runner.get());
        if (!(runnerRule instanceof JavaTestRunner)) {
          throw new HumanReadableException(
              "Java tests should have a java_test_runner as the runner for test protocol");
        }
        testRunner = (JavaTestRunner) runnerRule;

      } else {
        throw new HumanReadableException(
            "Java test should have a java_test_runner as the runner for test protocol");
      }

      params = params.copyAppendingExtraDeps(testRunner.getCompiledTestsLibrary());

      // Construct the build rule to build the binary JAR.
      ImmutableSet<JavaLibrary> transitiveClasspathDeps =
          JavaLibraryClasspathProvider.getClasspathDeps(params.getBuildDeps());
      ImmutableSet<SourcePath> transitiveClasspaths =
          JavaLibraryClasspathProvider.getClasspathsFromLibraries(transitiveClasspathDeps);
      JavaBinary javaBinary =
          new JavaBinary(
              buildTarget.withFlavors(InternalFlavor.of("bin")),
              projectFilesystem,
              params.copyAppendingExtraDeps(transitiveClasspathDeps),
              javaRuntimeConfig.apply(buildTarget.getTargetConfiguration()).getJavaRuntime(),
              testRunner.getMainClass(),
              args.getManifestFile().orElse(null),
              null,
              ImmutableSet.of(),
              transitiveClasspathDeps,
              transitiveClasspaths,
              javaBuckConfig.shouldCacheBinaries(),
              javaBuckConfig.getDuplicatesLogLevel(),
              false);

      graphBuilder.addToIndex(javaBinary);

      return new JavaTestX(
          buildTarget,
          projectFilesystem,
          params.copyAppendingExtraDeps(javaBinary),
          javaBinary,
          testsLibrary,
          args.getLabels(),
          args.getContacts(),
          TestRunnerSpecCoercer.coerce(args.getSpecs().get(), macrosConverter),
          vmArgs);
    } else if (runner.isPresent()) {
      throw new HumanReadableException("Should not have runner set when no specs are set");
    }

    return new JavaTest(
        buildTarget,
        projectFilesystem,
        params,
        testsLibrary,
        Optional.empty(),
        args.getLabels(),
        args.getContacts(),
        args.getTestType().orElse(TestType.JUNIT),
        javacOptions.getLanguageLevelOptions().getTargetLevel(),
        javaOptions.getJavaRuntime(),
        javaOptions.getJavaRuntimeVersion(),
        vmArgs,
        cxxLibraryEnhancement.nativeLibsEnvironment,
        cxxLibraryEnhancement.requiredPaths,
        args.getTestRuleTimeoutMs()
            .or(
                () ->
                    javaBuckConfig
                        .getDelegate()
                        .getView(TestBuckConfig.class)
                        .getDefaultTestRuleTimeoutMs()),
        args.getTestCaseTimeoutMs(),
        ImmutableMap.copyOf(Maps.transformValues(args.getEnv(), macrosConverter::convert)),
        args.getRunTestSeparately(),
        args.getForkMode(),
        args.getStdOutLogLevel(),
        args.getStdErrLogLevel(),
        args.getResources(),
        javaBuckConfig
            .getDelegate()
            .getView(TestBuckConfig.class)
            .useRelativePathsInClasspathFile(),
        downwardApiConfig.isEnabledForTests(),
        args.getUseDependencyOrderClasspath()
            .orElse(javaBuckConfig.useDependencyOrderClasspathForTests()));
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellNameResolver cellRoots,
      AbstractJavaTestDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    targetGraphOnlyDepsBuilder.addAll(
        getUnresolvedCxxPlatform(constructorArg, buildTarget.getTargetConfiguration())
            .getParseTimeDeps(buildTarget.getTargetConfiguration()));
    javacFactory.addParseTimeDeps(
        targetGraphOnlyDepsBuilder, constructorArg, buildTarget.getTargetConfiguration());
  }

  public interface CoreArg extends HasTestTimeout, HasDepsQuery, JavaLibraryDescription.CoreArg {
    ImmutableList<StringWithMacros> getVmArgs();

    Optional<TestType> getTestType();

    @Value.Default
    default boolean getRunTestSeparately() {
      return false;
    }

    @Value.Default
    default ForkMode getForkMode() {
      return ForkMode.NONE;
    }

    Optional<Level> getStdErrLogLevel();

    Optional<Level> getStdOutLogLevel();

    Optional<Boolean> getUseCxxLibraries();

    ImmutableSet<BuildTarget> getCxxLibraryWhitelist();

    Optional<Long> getTestCaseTimeoutMs();

    ImmutableMap<String, StringWithMacros> getEnv();

    Optional<Flavor> getDefaultCxxPlatform();

    Optional<Boolean> getUseDependencyOrderClasspath();
  }

  @RuleArg
  interface AbstractJavaTestDescriptionArg extends CoreArg, HasTestRunner {
    @Override
    default JavaTestDescriptionArg withDepsQuery(Query query) {
      if (getDepsQuery().equals(Optional.of(query))) {
        return (JavaTestDescriptionArg) this;
      }
      return JavaTestDescriptionArg.builder().from(this).setDepsQuery(query).build();
    }
  }

  public static class CxxLibraryEnhancement {
    public final BuildRuleParams updatedParams;
    public final ImmutableMap<String, String> nativeLibsEnvironment;
    public final ImmutableSet<Path> requiredPaths;

    public CxxLibraryEnhancement(
        BuildTarget buildTarget,
        ProjectFilesystem projectFilesystem,
        BuildRuleParams params,
        Optional<Boolean> useCxxLibraries,
        ImmutableSet<BuildTarget> cxxLibraryWhitelist,
        ActionGraphBuilder graphBuilder,
        CxxPlatform cxxPlatform,
        boolean isBuckLDSymLinkTreeSet) {
      if (useCxxLibraries.orElse(false)) {
        MappedSymlinkTree nativeLibsSymlinkTree =
            buildNativeLibsSymlinkTreeRule(
                buildTarget,
                projectFilesystem,
                graphBuilder,
                params.getBuildDeps(),
                cxxLibraryWhitelist,
                cxxPlatform);

        graphBuilder.addToIndex(nativeLibsSymlinkTree);
        updatedParams =
            params.copyAppendingExtraDeps(
                ImmutableList.<BuildRule>builder()
                    .add(nativeLibsSymlinkTree)
                    // Add all the native libraries as first-order dependencies.
                    // This has two effects:
                    // (1) They become runtime deps because JavaTest adds all first-order deps.
                    // (2) They affect the JavaTest's RuleKey, so changing them will invalidate
                    // the test results cache.
                    .addAll(
                        graphBuilder.filterBuildRuleInputs(
                            nativeLibsSymlinkTree.getLinks().values()))
                    .build());

        ImmutableSortedMap<Path, SourcePath> links = nativeLibsSymlinkTree.getLinks();
        Stream<Path> symlinkKeys =
            links.keySet().stream().map(path -> nativeLibsSymlinkTree.getRoot().resolve(path));
        Stream<Path> symlinkValues =
            links.values().stream()
                .map(
                    sourcePath ->
                        graphBuilder.getSourcePathResolver().getAbsolutePath(sourcePath).getPath());
        requiredPaths =
            Stream.concat(symlinkKeys, symlinkValues).collect(ImmutableSet.toImmutableSet());

        ImmutableMap.Builder<String, String> nativeLibsEnvMapBuilder =
            new ImmutableMap.Builder<String, String>()
                .put(
                    cxxPlatform
                        .getLd()
                        .resolve(graphBuilder, buildTarget.getTargetConfiguration())
                        .searchPathEnvVar(),
                    nativeLibsSymlinkTree.getRoot().toString());
        if (isBuckLDSymLinkTreeSet) {
          nativeLibsEnvMapBuilder.put(
              SYMLINK_TREE_ENV_VAR, nativeLibsSymlinkTree.getRoot().toString());
        }
        nativeLibsEnvironment = nativeLibsEnvMapBuilder.build();
      } else {
        updatedParams = params;
        nativeLibsEnvironment = ImmutableMap.of();
        requiredPaths = ImmutableSet.of();
      }
    }

    public static MappedSymlinkTree buildNativeLibsSymlinkTreeRule(
        BuildTarget buildTarget,
        ProjectFilesystem projectFilesystem,
        ActionGraphBuilder graphBuilder,
        SortedSet<BuildRule> buildDeps,
        ImmutableSet<BuildTarget> cxxLibraryWhitelist,
        CxxPlatform cxxPlatform) {
      Set<BuildRule> roots = buildDeps;
      // TODO(cjhopman): The behavior of this doesn't really make sense. This should use a
      // packageable interface and some sort of proper logic for finding native libraries. Currently
      // this includes native libraries contained within the second-order dependency set only.
      Function<BuildRule, Optional<Iterable<? extends BuildRule>>> passthroughFunc =
          r ->
              r instanceof JavaLibrary
                  ? Optional.of(
                      buildDeps.contains(r)
                          ? ((JavaLibrary) r).getDepsForTransitiveClasspathEntries()
                          : ImmutableList.of())
                  : Optional.empty();

      // If a whitelist is specified, use it as the roots, and don't pass through Java rules.
      if (!cxxLibraryWhitelist.isEmpty()) {
        passthroughFunc = r -> Optional.empty();
        roots =
            RichStream.from(cxxLibraryWhitelist).map(graphBuilder::requireRule).toImmutableSet();
      }

      return CxxDescriptionEnhancer.createSharedLibrarySymlinkTree(
          buildTarget, projectFilesystem, graphBuilder, cxxPlatform, roots, passthroughFunc);
    }
  }
}
