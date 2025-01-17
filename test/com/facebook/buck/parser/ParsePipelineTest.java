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

package com.facebook.buck.parser;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.Cells;
import com.facebook.buck.core.cell.DefaultCellNameResolverProvider;
import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.ForwardRelPath;
import com.facebook.buck.core.model.BuildFileTree;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.ConstantHostTargetConfigurationResolver;
import com.facebook.buck.core.model.UnconfiguredTargetConfiguration;
import com.facebook.buck.core.model.impl.FilesystemBackedBuildFileTree;
import com.facebook.buck.core.model.impl.MultiPlatformTargetConfigurationTransformer;
import com.facebook.buck.core.model.platform.ThrowingPlatformResolver;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.model.targetgraph.TargetNodeMaybeIncompatible;
import com.facebook.buck.core.model.targetgraph.impl.TargetNodeFactory;
import com.facebook.buck.core.parser.buildtargetparser.ParsingUnconfiguredBuildTargetViewFactory;
import com.facebook.buck.core.plugin.impl.BuckPluginManagerFactory;
import com.facebook.buck.core.rules.knowntypes.TestKnownRuleTypesProvider;
import com.facebook.buck.core.rules.knowntypes.provider.KnownRuleTypesProvider;
import com.facebook.buck.core.select.impl.SelectorFactory;
import com.facebook.buck.core.select.impl.SelectorListFactory;
import com.facebook.buck.core.select.impl.ThrowingSelectorListResolver;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.watchman.WatchmanError;
import com.facebook.buck.io.watchman.WatchmanFactory;
import com.facebook.buck.parser.api.ForwardingProjectBuildFileParserDecorator;
import com.facebook.buck.parser.api.PackageFileManifest;
import com.facebook.buck.parser.api.ProjectBuildFileParser;
import com.facebook.buck.parser.config.ParserConfig;
import com.facebook.buck.parser.detector.TargetConfigurationDetectorFactory;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.parser.exceptions.NoSuchBuildTargetException;
import com.facebook.buck.rules.coercer.ConstructorArgMarshaller;
import com.facebook.buck.rules.coercer.DefaultConstructorArgMarshaller;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.concurrent.MostExecutors;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Streams;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class ParsePipelineTest {
  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Rule public ExpectedException expectedException = ExpectedException.none();

  BuckEventBus eventBus;

  @Before
  public void setUp() {
    eventBus = BuckEventBusForTests.newInstance();
  }

  @Test
  public void testIgnoredDirsErr() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "ignored_dirs_err", tmp);
    workspace.setUp();

    // enforce creation of targetNode's
    ProcessResult processResult = workspace.runBuckBuild("//libraries/path-to-ignore:ignored-lib");
    processResult.assertFailure();
    assertThat(
        processResult.getStderr(),
        containsString(" cannot be built because it is defined in an ignored directory."));
  }

  private <T> void waitForAll(Iterable<T> items, Predicate<T> predicate)
      throws InterruptedException {
    boolean allThere = false;
    for (int i = 0; i < 50; ++i) {
      allThere |= Streams.stream(items).allMatch(predicate);
      if (allThere) {
        break;
      }
      Thread.sleep(100);
    }
    assertThat(allThere, is(true));
  }

  @Test
  public void speculativeDepsTraversal() throws Exception {
    Fixture fixture = createMultiThreadedFixture("pipeline_test");
    Cell cell = fixture.getCells();
    TargetNode<?> libTargetNode =
        fixture
            .getTargetNodeParsePipeline()
            .getNode(cell, BuildTargetFactory.newInstance("//:lib"), DependencyStack.root())
            .assertGetTargetNode(DependencyStack.root());

    waitForAll(libTargetNode.getBuildDeps(), fixture::targetExistsInCache);
    fixture.close();
  }

  @Test
  public void speculativeDepsTraversalWhenGettingAllNodes() throws Exception {
    Fixture fixture = createMultiThreadedFixture("pipeline_test");
    Cell cell = fixture.getCells();
    ImmutableList<TargetNodeMaybeIncompatible> libTargetNodes =
        fixture
            .getTargetNodeParsePipeline()
            .getAllRequestedTargetNodes(cell, ForwardRelPath.of("BUCK"), Optional.empty());
    FluentIterable<BuildTarget> allDeps =
        FluentIterable.from(libTargetNodes)
            .transformAndConcat(
                input -> input.assertGetTargetNode(DependencyStack.root()).getBuildDeps());
    waitForAll(allDeps, fixture::targetExistsInCache);
    fixture.close();
  }

  @Test
  public void missingTarget() throws Exception {
    try (Fixture fixture = createMultiThreadedFixture("parse_rule_with_bad_dependency")) {
      Cell cell = fixture.getCells();
      expectedException.expect(NoSuchBuildTargetException.class);
      expectedException.expectMessage(
          "The rule //:notthere could not be found.\nPlease check the spelling and whether");
      fixture
          .getTargetNodeParsePipeline()
          .getNode(cell, BuildTargetFactory.newInstance("//:notthere"), DependencyStack.root());
    }
  }

  @Test
  public void missingBuildFile() throws Exception {
    try (Fixture fixture = createMultiThreadedFixture("parse_rule_with_bad_dependency")) {
      Cell cell = fixture.getCells();
      expectedException.expect(BuildFileParseException.class);
      expectedException.expectMessage("BUCK cannot be loaded because it does not exist");
      fixture
          .getTargetNodeParsePipeline()
          .getAllRequestedTargetNodes(
              cell, ForwardRelPath.of("no/such/file/BUCK"), Optional.empty());
    }
  }

  @Test
  public void missingBuildFileRaw() throws Exception {
    try (Fixture fixture = createMultiThreadedFixture("parse_rule_with_bad_dependency")) {
      Cell cell = fixture.getCells();
      expectedException.expect(BuildFileParseException.class);
      expectedException.expectMessage("BUCK cannot be loaded because it does not exist");
      fixture
          .getBuildFileRawNodeParsePipeline()
          .getFile(cell, ForwardRelPath.of("no/such/file/BUCK"));
    }
  }

  @Test
  public void badDependency() throws Exception {
    try (Fixture fixture = createMultiThreadedFixture("parse_rule_with_bad_dependency")) {
      Cell cell = fixture.getCells();
      fixture
          .getTargetNodeParsePipeline()
          .getNode(cell, BuildTargetFactory.newInstance("//:base"), DependencyStack.root());
    }
  }

  @Test
  public void recoversAfterSyntaxError() throws Exception {
    try (Fixture fixture = createSynchronousExecutionFixture("syntax_error")) {
      Cell cell = fixture.getCells();
      try {
        cell.getFilesystem().getRootPath();
        fixture
            .getTargetNodeParsePipeline()
            .getNode(cell, BuildTargetFactory.newInstance("//error:error"), DependencyStack.root());
        Assert.fail("Expected BuildFileParseException");
      } catch (BuildFileParseException e) {
        assertThat(e.getMessage(), containsString("Cannot parse"));
      }

      fixture
          .getTargetNodeParsePipeline()
          .getNode(
              cell, BuildTargetFactory.newInstance("//correct:correct"), DependencyStack.root());
    }
  }

  @Test
  public void invalidedBuildFileInvalidatesRelevantNodesAndManifest() throws Exception {
    Fixture fixture = createMultiThreadedFixture("pipeline_test");
    Cell cell = fixture.getCells();

    ForwardRelPath rootBuildFile = ForwardRelPath.of("BUCK");

    BuildTarget libTarget = BuildTargetFactory.newInstance("//:lib");

    TargetNode<?> libTargetNode =
        fixture
            .getTargetNodeParsePipeline()
            .getNode(cell, libTarget, DependencyStack.root())
            .assertGetTargetNode(DependencyStack.root());

    Set<BuildTarget> deps = libTargetNode.getBuildDeps();
    for (BuildTarget buildTarget : deps) {
      fixture.getTargetNodeParsePipeline().getNode(cell, buildTarget, DependencyStack.root());
    }

    // Validate expected state
    assertTrue(fixture.buildFileExistsInCache(rootBuildFile));
    assertTrue(fixture.targetExistsInCache(libTarget));

    ForwardRelPath aBuildFile = ForwardRelPath.of("a/BUCK");
    assertTrue(fixture.buildFileExistsInCache(aBuildFile));
    BuildTarget aTarget = BuildTargetFactory.newInstance("//a:a");
    assertTrue(fixture.targetExistsInCache(aTarget));

    ForwardRelPath bBuildFile = ForwardRelPath.of("b/BUCK");
    assertTrue(fixture.buildFileExistsInCache(bBuildFile));
    BuildTarget bTarget = BuildTargetFactory.newInstance("//b:b");
    assertTrue(fixture.targetExistsInCache(bTarget));

    // Invalidation of build file should only remove the nodes in that build file
    fixture.invalidatePath(rootBuildFile);

    assertFalse(fixture.buildFileExistsInCache(rootBuildFile));
    assertFalse(fixture.targetExistsInCache(libTarget));

    assertTrue(fixture.buildFileExistsInCache(aBuildFile));
    assertTrue(fixture.targetExistsInCache(aTarget));

    assertTrue(fixture.buildFileExistsInCache(bBuildFile));
    assertTrue(fixture.targetExistsInCache(bTarget));
  }

  @Test
  public void invalidatedIncludeFileInvalidatesRelevantNodesAndManifest() throws Exception {
    Fixture fixture = createMultiThreadedFixture("pipeline_test");
    Cell cell = fixture.getCells();

    ForwardRelPath rootBuildFile = ForwardRelPath.of("BUCK");

    BuildTarget libTarget = BuildTargetFactory.newInstance("//:lib");

    TargetNode<?> libTargetNode =
        fixture
            .getTargetNodeParsePipeline()
            .getNode(cell, libTarget, DependencyStack.root())
            .assertGetTargetNode(DependencyStack.root());

    Set<BuildTarget> deps = libTargetNode.getBuildDeps();
    for (BuildTarget buildTarget : deps) {
      fixture.getTargetNodeParsePipeline().getNode(cell, buildTarget, DependencyStack.root());
    }

    // Validate expected state
    assertTrue(fixture.buildFileExistsInCache(rootBuildFile));
    assertTrue(fixture.targetExistsInCache(libTarget));

    ForwardRelPath aBuildFile = ForwardRelPath.of("a/BUCK");
    assertTrue(fixture.buildFileExistsInCache(aBuildFile));
    BuildTarget aTarget = BuildTargetFactory.newInstance("//a:a");
    assertTrue(fixture.targetExistsInCache(aTarget));

    ForwardRelPath bBuildFile = ForwardRelPath.of("b/BUCK");
    assertTrue(fixture.buildFileExistsInCache(bBuildFile));
    BuildTarget bTarget = BuildTargetFactory.newInstance("//b:b");
    assertTrue(fixture.targetExistsInCache(bTarget));

    // Invalidation of an include file only invalidates build files that depend on it
    fixture.invalidatePath(ForwardRelPath.of("a/test.bzl"));

    assertTrue(fixture.buildFileExistsInCache(rootBuildFile));
    assertTrue(fixture.targetExistsInCache(libTarget));

    assertFalse(fixture.buildFileExistsInCache(aBuildFile));
    assertFalse(fixture.targetExistsInCache(aTarget));

    assertTrue(fixture.buildFileExistsInCache(bBuildFile));
    assertTrue(fixture.targetExistsInCache(bTarget));
  }

  @Test
  public void invalidatedSourceFileDoesNotInvalidateNodesOrManifest() throws Exception {
    Fixture fixture = createMultiThreadedFixture("pipeline_test");
    Cell cell = fixture.getCells();

    ForwardRelPath rootBuildFile = ForwardRelPath.of("BUCK");

    BuildTarget libTarget = BuildTargetFactory.newInstance("//:lib");

    TargetNode<?> libTargetNode =
        fixture
            .getTargetNodeParsePipeline()
            .getNode(cell, libTarget, DependencyStack.root())
            .assertGetTargetNode(DependencyStack.root());

    Set<BuildTarget> deps = libTargetNode.getBuildDeps();
    for (BuildTarget buildTarget : deps) {
      fixture.getTargetNodeParsePipeline().getNode(cell, buildTarget, DependencyStack.root());
    }

    // Validate expected state
    assertTrue(fixture.buildFileExistsInCache(rootBuildFile));
    assertTrue(fixture.targetExistsInCache(libTarget));

    ForwardRelPath aBuildFile = ForwardRelPath.of("a/BUCK");
    assertTrue(fixture.buildFileExistsInCache(aBuildFile));
    BuildTarget aTarget = BuildTargetFactory.newInstance("//a:a");
    assertTrue(fixture.targetExistsInCache(aTarget));

    ForwardRelPath bBuildFile = ForwardRelPath.of("b/BUCK");
    assertTrue(fixture.buildFileExistsInCache(bBuildFile));
    BuildTarget bTarget = BuildTargetFactory.newInstance("//b:b");
    assertTrue(fixture.targetExistsInCache(bTarget));

    // Invalidation of a source file does not invalidate any nodes/manifests
    fixture.invalidatePath(ForwardRelPath.of("b/B.java"));

    assertTrue(fixture.buildFileExistsInCache(rootBuildFile));
    assertTrue(fixture.targetExistsInCache(libTarget));

    assertTrue(fixture.buildFileExistsInCache(aBuildFile));
    assertTrue(fixture.targetExistsInCache(aTarget));

    assertTrue(fixture.buildFileExistsInCache(bBuildFile));
    assertTrue(fixture.targetExistsInCache(bTarget));
  }

  @Test
  public void emptyPackageManifestCachedForNonExistentPackageFile() throws Exception {
    Fixture fixture = createMultiThreadedFixture("pipeline_test");
    Cell cell = fixture.getCells();

    BuildTarget aTarget = BuildTargetFactory.newInstance("//a:a");

    fixture.getTargetNodeParsePipeline().getNode(cell, aTarget, DependencyStack.root());

    ForwardRelPath nonExistentPackageFile = ForwardRelPath.of("a/PACKAGE");
    assertFalse(cell.getFilesystem().isFile(nonExistentPackageFile));

    assertTrue(fixture.packageFileExistsInCache(nonExistentPackageFile));

    PackageFileManifest cachedPackageFileManifest =
        fixture
            .daemonicParserState
            .getPackageFileCache()
            .lookupComputedNode(
                cell, nonExistentPackageFile, fixture.daemonicParserState.validationToken())
            .get();
    assertSame(PackageFileParsePipeline.NONEXISTENT_PACKAGE, cachedPackageFileManifest);
  }

  @Test
  public void invalidatedPackageFileInvalidatesNodeButNotManifest() throws Exception {
    Fixture fixture = createMultiThreadedFixture("pipeline_test");
    Cell cell = fixture.getCells();

    ForwardRelPath rootBuildFile = ForwardRelPath.of("BUCK");

    BuildTarget libTarget = BuildTargetFactory.newInstance("//:lib");

    TargetNode<?> libTargetNode =
        fixture
            .getTargetNodeParsePipeline()
            .getNode(cell, libTarget, DependencyStack.root())
            .assertGetTargetNode(DependencyStack.root());

    Set<BuildTarget> deps = libTargetNode.getBuildDeps();
    for (BuildTarget buildTarget : deps) {
      fixture.getTargetNodeParsePipeline().getNode(cell, buildTarget, DependencyStack.root());
    }

    // Validate expected state
    assertTrue(fixture.buildFileExistsInCache(rootBuildFile));
    assertTrue(fixture.targetExistsInCache(libTarget));

    ForwardRelPath aBuildFile = ForwardRelPath.of("a/BUCK");
    assertTrue(fixture.buildFileExistsInCache(aBuildFile));
    BuildTarget aTarget = BuildTargetFactory.newInstance("//a:a");
    assertTrue(fixture.targetExistsInCache(aTarget));

    ForwardRelPath bBuildFile = ForwardRelPath.of("b/BUCK");
    assertTrue(fixture.buildFileExistsInCache(bBuildFile));
    BuildTarget bTarget = BuildTargetFactory.newInstance("//b:b");
    assertTrue(fixture.targetExistsInCache(bTarget));

    ForwardRelPath packageFile = ForwardRelPath.of("b/PACKAGE");
    assertTrue(fixture.packageFileExistsInCache(packageFile));

    // Invalidation of a package only invalidates the nodes of the dependent build file
    fixture.invalidatePath(packageFile);

    assertTrue(fixture.buildFileExistsInCache(rootBuildFile));
    assertTrue(fixture.targetExistsInCache(libTarget));

    assertTrue(fixture.buildFileExistsInCache(aBuildFile));
    assertTrue(fixture.targetExistsInCache(aTarget));

    assertTrue(fixture.buildFileExistsInCache(bBuildFile));
    assertFalse(fixture.targetExistsInCache(bTarget));

    assertFalse(fixture.packageFileExistsInCache(packageFile));
  }

  @Test
  public void packageFileInvalidationInvalidatesAllChildNodes() throws Exception {
    try (Fixture fixture = createMultiThreadedFixture("package_inheritance")) {
      Cell cell = fixture.getCells();
      ForwardRelPath barBuildFilePath = ForwardRelPath.of("bar/BUCK");
      List<TargetNodeMaybeIncompatible> nodes =
          fixture
              .getTargetNodeParsePipeline()
              .getAllRequestedTargetNodes(cell, barBuildFilePath, Optional.empty());

      // Validate the cache has the data we expect
      assertTrue(fixture.buildFileExistsInCache(barBuildFilePath));
      assertEquals(4, nodes.size());
      assertTrue(fixture.targetExistsInCache(nodes.get(0).getBuildTarget()));
      assertTrue(fixture.targetExistsInCache(nodes.get(1).getBuildTarget()));

      ForwardRelPath parentPackageFile = ForwardRelPath.of("PACKAGE");
      assertTrue(fixture.packageFileExistsInCache(parentPackageFile));

      // Invalidate the package file
      fixture.invalidatePath(parentPackageFile);

      // Verify the expected cache state
      assertTrue(fixture.buildFileExistsInCache(barBuildFilePath));
      assertFalse(fixture.targetExistsInCache(nodes.get(0).getBuildTarget()));
      assertFalse(fixture.targetExistsInCache(nodes.get(1).getBuildTarget()));
      assertFalse(fixture.packageFileExistsInCache(parentPackageFile));
    }
  }

  @Test
  public void invalidationOfNonExistentPackageFileInvalidatesChildNodes() throws Exception {
    Fixture fixture = createMultiThreadedFixture("pipeline_test");
    Cell cell = fixture.getCells();

    BuildTarget aTarget = BuildTargetFactory.newInstance("//a:a");

    fixture.getTargetNodeParsePipeline().getNode(cell, aTarget, DependencyStack.root());

    // Verify that a cached package file manifest exists for a non-existent file
    ForwardRelPath nonExistentPackageFile = ForwardRelPath.of("a/PACKAGE");
    ForwardRelPath parentNonExistentPackafeFile = ForwardRelPath.of("PACKAGE");
    assertFalse(cell.getFilesystem().isFile(nonExistentPackageFile));
    assertFalse(cell.getFilesystem().isFile(parentNonExistentPackafeFile));

    assertTrue(fixture.packageFileExistsInCache(nonExistentPackageFile));
    assertTrue(fixture.packageFileExistsInCache(parentNonExistentPackafeFile));

    assertTrue(fixture.targetExistsInCache(aTarget));

    // Invalidation of a non-existent parent package file invalidates appropriate nodes
    fixture.invalidatePath(parentNonExistentPackafeFile);

    assertFalse(fixture.targetExistsInCache(aTarget));
    // The package file at a/PACKAGE should not be invalidated
    assertTrue(fixture.packageFileExistsInCache(nonExistentPackageFile));
    assertFalse(fixture.packageFileExistsInCache(parentNonExistentPackafeFile));
  }

  private static class TypedParsePipelineCache<K, V> implements PipelineNodeCache.Cache<K, V> {
    private final Map<K, V> nodeMap = new HashMap<>();

    @Override
    public synchronized Optional<V> lookupComputedNode(
        Cell cell, K key, DaemonicParserValidationToken validationToken) {
      return Optional.ofNullable(nodeMap.get(key));
    }

    @Override
    public synchronized V putComputedNodeIfNotPresent(
        Cell cell,
        K key,
        V value,
        boolean targetIsConfiguration,
        DaemonicParserValidationToken validationToken) {
      if (!nodeMap.containsKey(key)) {
        nodeMap.put(key, value);
      }
      return nodeMap.get(key);
    }
  }

  private Fixture createMultiThreadedFixture(String scenario) throws Exception {
    return new Fixture(
        scenario,
        MoreExecutors.listeningDecorator(
            MostExecutors.newMultiThreadExecutor("ParsePipelineTest", 4)),
        SpeculativeParsing.ENABLED);
  }

  // Use this method to make sure the Pipeline doesn't execute stuff on another thread, useful
  // if you're poking at the cache state directly.
  private Fixture createSynchronousExecutionFixture(String scenario) throws Exception {
    return new Fixture(
        scenario, MoreExecutors.newDirectExecutorService(), SpeculativeParsing.DISABLED);
  }

  private class Fixture implements AutoCloseable {

    private final int NUM_THREADS = 4;

    private final ProjectWorkspace workspace;
    private final BuckEventBus eventBus;
    private final TestConsole console;
    private final UnconfiguredTargetNodeToTargetNodeParsePipeline targetNodeParsePipeline;
    private final BuildFileRawNodeParsePipeline buildFileRawNodeParsePipeline;
    private final ProjectBuildFileParserPool projectBuildFileParserPool;
    private final Cells cells;
    private final DaemonicParserState daemonicParserState;
    private final ListeningExecutorService executorService;
    private final Set<CloseRecordingProjectBuildFileParserDecorator> projectBuildFileParsers;

    public Fixture(
        String scenario,
        ListeningExecutorService executorService,
        SpeculativeParsing speculativeParsing)
        throws Exception {
      this.workspace = TestDataHelper.createProjectWorkspaceForScenario(this, scenario, tmp);
      this.eventBus = BuckEventBusForTests.newInstance();
      this.console = new TestConsole();
      this.executorService = executorService;
      this.projectBuildFileParsers = new HashSet<>();
      this.workspace.setUp();

      this.cells = new Cells(this.workspace.asCellProvider());

      TypeCoercerFactory coercerFactory = new DefaultTypeCoercerFactory();
      ConstructorArgMarshaller constructorArgMarshaller = new DefaultConstructorArgMarshaller();

      this.daemonicParserState = new DaemonicParserState();

      projectBuildFileParserPool =
          new ProjectBuildFileParserPool(
              NUM_THREADS,
              (buckEventBus, input, watchman, edenClient) -> {
                CloseRecordingProjectBuildFileParserDecorator buildFileParser =
                    new CloseRecordingProjectBuildFileParserDecorator(
                        new DefaultProjectBuildFileParserFactory(
                                coercerFactory,
                                new ParserPythonInterpreterProvider(
                                    input.getBuckConfig(), new ExecutableFinder()),
                                TestKnownRuleTypesProvider.create(
                                    BuckPluginManagerFactory.createPluginManager()))
                            .createFileParser(eventBus, input, watchman, edenClient));
                synchronized (projectBuildFileParsers) {
                  projectBuildFileParsers.add(buildFileParser);
                }
                return buildFileParser;
              },
              false);
      TargetNodeListener<TargetNode<?>> nodeListener = (buildFile, node) -> {};
      LoadingCache<Cell, BuildFileTree> buildFileTrees =
          CacheBuilder.newBuilder()
              .build(
                  new CacheLoader<Cell, BuildFileTree>() {
                    @Override
                    public BuildFileTree load(Cell cell) {
                      return new FilesystemBackedBuildFileTree(
                          cell.getFilesystem(),
                          cell.getBuckConfigView(ParserConfig.class).getBuildFileName());
                    }
                  });
      buildFileRawNodeParsePipeline =
          new BuildFileRawNodeParsePipeline(
              new PipelineNodeCache<>(
                  daemonicParserState.getRawNodeCache(),
                  daemonicParserState.validationToken(),
                  n -> false),
              projectBuildFileParserPool,
              executorService,
              eventBus,
              new WatchmanFactory.NullWatchman("test", WatchmanError.TEST),
              Optional.empty());

      BuildTargetRawNodeParsePipeline buildTargetRawNodeParsePipeline =
          new BuildTargetRawNodeParsePipeline(executorService, buildFileRawNodeParsePipeline);

      KnownRuleTypesProvider knownRuleTypesProvider =
          TestKnownRuleTypesProvider.create(BuckPluginManagerFactory.createPluginManager());

      ParserPythonInterpreterProvider pythonInterpreterProvider =
          new ParserPythonInterpreterProvider(
              cells.getRootCell().getBuckConfig(), new ExecutableFinder());

      PackageFileParserFactory packageFileParserFactory =
          new PackageFileParserFactory(
              coercerFactory, pythonInterpreterProvider, knownRuleTypesProvider);

      PackageFileParserPool packageFileParserPool =
          new PackageFileParserPool(NUM_THREADS, packageFileParserFactory);

      PackageFileParsePipeline packageFileParsePipeline =
          new PackageFileParsePipeline(
              new PipelineNodeCache<>(
                  daemonicParserState.getPackageFileCache(),
                  daemonicParserState.validationToken(),
                  n -> false),
              packageFileParserPool,
              executorService,
              eventBus,
              new WatchmanFactory.NullWatchman("test", WatchmanError.TEST),
              Optional.empty());

      PackagePipeline packagePipeline =
          new PackagePipeline(executorService, eventBus, packageFileParsePipeline);

      UnconfiguredTargetNodePipeline unconfiguredTargetNodePipeline =
          new UnconfiguredTargetNodePipeline(
              executorService,
              daemonicParserState.validationToken(),
              new TypedParsePipelineCache<>(),
              eventBus,
              buildFileRawNodeParsePipeline,
              buildTargetRawNodeParsePipeline,
              packagePipeline,
              new DefaultUnconfiguredTargetNodeFactory(
                  knownRuleTypesProvider,
                  new BuiltTargetVerifier(),
                  cells,
                  new SelectorListFactory(
                      new SelectorFactory(new ParsingUnconfiguredBuildTargetViewFactory())),
                  coercerFactory));
      ParserTargetNodeFromUnconfiguredTargetNodeFactory rawTargetNodeToTargetNodeFactory =
          new UnconfiguredTargetNodeToTargetNodeFactory(
              cells,
              coercerFactory,
              knownRuleTypesProvider,
              constructorArgMarshaller,
              new TargetNodeFactory(coercerFactory, new DefaultCellNameResolverProvider(cells)),
              new ThrowingPackageBoundaryChecker(buildFileTrees),
              new NoopCellBoundaryChecker(),
              nodeListener,
              new ThrowingSelectorListResolver(),
              new ThrowingPlatformResolver(),
              new MultiPlatformTargetConfigurationTransformer(new ThrowingPlatformResolver()),
              new ConstantHostTargetConfigurationResolver(UnconfiguredTargetConfiguration.INSTANCE),
              cells.getRootCell().getBuckConfig(),
              Optional.empty());
      this.targetNodeParsePipeline =
          new UnconfiguredTargetNodeToTargetNodeParsePipeline(
              this.daemonicParserState.getTargetNodeCache(),
              daemonicParserState.validationToken(),
              this.executorService,
              cells,
              unconfiguredTargetNodePipeline,
              TargetConfigurationDetectorFactory.empty(),
              this.eventBus,
              "raw_target_node_parse_pipeline",
              speculativeParsing == SpeculativeParsing.ENABLED,
              rawTargetNodeToTargetNodeFactory,
              false);
    }

    public UnconfiguredTargetNodeToTargetNodeParsePipeline getTargetNodeParsePipeline() {
      return targetNodeParsePipeline;
    }

    public BuildFileRawNodeParsePipeline getBuildFileRawNodeParsePipeline() {
      return buildFileRawNodeParsePipeline;
    }

    public Cell getCells() {
      return cells.getRootCell();
    }

    public boolean targetExistsInCache(BuildTarget target) {
      return daemonicParserState
          .getTargetNodeCache()
          .lookupComputedNode(cells.getRootCell(), target, daemonicParserState.validationToken())
          .isPresent();
    }

    public boolean buildFileExistsInCache(ForwardRelPath path) {
      return daemonicParserState
          .getRawNodeCache()
          .lookupComputedNode(cells.getRootCell(), path, daemonicParserState.validationToken())
          .isPresent();
    }

    public boolean packageFileExistsInCache(ForwardRelPath path) {
      return daemonicParserState
          .getPackageFileCache()
          .lookupComputedNode(cells.getRootCell(), path, daemonicParserState.validationToken())
          .isPresent();
    }

    public void invalidatePath(ForwardRelPath path) {
      AbsPath absPath = cells.getRootCell().getRoot().resolve(path);
      daemonicParserState.invalidatePaths(ImmutableList.of(absPath));
    }

    private void waitForParsersToClose() throws InterruptedException {
      Iterable<CloseRecordingProjectBuildFileParserDecorator> parserSnapshot;
      synchronized (projectBuildFileParsers) {
        parserSnapshot = ImmutableSet.copyOf(projectBuildFileParsers);
      }
      waitForAll(parserSnapshot, CloseRecordingProjectBuildFileParserDecorator::isClosed);
    }

    @Override
    public void close() throws Exception {
      targetNodeParsePipeline.close();
      projectBuildFileParserPool.close();
      // We wait for the parsers to shut down gracefully, they do this on a separate threadpool.
      waitForParsersToClose();
      executorService.shutdown();
      assertThat(executorService.awaitTermination(5, TimeUnit.SECONDS), is(true));
      synchronized (projectBuildFileParsers) {
        for (CloseRecordingProjectBuildFileParserDecorator parser : projectBuildFileParsers) {
          assertThat(parser.isClosed(), is(true));
        }
      }
    }
  }

  /**
   * Decorates {@link com.facebook.buck.parser.api.ProjectBuildFileParser} to track whether it was
   * closed.
   */
  private static class CloseRecordingProjectBuildFileParserDecorator
      extends ForwardingProjectBuildFileParserDecorator {
    private final AtomicBoolean isClosed;

    private CloseRecordingProjectBuildFileParserDecorator(ProjectBuildFileParser delegate) {
      super(delegate);
      this.isClosed = new AtomicBoolean(false);
    }

    @Override
    public void close() throws BuildFileParseException, InterruptedException, IOException {
      try {
        delegate.close();
      } finally {
        isClosed.set(true);
      }
    }

    public boolean isClosed() {
      return isClosed.get();
    }
  }
}
