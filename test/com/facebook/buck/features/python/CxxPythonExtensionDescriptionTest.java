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

import static com.facebook.buck.core.cell.TestCellBuilder.createCellRoots;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.UnconfiguredTargetConfiguration;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.common.BuildableSupport;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.SourceWithFlags;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.cxx.CxxBinaryBuilder;
import com.facebook.buck.cxx.CxxCompilationDatabase;
import com.facebook.buck.cxx.CxxLibraryBuilder;
import com.facebook.buck.cxx.CxxLibraryDescriptionArg;
import com.facebook.buck.cxx.CxxLibraryGroup;
import com.facebook.buck.cxx.CxxLink;
import com.facebook.buck.cxx.CxxStrip;
import com.facebook.buck.cxx.CxxTestUtils;
import com.facebook.buck.cxx.PrebuiltCxxLibraryBuilder;
import com.facebook.buck.cxx.config.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.linker.LinkerProvider;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkTarget;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkTargetMode;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableInput;
import com.facebook.buck.features.python.CxxPythonExtensionDescription.Type;
import com.facebook.buck.features.python.toolchain.PythonEnvironment;
import com.facebook.buck.features.python.toolchain.PythonPlatform;
import com.facebook.buck.features.python.toolchain.PythonVersion;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.HasSourcePath;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.macros.StringWithMacrosUtils;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.regex.Pattern;
import org.hamcrest.Matchers;
import org.junit.Test;

public class CxxPythonExtensionDescriptionTest {

  private static final PythonPlatform PY_DEFAULT = createPyDefaultPlatform();

  private static final BuildTarget PYTHON2_DEP_TARGET =
      BuildTargetFactory.newInstance("//:python2_dep");
  private static final PythonPlatform PY2 = createPy2Platform(Optional.empty());

  private static final BuildTarget PYTHON3_DEP_TARGET =
      BuildTargetFactory.newInstance("//:python3_dep");
  private static final PythonPlatform PY3 = createPy3Platform(Optional.empty());

  private static PythonPlatform createPyDefaultPlatform() {
    return new TestPythonPlatform(
        InternalFlavor.of("py-default"),
        new PythonEnvironment(
            Paths.get("python2"),
            PythonVersion.of("CPython", "2.6"),
            PythonBuckConfig.SECTION,
            UnconfiguredTargetConfiguration.INSTANCE),
        Optional.empty());
  }

  private static PythonPlatform createPy2Platform(Optional<BuildTarget> cxxLibrary) {
    return new TestPythonPlatform(
        InternalFlavor.of("py2"),
        new PythonEnvironment(
            Paths.get("python2"),
            PythonVersion.of("CPython", "2.6"),
            PythonBuckConfig.SECTION,
            UnconfiguredTargetConfiguration.INSTANCE),
        cxxLibrary);
  }

  private static PythonPlatform createPy3Platform(Optional<BuildTarget> cxxLibrary) {
    return new TestPythonPlatform(
        InternalFlavor.of("py3"),
        new PythonEnvironment(
            Paths.get("python3"),
            PythonVersion.of("CPython", "3.5"),
            PythonBuckConfig.SECTION,
            UnconfiguredTargetConfiguration.INSTANCE),
        cxxLibrary);
  }

  @Test
  public void createBuildRuleBaseModule() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    BuildTarget target = BuildTargetFactory.newInstance("//:target");
    CxxPythonExtensionBuilder builder =
        new CxxPythonExtensionBuilder(
            target,
            FlavorDomain.of("Python Platform", PY2, PY3),
            new CxxBuckConfig(FakeBuckConfig.empty()),
            CxxTestUtils.createDefaultPlatforms());

    TargetGraph targetGraph = TargetGraphFactory.newInstance(builder.build());
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);

    CxxPythonExtension normal = builder.build(graphBuilder, filesystem, targetGraph);

    assertEquals(
        ImmutableSet.of(
            target
                .getCellRelativeBasePath()
                .getPath()
                .toPath(filesystem.getFileSystem())
                .resolve(
                    CxxPythonExtensionDescription.getExtensionName(
                        target.getShortName(), CxxPlatformUtils.DEFAULT_PLATFORM))),
        normal
            .getPythonModules(PY2, CxxPlatformUtils.DEFAULT_PLATFORM, graphBuilder)
            .map(modules -> modules.getComponents().keySet())
            .orElseGet(ImmutableSortedSet::of));

    // Verify that explicitly setting works.
    BuildTarget target2 = BuildTargetFactory.newInstance("//:target2#py2");
    String name = "blah";
    CxxPythonExtensionBuilder baseModuleBuilder =
        new CxxPythonExtensionBuilder(
                target2,
                FlavorDomain.of("Python Platform", PY2, PY3),
                new CxxBuckConfig(FakeBuckConfig.empty()),
                CxxTestUtils.createDefaultPlatforms())
            .setBaseModule(name);
    targetGraph = TargetGraphFactory.newInstance(baseModuleBuilder.build());
    graphBuilder = new TestActionGraphBuilder(targetGraph);
    CxxPythonExtension baseModule = baseModuleBuilder.build(graphBuilder, filesystem, targetGraph);
    assertEquals(
        ImmutableSet.of(
            Paths.get(name)
                .resolve(
                    CxxPythonExtensionDescription.getExtensionName(
                        target2.getShortName(), CxxPlatformUtils.DEFAULT_PLATFORM))),
        baseModule
            .getPythonModules(PY2, CxxPlatformUtils.DEFAULT_PLATFORM, graphBuilder)
            .map(modules -> modules.getComponents().keySet())
            .orElseGet(ImmutableSortedSet::of));
  }

  @Test
  public void createBuildRuleNativeLinkableDep() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildTarget target = BuildTargetFactory.newInstance("//:target");

    // Setup a C/C++ library that we'll depend on form the C/C++ binary description.
    BuildTarget cxxLibraryTarget = BuildTargetFactory.newInstance("//:dep");
    CxxLibraryBuilder cxxLibraryBuilder =
        new CxxLibraryBuilder(cxxLibraryTarget)
            .setSrcs(
                ImmutableSortedSet.of(
                    SourceWithFlags.of(FakeSourcePath.of("something.cpp"), ImmutableList.of())));
    CxxPythonExtensionBuilder builder =
        new CxxPythonExtensionBuilder(
                target,
                FlavorDomain.of("Python Platform", PY2, PY3),
                new CxxBuckConfig(FakeBuckConfig.empty()),
                CxxTestUtils.createDefaultPlatforms())
            .setDeps(ImmutableSortedSet.of(cxxLibraryTarget));

    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(cxxLibraryBuilder.build(), builder.build());
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);

    CxxLibraryGroup dep =
        (CxxLibraryGroup) cxxLibraryBuilder.build(graphBuilder, filesystem, targetGraph);
    CxxPythonExtension extension = builder.build(graphBuilder, filesystem, targetGraph);

    NativeLinkableInput depInput =
        dep.getNativeLinkableInput(
            CxxPlatformUtils.DEFAULT_PLATFORM,
            Linker.LinkableDepType.SHARED,
            graphBuilder,
            UnconfiguredTargetConfiguration.INSTANCE);

    // Verify that the shared library dep propagated to the link rule.
    extension.getPythonModules(PY2, CxxPlatformUtils.DEFAULT_PLATFORM, graphBuilder);
    BuildRule rule =
        graphBuilder.getRule(
            CxxPythonExtensionDescription.getExtensionTarget(
                target, PY2.getFlavor(), CxxPlatformUtils.DEFAULT_PLATFORM_FLAVOR));
    assertThat(
        rule.getBuildDeps(),
        Matchers.hasItems(
            FluentIterable.from(depInput.getArgs())
                .transformAndConcat(arg -> BuildableSupport.getDepsCollection(arg, graphBuilder))
                .toArray(BuildRule.class)));
  }

  @Test
  public void createBuildRulePythonPackageable() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    BuildTarget target = BuildTargetFactory.newInstance("//:target");
    CxxPythonExtensionBuilder builder =
        new CxxPythonExtensionBuilder(
            target,
            FlavorDomain.of("Python Platform", PY2, PY3),
            new CxxBuckConfig(FakeBuckConfig.empty()),
            CxxTestUtils.createDefaultPlatforms());

    TargetGraph targetGraph = TargetGraphFactory.newInstance(builder.build());
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);

    CxxPythonExtension extension = builder.build(graphBuilder, filesystem, targetGraph);

    // Verify that we get the expected view from the python packageable interface.
    ImmutableMap<Path, SourcePath> modules =
        extension
            .getPythonModules(PY2, CxxPlatformUtils.DEFAULT_PLATFORM, graphBuilder)
            .map(PythonMappedComponents::getComponents)
            .orElseGet(ImmutableSortedMap::of);
    BuildRule rule =
        graphBuilder.getRule(
            CxxPythonExtensionDescription.getExtensionTarget(
                target, PY2.getFlavor(), CxxPlatformUtils.DEFAULT_PLATFORM_FLAVOR));

    assertEquals(
        ImmutableMap.of(
            target
                .getCellRelativeBasePath()
                .getPath()
                .toPath(filesystem.getFileSystem())
                .resolve(
                    CxxPythonExtensionDescription.getExtensionName(
                        target.getShortName(), CxxPlatformUtils.DEFAULT_PLATFORM)),
            rule.getSourcePathToOutput()),
        modules);
    assertThat(extension.isPythonZipSafe(), Matchers.equalTo(Optional.of(false)));
  }

  @Test
  public void findDepsFromParamsAddsPythonDep() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildTarget target = BuildTargetFactory.newInstance("//:target");
    CxxPythonExtensionDescription desc =
        (CxxPythonExtensionDescription)
            new CxxPythonExtensionBuilder(
                    target,
                    FlavorDomain.of(
                        "Python Platform",
                        createPy2Platform(Optional.of(PYTHON2_DEP_TARGET)),
                        createPy3Platform(Optional.of(PYTHON3_DEP_TARGET))),
                    new CxxBuckConfig(FakeBuckConfig.empty()),
                    CxxTestUtils.createDefaultPlatforms())
                .build()
                .getDescription();
    CxxPythonExtensionDescriptionArg constructorArg =
        CxxPythonExtensionDescriptionArg.builder().setName("target").build();
    ImmutableSortedSet.Builder<BuildTarget> builder = ImmutableSortedSet.naturalOrder();
    desc.findDepsForTargetFromConstructorArgs(
        BuildTargetFactory.newInstance("//foo:bar"),
        createCellRoots(filesystem).getCellNameResolver(),
        constructorArg,
        builder,
        ImmutableSortedSet.naturalOrder());
    assertThat(builder.build(), Matchers.contains(PYTHON2_DEP_TARGET, PYTHON3_DEP_TARGET));
  }

  @Test
  public void py2AndPy3PropagateToLinkRules() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    PrebuiltCxxLibraryBuilder python2Builder =
        new PrebuiltCxxLibraryBuilder(PYTHON2_DEP_TARGET)
            .setHeaderOnly(true)
            .setExportedLinkerFlags("-lpython2");
    PrebuiltCxxLibraryBuilder python3Builder =
        new PrebuiltCxxLibraryBuilder(PYTHON3_DEP_TARGET)
            .setHeaderOnly(true)
            .setExportedLinkerFlags("-lpython3");

    PythonPlatform py2 = createPy2Platform(Optional.of(PYTHON2_DEP_TARGET));
    PythonPlatform py3 = createPy3Platform(Optional.of(PYTHON3_DEP_TARGET));

    BuildTarget target = BuildTargetFactory.newInstance("//:target");
    CxxPythonExtensionBuilder builder =
        new CxxPythonExtensionBuilder(
            target,
            FlavorDomain.of("Python Platform", py2, py3),
            new CxxBuckConfig(FakeBuckConfig.empty()),
            CxxTestUtils.createDefaultPlatforms());

    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(
            python2Builder.build(), python3Builder.build(), builder.build());
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);
    SourcePathResolverAdapter pathResolver = graphBuilder.getSourcePathResolver();

    python2Builder.build(graphBuilder, filesystem, targetGraph);
    python3Builder.build(graphBuilder, filesystem, targetGraph);
    CxxPythonExtension extension = builder.build(graphBuilder, filesystem, targetGraph);

    // Get the py2 extension, and verify it pulled in the py2 lib but not the py3 lib.
    CxxLink py2Ext =
        (CxxLink) extension.getExtension(py2, CxxPlatformUtils.DEFAULT_PLATFORM, graphBuilder);
    assertThat(
        Arg.stringify(py2Ext.getArgs(), pathResolver),
        Matchers.allOf(Matchers.hasItem("-lpython2"), Matchers.not(Matchers.hasItem("-lpython3"))));

    // Get the py3 extension, and verify it pulled in the py3 lib but not the py2 lib.
    CxxLink py3Ext =
        (CxxLink) extension.getExtension(py3, CxxPlatformUtils.DEFAULT_PLATFORM, graphBuilder);
    assertThat(
        Arg.stringify(py3Ext.getArgs(), pathResolver),
        Matchers.allOf(Matchers.hasItem("-lpython3"), Matchers.not(Matchers.hasItem("-lpython2"))));
  }

  @Test
  public void nativeLinkTargetMode() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    CxxPythonExtensionBuilder builder =
        new CxxPythonExtensionBuilder(
            BuildTargetFactory.newInstance("//:rule"),
            FlavorDomain.of("Python Platform", PY2, PY3),
            new CxxBuckConfig(FakeBuckConfig.empty()),
            CxxTestUtils.createDefaultPlatforms());
    CxxPythonExtension rule = builder.build(graphBuilder);
    NativeLinkTarget nativeLinkTarget =
        rule.getNativeLinkTarget(PY2, CxxPlatformUtils.DEFAULT_PLATFORM, graphBuilder, true, false);
    assertThat(
        nativeLinkTarget.getNativeLinkTargetMode(),
        Matchers.equalTo(NativeLinkTargetMode.library()));
  }

  @Test
  public void nativeLinkTargetDeps() {
    TargetNode<CxxLibraryDescriptionArg> cxxDepNode =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:dep")).build();

    ActionGraphBuilder graphBuilder =
        new TestActionGraphBuilder(TargetGraphFactory.newInstance(cxxDepNode));

    CxxLibraryGroup dep = (CxxLibraryGroup) graphBuilder.requireRule(cxxDepNode.getBuildTarget());

    CxxPythonExtensionBuilder builder =
        new CxxPythonExtensionBuilder(
            BuildTargetFactory.newInstance("//:rule"),
            FlavorDomain.of("Python Platform", PY2, PY3),
            new CxxBuckConfig(FakeBuckConfig.empty()),
            CxxTestUtils.createDefaultPlatforms());
    CxxPythonExtension rule =
        builder.setDeps(ImmutableSortedSet.of(dep.getBuildTarget())).build(graphBuilder);
    NativeLinkTarget nativeLinkTarget =
        rule.getNativeLinkTarget(PY2, CxxPlatformUtils.DEFAULT_PLATFORM, graphBuilder, true, false);
    assertThat(
        FluentIterable.from(nativeLinkTarget.getNativeLinkTargetDeps(graphBuilder))
            .transform(NativeLinkable::getBuildTarget),
        Matchers.hasItem(dep.getBuildTarget()));
  }

  @Test
  public void nativeLinkTargetDepsStaticLinkStyle() {
    TargetNode<CxxLibraryDescriptionArg> cxxDepNode =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:dep")).build();

    ActionGraphBuilder graphBuilder =
        new TestActionGraphBuilder(TargetGraphFactory.newInstance(cxxDepNode));

    CxxLibraryGroup dep = (CxxLibraryGroup) graphBuilder.requireRule(cxxDepNode.getBuildTarget());

    CxxPythonExtensionBuilder builder =
        new CxxPythonExtensionBuilder(
            BuildTargetFactory.newInstance("//:rule"),
            FlavorDomain.of("Python Platform", PY2, PY3),
            new CxxBuckConfig(FakeBuckConfig.empty()),
            CxxTestUtils.createDefaultPlatforms());
    CxxPythonExtension rule =
        builder
            .setDeps(ImmutableSortedSet.of(dep.getBuildTarget()))
            .setLinkStyle(Linker.LinkableDepType.STATIC)
            .build(graphBuilder);
    NativeLinkTarget nativeLinkTarget =
        rule.getNativeLinkTarget(PY2, CxxPlatformUtils.DEFAULT_PLATFORM, graphBuilder, true, false);
    assertThat(
        FluentIterable.from(nativeLinkTarget.getNativeLinkTargetDeps(graphBuilder))
            .transform(NativeLinkable::getBuildTarget),
        Matchers.not(Matchers.hasItem(dep.getBuildTarget())));
  }

  @Test
  public void nativeLinkTargetDepsIncludePlatformCxxLibrary() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    CxxLibraryBuilder python2Builder = new CxxLibraryBuilder(PYTHON2_DEP_TARGET);
    PythonPlatform platform = createPy2Platform(Optional.of(PYTHON2_DEP_TARGET));
    CxxPythonExtensionBuilder builder =
        new CxxPythonExtensionBuilder(
            BuildTargetFactory.newInstance("//:rule"),
            FlavorDomain.of("Python Platform", platform),
            new CxxBuckConfig(FakeBuckConfig.empty()),
            CxxTestUtils.createDefaultPlatforms());
    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(python2Builder.build(), builder.build());
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);
    python2Builder.build(graphBuilder, filesystem, targetGraph);
    CxxPythonExtension rule = builder.build(graphBuilder, filesystem, targetGraph);
    NativeLinkTarget nativeLinkTarget =
        rule.getNativeLinkTarget(
            platform, CxxPlatformUtils.DEFAULT_PLATFORM, graphBuilder, true, false);
    assertThat(
        FluentIterable.from(nativeLinkTarget.getNativeLinkTargetDeps(graphBuilder))
            .transform(NativeLinkable::getBuildTarget),
        Matchers.hasItem(PYTHON2_DEP_TARGET));
  }

  @Test
  public void nativeLinkTargetInput() {
    CxxPythonExtensionBuilder builder =
        new CxxPythonExtensionBuilder(
            BuildTargetFactory.newInstance("//:rule"),
            FlavorDomain.of("Python Platform", PY2, PY3),
            new CxxBuckConfig(FakeBuckConfig.empty()),
            CxxTestUtils.createDefaultPlatforms());
    builder.setLinkerFlags(ImmutableList.of(StringWithMacrosUtils.format("--flag")));
    ActionGraphBuilder graphBuilder =
        new TestActionGraphBuilder(TargetGraphFactory.newInstance(builder.build()));
    SourcePathResolverAdapter pathResolver = graphBuilder.getSourcePathResolver();
    CxxPythonExtension rule = builder.build(graphBuilder);
    assertThat(
        Arg.stringify(
            rule.getNativeLinkTarget(
                    PY2, CxxPlatformUtils.DEFAULT_PLATFORM, graphBuilder, true, false)
                .getNativeLinkTargetInput(graphBuilder, pathResolver)
                .getArgs(),
            pathResolver),
        Matchers.hasItems("--flag"));
    assertThat(
        Arg.stringify(
            rule.getNativeLinkTarget(
                    PY2, CxxPlatformUtils.DEFAULT_PLATFORM, graphBuilder, false, false)
                .getNativeLinkTargetInput(graphBuilder, pathResolver)
                .getArgs(),
            pathResolver),
        Matchers.not(Matchers.hasItems("--flag")));
  }

  @Test
  public void platformDeps() {
    TargetNode<CxxLibraryDescriptionArg> cxxDepNode =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:dep")).build();

    ActionGraphBuilder graphBuilder =
        new TestActionGraphBuilder(TargetGraphFactory.newInstance(cxxDepNode));

    CxxLibraryGroup dep = (CxxLibraryGroup) graphBuilder.requireRule(cxxDepNode.getBuildTarget());
    CxxPythonExtensionBuilder builder =
        new CxxPythonExtensionBuilder(
            BuildTargetFactory.newInstance("//:rule"),
            FlavorDomain.of("Python Platform", PY2, PY3),
            new CxxBuckConfig(FakeBuckConfig.empty()),
            CxxTestUtils.createDefaultPlatforms());
    CxxPythonExtension rule =
        builder
            .setPlatformDeps(
                PatternMatchedCollection.<ImmutableSortedSet<BuildTarget>>builder()
                    .add(
                        Pattern.compile(PY2.getFlavor().toString()),
                        ImmutableSortedSet.of(dep.getBuildTarget()))
                    .build())
            .build(graphBuilder);
    NativeLinkTarget py2NativeLinkTarget =
        rule.getNativeLinkTarget(PY2, CxxPlatformUtils.DEFAULT_PLATFORM, graphBuilder, true, false);
    assertThat(
        FluentIterable.from(py2NativeLinkTarget.getNativeLinkTargetDeps(graphBuilder))
            .transform(NativeLinkable::getBuildTarget),
        Matchers.hasItem(dep.getBuildTarget()));
    NativeLinkTarget py3NativeLinkTarget =
        rule.getNativeLinkTarget(PY3, CxxPlatformUtils.DEFAULT_PLATFORM, graphBuilder, true, false);
    assertThat(
        FluentIterable.from(py3NativeLinkTarget.getNativeLinkTargetDeps(graphBuilder))
            .transform(NativeLinkable::getBuildTarget),
        Matchers.not(Matchers.hasItem(dep.getBuildTarget())));
  }

  @Test
  public void platformDepsSeparateLinkage() throws IOException {
    PythonBuckConfig pythonBuckConfig = new PythonBuckConfig(FakeBuckConfig.empty());
    FlavorDomain<PythonPlatform> pythonPlatforms = FlavorDomain.of("Python Platform", PY2, PY3);

    CxxLibraryBuilder depBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of("test.c"))));
    CxxPythonExtensionBuilder extensionBuilder =
        new CxxPythonExtensionBuilder(
                BuildTargetFactory.newInstance("//:rule"),
                pythonPlatforms,
                new CxxBuckConfig(FakeBuckConfig.empty()),
                CxxTestUtils.createDefaultPlatforms())
            .setPlatformDeps(
                PatternMatchedCollection.<ImmutableSortedSet<BuildTarget>>builder()
                    .add(
                        Pattern.compile(PY2.getFlavor().toString()),
                        ImmutableSortedSet.of(depBuilder.getTarget()))
                    .build());
    PythonBinaryBuilder binary2Builder =
        PythonBinaryBuilder.create(
                BuildTargetFactory.newInstance("//:bin2"), pythonBuckConfig, pythonPlatforms)
            .setMainModule("test")
            .setPlatform(PY2.getFlavor().toString())
            .setDeps(ImmutableSortedSet.of(extensionBuilder.getTarget()));
    PythonBinaryBuilder binary3Builder =
        PythonBinaryBuilder.create(
                BuildTargetFactory.newInstance("//:bin3"), pythonBuckConfig, pythonPlatforms)
            .setMainModule("test")
            .setPlatform(PY3.getFlavor().toString())
            .setDeps(ImmutableSortedSet.of(extensionBuilder.getTarget()));

    ActionGraphBuilder graphBuilder =
        new TestActionGraphBuilder(
            TargetGraphFactory.newInstance(
                depBuilder.build(), extensionBuilder.build(), binary2Builder.build()));
    depBuilder.build(graphBuilder);
    extensionBuilder.build(graphBuilder);
    PythonBinary binary2 = binary2Builder.build(graphBuilder);
    PythonBinary binary3 = binary3Builder.build(graphBuilder);

    assertThat(
        binary2
            .getComponents()
            .resolve(graphBuilder.getSourcePathResolver())
            .getAllNativeLibraries()
            .keySet(),
        Matchers.contains(Paths.get("libdep.so")));
    assertThat(
        binary3
            .getComponents()
            .resolve(graphBuilder.getSourcePathResolver())
            .getAllNativeLibraries()
            .keySet(),
        Matchers.not(Matchers.contains(Paths.get("libdep.so"))));
  }

  @Test
  public void runtimeDeps() {
    ActionGraphBuilder graphBuilder =
        new TestActionGraphBuilder(
            TargetGraphFactory.newInstance(
                new CxxBinaryBuilder(BuildTargetFactory.newInstance("//:dep#sandbox")).build()));
    BuildTarget depTarget = BuildTargetFactory.newInstance("//:dep");
    BuildRule cxxBinary = new CxxBinaryBuilder(depTarget).build(graphBuilder);
    CxxPythonExtension cxxPythonExtension =
        new CxxPythonExtensionBuilder(
                BuildTargetFactory.newInstance("//:ext"),
                FlavorDomain.of("Python Platform", PY2, PY3),
                new CxxBuckConfig(FakeBuckConfig.empty()),
                CxxTestUtils.createDefaultPlatforms())
            .setDeps(ImmutableSortedSet.of(cxxBinary.getBuildTarget()))
            .build(graphBuilder);
    assertThat(
        cxxPythonExtension.getRuntimeDeps(graphBuilder).collect(ImmutableSet.toImmutableSet()),
        Matchers.hasItem(cxxBinary.getBuildTarget()));
  }

  @Test
  public void moduleName() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    CxxPythonExtension cxxPythonExtension =
        new CxxPythonExtensionBuilder(
                BuildTargetFactory.newInstance("//:ext"),
                FlavorDomain.of("Python Platform", PY2, PY3),
                new CxxBuckConfig(FakeBuckConfig.empty()),
                CxxTestUtils.createDefaultPlatforms())
            .setModuleName("blah")
            .build(graphBuilder);
    assertThat(
        cxxPythonExtension.getModule(CxxPlatformUtils.DEFAULT_PLATFORM).toString(),
        Matchers.endsWith(
            CxxPythonExtensionDescription.getExtensionName(
                "blah", CxxPlatformUtils.DEFAULT_PLATFORM)));
  }

  @Test
  public void moduleNameWindows() {
    LinkerProvider winLinkerProvider =
        CxxPlatformUtils.defaultLinkerProvider(
            LinkerProvider.Type.WINDOWS, CxxPlatformUtils.DEFAULT_TOOL);
    CxxPlatform winCxxPlatform = CxxPlatformUtils.DEFAULT_PLATFORM.withLd(winLinkerProvider);
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    CxxPythonExtension cxxPythonExtension =
        new CxxPythonExtensionBuilder(
                BuildTargetFactory.newInstance("//:ext"),
                FlavorDomain.of("Python Platform", PY2, PY3),
                new CxxBuckConfig(FakeBuckConfig.empty()),
                CxxTestUtils.createDefaultPlatforms())
            .setModuleName("blah")
            .build(graphBuilder);
    String winExtension = CxxPythonExtensionDescription.getExtensionName("blah", winCxxPlatform);
    assertThat(winExtension, Matchers.equalTo("blah.pyd"));
    assertThat(
        cxxPythonExtension.getModule(winCxxPlatform).toString(), Matchers.endsWith(winExtension));
  }

  @Test
  public void compilationDatabase() {
    CxxPythonExtensionBuilder builder =
        new CxxPythonExtensionBuilder(
            BuildTargetFactory.newInstance("//:ext"),
            FlavorDomain.of("Python Platform", PY2, PY3),
            new CxxBuckConfig(FakeBuckConfig.empty()),
            CxxTestUtils.createDefaultPlatforms());
    builder.setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of("test.c"))));
    ActionGraphBuilder graphBuilder =
        new TestActionGraphBuilder(TargetGraphFactory.newInstance(builder.build()));
    BuildRule rule =
        graphBuilder.requireRule(
            builder
                .getTarget()
                .withAppendedFlavors(
                    CxxPlatformUtils.DEFAULT_PLATFORM_FLAVOR,
                    PY2.getFlavor(),
                    Type.COMPILATION_DATABASE.getFlavor()));
    assertThat(rule, Matchers.instanceOf(CxxCompilationDatabase.class));
  }

  @Test
  public void compilationDatabaseDefaults() {
    CxxPythonExtensionBuilder builder =
        new CxxPythonExtensionBuilder(
            BuildTargetFactory.newInstance("//:ext"),
            FlavorDomain.of("Python Platform", PY_DEFAULT, PY2, PY3),
            new CxxBuckConfig(FakeBuckConfig.empty()),
            CxxTestUtils.createDefaultPlatforms());

    builder.setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of("test.cpp"))));
    ActionGraphBuilder graphBuilder =
        new TestActionGraphBuilder(TargetGraphFactory.newInstance(builder.build()));
    BuildRule rule =
        graphBuilder.requireRule(
            builder.getTarget().withAppendedFlavors(Type.COMPILATION_DATABASE.getFlavor()));
    assertThat(rule, Matchers.instanceOf(CxxCompilationDatabase.class));
  }

  @Test
  public void addImplicitFlavors() {
    CxxPythonExtensionBuilder builder =
        new CxxPythonExtensionBuilder(
            BuildTargetFactory.newInstance("//:ext"),
            FlavorDomain.of("Python Platform", PY2, PY3),
            new CxxBuckConfig(FakeBuckConfig.empty()),
            CxxPlatformUtils.DEFAULT_PLATFORMS);
    CxxPythonExtensionDescription description =
        (CxxPythonExtensionDescription) builder.build().getDescription();
    assertThat(
        description.addImplicitFlavors(
            ImmutableSortedSet.of(), UnconfiguredTargetConfiguration.INSTANCE),
        Matchers.equalTo(ImmutableSortedSet.of()));
    assertThat(
        description.addImplicitFlavors(
            ImmutableSortedSet.of(PY2.getFlavor()), UnconfiguredTargetConfiguration.INSTANCE),
        Matchers.equalTo(ImmutableSortedSet.of(PY2.getFlavor())));
    assertThat(
        description.addImplicitFlavors(
            ImmutableSortedSet.of(CxxPlatformUtils.DEFAULT_PLATFORM.getFlavor()),
            UnconfiguredTargetConfiguration.INSTANCE),
        Matchers.equalTo(ImmutableSortedSet.of(CxxPlatformUtils.DEFAULT_PLATFORM.getFlavor())));
    assertThat(
        description.addImplicitFlavors(
            ImmutableSortedSet.of(Type.EXTENSION.getFlavor()),
            UnconfiguredTargetConfiguration.INSTANCE),
        Matchers.equalTo(ImmutableSortedSet.of(Type.EXTENSION.getFlavor())));
  }

  @Test
  public void linkTargetInputPreferringStrippedNativeObjects() {
    CxxPythonExtensionBuilder extensionBuilder =
        new CxxPythonExtensionBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of("foo.cpp"))));
    ActionGraphBuilder graphBuilder =
        new TestActionGraphBuilder(TargetGraphFactory.newInstance(extensionBuilder.build()));
    CxxPythonExtension extension =
        (CxxPythonExtension) graphBuilder.requireRule(extensionBuilder.getTarget());
    NativeLinkableInput input =
        extension
            .getNativeLinkTarget(
                PythonTestUtils.PYTHON_PLATFORM,
                CxxPlatformUtils.DEFAULT_PLATFORM,
                graphBuilder,
                false,
                true)
            .getNativeLinkTargetInput(graphBuilder, graphBuilder.getSourcePathResolver());
    ImmutableList<Arg> args = input.getArgs();
    Arg object = args.get(args.size() - 1);
    assertThat(
        graphBuilder.getRule(((HasSourcePath) object).getPath()).orElseThrow(AssertionError::new),
        Matchers.instanceOf(CxxStrip.class));
  }
}
