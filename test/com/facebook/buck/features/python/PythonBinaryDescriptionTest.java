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

package com.facebook.buck.features.python;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeThat;

import com.facebook.buck.core.build.buildable.context.FakeBuildableContext;
import com.facebook.buck.core.build.context.FakeBuildContext;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.OutputLabel;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.UnconfiguredTargetConfiguration;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.common.BuildRules;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.SourceWithFlags;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.toolchain.impl.ToolchainProviderBuilder;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.tool.impl.CommandTool;
import com.facebook.buck.core.toolchain.tool.impl.HashedFileTool;
import com.facebook.buck.cxx.CxxBinaryBuilder;
import com.facebook.buck.cxx.CxxLibraryBuilder;
import com.facebook.buck.cxx.CxxLink;
import com.facebook.buck.cxx.Omnibus;
import com.facebook.buck.cxx.PrebuiltCxxLibraryBuilder;
import com.facebook.buck.cxx.config.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.cxx.toolchain.UnresolvedCxxPlatform;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkStrategy;
import com.facebook.buck.features.python.toolchain.PexToolProvider;
import com.facebook.buck.features.python.toolchain.PythonEnvironment;
import com.facebook.buck.features.python.toolchain.PythonPlatform;
import com.facebook.buck.features.python.toolchain.PythonVersion;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.AllExistingProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.coercer.SourceSortedSet;
import com.facebook.buck.rules.keys.DefaultRuleKeyFactory;
import com.facebook.buck.rules.keys.RuleKeyFieldLoader;
import com.facebook.buck.rules.keys.config.TestRuleKeyConfigurationFactory;
import com.facebook.buck.rules.macros.LocationMacro;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.rules.macros.StringWithMacrosUtils;
import com.facebook.buck.shell.Genrule;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.shell.ShBinary;
import com.facebook.buck.shell.ShBinaryBuilder;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.cache.FileHashCacheMode;
import com.facebook.buck.util.cache.impl.StackedFileHashCache;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.types.Pair;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.junit.Test;

public class PythonBinaryDescriptionTest {

  private static final BuildTarget PYTHON2_DEP_TARGET =
      BuildTargetFactory.newInstance("//:python2_dep");
  private static final PythonPlatform PY2 =
      new TestPythonPlatform(
          InternalFlavor.of("py2"),
          new PythonEnvironment(
              Paths.get("python2"),
              PythonVersion.of("CPython", "2.6"),
              PythonBuckConfig.SECTION,
              UnconfiguredTargetConfiguration.INSTANCE),
          Optional.of(PYTHON2_DEP_TARGET));

  @Test
  public void thatComponentSourcePathDepsPropagateProperly() {
    GenruleBuilder genruleBuilder =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:gen"))
            .setOut("blah.py");
    PythonLibraryBuilder libBuilder =
        new PythonLibraryBuilder(BuildTargetFactory.newInstance("//:lib"))
            .setSrcs(
                SourceSortedSet.ofUnnamedSources(
                    ImmutableSortedSet.of(
                        DefaultBuildTargetSourcePath.of(genruleBuilder.getTarget()))));
    PythonBinaryBuilder binaryBuilder =
        PythonBinaryBuilder.create(BuildTargetFactory.newInstance("//:bin"))
            .setMainModule("main")
            .setDeps(ImmutableSortedSet.of(libBuilder.getTarget()));

    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(
            genruleBuilder.build(), libBuilder.build(), binaryBuilder.build());
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);

    Genrule genrule = genruleBuilder.build(graphBuilder, filesystem, targetGraph);
    libBuilder.build(graphBuilder, filesystem, targetGraph);
    PythonBinary binary = binaryBuilder.build(graphBuilder, filesystem, targetGraph);
    assertThat(binary.getBuildDeps(), hasItem(genrule));
  }

  @Test
  public void thatMainSourcePathPropagatesToDeps() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    Genrule genrule =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:gen"))
            .setOut("blah.py")
            .build(graphBuilder);
    PythonBinary binary =
        PythonBinaryBuilder.create(BuildTargetFactory.newInstance("//:bin"))
            .setMain(genrule.getSourcePathToOutput())
            .build(graphBuilder);
    assertThat(binary.getBuildDeps(), hasItem(genrule));
  }

  @Test
  public void baseModule() throws IOException {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bin");
    String sourceName = "main.py";
    SourcePath source = FakeSourcePath.of("foo/" + sourceName);

    // Run without a base module set and verify it defaults to using the build target
    // base name.
    PythonBinary normal =
        PythonBinaryBuilder.create(target).setMain(source).build(new TestActionGraphBuilder());
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    assertThat(
        normal
            .getComponents()
            .resolve(graphBuilder.getSourcePathResolver())
            .getAllModules()
            .keySet(),
        hasItem(
            target
                .getCellRelativeBasePath()
                .getPath()
                .toPathDefaultFileSystem()
                .resolve(sourceName)));

    // Run *with* a base module set and verify it gets used to build the main module path.
    String baseModule = "blah";
    PythonBinary withBaseModule =
        PythonBinaryBuilder.create(target)
            .setMain(source)
            .setBaseModule(baseModule)
            .build(new TestActionGraphBuilder());
    assertThat(
        withBaseModule
            .getComponents()
            .resolve(graphBuilder.getSourcePathResolver())
            .getAllModules()
            .keySet(),
        hasItem(Paths.get(baseModule).resolve(sourceName)));
  }

  @Test
  public void mainModule() {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bin");
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    String mainModule = "foo.main";
    PythonBinary binary =
        PythonBinaryBuilder.create(target).setMainModule(mainModule).build(graphBuilder);
    assertThat(mainModule, equalTo(binary.getMainModule()));
  }

  @Test
  public void extensionConfig() {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bin");
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    PythonBuckConfig config =
        new PythonBuckConfig(
            FakeBuckConfig.builder()
                .setSections(
                    ImmutableMap.of(
                        "python", ImmutableMap.of("pex_extension", ".different_extension")))
                .build());
    PythonBinaryBuilder builder =
        PythonBinaryBuilder.create(target, config, PythonTestUtils.PYTHON_PLATFORMS);
    PythonBinary binary = builder.setMainModule("main").build(graphBuilder);
    assertThat(
        graphBuilder
            .getSourcePathResolver()
            .getCellUnsafeRelPath(Objects.requireNonNull(binary.getSourcePathToOutput()))
            .toString(),
        endsWith(".different_extension"));
  }

  @Test
  public void extensionParameter() {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bin");
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    PythonBinaryBuilder builder = PythonBinaryBuilder.create(target);
    PythonBinary binary =
        builder.setMainModule("main").setExtension(".different_extension").build(graphBuilder);
    assertThat(
        graphBuilder
            .getSourcePathResolver()
            .getCellUnsafeRelPath(Objects.requireNonNull(binary.getSourcePathToOutput()))
            .toString(),
        endsWith(".different_extension"));
  }

  @Test
  public void buildArgs() {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bin");
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    GenruleBuilder genruleBuilder =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:gen"))
            .setOut("out.txt");
    Genrule genrule = genruleBuilder.build(graphBuilder);
    PythonBinary binary =
        PythonBinaryBuilder.create(target)
            .setMainModule("main")
            .setBuildArgs(
                ImmutableList.of(
                    StringWithMacros.ofConstantString("--foo"),
                    StringWithMacrosUtils.format(
                        "--arg=%s", LocationMacro.of(genruleBuilder.getTarget()))))
            .build(graphBuilder);
    assertThat(binary.getBuildDeps(), hasItem(genrule));
    ImmutableList<? extends Step> buildSteps =
        binary.getBuildSteps(
            FakeBuildContext.withSourcePathResolver(graphBuilder.getSourcePathResolver()),
            new FakeBuildableContext());
    PexStep pexStep = FluentIterable.from(buildSteps).filter(PexStep.class).get(0);
    assertThat(
        pexStep.getCommandPrefix(),
        hasItems(
            "--foo",
            "--arg="
                + graphBuilder
                    .getSourcePathResolver()
                    .getAbsolutePath(genrule.getSourcePathToOutput())));
  }

  @Test
  public void explicitPythonHome() {
    PythonPlatform platform1 =
        new TestPythonPlatform(
            InternalFlavor.of("pyPlat1"),
            new PythonEnvironment(
                Paths.get("python2.6"),
                PythonVersion.of("CPython", "2.6.9"),
                PythonBuckConfig.SECTION,
                UnconfiguredTargetConfiguration.INSTANCE),
            Optional.empty());
    PythonPlatform platform2 =
        new TestPythonPlatform(
            InternalFlavor.of("pyPlat2"),
            new PythonEnvironment(
                Paths.get("python2.7"),
                PythonVersion.of("CPython", "2.7.11"),
                PythonBuckConfig.SECTION,
                UnconfiguredTargetConfiguration.INSTANCE),
            Optional.empty());
    PythonBinaryBuilder builder =
        PythonBinaryBuilder.create(
            BuildTargetFactory.newInstance("//:bin"),
            FlavorDomain.of("Python Platform", platform1, platform2));
    builder.setMainModule("main");
    PythonBinary binary1 =
        builder.setPlatform(platform1.getFlavor().toString()).build(new TestActionGraphBuilder());
    assertThat(binary1.getPythonPlatform(), equalTo(platform1));
    PythonBinary binary2 =
        builder.setPlatform(platform2.getFlavor().toString()).build(new TestActionGraphBuilder());
    assertThat(binary2.getPythonPlatform(), equalTo(platform2));
  }

  @Test
  public void runtimeDepOnDeps() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    for (PythonBuckConfig.PackageStyle packageStyle : PythonBuckConfig.PackageStyle.values()) {
      CxxBinaryBuilder cxxBinaryBuilder =
          new CxxBinaryBuilder(BuildTargetFactory.newInstance("//:dep"));
      PythonLibraryBuilder pythonLibraryBuilder =
          new PythonLibraryBuilder(BuildTargetFactory.newInstance("//:lib"))
              .setSrcs(
                  SourceSortedSet.ofUnnamedSources(
                      ImmutableSortedSet.of(FakeSourcePath.of("something.py"))))
              .setDeps(ImmutableSortedSet.of(cxxBinaryBuilder.getTarget()));
      PythonBinaryBuilder pythonBinaryBuilder =
          PythonBinaryBuilder.create(BuildTargetFactory.newInstance("//:bin"))
              .setMainModule("main")
              .setDeps(ImmutableSortedSet.of(pythonLibraryBuilder.getTarget()))
              .setPackageStyle(packageStyle);
      TargetGraph targetGraph =
          TargetGraphFactory.newInstance(
              cxxBinaryBuilder.build(), pythonLibraryBuilder.build(), pythonBinaryBuilder.build());
      ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);
      BuildRule cxxBinary = cxxBinaryBuilder.build(graphBuilder, filesystem, targetGraph);
      pythonLibraryBuilder.build(graphBuilder, filesystem, targetGraph);
      PythonBinary pythonBinary = pythonBinaryBuilder.build(graphBuilder, filesystem, targetGraph);
      assertThat(
          String.format(
              "Transitive runtime deps of %s [%s]", pythonBinary, packageStyle.toString()),
          BuildRules.getTransitiveRuntimeDeps(pythonBinary, graphBuilder),
          hasItem(cxxBinary.getBuildTarget()));
    }
  }

  @Test
  public void executableCommandWithPathToPexExecutor() {
    assumeThat(Platform.detect(), not(Platform.WINDOWS));

    BuildTarget target = BuildTargetFactory.newInstance("//foo:bin");
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolverAdapter pathResolver = graphBuilder.getSourcePathResolver();
    Path executor = Paths.get("/root/executor");
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    PythonBuckConfig config =
        new PythonBuckConfig(FakeBuckConfig.empty()) {
          @Override
          public Optional<Tool> getPexExecutor(
              BuildRuleResolver resolver, TargetConfiguration targetConfiguration) {
            return Optional.of(new HashedFileTool(PathSourcePath.of(filesystem, executor)));
          }
        };
    PythonBinaryBuilder builder =
        PythonBinaryBuilder.create(target, config, PythonTestUtils.PYTHON_PLATFORMS);
    PythonPackagedBinary binary =
        (PythonPackagedBinary) builder.setMainModule("main").build(graphBuilder);
    assertThat(
        binary.getExecutableCommand(OutputLabel.defaultLabel()).getCommandPrefix(pathResolver),
        contains(
            executor.toString(),
            pathResolver.getAbsolutePath(binary.getSourcePathToOutput()).toString()));
  }

  @Test
  public void executableCommandWithNoPathToPexExecutor() {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bin");
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolverAdapter pathResolver = graphBuilder.getSourcePathResolver();
    PythonPackagedBinary binary =
        (PythonPackagedBinary)
            PythonBinaryBuilder.create(target).setMainModule("main").build(graphBuilder);
    assertThat(
        binary.getExecutableCommand(OutputLabel.defaultLabel()).getCommandPrefix(pathResolver),
        contains(
            PythonTestUtils.PYTHON_PLATFORM.getEnvironment().getPythonPath().toString(),
            pathResolver.getAbsolutePath(binary.getSourcePathToOutput()).toString()));
  }

  @Test
  public void packagedBinaryAttachedPexToolDeps() {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bin");
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    final Genrule pexTool =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:pex_tool"))
            .setOut("pex-tool")
            .build(graphBuilder);
    PythonBuckConfig config = new PythonBuckConfig(FakeBuckConfig.empty());
    PexToolProvider pexToolProvider =
        (__, ___) ->
            new CommandTool.Builder()
                .addArg(SourcePathArg.of(pexTool.getSourcePathToOutput()))
                .build();
    PythonBinaryBuilder builder =
        PythonBinaryBuilder.create(
            target,
            config,
            new ToolchainProviderBuilder()
                .withToolchain(PexToolProvider.DEFAULT_NAME, pexToolProvider),
            PythonTestUtils.PYTHON_PLATFORMS);
    PythonPackagedBinary binary =
        (PythonPackagedBinary) builder.setMainModule("main").build(graphBuilder);
    assertThat(binary.getBuildDeps(), hasItem(pexTool));
  }

  @Test
  public void transitiveNativeDepsUsingMergedNativeLinkStrategy() throws IOException {
    CxxLibraryBuilder transitiveCxxDepBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:transitive_dep"))
            .setSrcs(
                ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of("transitive_dep.c"))));
    CxxLibraryBuilder cxxDepBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of("dep.c"))))
            .setDeps(ImmutableSortedSet.of(transitiveCxxDepBuilder.getTarget()));
    CxxLibraryBuilder cxxBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:cxx"))
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of("cxx.c"))))
            .setDeps(ImmutableSortedSet.of(cxxDepBuilder.getTarget()));

    PythonBuckConfig config =
        new PythonBuckConfig(FakeBuckConfig.empty()) {
          @Override
          public NativeLinkStrategy getNativeLinkStrategy() {
            return NativeLinkStrategy.MERGED;
          }
        };
    PythonBinaryBuilder binaryBuilder =
        PythonBinaryBuilder.create(
            BuildTargetFactory.newInstance("//:bin"), config, PythonTestUtils.PYTHON_PLATFORMS);
    binaryBuilder.setMainModule("main");
    binaryBuilder.setDeps(ImmutableSortedSet.of(cxxBuilder.getTarget()));

    ActionGraphBuilder graphBuilder =
        new TestActionGraphBuilder(
            TargetGraphFactory.newInstance(
                transitiveCxxDepBuilder.build(),
                cxxDepBuilder.build(),
                cxxBuilder.build(),
                binaryBuilder.build()));
    transitiveCxxDepBuilder.build(graphBuilder);
    cxxDepBuilder.build(graphBuilder);
    cxxBuilder.build(graphBuilder);
    PythonBinary binary = binaryBuilder.build(graphBuilder);
    assertThat(
        Iterables.transform(
            binary
                .getComponents()
                .resolve(graphBuilder.getSourcePathResolver())
                .getAllNativeLibraries()
                .keySet(),
            Object::toString),
        containsInAnyOrder("libomnibus.so", "libcxx.so"));
  }

  @Test
  public void transitiveNativeDepsUsingSeparateNativeLinkStrategy() throws IOException {
    CxxLibraryBuilder transitiveCxxDepBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:transitive_dep"))
            .setSrcs(
                ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of("transitive_dep.c"))));
    CxxLibraryBuilder cxxDepBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of("dep.c"))))
            .setDeps(ImmutableSortedSet.of(transitiveCxxDepBuilder.getTarget()));
    CxxLibraryBuilder cxxBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:cxx"))
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of("cxx.c"))))
            .setDeps(ImmutableSortedSet.of(cxxDepBuilder.getTarget()));

    PythonBuckConfig config =
        new PythonBuckConfig(FakeBuckConfig.empty()) {
          @Override
          public NativeLinkStrategy getNativeLinkStrategy() {
            return NativeLinkStrategy.SEPARATE;
          }
        };
    PythonBinaryBuilder binaryBuilder =
        PythonBinaryBuilder.create(
            BuildTargetFactory.newInstance("//:bin"), config, PythonTestUtils.PYTHON_PLATFORMS);
    binaryBuilder.setMainModule("main");
    binaryBuilder.setDeps(ImmutableSortedSet.of(cxxBuilder.getTarget()));

    ActionGraphBuilder graphBuilder =
        new TestActionGraphBuilder(
            TargetGraphFactory.newInstance(
                transitiveCxxDepBuilder.build(),
                cxxDepBuilder.build(),
                cxxBuilder.build(),
                binaryBuilder.build()));
    transitiveCxxDepBuilder.build(graphBuilder);
    cxxDepBuilder.build(graphBuilder);
    cxxBuilder.build(graphBuilder);
    PythonBinary binary = binaryBuilder.build(graphBuilder);
    assertThat(
        Iterables.transform(
            binary
                .getComponents()
                .resolve(graphBuilder.getSourcePathResolver())
                .getAllNativeLibraries()
                .keySet(),
            Object::toString),
        containsInAnyOrder("libtransitive_dep.so", "libdep.so", "libcxx.so"));
  }

  @Test
  public void extensionDepUsingMergedNativeLinkStrategy() throws IOException {
    FlavorDomain<PythonPlatform> pythonPlatforms = FlavorDomain.of("Python Platform", PY2);

    PrebuiltCxxLibraryBuilder python2Builder =
        new PrebuiltCxxLibraryBuilder(PYTHON2_DEP_TARGET)
            .setProvided(true)
            .setSharedLib(FakeSourcePath.of("libpython2.so"));

    CxxPythonExtensionBuilder extensionBuilder =
        new CxxPythonExtensionBuilder(
            BuildTargetFactory.newInstance("//:extension"),
            pythonPlatforms,
            new CxxBuckConfig(FakeBuckConfig.empty()),
            CxxPlatformUtils.DEFAULT_PLATFORMS);
    extensionBuilder.setBaseModule("hello");

    PythonBuckConfig config =
        new PythonBuckConfig(FakeBuckConfig.empty()) {
          @Override
          public NativeLinkStrategy getNativeLinkStrategy() {
            return NativeLinkStrategy.MERGED;
          }
        };
    PythonBinaryBuilder binaryBuilder =
        PythonBinaryBuilder.create(
            BuildTargetFactory.newInstance("//:bin"), config, pythonPlatforms);
    binaryBuilder.setMainModule("main");
    binaryBuilder.setDeps(ImmutableSortedSet.of(extensionBuilder.getTarget()));

    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(
            python2Builder.build(), extensionBuilder.build(), binaryBuilder.build());
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);

    python2Builder.build(graphBuilder, filesystem, targetGraph);
    extensionBuilder.build(graphBuilder, filesystem, targetGraph);
    PythonBinary binary = binaryBuilder.build(graphBuilder, filesystem, targetGraph);

    assertThat(binary.getComponents().getNativeLibraries().getComponents().keySet(), empty());
    assertThat(
        Iterables.transform(
            binary
                .getComponents()
                .resolve(graphBuilder.getSourcePathResolver())
                .getAllModules()
                .keySet(),
            Object::toString),
        containsInAnyOrder(MorePaths.pathWithPlatformSeparators("hello/extension.so")));
  }

  @Test
  public void transitiveDepsOfLibsWithPrebuiltNativeLibsAreNotIncludedInMergedLink()
      throws IOException {
    CxxLibraryBuilder transitiveCxxLibraryBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:transitive_cxx"))
            .setSrcs(
                ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of("transitive_cxx.c"))));
    CxxLibraryBuilder cxxLibraryBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:cxx"))
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of("cxx.c"))))
            .setDeps(ImmutableSortedSet.of(transitiveCxxLibraryBuilder.getTarget()));
    PythonLibraryBuilder pythonLibraryBuilder =
        new PythonLibraryBuilder(BuildTargetFactory.newInstance("//:lib"))
            .setSrcs(
                SourceSortedSet.ofUnnamedSources(
                    ImmutableSortedSet.of(FakeSourcePath.of("prebuilt.so"))))
            .setDeps(ImmutableSortedSet.of(cxxLibraryBuilder.getTarget()));
    PythonBuckConfig config =
        new PythonBuckConfig(FakeBuckConfig.empty()) {
          @Override
          public NativeLinkStrategy getNativeLinkStrategy() {
            return NativeLinkStrategy.MERGED;
          }
        };
    PythonBinaryBuilder pythonBinaryBuilder =
        PythonBinaryBuilder.create(
            BuildTargetFactory.newInstance("//:bin"), config, PythonTestUtils.PYTHON_PLATFORMS);
    pythonBinaryBuilder.setMainModule("main");
    pythonBinaryBuilder.setDeps(ImmutableSortedSet.of(pythonLibraryBuilder.getTarget()));
    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(
            transitiveCxxLibraryBuilder.build(),
            cxxLibraryBuilder.build(),
            pythonLibraryBuilder.build(),
            pythonBinaryBuilder.build());
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);
    transitiveCxxLibraryBuilder.build(graphBuilder, filesystem, targetGraph);
    cxxLibraryBuilder.build(graphBuilder, filesystem, targetGraph);
    pythonLibraryBuilder.build(graphBuilder, filesystem, targetGraph);
    PythonBinary binary = pythonBinaryBuilder.build(graphBuilder, filesystem, targetGraph);
    assertThat(
        Iterables.transform(
            binary
                .getComponents()
                .resolve(graphBuilder.getSourcePathResolver())
                .getAllNativeLibraries()
                .keySet(),
            Object::toString),
        hasItem("libtransitive_cxx.so"));
  }

  @Test
  public void packageStyleParam() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    PythonBinary pythonBinary =
        PythonBinaryBuilder.create(BuildTargetFactory.newInstance("//:bin"))
            .setMainModule("main")
            .setPackageStyle(PythonBuckConfig.PackageStyle.INPLACE)
            .build(graphBuilder);
    assertThat(pythonBinary, instanceOf(PythonInPlaceBinary.class));
    graphBuilder = new TestActionGraphBuilder();
    pythonBinary =
        PythonBinaryBuilder.create(BuildTargetFactory.newInstance("//:bin"))
            .setMainModule("main")
            .setPackageStyle(PythonBuckConfig.PackageStyle.STANDALONE)
            .build(graphBuilder);
    assertThat(pythonBinary, instanceOf(PythonPackagedBinary.class));
  }

  @Test
  public void preloadLibraries() throws IOException {
    for (NativeLinkStrategy strategy : NativeLinkStrategy.values()) {
      CxxLibraryBuilder cxxLibraryBuilder =
          new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:dep"))
              .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of("test.c"))));
      PythonBuckConfig config =
          new PythonBuckConfig(FakeBuckConfig.empty()) {
            @Override
            public NativeLinkStrategy getNativeLinkStrategy() {
              return strategy;
            }
          };
      PythonBinaryBuilder binaryBuilder =
          PythonBinaryBuilder.create(
              BuildTargetFactory.newInstance("//:bin"), config, PythonTestUtils.PYTHON_PLATFORMS);
      binaryBuilder.setMainModule("main");
      binaryBuilder.setPreloadDeps(ImmutableSortedSet.of(cxxLibraryBuilder.getTarget()));
      ActionGraphBuilder graphBuilder =
          new TestActionGraphBuilder(
              TargetGraphFactory.newInstance(cxxLibraryBuilder.build(), binaryBuilder.build()));
      cxxLibraryBuilder.build(graphBuilder);
      PythonBinary binary = binaryBuilder.build(graphBuilder);
      assertThat("Using " + strategy, binary.getPreloadLibraries(), hasItems("libdep.so"));
      assertThat(
          "Using " + strategy,
          binary
              .getComponents()
              .resolve(graphBuilder.getSourcePathResolver())
              .getAllNativeLibraries()
              .keySet(),
          hasItems(Paths.get("libdep.so")));
    }
  }

  @Test
  public void pexExecutorRuleIsAddedToParseTimeDeps() {
    ShBinaryBuilder pexExecutorBuilder =
        new ShBinaryBuilder(BuildTargetFactory.newInstance("//:pex_executor"))
            .setMain(FakeSourcePath.of("run.sh"));
    PythonBinaryBuilder builder =
        PythonBinaryBuilder.create(
            BuildTargetFactory.newInstance("//:bin"),
            new PythonBuckConfig(
                FakeBuckConfig.builder()
                    .setSections(
                        ImmutableMap.of(
                            "python",
                            ImmutableMap.of(
                                "path_to_pex_executer", pexExecutorBuilder.getTarget().toString())))
                    .build()),
            PythonTestUtils.PYTHON_PLATFORMS);
    builder.setMainModule("main").setPackageStyle(PythonBuckConfig.PackageStyle.STANDALONE);
    assertThat(builder.build().getExtraDeps(), hasItem(pexExecutorBuilder.getTarget()));
  }

  @Test
  public void linkerFlagsUsingMergedNativeLinkStrategy() {
    CxxLibraryBuilder cxxDepBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of("dep.c"))));
    CxxLibraryBuilder cxxBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:cxx"))
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of("cxx.c"))))
            .setDeps(ImmutableSortedSet.of(cxxDepBuilder.getTarget()));

    PythonBuckConfig config =
        new PythonBuckConfig(FakeBuckConfig.empty()) {
          @Override
          public NativeLinkStrategy getNativeLinkStrategy() {
            return NativeLinkStrategy.MERGED;
          }
        };
    PythonBinaryBuilder binaryBuilder =
        PythonBinaryBuilder.create(
            BuildTargetFactory.newInstance("//:bin"), config, PythonTestUtils.PYTHON_PLATFORMS);
    binaryBuilder.setLinkerFlags(ImmutableList.of(StringWithMacrosUtils.format("-flag")));
    binaryBuilder.setMainModule("main");
    binaryBuilder.setDeps(ImmutableSortedSet.of(cxxBuilder.getTarget()));

    ActionGraphBuilder graphBuilder =
        new TestActionGraphBuilder(
            TargetGraphFactory.newInstance(
                cxxDepBuilder.build(), cxxBuilder.build(), binaryBuilder.build()));
    cxxDepBuilder.build(graphBuilder);
    cxxBuilder.build(graphBuilder);
    binaryBuilder.build(graphBuilder);
    CxxLink link =
        graphBuilder.getRuleWithType(
            binaryBuilder.getTarget().withAppendedFlavors(Omnibus.OMNIBUS_FLAVOR), CxxLink.class);
    assertThat(
        Arg.stringify(link.getArgs(), graphBuilder.getSourcePathResolver()), hasItem("-flag"));
  }

  @Test
  public void explicitDepOnlinkWholeLibPullsInSharedLibrary() throws IOException {
    for (NativeLinkStrategy strategy : NativeLinkStrategy.values()) {
      ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
      CxxLibraryBuilder cxxLibraryBuilder =
          new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:dep1"))
              .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of("test.c"))))
              .setForceStatic(true);
      PrebuiltCxxLibraryBuilder prebuiltCxxLibraryBuilder =
          new PrebuiltCxxLibraryBuilder(BuildTargetFactory.newInstance("//:dep2"))
              .setStaticLib(FakeSourcePath.of("libdep2.a"))
              .setForceStatic(true);
      PythonBuckConfig config =
          new PythonBuckConfig(FakeBuckConfig.empty()) {
            @Override
            public NativeLinkStrategy getNativeLinkStrategy() {
              return strategy;
            }
          };
      PythonBinaryBuilder binaryBuilder =
          PythonBinaryBuilder.create(
              BuildTargetFactory.newInstance("//:bin"), config, PythonTestUtils.PYTHON_PLATFORMS);
      binaryBuilder.setMainModule("main");
      binaryBuilder.setDeps(
          ImmutableSortedSet.of(
              cxxLibraryBuilder.getTarget(), prebuiltCxxLibraryBuilder.getTarget()));
      TargetGraph targetGraph =
          TargetGraphFactory.newInstance(
              cxxLibraryBuilder.build(), prebuiltCxxLibraryBuilder.build(), binaryBuilder.build());
      ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);
      cxxLibraryBuilder.build(graphBuilder, filesystem, targetGraph);
      prebuiltCxxLibraryBuilder.build(graphBuilder, filesystem, targetGraph);
      PythonBinary binary = binaryBuilder.build(graphBuilder, filesystem, targetGraph);
      assertThat(
          "Using " + strategy,
          binary
              .getComponents()
              .resolve(graphBuilder.getSourcePathResolver())
              .getAllNativeLibraries()
              .keySet(),
          hasItems(Paths.get("libdep1.so"), Paths.get("libdep2.so")));
    }
  }

  @Test
  public void transitiveDepsOfPreloadDepsAreExcludedFromMergedNativeLinkStrategy()
      throws IOException {
    CxxLibraryBuilder transitiveCxxDepBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:transitive_dep"))
            .setSrcs(
                ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of("transitive_dep.c"))));
    CxxLibraryBuilder cxxDepBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of("dep.c"))))
            .setDeps(ImmutableSortedSet.of(transitiveCxxDepBuilder.getTarget()));
    CxxLibraryBuilder cxxBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:cxx"))
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of("cxx.c"))))
            .setDeps(ImmutableSortedSet.of(cxxDepBuilder.getTarget()));
    CxxLibraryBuilder preloadCxxBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:preload_cxx"))
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of("preload_cxx.c"))))
            .setDeps(ImmutableSortedSet.of(transitiveCxxDepBuilder.getTarget()));

    PythonBuckConfig config =
        new PythonBuckConfig(FakeBuckConfig.empty()) {
          @Override
          public NativeLinkStrategy getNativeLinkStrategy() {
            return NativeLinkStrategy.MERGED;
          }
        };
    PythonBinaryBuilder binaryBuilder =
        PythonBinaryBuilder.create(
            BuildTargetFactory.newInstance("//:bin"), config, PythonTestUtils.PYTHON_PLATFORMS);
    binaryBuilder.setMainModule("main");
    binaryBuilder.setDeps(ImmutableSortedSet.of(cxxBuilder.getTarget()));
    binaryBuilder.setPreloadDeps(ImmutableSet.of(preloadCxxBuilder.getTarget()));

    ActionGraphBuilder graphBuilder =
        new TestActionGraphBuilder(
            TargetGraphFactory.newInstance(
                transitiveCxxDepBuilder.build(),
                cxxDepBuilder.build(),
                cxxBuilder.build(),
                preloadCxxBuilder.build(),
                binaryBuilder.build()));
    transitiveCxxDepBuilder.build(graphBuilder);
    cxxDepBuilder.build(graphBuilder);
    cxxBuilder.build(graphBuilder);
    preloadCxxBuilder.build(graphBuilder);
    PythonBinary binary = binaryBuilder.build(graphBuilder);
    assertThat(
        Iterables.transform(
            binary
                .getComponents()
                .resolve(graphBuilder.getSourcePathResolver())
                .getAllNativeLibraries()
                .keySet(),
            Object::toString),
        containsInAnyOrder(
            "libomnibus.so", "libcxx.so", "libpreload_cxx.so", "libtransitive_dep.so"));
  }

  @Test
  public void pexBuilderAddedToParseTimeDeps() {
    BuildTarget pexBuilder = BuildTargetFactory.newInstance("//:pex_builder");
    PythonBuckConfig config =
        new PythonBuckConfig(FakeBuckConfig.empty()) {
          @Override
          public Optional<BuildTarget> getPexExecutorTarget(
              TargetConfiguration targetConfiguration) {
            return Optional.of(pexBuilder);
          }
        };

    PythonBinaryBuilder inplaceBinary =
        PythonBinaryBuilder.create(
                BuildTargetFactory.newInstance("//:bin"), config, PythonTestUtils.PYTHON_PLATFORMS)
            .setPackageStyle(PythonBuckConfig.PackageStyle.INPLACE);
    assertThat(inplaceBinary.findImplicitDeps(), not(hasItem(pexBuilder)));

    PythonBinaryBuilder standaloneBinary =
        PythonBinaryBuilder.create(
                BuildTargetFactory.newInstance("//:bin"), config, PythonTestUtils.PYTHON_PLATFORMS)
            .setPackageStyle(PythonBuckConfig.PackageStyle.STANDALONE);
    assertThat(standaloneBinary.findImplicitDeps(), hasItem(pexBuilder));
  }

  @Test
  public void pexToolBuilderAddedToRuntimeDeps() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(TargetGraphFactory.newInstance());

    ShBinary pyTool =
        new ShBinaryBuilder(BuildTargetFactory.newInstance("//:py_tool"))
            .setMain(FakeSourcePath.of("run.sh"))
            .build(graphBuilder);

    PythonBuckConfig config =
        new PythonBuckConfig(FakeBuckConfig.empty()) {
          @Override
          public Optional<Tool> getPexExecutor(
              BuildRuleResolver resolver, TargetConfiguration targetConfiguration) {
            return Optional.of(pyTool.getExecutableCommand(OutputLabel.defaultLabel()));
          }
        };

    PythonBinary standaloneBinary =
        PythonBinaryBuilder.create(
                BuildTargetFactory.newInstance("//:bin"), config, PythonTestUtils.PYTHON_PLATFORMS)
            .setMainModule("hello")
            .setPackageStyle(PythonBuckConfig.PackageStyle.STANDALONE)
            .build(graphBuilder);
    assertThat(
        standaloneBinary.getRuntimeDeps(graphBuilder).collect(ImmutableSet.toImmutableSet()),
        hasItem(pyTool.getBuildTarget()));
  }

  @Test
  public void targetGraphOnlyDepsDoNotAffectRuleKey() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    for (PythonBuckConfig.PackageStyle packageStyle : PythonBuckConfig.PackageStyle.values()) {

      // First, calculate the rule key of a python binary with no deps.
      PythonBinaryBuilder pythonBinaryBuilder =
          PythonBinaryBuilder.create(BuildTargetFactory.newInstance("//:bin"))
              .setMainModule("main")
              .setPackageStyle(packageStyle);
      TargetGraph targetGraph = TargetGraphFactory.newInstance(pythonBinaryBuilder.build());
      ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);
      PythonBinary pythonBinaryWithoutDep =
          pythonBinaryBuilder.build(graphBuilder, filesystem, targetGraph);
      RuleKey ruleKeyWithoutDep = calculateRuleKey(graphBuilder, pythonBinaryWithoutDep);

      // Next, calculate the rule key of a python binary with a deps on another binary.
      CxxBinaryBuilder cxxBinaryBuilder =
          new CxxBinaryBuilder(BuildTargetFactory.newInstance("//:dep"));
      pythonBinaryBuilder.setDeps(ImmutableSortedSet.of(cxxBinaryBuilder.getTarget()));
      targetGraph =
          TargetGraphFactory.newInstance(cxxBinaryBuilder.build(), pythonBinaryBuilder.build());
      graphBuilder = new TestActionGraphBuilder(targetGraph);
      cxxBinaryBuilder.build(graphBuilder, filesystem, targetGraph);
      PythonBinary pythonBinaryWithDep =
          pythonBinaryBuilder.build(graphBuilder, filesystem, targetGraph);
      RuleKey ruleKeyWithDep = calculateRuleKey(graphBuilder, pythonBinaryWithDep);

      // Verify that the rule keys are identical.
      assertThat(ruleKeyWithoutDep, equalTo(ruleKeyWithDep));
    }
  }

  @Test
  public void platformDeps() throws IOException {
    SourcePath libASrc = FakeSourcePath.of("libA.py");
    PythonLibraryBuilder libraryABuilder =
        PythonLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:libA"))
            .setSrcs(SourceSortedSet.ofUnnamedSources(ImmutableSortedSet.of(libASrc)));
    SourcePath libBSrc = FakeSourcePath.of("libB.py");
    PythonLibraryBuilder libraryBBuilder =
        PythonLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:libB"))
            .setSrcs(SourceSortedSet.ofUnnamedSources(ImmutableSortedSet.of(libBSrc)));
    PythonBinaryBuilder binaryBuilder =
        PythonBinaryBuilder.create(BuildTargetFactory.newInstance("//:bin"))
            .setMainModule("main")
            .setPlatformDeps(
                PatternMatchedCollection.<ImmutableSortedSet<BuildTarget>>builder()
                    .add(
                        Pattern.compile(
                            CxxPlatformUtils.DEFAULT_PLATFORM_FLAVOR.toString(), Pattern.LITERAL),
                        ImmutableSortedSet.of(libraryABuilder.getTarget()))
                    .add(
                        Pattern.compile("matches nothing", Pattern.LITERAL),
                        ImmutableSortedSet.of(libraryBBuilder.getTarget()))
                    .build());
    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(
            libraryABuilder.build(), libraryBBuilder.build(), binaryBuilder.build());
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);
    PythonBinary binary = (PythonBinary) graphBuilder.requireRule(binaryBuilder.getTarget());
    assertThat(
        binary
            .getComponents()
            .resolve(graphBuilder.getSourcePathResolver())
            .getAllModules()
            .values(),
        allOf(
            hasItem(graphBuilder.getSourcePathResolver().getAbsolutePath(libASrc).getPath()),
            not(hasItem(graphBuilder.getSourcePathResolver().getAbsolutePath(libBSrc).getPath()))));
  }

  @Test
  public void cxxPlatform() throws IOException {
    UnresolvedCxxPlatform platformA =
        CxxPlatformUtils.DEFAULT_UNRESOLVED_PLATFORM.withFlavor(InternalFlavor.of("platA"));
    UnresolvedCxxPlatform platformB =
        CxxPlatformUtils.DEFAULT_UNRESOLVED_PLATFORM.withFlavor(InternalFlavor.of("platB"));
    FlavorDomain<UnresolvedCxxPlatform> cxxPlatforms =
        FlavorDomain.from("C/C++ platform", ImmutableList.of(platformA, platformB));
    SourcePath libASrc = FakeSourcePath.of("libA.py");
    PythonLibraryBuilder libraryABuilder =
        new PythonLibraryBuilder(
                BuildTargetFactory.newInstance("//:libA"),
                PythonTestUtils.PYTHON_PLATFORMS,
                cxxPlatforms)
            .setSrcs(SourceSortedSet.ofUnnamedSources(ImmutableSortedSet.of(libASrc)));
    SourcePath libBSrc = FakeSourcePath.of("libB.py");
    PythonLibraryBuilder libraryBBuilder =
        new PythonLibraryBuilder(
                BuildTargetFactory.newInstance("//:libB"),
                PythonTestUtils.PYTHON_PLATFORMS,
                cxxPlatforms)
            .setSrcs(SourceSortedSet.ofUnnamedSources(ImmutableSortedSet.of(libBSrc)));
    PythonBinaryBuilder binaryBuilder =
        PythonBinaryBuilder.create(
                BuildTargetFactory.newInstance("//:bin"),
                PythonTestUtils.PYTHON_CONFIG,
                PythonTestUtils.PYTHON_PLATFORMS,
                CxxPlatformUtils.DEFAULT_UNRESOLVED_PLATFORM,
                cxxPlatforms)
            .setMainModule("main")
            .setCxxPlatform(platformA.getFlavor())
            .setPlatformDeps(
                PatternMatchedCollection.<ImmutableSortedSet<BuildTarget>>builder()
                    .add(
                        Pattern.compile(platformA.getFlavor().toString(), Pattern.LITERAL),
                        ImmutableSortedSet.of(libraryABuilder.getTarget()))
                    .add(
                        Pattern.compile(platformB.getFlavor().toString(), Pattern.LITERAL),
                        ImmutableSortedSet.of(libraryBBuilder.getTarget()))
                    .build());
    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(
            libraryABuilder.build(), libraryBBuilder.build(), binaryBuilder.build());
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);
    PythonBinary binary = (PythonBinary) graphBuilder.requireRule(binaryBuilder.getTarget());
    assertThat(
        binary
            .getComponents()
            .resolve(graphBuilder.getSourcePathResolver())
            .getAllModules()
            .values(),
        allOf(
            hasItem(graphBuilder.getSourcePathResolver().getAbsolutePath(libASrc).getPath()),
            not(hasItem(graphBuilder.getSourcePathResolver().getAbsolutePath(libBSrc).getPath()))));
  }

  @Test
  public void packageStyleFlavor() {
    for (Pair<PythonBuckConfig.PackageStyle, ? extends Class<?>> style :
        ImmutableList.of(
            new Pair<>(PythonBuckConfig.PackageStyle.INPLACE, PythonInPlaceBinary.class),
            new Pair<>(PythonBuckConfig.PackageStyle.STANDALONE, PythonPackagedBinary.class))) {
      PythonBinaryBuilder pythonBinaryBuilder =
          PythonBinaryBuilder.create(BuildTargetFactory.newInstance("//:bin"))
              .setMainModule("main");
      TargetGraph targetGraph = TargetGraphFactory.newInstance(pythonBinaryBuilder.build());
      ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);
      PythonBinary pythonBinary =
          (PythonBinary)
              graphBuilder.requireRule(
                  pythonBinaryBuilder
                      .getTarget()
                      .withAppendedFlavors(style.getFirst().getFlavor()));
      assertThat(pythonBinary, instanceOf(style.getSecond()));
    }
  }

  @Test
  public void sourceDb() {
    PythonLibraryBuilder libraryBuilder =
        new PythonLibraryBuilder(BuildTargetFactory.newInstance("//:lib"))
            .setSrcs(
                SourceSortedSet.ofUnnamedSources(
                    ImmutableSortedSet.of(FakeSourcePath.of("lib.py"))));
    PythonBinaryBuilder ruleBuilder =
        PythonBinaryBuilder.create(BuildTargetFactory.newInstance("//:rule"))
            .setDeps(ImmutableSortedSet.of(libraryBuilder.getTarget()))
            .setMain(FakeSourcePath.of("rule.py"));
    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(ruleBuilder.build(), libraryBuilder.build());
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);
    BuildRule db =
        graphBuilder.requireRule(
            ruleBuilder
                .getTarget()
                .withAppendedFlavors(
                    PythonTestUtils.PYTHON_PLATFORM.getFlavor(),
                    CxxPlatformUtils.DEFAULT_PLATFORM.getFlavor(),
                    PythonLibraryDescription.LibraryType.SOURCE_DB.getFlavor()));
    assertThat(db, instanceOf(PythonSourceDatabase.class));
  }

  @Test
  public void deduplicateMergedLinkRoots() {
    CxxLibraryBuilder dummyOmnibus =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:dummy"));

    CxxLibraryBuilder cxxBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:cxx"))
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of("cxx.c"))));

    PythonBuckConfig config =
        new PythonBuckConfig(FakeBuckConfig.empty()) {
          @Override
          public NativeLinkStrategy getNativeLinkStrategy() {
            return NativeLinkStrategy.MERGED;
          }
        };

    CxxBuckConfig cxxBuckConfig =
        new CxxBuckConfig(FakeBuckConfig.empty()) {
          @Override
          public Optional<BuildTarget> getDummyOmnibusTarget(
              TargetConfiguration targetConfiguration) {
            return Optional.of(dummyOmnibus.getTarget());
          }
        };

    PythonBinaryBuilder binary1Builder =
        PythonBinaryBuilder.create(
                BuildTargetFactory.newInstance("//:bin1"),
                config,
                PythonTestUtils.PYTHON_PLATFORMS,
                cxxBuckConfig,
                CxxPlatformUtils.DEFAULT_PLATFORMS)
            .setMainModule("main")
            .setDeps(ImmutableSortedSet.of(cxxBuilder.getTarget()))
            .setDeduplicateMergedLinkRoots(true);

    PythonBinaryBuilder binary2Builder =
        PythonBinaryBuilder.create(
                BuildTargetFactory.newInstance("//:bin2"),
                config,
                PythonTestUtils.PYTHON_PLATFORMS,
                cxxBuckConfig,
                CxxPlatformUtils.DEFAULT_PLATFORMS)
            .setMainModule("main")
            .setDeps(ImmutableSortedSet.of(cxxBuilder.getTarget()))
            .setDeduplicateMergedLinkRoots(true);

    ActionGraphBuilder graphBuilder =
        new TestActionGraphBuilder(
            TargetGraphFactory.newInstance(
                dummyOmnibus.build(),
                cxxBuilder.build(),
                binary1Builder.build(),
                binary2Builder.build()));

    PythonBinary binary1 = (PythonBinary) graphBuilder.requireRule(binary1Builder.getTarget());
    List<SourcePath> binary1Libs = new ArrayList<>();
    binary1.getComponents().getNativeLibraries().getComponents().values().stream()
        .flatMap(Collection::stream)
        .forEach(pythonComponents -> pythonComponents.forEachInput(binary1Libs::add));

    PythonBinary binary2 = (PythonBinary) graphBuilder.requireRule(binary2Builder.getTarget());
    List<SourcePath> binary2Libs = new ArrayList<>();
    binary2.getComponents().getNativeLibraries().getComponents().values().stream()
        .flatMap(Collection::stream)
        .forEach(pythonComponents -> pythonComponents.forEachInput(binary2Libs::add));

    assertEquals(binary1Libs, binary2Libs);
  }

  private RuleKey calculateRuleKey(BuildRuleResolver ruleResolver, BuildRule rule) {
    DefaultRuleKeyFactory ruleKeyFactory =
        new DefaultRuleKeyFactory(
            new RuleKeyFieldLoader(TestRuleKeyConfigurationFactory.create()),
            StackedFileHashCache.createDefaultHashCaches(
                rule.getProjectFilesystem(), FileHashCacheMode.DEFAULT, false),
            ruleResolver);
    return ruleKeyFactory.build(rule);
  }
}
