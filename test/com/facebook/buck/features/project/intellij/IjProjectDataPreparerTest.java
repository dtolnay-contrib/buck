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

package com.facebook.buck.features.project.intellij;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasProperty;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.features.project.intellij.aggregation.AggregationMode;
import com.facebook.buck.features.project.intellij.lang.java.ParsingJavaPackageFinder;
import com.facebook.buck.features.project.intellij.model.ContentRoot;
import com.facebook.buck.features.project.intellij.model.IjLibrary;
import com.facebook.buck.features.project.intellij.model.IjModule;
import com.facebook.buck.features.project.intellij.model.IjModuleType;
import com.facebook.buck.features.project.intellij.model.ModuleIndexEntry;
import com.facebook.buck.features.project.intellij.model.folders.ExcludeFolder;
import com.facebook.buck.features.project.intellij.model.folders.IjFolder;
import com.facebook.buck.features.project.intellij.model.folders.IjSourceFolder;
import com.facebook.buck.features.project.intellij.model.folders.SourceFolder;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.jvm.core.JavaPackageFinder;
import com.facebook.buck.jvm.java.DefaultJavaPackageFinder;
import com.facebook.buck.jvm.java.JavaCompilationConstants;
import com.facebook.buck.jvm.java.JavaFileParser;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.jvm.java.JavaTestBuilder;
import com.facebook.buck.jvm.java.PrebuiltJarBuilder;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.test.selectors.Nullable;
import com.facebook.buck.util.timing.FakeClock;
import com.google.common.base.Functions;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;

public class IjProjectDataPreparerTest {

  private FakeProjectFilesystem filesystem;
  private JavaPackageFinder javaPackageFinder;

  @Before
  public void setUp() {
    filesystem = new FakeProjectFilesystem();
    javaPackageFinder =
        DefaultJavaPackageFinder.createDefaultJavaPackageFinder(
            filesystem, ImmutableSet.of("/java/", "/javatests/"));
  }

  @Test
  public void testWriteModule() throws Exception {
    TargetNode<?> guavaTargetNode =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//third-party/guava:guava"))
            .addSrc(Paths.get("third-party/guava/src/Collections.java"))
            .build();

    TargetNode<?> baseTargetNode =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//java/com/example/base:base"))
            .addDep(guavaTargetNode.getBuildTarget())
            .addSrc(Paths.get("java/com/example/base/Base.java"))
            .build();

    IjModuleGraph moduleGraph =
        IjModuleGraphTest.createModuleGraph(ImmutableSet.of(guavaTargetNode, baseTargetNode));
    IjModule baseModule = IjModuleGraphTest.getModuleForTarget(moduleGraph, baseTargetNode);

    IjProjectTemplateDataPreparer dataPreparer =
        new IjProjectTemplateDataPreparer(
            javaPackageFinder, moduleGraph, filesystem, IjTestProjectConfig.create());

    ContentRoot contentRoot = dataPreparer.getContentRoots(baseModule).asList().get(0);
    assertEquals("file://$MODULE_DIR$", contentRoot.getUrl());

    IjSourceFolder baseSourceFolder = contentRoot.getFolders().iterator().next();
    assertEquals("sourceFolder", baseSourceFolder.getType());
    assertFalse(baseSourceFolder.getIsTestSource());
    assertEquals("com.example.base", baseSourceFolder.getPackagePrefix());
    assertEquals("file://$MODULE_DIR$", baseSourceFolder.getUrl());

    assertThat(
        dataPreparer.getDependencies(baseModule, null),
        contains(
            allOf(
                hasProperty("type", equalTo(IjDependencyListBuilder.Type.MODULE)),
                hasProperty(
                    "data",
                    equalTo(
                        Optional.of(
                            ImmutableDependencyEntryData.ofImpl(
                                "third_party_guava",
                                IjDependencyListBuilder.Scope.COMPILE,
                                false,
                                null)))))));
  }

  @Test
  public void testWriteModulesNoPackageNameWithMultiCellModulesEnabled() throws Exception {
    testWriteModuleWithMultiCellModulesEnabledHelper(null);
  }

  @Test
  public void testWriteModulesPackagePrefixWithMultiCellModulesEnabled() throws Exception {
    testWriteModuleWithMultiCellModulesEnabledHelper("foo.bar");
  }

  private void testWriteModuleWithMultiCellModulesEnabledHelper(@Nullable String packageName)
      throws Exception {
    ProjectFilesystem depFileSystem =
        new FakeProjectFilesystem(
            CanonicalCellName.unsafeOf(Optional.of("dep")), Paths.get("dep").toAbsolutePath());
    ProjectFilesystem mainFileSystem =
        new FakeProjectFilesystem(
            CanonicalCellName.unsafeOf(Optional.of("main")), Paths.get("main").toAbsolutePath());

    Path depPath = Paths.get("java/com/example/Dep.java");
    TargetNode<?> depTargetNode =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("dep//java/com/example:dep"), depFileSystem)
            .addSrc(depPath)
            .build();

    RelPath depPathToProjectRoot = mainFileSystem.relativize(depFileSystem.resolve(depPath));
    if (packageName != null) {
      mainFileSystem.writeContentsToPath(
          "package " + packageName + ";\nclass Dep{}", depPathToProjectRoot.getPath());
    }

    TargetNode<?> mainTargetNode =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("main//java/com/example:main"), mainFileSystem)
            .addSrc(Paths.get("java/com/example/Main.java"))
            .addDep(depTargetNode.getBuildTarget())
            .build();

    IjModuleGraph moduleGraph =
        IjModuleGraphTest.createModuleGraph(
            mainFileSystem,
            ImmutableSet.of(depTargetNode, mainTargetNode),
            ImmutableMap.of(),
            Functions.constant(Optional.empty()),
            AggregationMode.NONE,
            true);
    IjModule depModule = IjModuleGraphTest.getModuleForTarget(moduleGraph, depTargetNode);

    JavaFileParser javaFileParser =
        JavaFileParser.createJavaFileParser(
            JavaCompilationConstants.DEFAULT_JAVAC_OPTIONS.getLanguageLevelOptions());

    IjProjectTemplateDataPreparer dataPreparer =
        new IjProjectTemplateDataPreparer(
            ParsingJavaPackageFinder.preparse(
                javaFileParser,
                mainFileSystem,
                ImmutableSet.of(depPathToProjectRoot.getPath()),
                javaPackageFinder),
            moduleGraph,
            mainFileSystem,
            IjTestProjectConfig.create());

    ImmutableList<ContentRoot> contentRoots = dataPreparer.getContentRoots(depModule);
    assertEquals(1, contentRoots.size());

    ContentRoot contentRoot = contentRoots.get(0);
    assertEquals("file://$MODULE_DIR$", contentRoot.getUrl());
    assertEquals(1, contentRoot.getFolders().size());

    IjSourceFolder sourceFolder = contentRoot.getFolders().get(0);
    assertEquals("sourceFolder", sourceFolder.getType());
    assertFalse(sourceFolder.getIsTestSource());
    assertEquals(packageName, sourceFolder.getPackagePrefix());
    assertEquals("file://$MODULE_DIR$", sourceFolder.getUrl());

    IjModule mainModule = IjModuleGraphTest.getModuleForTarget(moduleGraph, mainTargetNode);

    assertThat(
        dataPreparer.getDependencies(mainModule, null),
        contains(
            allOf(
                hasProperty("type", equalTo(IjDependencyListBuilder.Type.MODULE)),
                hasProperty(
                    "data",
                    equalTo(
                        Optional.of(
                            ImmutableDependencyEntryData.ofImpl(
                                "___dep_java_com_example",
                                IjDependencyListBuilder.Scope.COMPILE,
                                false,
                                null)))))));
  }

  @Test
  public void testDependencies() {
    TargetNode<?> hamcrestTargetNode =
        PrebuiltJarBuilder.createBuilder(
                BuildTargetFactory.newInstance("//third-party/hamcrest:hamcrest"))
            .setBinaryJar(Paths.get("third-party/hamcrest/hamcrest.jar"))
            .build();

    TargetNode<?> guavaTargetNode =
        PrebuiltJarBuilder.createBuilder(
                BuildTargetFactory.newInstance("//third-party/guava:guava"))
            .setBinaryJar(Paths.get("third-party/guava/guava.jar"))
            .build();

    TargetNode<?> baseTargetNode =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//java/com/example/base:base"))
            .addDep(guavaTargetNode.getBuildTarget())
            .addSrc(Paths.get("java/com/example/base/Base.java"))
            .build();

    TargetNode<?> baseGenruleTarget =
        GenruleBuilder.newGenruleBuilder(
                BuildTargetFactory.newInstance("//java/com/example/base:genrule"))
            .setOut("out")
            .build();

    TargetNode<?> baseInlineTestsTargetNode =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//java/com/example/base:tests"))
            .addDep(hamcrestTargetNode.getBuildTarget())
            .addSrc(Paths.get("java/com/example/base/TestBase.java"))
            .addSrcTarget(baseGenruleTarget.getBuildTarget())
            .build();

    TargetNode<?> baseTestsTargetNode =
        JavaTestBuilder.createBuilder(
                BuildTargetFactory.newInstance("//javatests/com/example/base:base"))
            .addDep(baseTargetNode.getBuildTarget())
            .addDep(hamcrestTargetNode.getBuildTarget())
            .addSrc(Paths.get("javatests/com/example/base/Base.java"))
            .build();

    IjModuleGraph moduleGraph =
        IjModuleGraphTest.createModuleGraph(
            ImmutableSet.of(
                hamcrestTargetNode,
                guavaTargetNode,
                baseTargetNode,
                baseGenruleTarget,
                baseInlineTestsTargetNode,
                baseTestsTargetNode),
            ImmutableMap.of(
                baseInlineTestsTargetNode, FakeSourcePath.of("buck-out/baseInlineTests.jar")),
            Functions.constant(Optional.empty()));
    IjLibrary hamcrestLibrary =
        IjModuleGraphTest.getLibraryForTarget(moduleGraph, hamcrestTargetNode);
    IjLibrary guavaLibrary = IjModuleGraphTest.getLibraryForTarget(moduleGraph, guavaTargetNode);
    IjModule baseModule = IjModuleGraphTest.getModuleForTarget(moduleGraph, baseTargetNode);
    IjModule baseTestModule =
        IjModuleGraphTest.getModuleForTarget(moduleGraph, baseTestsTargetNode);

    IjProjectTemplateDataPreparer dataPreparer =
        new IjProjectTemplateDataPreparer(
            javaPackageFinder, moduleGraph, filesystem, IjTestProjectConfig.create());

    assertEquals(
        IjModuleGraphTest.getModuleForTarget(moduleGraph, baseInlineTestsTargetNode),
        IjModuleGraphTest.getModuleForTarget(moduleGraph, baseTargetNode));

    assertThat(
        dataPreparer.getDependencies(baseModule, null),
        contains(
            allOf(
                hasProperty("type", equalTo(IjDependencyListBuilder.Type.LIBRARY)),
                hasProperty(
                    "data",
                    equalTo(
                        Optional.of(
                            ImmutableDependencyEntryData.ofImpl(
                                "//java/com/example/base:tests",
                                IjDependencyListBuilder.Scope.PROVIDED,
                                true,
                                null))))),
            allOf(
                hasProperty("type", equalTo(IjDependencyListBuilder.Type.LIBRARY)),
                hasProperty(
                    "data",
                    equalTo(
                        Optional.of(
                            ImmutableDependencyEntryData.ofImpl(
                                guavaLibrary.getName(),
                                IjDependencyListBuilder.Scope.COMPILE,
                                false,
                                null))))),
            allOf(
                hasProperty("type", equalTo(IjDependencyListBuilder.Type.LIBRARY)),
                hasProperty(
                    "data",
                    equalTo(
                        Optional.of(
                            ImmutableDependencyEntryData.ofImpl(
                                hamcrestLibrary.getName(),
                                IjDependencyListBuilder.Scope.COMPILE,
                                false,
                                null)))))));

    assertThat(
        dataPreparer.getDependencies(baseTestModule, null),
        contains(
            allOf(
                hasProperty("type", equalTo(IjDependencyListBuilder.Type.MODULE)),
                hasProperty(
                    "data",
                    equalTo(
                        Optional.of(
                            ImmutableDependencyEntryData.ofImpl(
                                baseModule.getName(),
                                IjDependencyListBuilder.Scope.TEST,
                                false,
                                null))))),
            allOf(
                hasProperty("type", equalTo(IjDependencyListBuilder.Type.LIBRARY)),
                hasProperty(
                    "data",
                    equalTo(
                        Optional.of(
                            ImmutableDependencyEntryData.ofImpl(
                                hamcrestLibrary.getName(),
                                IjDependencyListBuilder.Scope.TEST,
                                false,
                                null)))))));
  }

  @Test
  public void testEmptyRootModule() {

    Path baseTargetSrcFilePath = Paths.get("java/com/example/base/Base.java");
    TargetNode<?> baseTargetNode =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//java/com/example/base:base"))
            .addSrc(baseTargetSrcFilePath)
            .build();

    IjModuleGraph moduleGraph =
        IjModuleGraphTest.createModuleGraph(ImmutableSet.of(baseTargetNode));
    IjProjectTemplateDataPreparer dataPreparer =
        new IjProjectTemplateDataPreparer(
            javaPackageFinder, moduleGraph, filesystem, IjTestProjectConfig.create());

    assertThat(
        dataPreparer.getModulesToBeWritten(),
        containsInAnyOrder(
            IjModule.builder()
                .setModuleBasePath(Paths.get("java/com/example/base"))
                .setTargets(ImmutableSet.of(baseTargetNode.getBuildTarget()))
                .addFolders(
                    new SourceFolder(
                        Paths.get("java/com/example/base"),
                        true,
                        ImmutableSortedSet.of(baseTargetSrcFilePath)))
                .setModuleType(IjModuleType.JAVA_MODULE)
                .build(),
            IjModule.builder()
                .setModuleBasePath(Paths.get(""))
                .setTargets(ImmutableSet.of())
                .setModuleType(IjModuleType.UNKNOWN_MODULE)
                .build()));
  }

  @Test
  public void testModuleIndex() {
    TargetNode<?> guavaTargetNode =
        PrebuiltJarBuilder.createBuilder(
                BuildTargetFactory.newInstance("//third-party/guava:guava"))
            .setBinaryJar(Paths.get("third-party/guava/guava.jar"))
            .build();

    TargetNode<?> baseTargetNode =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//java/com/example/base:base"))
            .addDep(guavaTargetNode.getBuildTarget())
            .addSrc(Paths.get("java/com/example/base/Base.java"))
            .build();

    TargetNode<?> baseTestsTargetNode =
        JavaTestBuilder.createBuilder(
                BuildTargetFactory.newInstance("//javatests/com/example/base:base"))
            .addDep(baseTargetNode.getBuildTarget())
            .addSrc(Paths.get("javatests/com/example/base/Base.java"))
            .build();

    IjModuleGraph moduleGraph =
        IjModuleGraphTest.createModuleGraph(
            ImmutableSet.of(guavaTargetNode, baseTargetNode, baseTestsTargetNode));
    IjProjectTemplateDataPreparer dataPreparer =
        new IjProjectTemplateDataPreparer(
            javaPackageFinder, moduleGraph, filesystem, IjTestProjectConfig.create());

    // Libraries don't go into the index.
    assertEquals(
        ImmutableSet.of(
            ModuleIndexEntry.of(
                "file://$PROJECT_DIR$/project_root.iml", Paths.get("project_root.iml"), null),
            ModuleIndexEntry.of(
                "file://$PROJECT_DIR$/java/com/example/base/java_com_example_base.iml",
                Paths.get("java/com/example/base/java_com_example_base.iml"),
                "modules"),
            ModuleIndexEntry.of(
                "file://$PROJECT_DIR$/javatests/com/example/base/javatests_com_example_base.iml",
                Paths.get("javatests/com/example/base/javatests_com_example_base.iml"),
                "modules")),
        dataPreparer.getModuleIndexEntries());
  }

  @Test
  public void testModuleIndexWithMultiCellModulesEnabled() {
    ProjectFilesystem depFileSystem =
        new FakeProjectFilesystem(
            CanonicalCellName.unsafeOf(Optional.of("dep")), Paths.get("dep").toAbsolutePath());
    ProjectFilesystem mainFileSystem =
        new FakeProjectFilesystem(
            CanonicalCellName.unsafeOf(Optional.of("main")), Paths.get("main").toAbsolutePath());

    TargetNode<?> depTargetNode =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("dep//java/com/example:dep"), depFileSystem)
            .addSrc(Paths.get("java/com/example/Dep.java"))
            .build();

    TargetNode<?> mainTargetNode =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("main//java/com/example:main"), mainFileSystem)
            .addSrc(Paths.get("java/com/example/Main.java"))
            .addDep(depTargetNode.getBuildTarget())
            .build();

    IjModuleGraph moduleGraph =
        IjModuleGraphTest.createModuleGraph(
            mainFileSystem,
            ImmutableSet.of(depTargetNode, mainTargetNode),
            ImmutableMap.of(),
            Functions.constant(Optional.empty()),
            AggregationMode.NONE,
            true);
    IjProjectTemplateDataPreparer dataPreparer =
        new IjProjectTemplateDataPreparer(
            javaPackageFinder, moduleGraph, filesystem, IjTestProjectConfig.create());
    assertEquals(
        ImmutableSet.of(
            ModuleIndexEntry.of(
                "file://$PROJECT_DIR$/../dep/java/com/example/___dep_java_com_example.iml",
                Paths.get("../dep/java/com/example/___dep_java_com_example.iml"),
                "modules"),
            ModuleIndexEntry.of(
                "file://$PROJECT_DIR$/java/com/example/java_com_example.iml",
                Paths.get("java/com/example/java_com_example.iml"),
                "modules"),
            ModuleIndexEntry.of(
                "file://$PROJECT_DIR$/project_root.iml", Paths.get("project_root.iml"), null)),
        dataPreparer.getModuleIndexEntries());
  }

  @Test
  public void testExcludePaths() throws Exception {
    /**
     * Fake filesystem structure .idea |- misc.xml .git |- HEAD java |- com |- BUCK |- data |-
     * wsad.txt |- src |- BUCK |- Source.java |- foo |- Foo.java |- src2 |- Code.java |- org |- bar
     * |- Bar.java lib |- BUCK |- guava.jar
     */
    ImmutableSet<Path> paths =
        ImmutableSet.of(
            Paths.get(".idea/misc.xml"),
            Paths.get(".git/HEAD"),
            Paths.get("java/com/BUCK"),
            Paths.get("java/com/data/wsad.txt"),
            Paths.get("java/com/src/BUCK"),
            Paths.get("java/com/src/Source.java"),
            Paths.get("java/com/src/foo/Foo.java"),
            Paths.get("java/org/bar/Bar.java"),
            Paths.get("lib/BUCK"),
            Paths.get("lib/guava.jar"));

    FakeProjectFilesystem filesystemForExcludesTest =
        new FakeProjectFilesystem(
            FakeClock.doNotCare(),
            CanonicalCellName.rootCell(),
            AbsPath.of(Paths.get(".").toAbsolutePath()),
            paths);

    TargetNode<?> guavaTargetNode =
        PrebuiltJarBuilder.createBuilder(BuildTargetFactory.newInstance("//lib:guava"))
            .setBinaryJar(Paths.get("lib/guava.jar"))
            .build();

    TargetNode<?> srcTargetNode =
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//java/com/src:src"))
            .addDep(guavaTargetNode.getBuildTarget())
            .addSrc(Paths.get("java/com/src/Source.java"))
            .build();

    TargetNode<?> src2TargetNode =
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//java/com:src2"))
            .addDep(guavaTargetNode.getBuildTarget())
            .addSrc(Paths.get("java/com/src2/Code.java"))
            .build();

    TargetNode<?> rootTargetNode =
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:root")).build();

    IjModuleGraph moduleGraph =
        IjModuleGraphTest.createModuleGraph(
            ImmutableSet.of(guavaTargetNode, srcTargetNode, src2TargetNode, rootTargetNode));
    IjModule srcModule = IjModuleGraphTest.getModuleForTarget(moduleGraph, srcTargetNode);
    IjModule src2Module = IjModuleGraphTest.getModuleForTarget(moduleGraph, src2TargetNode);
    IjModule rootModule = IjModuleGraphTest.getModuleForTarget(moduleGraph, rootTargetNode);

    IjProjectTemplateDataPreparer dataPreparer =
        new IjProjectTemplateDataPreparer(
            javaPackageFinder,
            moduleGraph,
            filesystemForExcludesTest,
            IjTestProjectConfig.create());

    assertEquals(
        ImmutableSet.of(Paths.get("java/com/src/foo")),
        distillExcludeFolders(dataPreparer.createExcludes(srcModule)));

    assertEquals(
        ImmutableSet.of(Paths.get("java/com/data")),
        distillExcludeFolders(dataPreparer.createExcludes(src2Module)));

    // In this case it's fine to exclude "lib" as there is no source code there.
    assertEquals(
        ImmutableSet.of(Paths.get(".git"), Paths.get("java/org"), Paths.get("lib")),
        distillExcludeFolders(dataPreparer.createExcludes(rootModule)));
  }

  @Test
  public void testCreatePackageLookupPahtSet() {
    TargetNode<?> guavaTargetNode =
        PrebuiltJarBuilder.createBuilder(BuildTargetFactory.newInstance("//lib:guava"))
            .setBinaryJar(Paths.get("lib/guava.jar"))
            .build();

    Path sourcePath = Paths.get("java/com/src/Source.java");
    Path subSourcePath = Paths.get("java/com/src/subpackage/SubSource.java");
    TargetNode<?> srcTargetNode =
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//java/com/src:src"))
            .addDep(guavaTargetNode.getBuildTarget())
            .addSrc(sourcePath)
            .addSrc(subSourcePath)
            .build();

    IjModuleGraph moduleGraph =
        IjModuleGraphTest.createModuleGraph(ImmutableSet.of(guavaTargetNode, srcTargetNode));

    assertThat(
        IjProjectTemplateDataPreparer.createPackageLookupPathSet(moduleGraph),
        containsInAnyOrder(subSourcePath, sourcePath));
  }

  private static ImmutableSet<Path> distillExcludeFolders(ImmutableCollection<IjFolder> folders) {
    Preconditions.checkArgument(
        !FluentIterable.from(folders).anyMatch(input -> !(input instanceof ExcludeFolder)));
    return FluentIterable.from(folders).uniqueIndex(IjFolder::getPath).keySet();
  }
}
