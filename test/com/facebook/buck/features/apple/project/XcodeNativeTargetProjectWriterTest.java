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

package com.facebook.buck.features.apple.project;

import static com.facebook.buck.features.apple.project.ProjectGeneratorTestUtils.assertHasSingletonFrameworksPhaseWithFrameworkEntries;
import static com.facebook.buck.features.apple.project.ProjectGeneratorTestUtils.assertHasSingletonPhaseWithEntries;
import static com.facebook.buck.features.apple.project.ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget;
import static com.facebook.buck.features.apple.project.ProjectGeneratorTestUtils.getSingletonPhaseByType;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.collection.IsCollectionWithSize.hasSize;
import static org.hamcrest.collection.IsEmptyIterable.emptyIterable;
import static org.hamcrest.core.IsNot.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

import com.facebook.buck.apple.AppleAssetCatalogDescriptionArg;
import com.facebook.buck.apple.AppleResourceBundleDestination;
import com.facebook.buck.apple.AppleResourceDescriptionArg;
import com.facebook.buck.apple.XcodePostbuildScriptBuilder;
import com.facebook.buck.apple.XcodePrebuildScriptBuilder;
import com.facebook.buck.apple.xcode.AbstractPBXObjectFactory;
import com.facebook.buck.apple.xcode.xcodeproj.CopyFilePhaseDestinationSpec;
import com.facebook.buck.apple.xcode.xcodeproj.PBXBuildPhase;
import com.facebook.buck.apple.xcode.xcodeproj.PBXCopyFilesBuildPhase;
import com.facebook.buck.apple.xcode.xcodeproj.PBXFileReference;
import com.facebook.buck.apple.xcode.xcodeproj.PBXGroup;
import com.facebook.buck.apple.xcode.xcodeproj.PBXNativeTarget;
import com.facebook.buck.apple.xcode.xcodeproj.PBXProject;
import com.facebook.buck.apple.xcode.xcodeproj.PBXReference;
import com.facebook.buck.apple.xcode.xcodeproj.PBXResourcesBuildPhase;
import com.facebook.buck.apple.xcode.xcodeproj.PBXShellScriptBuildPhase;
import com.facebook.buck.apple.xcode.xcodeproj.ProductTypes;
import com.facebook.buck.apple.xcode.xcodeproj.SourceTreePath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.SourceWithFlags;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.features.js.JsBundleGenruleBuilder;
import com.facebook.buck.features.js.JsTestScenario;
import com.facebook.buck.parser.exceptions.NoSuchBuildTargetException;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.rules.macros.StringWithMacrosUtils;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.hamcrest.Matchers;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

public class XcodeNativeTargetProjectWriterTest {
  private PBXProject generatedProject;
  private PathRelativizer pathRelativizer;
  private SourcePathResolverAdapter sourcePathResolverAdapter;
  private BuildRuleResolver buildRuleResolver;

  @Before
  public void setUp() {
    Assume.assumeThat(Platform.detect(), Matchers.not(Platform.WINDOWS));
    generatedProject =
        new PBXProject("TestProject", Optional.empty(), AbstractPBXObjectFactory.DefaultFactory());
    buildRuleResolver = new TestActionGraphBuilder();
    sourcePathResolverAdapter = buildRuleResolver.getSourcePathResolver();
    pathRelativizer =
        new PathRelativizer(
            Paths.get("_output"),
            sourcePath -> sourcePathResolverAdapter.getCellUnsafeRelPath(sourcePath).getPath());
  }

  @Test
  public void shouldCreateTargetAndTargetGroup() throws NoSuchBuildTargetException {
    NewNativeTargetProjectMutator mutator =
        new NewNativeTargetProjectMutator(
            pathRelativizer,
            sourcePath -> sourcePathResolverAdapter.getCellUnsafeRelPath(sourcePath).getPath(),
            Paths.get("copiedVariantsDir"),
            ImmutableSet.builder());
    mutator
        .setTargetName("TestTarget")
        .setProduct(ProductTypes.BUNDLE, "TestTargetProduct", Paths.get("TestTargetProduct.bundle"))
        .buildTargetAndAddToProject(generatedProject, true);

    assertTargetExistsAndReturnTarget(generatedProject, "TestTarget");
    assertHasTargetGroupWithName(generatedProject, "TestTarget");
  }

  @Test
  public void shouldCreateTargetAndCustomTargetGroup() throws NoSuchBuildTargetException {
    NewNativeTargetProjectMutator mutator =
        new NewNativeTargetProjectMutator(
            pathRelativizer,
            sourcePath -> sourcePathResolverAdapter.getCellUnsafeRelPath(sourcePath).getPath(),
            Paths.get("copiedVariantsDir"),
            ImmutableSet.builder());
    mutator
        .setTargetName("TestTarget")
        .setTargetGroupPath(ImmutableList.of("Grandparent", "Parent"))
        .setProduct(ProductTypes.BUNDLE, "TestTargetProduct", Paths.get("TestTargetProduct.bundle"))
        .buildTargetAndAddToProject(generatedProject, true);

    assertTargetExistsAndReturnTarget(generatedProject, "TestTarget");
    PBXGroup grandparentGroup =
        assertHasSubgroupAndReturnIt(generatedProject.getMainGroup(), "Grandparent");
    assertHasSubgroupAndReturnIt(grandparentGroup, "Parent");
  }

  @Test
  public void shouldCreateTargetAndNoGroup() throws NoSuchBuildTargetException {
    NewNativeTargetProjectMutator mutator =
        new NewNativeTargetProjectMutator(
            pathRelativizer,
            sourcePath -> sourcePathResolverAdapter.getCellUnsafeRelPath(sourcePath).getPath(),
            Paths.get("copiedVariantsDir"),
            ImmutableSet.builder());
    NewNativeTargetProjectMutator.Result result =
        mutator
            .setTargetName("TestTarget")
            .setTargetGroupPath(ImmutableList.of("Grandparent", "Parent"))
            .setProduct(
                ProductTypes.BUNDLE, "TestTargetProduct", Paths.get("TestTargetProduct.bundle"))
            .buildTargetAndAddToProject(generatedProject, false);

    assertFalse(result.targetGroup.isPresent());
  }

  @Test
  public void testSourceGroups() throws NoSuchBuildTargetException {
    NewNativeTargetProjectMutator mutator = mutatorWithCommonDefaults();

    SourcePath foo = FakeSourcePath.of("Group1/foo.m");
    SourcePath bar = FakeSourcePath.of("Group1/bar.m");
    SourcePath baz = FakeSourcePath.of("Group2/baz.m");
    mutator.setSourcesWithFlags(
        ImmutableSet.of(
            SourceWithFlags.of(foo),
            SourceWithFlags.of(bar, StringWithMacrosUtils.fromStrings("-Wall")),
            SourceWithFlags.of(baz)));
    NewNativeTargetProjectMutator.Result result =
        mutator.buildTargetAndAddToProject(generatedProject, true);

    PBXGroup sourcesGroup = result.targetGroup.get().getOrCreateChildGroupByName("Sources");

    PBXGroup group1 = (PBXGroup) Iterables.get(sourcesGroup.getChildren(), 0);
    assertEquals("Group1", group1.getName());
    assertThat(group1.getChildren(), hasSize(2));
    PBXFileReference fileRefBar = (PBXFileReference) Iterables.get(group1.getChildren(), 0);
    assertEquals("bar.m", fileRefBar.getName());
    PBXFileReference fileRefFoo = (PBXFileReference) Iterables.get(group1.getChildren(), 1);
    assertEquals("foo.m", fileRefFoo.getName());

    PBXGroup group2 = (PBXGroup) Iterables.get(sourcesGroup.getChildren(), 1);
    assertEquals("Group2", group2.getName());
    assertThat(group2.getChildren(), hasSize(1));
    PBXFileReference fileRefBaz = (PBXFileReference) Iterables.get(group2.getChildren(), 0);
    assertEquals("baz.m", fileRefBaz.getName());
  }

  @Test
  public void testLibraryHeaderGroups() throws NoSuchBuildTargetException {
    NewNativeTargetProjectMutator mutator = mutatorWithCommonDefaults();

    SourcePath foo = FakeSourcePath.of("HeaderGroup1/foo.h");
    SourcePath bar = FakeSourcePath.of("HeaderGroup1/bar.h");
    SourcePath baz = FakeSourcePath.of("HeaderGroup2/baz.h");
    mutator.setPublicHeaders(ImmutableSet.of(bar, baz));
    mutator.setPrivateHeaders(ImmutableSet.of(foo));
    NewNativeTargetProjectMutator.Result result =
        mutator.buildTargetAndAddToProject(generatedProject, true);

    PBXGroup sourcesGroup = result.targetGroup.get().getOrCreateChildGroupByName("Sources");

    assertThat(sourcesGroup.getChildren(), hasSize(2));

    PBXGroup group1 = (PBXGroup) Iterables.get(sourcesGroup.getChildren(), 0);
    assertEquals("HeaderGroup1", group1.getName());
    assertThat(group1.getChildren(), hasSize(2));
    PBXFileReference fileRefBar = (PBXFileReference) Iterables.get(group1.getChildren(), 0);
    assertEquals("bar.h", fileRefBar.getName());
    PBXFileReference fileRefFoo = (PBXFileReference) Iterables.get(group1.getChildren(), 1);
    assertEquals("foo.h", fileRefFoo.getName());

    PBXGroup group2 = (PBXGroup) Iterables.get(sourcesGroup.getChildren(), 1);
    assertEquals("HeaderGroup2", group2.getName());
    assertThat(group2.getChildren(), hasSize(1));
    PBXFileReference fileRefBaz = (PBXFileReference) Iterables.get(group2.getChildren(), 0);
    assertEquals("baz.h", fileRefBaz.getName());
  }

  @Test
  public void testPrefixHeaderInSourceGroup() throws NoSuchBuildTargetException {
    NewNativeTargetProjectMutator mutator = mutatorWithCommonDefaults();
    SourcePath prefixHeader = FakeSourcePath.of("Group1/prefix.pch");
    mutator.setPrefixHeader(Optional.of(prefixHeader));

    NewNativeTargetProjectMutator.Result result =
        mutator.buildTargetAndAddToProject(generatedProject, true);

    // No matter where the prefixHeader file is it should always be directly inside Sources
    PBXGroup sourcesGroup = result.targetGroup.get().getOrCreateChildGroupByName("Sources");

    assertThat(sourcesGroup.getChildren(), hasSize(1));
    PBXFileReference fileRef = (PBXFileReference) Iterables.get(sourcesGroup.getChildren(), 0);
    assertEquals("prefix.pch", fileRef.getName());
  }

  @Test
  public void testFrameworkBuildPhase() throws NoSuchBuildTargetException {
    NewNativeTargetProjectMutator mutator = mutatorWithCommonDefaults();
    mutator.setFrameworks(
        ImmutableSet.of(
            FrameworkPath.ofSourceTreePath(
                new SourceTreePath(
                    PBXReference.SourceTree.SDKROOT,
                    Paths.get("Foo.framework"),
                    Optional.empty()))));
    mutator.setArchives(
        ImmutableSet.of(
            new PBXFileReference(
                "libdep.a",
                "libdep.a",
                PBXReference.SourceTree.BUILT_PRODUCTS_DIR,
                Optional.empty())));
    NewNativeTargetProjectMutator.Result result =
        mutator.buildTargetAndAddToProject(generatedProject, true);
    assertHasSingletonFrameworksPhaseWithFrameworkEntries(
        result.target, ImmutableList.of("$SDKROOT/Foo.framework", "$BUILT_PRODUCTS_DIR/libdep.a"));
  }

  @Test
  public void testResourcesBuildPhase() throws NoSuchBuildTargetException {
    NewNativeTargetProjectMutator mutator = mutatorWithCommonDefaults();

    AppleResourceDescriptionArg arg =
        AppleResourceDescriptionArg.builder()
            .setName("resources")
            .setFiles(ImmutableSet.of(FakeSourcePath.of("foo.png")))
            .build();

    mutator.setRecursiveResources(ImmutableSet.of(arg));
    NewNativeTargetProjectMutator.Result result =
        mutator.buildTargetAndAddToProject(generatedProject, true);

    assertHasSingletonPhaseWithEntries(
        result.target, PBXResourcesBuildPhase.class, ImmutableList.of("$SOURCE_ROOT/../foo.png"));
  }

  @Test
  public void testResourceWithStdDestinationAddedToResourceBuildPhase()
      throws NoSuchBuildTargetException {
    NewNativeTargetProjectMutator mutator = mutatorWithCommonDefaults();

    AppleResourceDescriptionArg arg =
        AppleResourceDescriptionArg.builder()
            .setName("resources")
            .setFiles(ImmutableSet.of(FakeSourcePath.of("foo.png")))
            .setDestination(AppleResourceBundleDestination.RESOURCES)
            .build();

    mutator.setRecursiveResources(ImmutableSet.of(arg));
    NewNativeTargetProjectMutator.Result result =
        mutator.buildTargetAndAddToProject(generatedProject, true);

    assertHasSingletonPhaseWithEntries(
        result.target, PBXResourcesBuildPhase.class, ImmutableList.of("$SOURCE_ROOT/../foo.png"));
  }

  @Test
  public void testResourceWithNonStdDestinationIsOnlyAddedToCopyFilesBuildPhase()
      throws NoSuchBuildTargetException {
    NewNativeTargetProjectMutator mutator = mutatorWithCommonDefaults();

    AppleResourceDescriptionArg arg =
        AppleResourceDescriptionArg.builder()
            .setName("resources")
            .setFiles(ImmutableSet.of(FakeSourcePath.of("foo.png")))
            .setDestination(AppleResourceBundleDestination.FRAMEWORKS)
            .build();

    mutator.setRecursiveResources(ImmutableSet.of(arg));
    NewNativeTargetProjectMutator.Result result =
        mutator.buildTargetAndAddToProject(generatedProject, true);

    assertThat(
        "Resource build phase should not exist",
        result.target.getBuildPhases().stream()
            .filter(e -> e instanceof PBXResourcesBuildPhase)
            .collect(Collectors.toSet()),
        is(emptyIterable()));

    Optional<PBXBuildPhase> maybeBuildPhase =
        result.target.getBuildPhases().stream()
            .filter(e -> e instanceof PBXCopyFilesBuildPhase)
            .findFirst();

    assertThat("Copy files build phase exists", maybeBuildPhase, notNullValue());

    if (maybeBuildPhase.isPresent()) {
      PBXCopyFilesBuildPhase buildPhase = (PBXCopyFilesBuildPhase) maybeBuildPhase.get();
      assertThat(
          "Copy files build phase contains expected file",
          buildPhase.getFiles().stream()
              .map(e -> e.getFileRef().getPath())
              .collect(Collectors.toSet()),
          contains(containsString("foo.png")));
      assertThat(
          "Copy files build phase has expected destination",
          buildPhase.getDstSubfolderSpec().getDestination(),
          equalTo(PBXCopyFilesBuildPhase.Destination.FRAMEWORKS));
    }
  }

  @Test
  public void testCopyFilesBuildPhase() throws NoSuchBuildTargetException {
    NewNativeTargetProjectMutator mutator = mutatorWithCommonDefaults();

    PBXBuildPhase copyPhase =
        new PBXCopyFilesBuildPhase(
            CopyFilePhaseDestinationSpec.of(
                PBXCopyFilesBuildPhase.Destination.FRAMEWORKS, Optional.of("foo.png")));

    mutator.setCopyFilesPhases(ImmutableList.of(copyPhase));

    NewNativeTargetProjectMutator.Result result =
        mutator.buildTargetAndAddToProject(generatedProject, true);

    PBXBuildPhase buildPhaseToTest =
        getSingletonPhaseByType(result.target, PBXCopyFilesBuildPhase.class);
    assertThat(copyPhase, equalTo(buildPhaseToTest));
  }

  @Test
  public void testCopyFilesBuildPhaseIsBeforePostBuildScriptBuildPhase()
      throws NoSuchBuildTargetException {
    NewNativeTargetProjectMutator mutator = mutatorWithCommonDefaults();

    PBXBuildPhase copyFilesPhase =
        new PBXCopyFilesBuildPhase(
            CopyFilePhaseDestinationSpec.of(
                PBXCopyFilesBuildPhase.Destination.FRAMEWORKS, Optional.of("script/input.png")));

    mutator.setCopyFilesPhases(ImmutableList.of(copyFilesPhase));

    TargetNode<?> postbuildNode =
        XcodePostbuildScriptBuilder.createBuilder(BuildTargetFactory.newInstance("//foo:script"))
            .setCmd("echo \"hello world!\"")
            .build();
    mutator.setPostBuildRunScriptPhasesFromTargetNodes(
        ImmutableList.of(postbuildNode), x -> buildRuleResolver);

    NewNativeTargetProjectMutator.Result result =
        mutator.buildTargetAndAddToProject(generatedProject, true);

    PBXNativeTarget target = result.target;

    List<PBXBuildPhase> buildPhases = target.getBuildPhases();

    PBXBuildPhase copyBuildPhaseToTest =
        getSingletonPhaseByType(target, PBXCopyFilesBuildPhase.class);
    PBXBuildPhase postBuildScriptPhase =
        getSingletonPhaseByType(target, PBXShellScriptBuildPhase.class);

    assertThat(
        buildPhases.indexOf(copyBuildPhaseToTest),
        lessThan(buildPhases.indexOf(postBuildScriptPhase)));
  }

  @Test
  public void assetCatalogsBuildPhaseBuildsAssetCatalogs() throws NoSuchBuildTargetException {
    AppleAssetCatalogDescriptionArg arg =
        AppleAssetCatalogDescriptionArg.builder()
            .setName("some_rule")
            .setDirs(ImmutableSortedSet.of(FakeSourcePath.of("AssetCatalog1.xcassets")))
            .build();

    NewNativeTargetProjectMutator mutator = mutatorWithCommonDefaults();
    mutator.setRecursiveAssetCatalogs(ImmutableSet.of(arg));
    NewNativeTargetProjectMutator.Result result =
        mutator.buildTargetAndAddToProject(generatedProject, true);
    assertHasSingletonPhaseWithEntries(
        result.target,
        PBXResourcesBuildPhase.class,
        ImmutableList.of("$SOURCE_ROOT/../AssetCatalog1.xcassets"));
  }

  @Test
  public void testScriptBuildPhase() throws NoSuchBuildTargetException {
    NewNativeTargetProjectMutator mutator = mutatorWithCommonDefaults();

    TargetNode<?> prebuildNode =
        XcodePrebuildScriptBuilder.createBuilder(BuildTargetFactory.newInstance("//foo:script"))
            .setSrcs(ImmutableSortedSet.of(FakeSourcePath.of("script/input.png")))
            .setInputs(ImmutableSortedSet.of("$(SRCROOT)/helloworld.md"))
            .setInputFileLists(ImmutableSortedSet.of("$(SRCROOT)/foo-inputs.xcfilelist"))
            .setOutputs(ImmutableSortedSet.of("helloworld.txt"))
            .setOutputFileLists(ImmutableSortedSet.of("$(SRCROOT)/foo-outputs.xcfilelist"))
            .setCmd("echo \"hello world!\"")
            .build();

    mutator.setPostBuildRunScriptPhasesFromTargetNodes(
        ImmutableList.of(prebuildNode), x -> buildRuleResolver);
    NewNativeTargetProjectMutator.Result result =
        mutator.buildTargetAndAddToProject(generatedProject, true);

    PBXShellScriptBuildPhase phase =
        getSingletonPhaseByType(result.target, PBXShellScriptBuildPhase.class);
    assertThat("Should set input paths correctly", phase.getInputPaths().size(), is(equalTo(2)));
    assertThat(
        "Should set input paths correctly",
        phase.getInputPaths(),
        is(hasItems("../script/input.png", "$(SRCROOT)/helloworld.md")));
    assertThat(
        "Should set input file list paths correctly",
        Iterables.getOnlyElement(phase.getInputFileListPaths()),
        is(equalTo("$(SRCROOT)/foo-inputs.xcfilelist")));
    assertThat(
        "Should set output paths correctly",
        "helloworld.txt",
        is(equalTo(Iterables.getOnlyElement(phase.getOutputPaths()))));
    assertThat(
        "Should set output file list paths correctly",
        Iterables.getOnlyElement(phase.getOutputFileListPaths()),
        is(equalTo("$(SRCROOT)/foo-outputs.xcfilelist")));
    assertEquals("should set script correctly", "echo \"hello world!\"", phase.getShellScript());
  }

  @Test
  public void testScriptBuildPhaseWithJsBundle() throws NoSuchBuildTargetException {
    BuildTarget depBuildTarget = BuildTargetFactory.newInstance("//foo:dep");
    JsTestScenario scenario =
        JsTestScenario.builder().bundle(depBuildTarget, ImmutableSortedSet.of()).build();

    NewNativeTargetProjectMutator mutator = mutator(scenario.graphBuilder.getSourcePathResolver());

    TargetNode<?> jsBundleNode = scenario.targetGraph.get(depBuildTarget);

    mutator.setPostBuildRunScriptPhasesFromTargetNodes(
        ImmutableList.of(jsBundleNode), x -> scenario.graphBuilder);
    NewNativeTargetProjectMutator.Result result =
        mutator.buildTargetAndAddToProject(generatedProject, true);

    PBXShellScriptBuildPhase phase =
        getSingletonPhaseByType(result.target, PBXShellScriptBuildPhase.class);
    String shellScript = phase.getShellScript();
    RelPath genPath =
        BuildTargetPaths.getGenPath(scenario.filesystem.getBuckPaths(), depBuildTarget, "%s");
    Path jsGenPath = genPath.resolve("js").toAbsolutePath();
    Path resGenPath = genPath.resolve("res").toAbsolutePath();
    assertEquals(
        String.format(
            "BASE_DIR=\"${TARGET_BUILD_DIR}/${UNLOCALIZED_RESOURCES_FOLDER_PATH}\"\n"
                + "mkdir -p \"${BASE_DIR}\"\n\n"
                + "cp -a \"%s/\" \"${BASE_DIR}/\"\n"
                + "cp -a \"%s/\" \"${BASE_DIR}/\"\n",
            jsGenPath, resGenPath),
        shellScript);
  }

  @Test
  public void testScriptBuildPhaseWithJsBundleGenrule() throws NoSuchBuildTargetException {
    BuildTarget bundleBuildTarget = BuildTargetFactory.newInstance("//foo:bundle");
    BuildTarget depBuildTarget = BuildTargetFactory.newInstance("//foo:dep");
    JsTestScenario scenario =
        JsTestScenario.builder()
            .bundle(bundleBuildTarget, ImmutableSortedSet.of())
            .bundleGenrule(JsBundleGenruleBuilder.Options.of(depBuildTarget, bundleBuildTarget))
            .build();

    NewNativeTargetProjectMutator mutator = mutator(scenario.graphBuilder.getSourcePathResolver());

    TargetNode<?> jsBundleGenruleNode = scenario.targetGraph.get(depBuildTarget);

    mutator.setPostBuildRunScriptPhasesFromTargetNodes(
        ImmutableList.of(jsBundleGenruleNode), x -> scenario.graphBuilder);
    NewNativeTargetProjectMutator.Result result =
        mutator.buildTargetAndAddToProject(generatedProject, true);

    PBXShellScriptBuildPhase phase =
        getSingletonPhaseByType(result.target, PBXShellScriptBuildPhase.class);
    String shellScript = phase.getShellScript();
    Path depGenPath =
        BuildTargetPaths.getGenPath(scenario.filesystem.getBuckPaths(), depBuildTarget, "%s")
            .resolve("js")
            .toAbsolutePath();
    Path bundleGenPath =
        BuildTargetPaths.getGenPath(scenario.filesystem.getBuckPaths(), bundleBuildTarget, "%s")
            .resolve("res")
            .toAbsolutePath();
    assertEquals(
        String.format(
            "BASE_DIR=\"${TARGET_BUILD_DIR}/${UNLOCALIZED_RESOURCES_FOLDER_PATH}\"\n"
                + "mkdir -p \"${BASE_DIR}\"\n\n"
                + "cp -a \"%s/\" \"${BASE_DIR}/\"\n"
                + "cp -a \"%s/\" \"${BASE_DIR}/\"\n",
            depGenPath, bundleGenPath),
        shellScript);
  }

  private NewNativeTargetProjectMutator mutatorWithCommonDefaults() {
    return mutator(sourcePathResolverAdapter, pathRelativizer);
  }

  private NewNativeTargetProjectMutator mutator(SourcePathResolverAdapter pathResolver) {
    return mutator(
        pathResolver,
        new PathRelativizer(
            Paths.get("_output"),
            sourcePath -> pathResolver.getCellUnsafeRelPath(sourcePath).getPath()));
  }

  private NewNativeTargetProjectMutator mutator(
      SourcePathResolverAdapter pathResolver, PathRelativizer relativizer) {
    NewNativeTargetProjectMutator mutator =
        new NewNativeTargetProjectMutator(
            relativizer,
            sourcePath -> pathResolver.getCellUnsafeRelPath(sourcePath).getPath(),
            Paths.get("copiedVariantsDir"),
            ImmutableSet.builder());
    mutator
        .setTargetName("TestTarget")
        .setProduct(
            ProductTypes.BUNDLE, "TestTargetProduct", Paths.get("TestTargetProduct.bundle"));
    return mutator;
  }

  private static void assertHasTargetGroupWithName(PBXProject project, String name) {
    assertThat(
        "Should contain a target group named: " + name,
        Iterables.filter(
            project.getMainGroup().getChildren(), input -> input.getName().equals(name)),
        not(emptyIterable()));
  }

  private static PBXGroup assertHasSubgroupAndReturnIt(PBXGroup group, String subgroupName) {
    ImmutableList<PBXGroup> candidates =
        FluentIterable.from(group.getChildren())
            .filter(input -> input.getName().equals(subgroupName))
            .filter(PBXGroup.class)
            .toList();
    if (candidates.size() != 1) {
      fail("Could not find a unique subgroup by its name");
    }
    return candidates.get(0);
  }
}
