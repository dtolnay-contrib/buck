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

import static com.facebook.buck.parser.config.ParserConfig.DEFAULT_BUILD_FILE_NAME;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasProperty;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesRegex;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeThat;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.apple.AppleNativeIntegrationTestUtils;
import com.facebook.buck.apple.toolchain.AppleCxxPlatformsProvider;
import com.facebook.buck.apple.toolchain.AppleDeveloperDirectoryProvider;
import com.facebook.buck.apple.toolchain.ApplePlatform;
import com.facebook.buck.apple.toolchain.AppleSdkLocation;
import com.facebook.buck.apple.toolchain.AppleToolchainProvider;
import com.facebook.buck.apple.toolchain.impl.AppleCxxPlatformsProviderFactory;
import com.facebook.buck.apple.toolchain.impl.AppleDeveloperDirectoryProviderFactory;
import com.facebook.buck.apple.toolchain.impl.AppleSdkLocationFactory;
import com.facebook.buck.apple.toolchain.impl.AppleToolchainProviderFactory;
import com.facebook.buck.core.cell.Cells;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.ForwardRelPath;
import com.facebook.buck.core.graph.transformation.executor.DepsAwareExecutor;
import com.facebook.buck.core.graph.transformation.executor.impl.DefaultDepsAwareExecutor;
import com.facebook.buck.core.graph.transformation.model.ComputeResult;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.CellRelativePath;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.UnconfiguredBuildTargetFactoryForTests;
import com.facebook.buck.core.model.UnconfiguredTargetConfiguration;
import com.facebook.buck.core.model.actiongraph.computation.ActionGraphProviderBuilder;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.model.targetgraph.TestTargetGraphCreationResultFactory;
import com.facebook.buck.core.model.targetgraph.impl.TargetNodes;
import com.facebook.buck.core.parser.buildtargetparser.ParsingUnconfiguredBuildTargetViewFactory;
import com.facebook.buck.core.plugin.impl.BuckPluginManagerFactory;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.knowntypes.TestKnownRuleTypesProvider;
import com.facebook.buck.core.rules.knowntypes.provider.KnownRuleTypesProvider;
import com.facebook.buck.core.toolchain.ToolchainCreationContext;
import com.facebook.buck.core.toolchain.impl.ToolchainProviderBuilder;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.event.FakeBuckEventListener;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.io.watchman.WatchmanError;
import com.facebook.buck.io.watchman.WatchmanEvent.Kind;
import com.facebook.buck.io.watchman.WatchmanFactory;
import com.facebook.buck.io.watchman.WatchmanOverflowEvent;
import com.facebook.buck.io.watchman.WatchmanPathEvent;
import com.facebook.buck.io.watchman.WatchmanWatcherOneBigEvent;
import com.facebook.buck.json.JsonObjectHashing;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.parser.config.ParserConfig;
import com.facebook.buck.parser.config.ParserConfig.ApplyDefaultFlavorsMode;
import com.facebook.buck.parser.events.ParseBuckFileEvent;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.parser.exceptions.MissingBuildFileException;
import com.facebook.buck.parser.spec.BuildFileSpec;
import com.facebook.buck.parser.spec.BuildTargetSpec;
import com.facebook.buck.parser.spec.TargetNodePredicateSpec;
import com.facebook.buck.rules.coercer.DefaultConstructorArgMarshaller;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.rules.keys.config.TestRuleKeyConfigurationFactory;
import com.facebook.buck.rules.param.ParamName;
import com.facebook.buck.rules.param.ParamNameOrSpecial;
import com.facebook.buck.shell.GenruleDescriptionArg;
import com.facebook.buck.testutil.CloseableResource;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.CreateSymlinksForTests;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.config.ConfigBuilder;
import com.facebook.buck.util.environment.EnvVariablesProvider;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.eventbus.Subscribe;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedMap;
import java.util.concurrent.Executors;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.pf4j.PluginManager;

@RunWith(Parameterized.class)
public class ParserWithConfigurableAttributesTest {

  @Rule
  public CloseableResource<DepsAwareExecutor<? super ComputeResult, ?>> executor =
      CloseableResource.of(() -> DefaultDepsAwareExecutor.of(4));

  @Rule public TemporaryPaths tempDir = new TemporaryPaths();
  @Rule public ExpectedException thrown = ExpectedException.none();

  @Parameterized.Parameter(0)
  public int parsingThreads;

  @Parameterized.Parameter(1)
  public boolean parallelParsing;

  private BuildTarget buildTarget;
  private AbsPath defaultIncludeFile;
  private AbsPath includedByIncludeFile;
  private AbsPath includedByBuildFile;
  private AbsPath testBuildFile;
  private Parser parser;
  private TypeCoercerFactory typeCoercerFactory;
  private ProjectFilesystem filesystem;
  private AbsPath cellRoot;
  private BuckEventBus eventBus;
  private Cells cells;
  private KnownRuleTypesProvider knownRuleTypesProvider;
  private ParseEventStartedCounter counter;
  private ListeningExecutorService executorService;
  private ExecutableFinder executableFinder;
  private ParsingContext parsingContext;

  @Parameterized.Parameters(name = "project.parsing_threads={0}, project.parallel_parsing={1}")
  public static Collection<Object[]> generateData() {
    return Arrays.asList(
        new Object[][] {
          {2, true},
        });
  }

  /** Helper to construct a PerBuildState and use it to get nodes. */
  private static void getRawTargetNodes(
      Parser parser,
      TypeCoercerFactory typeCoercerFactory,
      BuckEventBus eventBus,
      Cells cells,
      KnownRuleTypesProvider knownRuleTypesProvider,
      boolean enableProfiling,
      ListeningExecutorService executor,
      ExecutableFinder executableFinder,
      AbsPath buildFile)
      throws BuildFileParseException {
    try (PerBuildState state =
        new PerBuildStateFactory(
                typeCoercerFactory,
                new DefaultConstructorArgMarshaller(),
                knownRuleTypesProvider,
                new ParserPythonInterpreterProvider(cells.getBuckConfig(), executableFinder),
                new WatchmanFactory.NullWatchman("test", WatchmanError.TEST),
                Optional.empty(),
                eventBus,
                new ParsingUnconfiguredBuildTargetViewFactory(),
                UnconfiguredTargetConfiguration.INSTANCE)
            .create(
                ParsingContext.builder(cells, executor)
                    .setProfilingEnabled(enableProfiling)
                    .build(),
                parser.getPermState())) {
      AbstractParser.getTargetNodeRawAttributes(state, cells.getRootCell(), buildFile).getTargets();
    }
  }

  @Before
  public void setUp() throws IOException {
    tempDir.newFolder("java", "com", "facebook");

    defaultIncludeFile = tempDir.newFile("java/com/facebook/defaultIncludeFile").toRealPath();
    Files.write(defaultIncludeFile.getPath(), "FOO = 1\n".getBytes(UTF_8));

    includedByIncludeFile = tempDir.newFile("java/com/facebook/includedByIncludeFile").toRealPath();
    Files.write(includedByIncludeFile.getPath(), "BAR = 2\n".getBytes(UTF_8));

    includedByBuildFile = tempDir.newFile("java/com/facebook/includedByBuildFile").toRealPath();
    Files.write(
        includedByBuildFile.getPath(),
        ("load('//:java/com/facebook/includedByIncludeFile', _BAR='BAR')\n" + "BAR = _BAR\n")
            .getBytes(UTF_8));

    testBuildFile = tempDir.newFile("java/com/facebook/BUCK").toRealPath();
    Files.write(
        testBuildFile.getPath(),
        ("load('//:java/com/facebook/includedByBuildFile', 'BAR')\n"
                + "java_library(name = 'foo')\n"
                + "java_library(name = 'bar')\n"
                + "genrule(name = 'baz', out = '.')\n")
            .getBytes(UTF_8));

    tempDir.newFile("bar.py");

    // Create a temp directory with some build files.
    AbsPath root = tempDir.getRoot().toRealPath();
    filesystem =
        TestProjectFilesystems.createProjectFilesystem(
            root, ConfigBuilder.createFromText("[project]", "ignore = **/*.swp"));
    cellRoot = filesystem.getRootPath();
    buildTarget = BuildTargetFactory.newInstance("//:cake");
    eventBus = BuckEventBusForTests.newInstance();

    ImmutableMap.Builder<String, String> projectSectionBuilder = ImmutableMap.builder();
    projectSectionBuilder.put("allow_symlinks", "warn");
    if (parallelParsing) {
      projectSectionBuilder.put("parallel_parsing", "true");
      projectSectionBuilder.put("parsing_threads", Integer.toString(parsingThreads));
    }

    ImmutableMap.Builder<String, ImmutableMap<String, String>> configSectionsBuilder =
        ImmutableMap.builder();
    configSectionsBuilder.put(
        "buildfile", ImmutableMap.of("includes", "//java/com/facebook/defaultIncludeFile"));
    configSectionsBuilder.put("project", projectSectionBuilder.build());

    BuckConfig config =
        FakeBuckConfig.builder()
            .setFilesystem(filesystem)
            .setSections(configSectionsBuilder.build())
            .build();

    ProcessExecutor processExecutor = new DefaultProcessExecutor(new TestConsole());

    executableFinder = new ExecutableFinder();

    ToolchainCreationContext toolchainCreationContext =
        ToolchainCreationContext.of(
            ImmutableMap.of(),
            config,
            filesystem,
            processExecutor,
            executableFinder,
            TestRuleKeyConfigurationFactory.create());

    ToolchainProviderBuilder toolchainProviderBuilder = new ToolchainProviderBuilder();
    Optional<AppleDeveloperDirectoryProvider> appleDeveloperDirectoryProvider =
        new AppleDeveloperDirectoryProviderFactory()
            .createToolchain(
                toolchainProviderBuilder.build(),
                toolchainCreationContext,
                UnconfiguredTargetConfiguration.INSTANCE);
    appleDeveloperDirectoryProvider.ifPresent(
        provider ->
            toolchainProviderBuilder.withToolchain(
                AppleDeveloperDirectoryProvider.DEFAULT_NAME, provider));
    Optional<AppleToolchainProvider> appleToolchainProvider =
        new AppleToolchainProviderFactory()
            .createToolchain(
                toolchainProviderBuilder.build(),
                toolchainCreationContext,
                UnconfiguredTargetConfiguration.INSTANCE);
    appleToolchainProvider.ifPresent(
        provider ->
            toolchainProviderBuilder.withToolchain(AppleToolchainProvider.DEFAULT_NAME, provider));
    Optional<AppleSdkLocation> appleSdkLocation =
        new AppleSdkLocationFactory()
            .createToolchain(
                toolchainProviderBuilder.build(),
                toolchainCreationContext,
                UnconfiguredTargetConfiguration.INSTANCE);
    appleSdkLocation.ifPresent(
        provider ->
            toolchainProviderBuilder.withToolchain(AppleSdkLocation.DEFAULT_NAME, provider));
    Optional<AppleCxxPlatformsProvider> appleCxxPlatformsProvider =
        new AppleCxxPlatformsProviderFactory()
            .createToolchain(
                toolchainProviderBuilder.build(),
                toolchainCreationContext,
                UnconfiguredTargetConfiguration.INSTANCE);
    appleCxxPlatformsProvider.ifPresent(
        provider ->
            toolchainProviderBuilder.withToolchain(
                AppleCxxPlatformsProvider.DEFAULT_NAME, provider));

    cells = new TestCellBuilder().setFilesystem(filesystem).setBuckConfig(config).build();
    PluginManager pluginManager = BuckPluginManagerFactory.createPluginManager();
    knownRuleTypesProvider = TestKnownRuleTypesProvider.create(pluginManager);

    typeCoercerFactory = new DefaultTypeCoercerFactory();
    parser = TestParserFactory.create(executor.get(), cells, knownRuleTypesProvider, eventBus);

    counter = new ParseEventStartedCounter();
    eventBus.register(counter);

    executorService =
        MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(parsingThreads));

    parsingContext = ParsingContext.builder(cells, executorService).build();
  }

  @After
  public void tearDown() {
    executorService.shutdown();
  }

  @Test
  public void testParseBuildFilesForTargetsWithOverlappingTargets() throws Exception {
    // Execute buildTargetGraphForBuildTargets() with multiple targets that require parsing the same
    // build file.
    BuildTarget fooTarget = BuildTargetFactory.newInstance("//java/com/facebook", "foo");
    BuildTarget barTarget = BuildTargetFactory.newInstance("//java/com/facebook", "bar");
    ImmutableSet<BuildTarget> buildTargets = ImmutableSet.of(fooTarget, barTarget);

    // The EventBus should be updated with events indicating how parsing ran.
    FakeBuckEventListener listener = new FakeBuckEventListener();
    eventBus.register(listener);

    TargetGraph targetGraph =
        parser.buildTargetGraph(parsingContext, buildTargets).getTargetGraph();
    ActionGraphBuilder graphBuilder = buildActionGraph(eventBus, targetGraph, cells);
    BuildRule fooRule = graphBuilder.requireRule(fooTarget);
    assertNotNull(fooRule);
    BuildRule barRule = graphBuilder.requireRule(barTarget);
    assertNotNull(barRule);

    Iterable<ParseEvent> events = Iterables.filter(listener.getEvents(), ParseEvent.class);
    assertThat(
        events,
        contains(
            hasProperty("buildTargets", equalTo(buildTargets)),
            allOf(
                hasProperty("buildTargets", equalTo(buildTargets)),
                hasProperty("graph", equalTo(Optional.of(targetGraph))))));
  }

  @Test
  public void testMissingBuildRuleInValidFile()
      throws BuildFileParseException, IOException, InterruptedException {
    // Execute buildTargetGraphForBuildTargets() with a target in a valid file but a bad rule name.
    BuildTarget fooTarget = BuildTargetFactory.newInstance("//java/com/facebook", "foo");
    BuildTarget razTarget = BuildTargetFactory.newInstance("//java/com/facebook", "raz");
    ImmutableSet<BuildTarget> buildTargets = ImmutableSet.of(fooTarget, razTarget);

    thrown.expectMessage(
        "The rule //java/com/facebook:raz could not be found.\n"
            + "Please check the spelling and whether it is one of the 3 targets in "
            + filesystem
                .resolve(razTarget.getCellRelativeBasePath().getPath())
                .resolve(DEFAULT_BUILD_FILE_NAME));

    parser.buildTargetGraph(parsingContext, buildTargets);
  }

  @Test
  public void testMissingBuildFile()
      throws InterruptedException, BuildFileParseException, IOException {
    BuildTarget target = BuildTargetFactory.newInstance("//path/to/nowhere", "nowhere");
    ImmutableSet<BuildTarget> buildTargets = ImmutableSet.of(target);

    thrown.expect(MissingBuildFileException.class);
    thrown.expectMessage(
        String.format(
            "No build file at %s when resolving target //path/to/nowhere:nowhere",
            Paths.get("path", "to", "nowhere", "BUCK").toString()));

    parser.buildTargetGraph(parsingContext, buildTargets);
  }

  @Test
  public void shouldThrowAnExceptionIfConstructorArgMashallingFails()
      throws IOException, BuildFileParseException {
    thrown.expect(HumanReadableException.class);
    thrown.expectMessage("When parsing ////cake:walk");

    AbsPath buckFile = cellRoot.resolve("BUCK");
    Files.write(
        buckFile.getPath(),
        "genrule(name = 'cake', out = 'file.txt', cmd = '$(exe ////cake:walk) > $OUT')"
            .getBytes(UTF_8));

    parser.getTargetNodeAssertCompatible(parsingContext, buildTarget, DependencyStack.root());
  }

  @Test
  public void shouldThrowAnExceptionIfADepIsInAFileThatCannotBeParsed()
      throws IOException, InterruptedException, BuildFileParseException {
    thrown.expectMessage(matchesRegex("(?s).*Buck wasn't able to parse .*|.*Cannot parse .*"));
    thrown.expectMessage(Paths.get("foo/BUCK").toString());

    AbsPath buckFile = cellRoot.resolve("BUCK");
    Files.write(
        buckFile.getPath(),
        "genrule(name = 'cake', out = 'foo.txt', cmd = '$(exe //foo:bar) > $OUT')".getBytes(UTF_8));

    buckFile = cellRoot.resolve("foo/BUCK");
    Files.createDirectories(buckFile.getParent().getPath());
    Files.write(buckFile.getPath(), "I do not parse as python".getBytes(UTF_8));

    parser.buildTargetGraph(
        parsingContext, ImmutableSet.of(BuildTargetFactory.newInstance("//:cake")));
  }

  @Test
  public void shouldThrowAnExceptionIfMultipleTargetsAreDefinedWithTheSameName()
      throws IOException, BuildFileParseException {
    thrown.expect(BuildFileParseException.class);
    thrown.expectMessage(
        matchesRegex(
            "(?s).*Duplicate rule definition 'cake' found.*|.*Cannot register rule :cake of type genrule with.*"));

    AbsPath buckFile = cellRoot.resolve("BUCK");
    Files.write(
        buckFile.getPath(),
        ("export_file(name = 'cake', src = 'hello.txt')\n"
                + "genrule(name = 'cake', out = 'file.txt', cmd = 'touch $OUT')\n")
            .getBytes(UTF_8));

    parser.getTargetNodeAssertCompatible(parsingContext, buildTarget, DependencyStack.root());
  }

  @Test
  public void shouldAllowAccessingBuiltInRulesViaNative() throws Exception {
    Files.write(
        includedByBuildFile.getPath(),
        "def foo(name): native.export_file(name=name)\n".getBytes(UTF_8),
        StandardOpenOption.APPEND);
    Files.write(
        testBuildFile.getPath(),
        "load('//:java/com/facebook/includedByBuildFile', 'foo')\n".getBytes(UTF_8));
    Files.write(
        testBuildFile.getPath(), "foo(name='BUCK')\n".getBytes(UTF_8), StandardOpenOption.APPEND);
    parser.getTargetNodeAssertCompatible(
        parsingContext,
        BuildTargetFactory.newInstance("//java/com/facebook:BUCK"),
        DependencyStack.root());
  }

  @Test
  public void shouldThrowAnExceptionIfNameIsNone() throws IOException, BuildFileParseException {
    thrown.expect(BuildFileParseException.class);
    thrown.expectMessage(
        matchesRegex(
            "(?s).*rules 'name' field must be a string.  Found None.*|.*rule without a name.*"));

    AbsPath buckFile = cellRoot.resolve("BUCK");
    Files.write(
        buckFile.getPath(),
        ("genrule(name = None, out = 'file.txt', cmd = 'touch $OUT')\n").getBytes(UTF_8));

    parser.getTargetNodeAssertCompatible(parsingContext, buildTarget, DependencyStack.root());
  }

  @Test
  public void shouldThrowAnExceptionWhenAnUnknownFlavorIsSeen()
      throws BuildFileParseException, InterruptedException, IOException {
    BuildTarget flavored =
        BuildTargetFactory.newInstance(
            "//java/com/facebook", "foo", InternalFlavor.of("doesNotExist"));

    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(
        containsString(
            "The following flavor(s) are not supported on target "
                + "//java/com/facebook:foo#doesNotExist"));
    parser.buildTargetGraph(parsingContext, ImmutableSortedSet.of(flavored));
  }

  @Test
  public void shouldThrowAnExceptionWhenAnUnknownFlavorIsSeenAndShowSuggestionsDefault()
      throws BuildFileParseException, InterruptedException, IOException {
    BuildTarget flavored =
        BuildTargetFactory.newInstance(
            "//java/com/facebook", "foo", InternalFlavor.of("android-unknown"));

    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(
        containsString(
            "The following flavor(s) are not supported on target "
                + "//java/com/facebook:foo#android-unknown"));
    thrown.expectMessage(
        containsString(
            "android-unknown: Please make sure you have the Android SDK/NDK "
                + "installed and set up. "
                + "See https://dev.buck.build/setup/getting_started.html#locate-android-sdk"));
    parser.buildTargetGraph(parsingContext, ImmutableSortedSet.of(flavored));
  }

  @Test
  public void shouldThrowAnExceptionWhenAFlavorIsAskedOfATargetThatDoesntSupportFlavors()
      throws BuildFileParseException, InterruptedException, IOException {
    BuildTarget flavored =
        BuildTargetFactory.newInstance("//java/com/facebook", "baz", JavaLibrary.SRC_JAR);

    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(
        "The following flavor(s) are not supported on target //java/com/facebook:baz:\n" + "src.");
    parser.buildTargetGraph(parsingContext, ImmutableSortedSet.of(flavored));
  }

  @Test
  public void testInvalidDepFromValidFile()
      throws IOException, BuildFileParseException, InterruptedException {
    // Ensure an exception with a specific message is thrown.
    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(
        "This error happened while trying to get dependency '//java/com/facebook/invalid/lib:missing_rule' of target '//java/com/facebook/invalid:foo'");

    // Execute buildTargetGraphForBuildTargets() with a target in a valid file but a bad rule name.
    tempDir.newFolder("java", "com", "facebook", "invalid");

    AbsPath testInvalidBuildFile = tempDir.newFile("java/com/facebook/invalid/BUCK");
    Files.write(
        testInvalidBuildFile.getPath(),
        ("java_library(name = 'foo', deps = ['//java/com/facebook/invalid/lib:missing_rule'])\n"
                + "java_library(name = 'bar')\n")
            .getBytes(UTF_8));

    tempDir.newFolder("java", "com", "facebook", "invalid", "lib");
    tempDir.newFile("java/com/facebook/invalid/lib/BUCK");

    BuildTarget fooTarget = BuildTargetFactory.newInstance("//java/com/facebook/invalid", "foo");
    ImmutableSet<BuildTarget> buildTargets = ImmutableSet.of(fooTarget);

    parser.buildTargetGraph(parsingContext, buildTargets);
  }

  @Test
  public void whenAllRulesAreRequestedMultipleTimesThenRulesAreOnlyParsedOnce()
      throws BuildFileParseException, IOException, InterruptedException {
    filterAllTargetsInProject(parser, parsingContext);
    filterAllTargetsInProject(parser, parsingContext);

    assertEquals("Should have cached build rules.", 1, counter.calls);
  }

  @Test
  public void whenNotifiedOfNonPathEventThenCacheRulesAreInvalidated()
      throws BuildFileParseException, IOException, InterruptedException {
    // Call filterAllTargetsInProject to populate the cache.
    filterAllTargetsInProject(parser, parsingContext);

    // Process event.
    parser
        .getPermState()
        .invalidateBasedOn(
            WatchmanWatcherOneBigEvent.overflow(
                WatchmanOverflowEvent.of(filesystem.getRootPath(), "")));

    // Call filterAllTargetsInProject to request cached rules.
    filterAllTargetsInProject(parser, parsingContext);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, counter.calls);
  }

  @Test
  public void pathInvalidationWorksAfterOverflow() throws Exception {
    // Call filterAllTargetsInProject to populate the cache.
    filterAllTargetsInProject(parser, parsingContext);

    // Send overflow event.
    parser
        .getPermState()
        .invalidateBasedOn(
            WatchmanWatcherOneBigEvent.overflow(
                WatchmanOverflowEvent.of(filesystem.getRootPath(), "")));

    // Call filterAllTargetsInProject to request cached rules.
    filterAllTargetsInProject(parser, parsingContext);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, counter.calls);

    // Send a "file added" event.
    parser
        .getPermState()
        .invalidateBasedOn(
            WatchmanWatcherOneBigEvent.pathEvent(
                WatchmanPathEvent.of(
                    filesystem.getRootPath(),
                    Kind.CREATE,
                    ForwardRelPath.of("java/com/facebook/Something.java"))));

    // Call filterAllTargetsInProject to request cached rules.
    filterAllTargetsInProject(parser, parsingContext);

    // Test that the third parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 3, counter.calls);
  }

  @Test
  public void whenNotifiedOfBuildFileWithConfigurationRulesThenCacheRulesAreInvalidated()
      throws BuildFileParseException, IOException {

    tempDir.newFolder("config");
    AbsPath configBuckFile = tempDir.newFile("config/BUCK");
    Files.write(
        configBuckFile.getPath(),
        ("config_setting(name = 'config', values = {'a.c': 'c'})").getBytes(UTF_8),
        StandardOpenOption.APPEND);

    AbsPath buckFile = tempDir.newFile("BUCK");
    Files.write(
        buckFile.getPath(),
        ("java_library("
                + "  name = 'cake',"
                + "  target = select({"
                + "    '//config:config': '7',"
                + "    'DEFAULT': '8',"
                + "  })"
                + ")"
                + "\n")
            .getBytes(UTF_8),
        StandardOpenOption.APPEND);

    parser.getTargetNodeAssertCompatible(parsingContext, buildTarget, DependencyStack.root());

    assertEquals(2, counter.calls);

    WatchmanPathEvent event =
        WatchmanPathEvent.of(
            filesystem.getRootPath(),
            Kind.MODIFY,
            ForwardRelPath.ofRelPath(
                MorePaths.relativize(tempDir.getRoot().toRealPath(), configBuckFile)));
    parser.getPermState().invalidateBasedOn(WatchmanWatcherOneBigEvent.pathEvent(event));

    parser.getTargetNodeAssertCompatible(parsingContext, buildTarget, DependencyStack.root());

    // Test that the second call triggers re-parsing of all files.
    assertEquals("Should have invalidated cache.", 4, counter.calls);
  }

  @Test
  public void whenNotifiedOfBuildFileDependentWithConfigurationRulesThenCacheRulesAreInvalidated()
      throws BuildFileParseException, IOException {

    tempDir.newFolder("config");
    AbsPath configBuckFile = tempDir.newFile("config/BUCK");
    Files.write(
        configBuckFile.getPath(),
        ("load('//config:defs.bzl', 'java_lib')\n"
                + "config_setting(name = 'config', values = {'a.c': 'c'})")
            .getBytes(UTF_8),
        StandardOpenOption.APPEND);
    AbsPath defsFile = tempDir.newFile("config/defs.bzl");
    Files.write(
        defsFile.getPath(),
        ("def java_lib(name, **kwargs):\n"
                + "    return native.java_library(\n"
                + "        name = name,\n"
                + "        **kwargs\n"
                + "    )\n")
            .getBytes(UTF_8),
        StandardOpenOption.APPEND);

    AbsPath buckFile = tempDir.newFile("BUCK");
    Files.write(
        buckFile.getPath(),
        ("java_library("
                + "  name = 'cake',"
                + "  target = select({"
                + "    '//config:config': '7',"
                + "    'DEFAULT': '8',"
                + "  })"
                + ")"
                + "\n")
            .getBytes(UTF_8),
        StandardOpenOption.APPEND);

    parser.getTargetNodeAssertCompatible(parsingContext, buildTarget, DependencyStack.root());

    assertEquals(2, counter.calls);

    WatchmanPathEvent event =
        WatchmanPathEvent.of(
            filesystem.getRootPath(),
            Kind.MODIFY,
            ForwardRelPath.ofRelPath(
                MorePaths.relativize(tempDir.getRoot().toRealPath(), defsFile)));
    parser.getPermState().invalidateBasedOn(WatchmanWatcherOneBigEvent.pathEvent(event));

    parser.getTargetNodeAssertCompatible(parsingContext, buildTarget, DependencyStack.root());

    // Test that the second call triggers re-parsing of all files.
    assertEquals("Should have invalidated cache.", 4, counter.calls);
  }

  @Test
  public void whenEnvironmentNotChangedThenCacheRulesAreNotInvalidated()
      throws BuildFileParseException, IOException, InterruptedException {
    BuckConfig config =
        FakeBuckConfig.builder()
            .setFilesystem(filesystem)
            .setEnvironment(
                ImmutableMap.of(
                    "Some Key",
                    "Some Value",
                    "PATH",
                    EnvVariablesProvider.getSystemEnv().get("PATH")))
            .build();

    Cells cells = new TestCellBuilder().setFilesystem(filesystem).setBuckConfig(config).build();

    // Call filterAllTargetsInProject to populate the cache.
    filterAllTargetsInProject(parser, parsingContext.withCells(cells));

    // Call filterAllTargetsInProject to request cached rules with identical environment.
    filterAllTargetsInProject(parser, parsingContext.withCells(cells));

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should not have invalidated cache.", 1, counter.calls);
  }

  @Test
  public void whenNotifiedOfBuildFileAddThenCacheRulesAreInvalidated()
      throws BuildFileParseException, IOException {
    // Call parseBuildFile to populate the cache.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cells,
        knownRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

    // Process event.
    parser
        .getPermState()
        .invalidateBasedOn(
            WatchmanWatcherOneBigEvent.pathEvent(
                WatchmanPathEvent.of(
                    filesystem.getRootPath(),
                    Kind.CREATE,
                    ForwardRelPath.ofRelPath(
                        MorePaths.relativize(tempDir.getRoot().toRealPath(), testBuildFile)))));

    // Call parseBuildFile to request cached rules.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cells,
        knownRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, counter.calls);
  }

  @Test
  public void whenNotifiedOfBuildFileChangeThenCacheRulesAreInvalidated()
      throws BuildFileParseException, IOException {
    // Call parseBuildFile to populate the cache.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cells,
        knownRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

    // Process event.
    WatchmanPathEvent event =
        WatchmanPathEvent.of(
            filesystem.getRootPath(),
            Kind.MODIFY,
            ForwardRelPath.ofRelPath(
                MorePaths.relativize(tempDir.getRoot().toRealPath(), testBuildFile)));
    parser.getPermState().invalidateBasedOn(WatchmanWatcherOneBigEvent.pathEvent(event));

    // Call parseBuildFile to request cached rules.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cells,
        knownRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, counter.calls);
  }

  @Test
  public void whenNotifiedOfBuildFileDeleteThenCacheRulesAreInvalidated()
      throws BuildFileParseException, IOException {
    // Call parseBuildFile to populate the cache.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cells,
        knownRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

    // Process event.
    WatchmanPathEvent event =
        WatchmanPathEvent.of(
            filesystem.getRootPath(),
            Kind.DELETE,
            ForwardRelPath.ofRelPath(
                MorePaths.relativize(tempDir.getRoot().toRealPath(), testBuildFile)));
    parser.getPermState().invalidateBasedOn(WatchmanWatcherOneBigEvent.pathEvent(event));

    // Call parseBuildFile to request cached rules.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cells,
        knownRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, counter.calls);
  }

  @Test
  public void whenNotifiedOfIncludeFileAddThenCacheRulesAreInvalidated()
      throws BuildFileParseException, IOException {
    // Call parseBuildFile to populate the cache.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cells,
        knownRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

    // Process event.
    WatchmanPathEvent event =
        WatchmanPathEvent.of(
            filesystem.getRootPath(),
            Kind.CREATE,
            ForwardRelPath.ofRelPath(
                MorePaths.relativize(tempDir.getRoot().toRealPath(), includedByBuildFile)));
    parser.getPermState().invalidateBasedOn(WatchmanWatcherOneBigEvent.pathEvent(event));

    // Call parseBuildFile to request cached rules.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cells,
        knownRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, counter.calls);
  }

  @Test
  public void whenNotifiedOfIncludeFileChangeThenCacheRulesAreInvalidated()
      throws BuildFileParseException, IOException {
    // Call parseBuildFile to populate the cache.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cells,
        knownRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

    assertEquals("Should have parsed at all.", 1, counter.calls);

    // Process event.
    WatchmanPathEvent event =
        WatchmanPathEvent.of(
            filesystem.getRootPath(),
            Kind.MODIFY,
            ForwardRelPath.ofRelPath(
                MorePaths.relativize(tempDir.getRoot().toRealPath(), includedByBuildFile)));
    parser.getPermState().invalidateBasedOn(WatchmanWatcherOneBigEvent.pathEvent(event));

    // Call parseBuildFile to request cached rules.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cells,
        knownRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, counter.calls);
  }

  @Test
  public void whenNotifiedOfIncludeFileDeleteThenCacheRulesAreInvalidated()
      throws BuildFileParseException, IOException {
    // Call parseBuildFile to populate the cache.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cells,
        knownRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

    // Process event.
    WatchmanPathEvent event =
        WatchmanPathEvent.of(
            filesystem.getRootPath(),
            Kind.DELETE,
            ForwardRelPath.ofRelPath(
                MorePaths.relativize(tempDir.getRoot().toRealPath(), includedByBuildFile)));
    parser.getPermState().invalidateBasedOn(WatchmanWatcherOneBigEvent.pathEvent(event));

    // Call parseBuildFile to request cached rules.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cells,
        knownRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, counter.calls);
  }

  @Test
  public void whenNotifiedOf2ndOrderIncludeFileAddThenCacheRulesAreInvalidated()
      throws BuildFileParseException, IOException {
    // Call parseBuildFile to populate the cache.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cells,
        knownRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

    // Process event.
    WatchmanPathEvent event =
        WatchmanPathEvent.of(
            filesystem.getRootPath(),
            Kind.CREATE,
            ForwardRelPath.ofRelPath(
                MorePaths.relativize(tempDir.getRoot().toRealPath(), includedByIncludeFile)));
    parser.getPermState().invalidateBasedOn(WatchmanWatcherOneBigEvent.pathEvent(event));

    // Call parseBuildFile to request cached rules.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cells,
        knownRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, counter.calls);
  }

  @Test
  public void whenNotifiedOf2ndOrderIncludeFileChangeThenCacheRulesAreInvalidated()
      throws BuildFileParseException, IOException {
    // Call parseBuildFile to populate the cache.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cells,
        knownRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

    // Process event.
    WatchmanPathEvent event =
        WatchmanPathEvent.of(
            filesystem.getRootPath(),
            Kind.MODIFY,
            ForwardRelPath.ofRelPath(
                MorePaths.relativize(tempDir.getRoot().toRealPath(), includedByIncludeFile)));
    parser.getPermState().invalidateBasedOn(WatchmanWatcherOneBigEvent.pathEvent(event));

    // Call parseBuildFile to request cached rules.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cells,
        knownRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, counter.calls);
  }

  @Test
  public void whenNotifiedOf2ndOrderIncludeFileDeleteThenCacheRulesAreInvalidated()
      throws BuildFileParseException, IOException {
    // Call parseBuildFile to populate the cache.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cells,
        knownRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

    // Process event.
    WatchmanPathEvent event =
        WatchmanPathEvent.of(
            filesystem.getRootPath(),
            Kind.DELETE,
            ForwardRelPath.ofRelPath(
                MorePaths.relativize(tempDir.getRoot().toRealPath(), includedByIncludeFile)));
    parser.getPermState().invalidateBasedOn(WatchmanWatcherOneBigEvent.pathEvent(event));

    // Call parseBuildFile to request cached rules.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cells,
        knownRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, counter.calls);
  }

  @Test
  public void whenNotifiedOfDefaultIncludeFileAddThenCacheRulesAreInvalidated()
      throws BuildFileParseException, IOException {
    // Call parseBuildFile to populate the cache.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cells,
        knownRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

    // Process event.
    WatchmanPathEvent event =
        WatchmanPathEvent.of(
            filesystem.getRootPath(),
            Kind.CREATE,
            ForwardRelPath.ofRelPath(
                MorePaths.relativize(tempDir.getRoot().toRealPath(), defaultIncludeFile)));
    parser.getPermState().invalidateBasedOn(WatchmanWatcherOneBigEvent.pathEvent(event));

    // Call parseBuildFile to request cached rules.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cells,
        knownRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, counter.calls);
  }

  @Test
  public void whenNotifiedOfDefaultIncludeFileChangeThenCacheRulesAreInvalidated()
      throws BuildFileParseException, IOException {
    // Call parseBuildFile to populate the cache.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cells,
        knownRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

    // Process event.
    WatchmanPathEvent event =
        WatchmanPathEvent.of(
            filesystem.getRootPath(),
            Kind.MODIFY,
            ForwardRelPath.ofRelPath(
                MorePaths.relativize(tempDir.getRoot().toRealPath(), defaultIncludeFile)));
    parser.getPermState().invalidateBasedOn(WatchmanWatcherOneBigEvent.pathEvent(event));

    // Call parseBuildFile to request cached rules.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cells,
        knownRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, counter.calls);
  }

  @Test
  public void whenNotifiedOfDefaultIncludeFileDeleteThenCacheRulesAreInvalidated()
      throws BuildFileParseException, IOException {
    // Call parseBuildFile to populate the cache.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cells,
        knownRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

    // Process event.
    WatchmanPathEvent event =
        WatchmanPathEvent.of(
            filesystem.getRootPath(),
            Kind.DELETE,
            ForwardRelPath.ofRelPath(
                MorePaths.relativize(tempDir.getRoot().toRealPath(), defaultIncludeFile)));
    parser.getPermState().invalidateBasedOn(WatchmanWatcherOneBigEvent.pathEvent(event));

    // Call parseBuildFile to request cached rules.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cells,
        knownRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, counter.calls);
  }

  @Test
  // TODO(simons): avoid invalidation when arbitrary contained (possibly backup) files are added.
  public void whenNotifiedOfContainedFileAddThenCacheRulesAreInvalidated()
      throws BuildFileParseException {
    // Call parseBuildFile to populate the cache.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cells,
        knownRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

    // Process event.
    WatchmanPathEvent event =
        WatchmanPathEvent.of(
            filesystem.getRootPath(),
            Kind.CREATE,
            ForwardRelPath.of("java/com/facebook/SomeClass.java"));
    parser.getPermState().invalidateBasedOn(WatchmanWatcherOneBigEvent.pathEvent(event));

    // Call parseBuildFile to request cached rules.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cells,
        knownRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, counter.calls);
  }

  @Test
  public void whenNotifiedOfContainedFileAddCachedAncestorsAreInvalidatedWithoutBoundaryChecks()
      throws Exception {
    BuckConfig config =
        FakeBuckConfig.builder()
            .setFilesystem(filesystem)
            .setSections(
                "[buildfile]",
                "includes = //java/com/facebook/defaultIncludeFile",
                "[project]",
                "check_package_boundary = false")
            .build();
    Cells cell = new TestCellBuilder().setFilesystem(filesystem).setBuckConfig(config).build();

    AbsPath testAncestorBuildFile = tempDir.newFile("java/BUCK").toRealPath();
    Files.write(testAncestorBuildFile.getPath(), "java_library(name = 'root')\n".getBytes(UTF_8));

    // Call parseBuildFile to populate the cache.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cell,
        knownRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testAncestorBuildFile);

    // Process event.
    WatchmanPathEvent event =
        WatchmanPathEvent.of(
            filesystem.getRootPath(),
            Kind.CREATE,
            ForwardRelPath.of("java/com/facebook/SomeClass.java"));
    parser.getPermState().invalidateBasedOn(WatchmanWatcherOneBigEvent.pathEvent(event));

    // Call parseBuildFile to request cached rules.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cell,
        knownRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testAncestorBuildFile);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, counter.calls);
  }

  @Test
  public void whenNotifiedOfContainedFileChangeThenCacheRulesAreNotInvalidated()
      throws BuildFileParseException {
    // Call parseBuildFile to populate the cache.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cells,
        knownRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

    // Process event.
    WatchmanPathEvent event =
        WatchmanPathEvent.of(
            filesystem.getRootPath(),
            Kind.MODIFY,
            ForwardRelPath.of("java/com/facebook/SomeClass.java"));
    parser.getPermState().invalidateBasedOn(WatchmanWatcherOneBigEvent.pathEvent(event));

    // Call parseBuildFile to request cached rules.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cells,
        knownRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

    // Test that the second parseBuildFile call did not repopulate the cache.
    assertEquals("Should have not invalidated cache.", 1, counter.calls);
  }

  @Test
  // TODO(simons): avoid invalidation when arbitrary contained (possibly backup) files are deleted.
  public void whenNotifiedOfContainedFileDeleteThenCacheRulesAreInvalidated()
      throws BuildFileParseException {
    // Call parseBuildFile to populate the cache.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cells,
        knownRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

    // Process event.
    WatchmanPathEvent event =
        WatchmanPathEvent.of(
            filesystem.getRootPath(),
            Kind.DELETE,
            ForwardRelPath.of("java/com/facebook/SomeClass.java"));
    parser.getPermState().invalidateBasedOn(WatchmanWatcherOneBigEvent.pathEvent(event));

    // Call parseBuildFile to request cached rules.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cells,
        knownRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, counter.calls);
  }

  @Test
  public void whenNotifiedOfContainedTempFileAddThenCachedRulesAreNotInvalidated()
      throws BuildFileParseException {
    // Call parseBuildFile to populate the cache.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cells,
        knownRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

    // Process event.
    WatchmanPathEvent event =
        WatchmanPathEvent.of(
            filesystem.getRootPath(),
            Kind.CREATE,
            ForwardRelPath.of("java/com/facebook/MumbleSwp.Java.swp"));
    parser.getPermState().invalidateBasedOn(WatchmanWatcherOneBigEvent.pathEvent(event));

    // Call parseBuildFile to request cached rules.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cells,
        knownRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should not have invalidated cache.", 1, counter.calls);
  }

  @Test
  public void whenNotifiedOfContainedTempFileChangeThenCachedRulesAreNotInvalidated()
      throws BuildFileParseException {
    // Call parseBuildFile to populate the cache.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cells,
        knownRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

    // Process event.
    WatchmanPathEvent event =
        WatchmanPathEvent.of(
            filesystem.getRootPath(),
            Kind.MODIFY,
            ForwardRelPath.of("java/com/facebook/MumbleSwp.Java.swp"));
    parser.getPermState().invalidateBasedOn(WatchmanWatcherOneBigEvent.pathEvent(event));

    // Call parseBuildFile to request cached rules.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cells,
        knownRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should not have invalidated cache.", 1, counter.calls);
  }

  @Test
  public void whenNotifiedOfContainedTempFileDeleteThenCachedRulesAreNotInvalidated()
      throws BuildFileParseException {
    // Call parseBuildFile to populate the cache.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cells,
        knownRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

    // Process event.
    WatchmanPathEvent event =
        WatchmanPathEvent.of(
            filesystem.getRootPath(),
            Kind.DELETE,
            ForwardRelPath.of("java/com/facebook/MumbleSwp.Java.swp"));
    parser.getPermState().invalidateBasedOn(WatchmanWatcherOneBigEvent.pathEvent(event));

    // Call parseBuildFile to request cached rules.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cells,
        knownRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should not have invalidated cache.", 1, counter.calls);
  }

  @Test
  public void whenNotifiedOfUnrelatedFileAddThenCacheRulesAreNotInvalidated()
      throws BuildFileParseException {
    // Call parseBuildFile to populate the cache.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cells,
        knownRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

    // Process event.
    WatchmanPathEvent event =
        WatchmanPathEvent.of(
            filesystem.getRootPath(), Kind.CREATE, ForwardRelPath.of("SomeClass.java__backup"));
    parser.getPermState().invalidateBasedOn(WatchmanWatcherOneBigEvent.pathEvent(event));

    // Call parseBuildFile to request cached rules.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cells,
        knownRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

    // Test that the second parseBuildFile call did not repopulate the cache.
    assertEquals("Should have not invalidated cache.", 1, counter.calls);
  }

  @Test
  public void whenNotifiedOfUnrelatedFileChangeThenCacheRulesAreNotInvalidated()
      throws BuildFileParseException {
    // Call parseBuildFile to populate the cache.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cells,
        knownRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

    // Process event.
    WatchmanPathEvent event =
        WatchmanPathEvent.of(
            filesystem.getRootPath(), Kind.MODIFY, ForwardRelPath.of("SomeClass.java__backup"));
    parser.getPermState().invalidateBasedOn(WatchmanWatcherOneBigEvent.pathEvent(event));

    // Call parseBuildFile to request cached rules.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cells,
        knownRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

    // Test that the second parseBuildFile call did not repopulate the cache.
    assertEquals("Should have not invalidated cache.", 1, counter.calls);
  }

  @Test
  public void whenNotifiedOfUnrelatedFileDeleteThenCacheRulesAreNotInvalidated()
      throws BuildFileParseException {
    // Call parseBuildFile to populate the cache.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cells,
        knownRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

    // Process event.
    WatchmanPathEvent event =
        WatchmanPathEvent.of(
            filesystem.getRootPath(), Kind.DELETE, ForwardRelPath.of("SomeClass.java__backup"));
    parser.getPermState().invalidateBasedOn(WatchmanWatcherOneBigEvent.pathEvent(event));

    // Call parseBuildFile to request cached rules.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cells,
        knownRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

    // Test that the second parseBuildFile call did not repopulate the cache.
    assertEquals("Should have not invalidated cache.", 1, counter.calls);
  }

  @Test
  public void whenAllRulesAreRequestedWithDifferingCellsThenRulesAreParsedOnce()
      throws BuildFileParseException, IOException, InterruptedException {
    filterAllTargetsInProject(parser, parsingContext);

    assertEquals("Should have parsed once.", 1, counter.calls);

    // create subcell
    AbsPath newTempDir = tempDir.newFolder("subcell");
    Files.createFile(newTempDir.resolve("bar.py").getPath());
    ProjectFilesystem newFilesystem = TestProjectFilesystems.createProjectFilesystem(newTempDir);
    BuckConfig newConfig =
        FakeBuckConfig.builder()
            .setFilesystem(newFilesystem)
            .setSections(
                ImmutableMap.of(
                    ParserConfig.BUILDFILE_SECTION_NAME,
                    ImmutableMap.of(ParserConfig.INCLUDES_PROPERTY_NAME, "//bar.py")))
            .build();
    Cells newCells =
        new TestCellBuilder().setFilesystem(newFilesystem).setBuckConfig(newConfig).build();

    Parser newParser =
        TestParserFactory.create(executor.get(), newCells, knownRuleTypesProvider, eventBus);

    filterAllTargetsInProject(newParser, parsingContext.withCells(newCells));

    assertEquals("Should not have invalidated cache.", 1, counter.calls);
  }

  @Test
  public void whenAllRulesThenSingleTargetRequestedThenRulesAreParsedOnce()
      throws BuildFileParseException, IOException, InterruptedException {
    filterAllTargetsInProject(parser, parsingContext);
    BuildTarget foo = BuildTargetFactory.newInstance("//java/com/facebook", "foo");
    parser.buildTargetGraph(parsingContext, ImmutableSet.of(foo));

    assertEquals("Should have cached build rules.", 1, counter.calls);
  }

  @Test
  public void whenSingleTargetThenAllRulesRequestedThenRulesAreParsedOnce()
      throws BuildFileParseException, IOException, InterruptedException {
    BuildTarget foo = BuildTargetFactory.newInstance("//java/com/facebook", "foo");
    parser.buildTargetGraph(parsingContext, ImmutableSet.of(foo));
    filterAllTargetsInProject(parser, parsingContext);

    assertEquals("Should have replaced build rules", 1, counter.calls);
  }

  @Test
  public void whenBuildFilePathChangedThenFlavorsOfTargetsInPathAreInvalidated() throws Exception {
    tempDir.newFolder("foo");
    tempDir.newFolder("bar");

    AbsPath testFooBuckFile = tempDir.newFile("foo/BUCK");
    Files.write(
        testFooBuckFile.getPath(),
        "java_library(name = 'foo', visibility=['PUBLIC'])\n".getBytes(UTF_8));

    AbsPath testBarBuckFile = tempDir.newFile("bar/BUCK");
    Files.write(
        testBarBuckFile.getPath(),
        ("java_library(name = 'bar',\n" + "  deps = ['//foo:foo'])\n").getBytes(UTF_8));

    // Fetch //bar:bar#src to put it in cache.
    BuildTarget barTarget =
        BuildTargetFactory.newInstance("//bar", "bar", InternalFlavor.of("src"));
    ImmutableSet<BuildTarget> buildTargets = ImmutableSet.of(barTarget);

    parser.buildTargetGraph(parsingContext, buildTargets);

    // Rewrite //bar:bar so it doesn't depend on //foo:foo any more.
    // Delete foo/BUCK and invalidate the cache, which should invalidate
    // the cache entry for //bar:bar#src.
    Files.delete(testFooBuckFile.getPath());
    Files.write(testBarBuckFile.getPath(), "java_library(name = 'bar')\n".getBytes(UTF_8));
    WatchmanPathEvent deleteEvent =
        WatchmanPathEvent.of(filesystem.getRootPath(), Kind.DELETE, ForwardRelPath.of("foo/BUCK"));
    parser.getPermState().invalidateBasedOn(WatchmanWatcherOneBigEvent.pathEvent(deleteEvent));
    WatchmanPathEvent modifyEvent =
        WatchmanPathEvent.of(filesystem.getRootPath(), Kind.MODIFY, ForwardRelPath.of("bar/BUCK"));
    parser.getPermState().invalidateBasedOn(WatchmanWatcherOneBigEvent.pathEvent(modifyEvent));

    parser.buildTargetGraph(parsingContext, buildTargets);
  }

  @Test
  public void targetWithSourceFileChangesHash() throws Exception {
    tempDir.newFolder("foo");

    AbsPath testFooBuckFile = tempDir.newFile("foo/BUCK");
    Files.write(
        testFooBuckFile.getPath(),
        "java_library(name = 'lib', srcs=glob(['*.java']), visibility=['PUBLIC'])\n"
            .getBytes(UTF_8));
    BuildTarget fooLibTarget = BuildTargetFactory.newInstance("//foo", "lib");
    HashCode original = buildTargetGraphAndGetHashCodes(parser, fooLibTarget).get(fooLibTarget);

    parser = TestParserFactory.create(executor.get(), cells, knownRuleTypesProvider);
    AbsPath testFooJavaFile = tempDir.newFile("foo/Foo.java");
    Files.write(testFooJavaFile.getPath(), "// Ceci n'est pas une Javafile\n".getBytes(UTF_8));
    HashCode updated = buildTargetGraphAndGetHashCodes(parser, fooLibTarget).get(fooLibTarget);

    assertNotEquals(original, updated);
  }

  @Test
  public void deletingSourceFileChangesHash() throws Exception {
    tempDir.newFolder("foo");

    AbsPath testFooBuckFile = tempDir.newFile("foo/BUCK");
    Files.write(
        testFooBuckFile.getPath(),
        "java_library(name = 'lib', srcs=glob(['*.java']), visibility=['PUBLIC'])\n"
            .getBytes(UTF_8));

    AbsPath testFooJavaFile = tempDir.newFile("foo/Foo.java");
    Files.write(testFooJavaFile.getPath(), "// Ceci n'est pas une Javafile\n".getBytes(UTF_8));

    AbsPath testBarJavaFile = tempDir.newFile("foo/Bar.java");
    Files.write(testBarJavaFile.getPath(), "// Seriously, no Java here\n".getBytes(UTF_8));

    BuildTarget fooLibTarget = BuildTargetFactory.newInstance("//foo", "lib");
    HashCode originalHash = buildTargetGraphAndGetHashCodes(parser, fooLibTarget).get(fooLibTarget);

    Files.delete(testBarJavaFile.getPath());
    WatchmanPathEvent deleteEvent =
        WatchmanPathEvent.of(
            filesystem.getRootPath(), Kind.DELETE, ForwardRelPath.of("foo/Bar.java"));
    parser.getPermState().invalidateBasedOn(WatchmanWatcherOneBigEvent.pathEvent(deleteEvent));

    HashCode updatedHash = buildTargetGraphAndGetHashCodes(parser, fooLibTarget).get(fooLibTarget);

    assertNotEquals(originalHash, updatedHash);
  }

  @Test
  public void renamingSourceFileChangesHash() throws Exception {
    tempDir.newFolder("foo");

    AbsPath testFooBuckFile = tempDir.newFile("foo/BUCK");
    Files.write(
        testFooBuckFile.getPath(),
        "java_library(name = 'lib', srcs=glob(['*.java']), visibility=['PUBLIC'])\n"
            .getBytes(UTF_8));

    AbsPath testFooJavaFile = tempDir.newFile("foo/Foo.java");
    Files.write(testFooJavaFile.getPath(), "// Ceci n'est pas une Javafile\n".getBytes(UTF_8));

    BuildTarget fooLibTarget = BuildTargetFactory.newInstance("//foo", "lib");

    HashCode originalHash = buildTargetGraphAndGetHashCodes(parser, fooLibTarget).get(fooLibTarget);

    Files.move(testFooJavaFile.getPath(), testFooJavaFile.getPath().resolveSibling("Bar.java"));
    WatchmanPathEvent deleteEvent =
        WatchmanPathEvent.of(
            filesystem.getRootPath(), Kind.DELETE, ForwardRelPath.of("foo/Foo.java"));
    WatchmanPathEvent createEvent =
        WatchmanPathEvent.of(
            (filesystem.getRootPath()), Kind.CREATE, ForwardRelPath.of("foo/Bar.java"));
    parser.getPermState().invalidateBasedOn(WatchmanWatcherOneBigEvent.pathEvent(deleteEvent));
    parser.getPermState().invalidateBasedOn(WatchmanWatcherOneBigEvent.pathEvent(createEvent));

    HashCode updatedHash = buildTargetGraphAndGetHashCodes(parser, fooLibTarget).get(fooLibTarget);

    assertNotEquals(originalHash, updatedHash);
  }

  @Test
  public void twoBuildTargetHashCodesPopulatesCorrectly() throws Exception {
    tempDir.newFolder("foo");

    AbsPath testFooBuckFile = tempDir.newFile("foo/BUCK");
    Files.write(
        testFooBuckFile.getPath(),
        ("java_library(name = 'lib', visibility=['PUBLIC'])\n"
                + "java_library(name = 'lib2', visibility=['PUBLIC'])\n")
            .getBytes(UTF_8));

    BuildTarget fooLibTarget = BuildTargetFactory.newInstance("//foo", "lib");
    BuildTarget fooLib2Target = BuildTargetFactory.newInstance("//foo", "lib2");

    ImmutableMap<BuildTarget, HashCode> hashes =
        buildTargetGraphAndGetHashCodes(parser, fooLibTarget, fooLib2Target);

    assertNotNull(hashes.get(fooLibTarget));
    assertNotNull(hashes.get(fooLib2Target));

    assertNotEquals(hashes.get(fooLibTarget), hashes.get(fooLib2Target));
  }

  @Test
  public void addingDepToTargetChangesHashOfDependingTargetOnly() throws Exception {
    tempDir.newFolder("foo");

    AbsPath testFooBuckFile = tempDir.newFile("foo/BUCK");
    Files.write(
        testFooBuckFile.getPath(),
        ("java_library(name = 'lib', deps = [], visibility=['PUBLIC'])\n"
                + "java_library(name = 'lib2', deps = [], visibility=['PUBLIC'])\n")
            .getBytes(UTF_8));

    BuildTarget fooLibTarget = BuildTargetFactory.newInstance("//foo", "lib");
    BuildTarget fooLib2Target = BuildTargetFactory.newInstance("//foo", "lib2");
    ImmutableMap<BuildTarget, HashCode> hashes =
        buildTargetGraphAndGetHashCodes(parser, fooLibTarget, fooLib2Target);
    HashCode libKey = hashes.get(fooLibTarget);
    HashCode lib2Key = hashes.get(fooLib2Target);

    parser = TestParserFactory.create(executor.get(), cells, knownRuleTypesProvider);
    Files.write(
        testFooBuckFile.getPath(),
        ("java_library(name = 'lib', deps = [], visibility=['PUBLIC'])\njava_library("
                + "name = 'lib2', deps = [':lib'], visibility=['PUBLIC'])\n")
            .getBytes(UTF_8));

    hashes = buildTargetGraphAndGetHashCodes(parser, fooLibTarget, fooLib2Target);

    assertEquals(libKey, hashes.get(fooLibTarget));
    assertNotEquals(lib2Key, hashes.get(fooLib2Target));
  }

  @Test
  public void getOrLoadTargetNodeRules() throws IOException, BuildFileParseException {
    tempDir.newFolder("foo");

    AbsPath testFooBuckFile = tempDir.newFile("foo/BUCK");
    Files.write(testFooBuckFile.getPath(), "java_library(name = 'lib')\n".getBytes(UTF_8));
    BuildTarget fooLibTarget = BuildTargetFactory.newInstance("//foo", "lib");

    TargetNode<?> targetNode =
        parser.getTargetNodeAssertCompatible(parsingContext, fooLibTarget, DependencyStack.root());
    assertThat(targetNode.getBuildTarget(), equalTo(fooLibTarget));

    PerBuildState state =
        parser.getPerBuildStateFactory().create(parsingContext, parser.getPermState());
    SortedMap<ParamNameOrSpecial, Object> targetNodeAttributes =
        parser.getTargetNodeRawAttributes(
            state, parsingContext.getCells(), targetNode, DependencyStack.root());
    assertThat(
        targetNodeAttributes.get(ParamName.bySnakeCase("name")),
        equalTo(targetNode.getBuildTarget().getShortName()));
  }

  @Test
  public void whenSymlinksForbiddenThenParseFailsOnSymlinkInSources() throws Exception {
    // This test depends on creating symbolic links which we cannot do on Windows.
    assumeThat(Platform.detect(), not(Platform.WINDOWS));

    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(
        "Target //foo:lib contains input files under a path which contains a symbolic link ("
            + "{foo/bar=bar}). To resolve this, use separate rules and declare dependencies "
            + "instead of using symbolic links.");

    BuckConfig config =
        FakeBuckConfig.builder()
            .setFilesystem(filesystem)
            .setSections("[project]", "allow_symlinks = forbid")
            .build();
    cells = new TestCellBuilder().setBuckConfig(config).setFilesystem(filesystem).build();

    tempDir.newFolder("bar");
    tempDir.newFile("bar/Bar.java");
    tempDir.newFolder("foo");
    AbsPath rootPath = tempDir.getRoot().toRealPath();
    CreateSymlinksForTests.createSymLink(rootPath.resolve("foo/bar"), rootPath.resolve("bar"));

    AbsPath testBuckFile = rootPath.resolve("foo").resolve("BUCK");
    Files.write(
        testBuckFile.getPath(),
        "java_library(name = 'lib', srcs=['bar/Bar.java'])\n".getBytes(UTF_8));

    BuildTarget libTarget = BuildTargetFactory.newInstance("//foo", "lib");
    ImmutableSet<BuildTarget> buildTargets = ImmutableSet.of(libTarget);

    parser.buildTargetGraph(parsingContext.withCells(cells), buildTargets);
  }

  @Test
  public void whenSymlinksAreInReadOnlyPathsCachingIsNotDisabled() throws Exception {
    // This test depends on creating symbolic links which we cannot do on Windows.
    assumeThat(Platform.detect(), not(Platform.WINDOWS));

    AbsPath rootPath = tempDir.getRoot().toRealPath();
    BuckConfig config =
        FakeBuckConfig.builder()
            .setFilesystem(filesystem)
            .setSections("[project]", "read_only_paths = foo/bar")
            .build();
    cells = new TestCellBuilder().setBuckConfig(config).setFilesystem(filesystem).build();

    tempDir.newFolder("bar");
    tempDir.newFile("bar/Bar.java");
    tempDir.newFolder("foo");

    CreateSymlinksForTests.createSymLink(rootPath.resolve("foo/bar"), rootPath.resolve("bar"));

    AbsPath testBuckFile = rootPath.resolve("foo").resolve("BUCK");
    Files.write(
        testBuckFile.getPath(),
        "java_library(name = 'lib', srcs=['bar/Bar.java'])\n".getBytes(UTF_8));

    BuildTarget libTarget = BuildTargetFactory.newInstance("//foo", "lib");
    ImmutableSet<BuildTarget> buildTargets = ImmutableSet.of(libTarget);

    parser.buildTargetGraph(parsingContext.withCells(cells), buildTargets);

    DaemonicParserState permState = parser.getPermState();
    for (BuildTarget target : buildTargets) {
      assertTrue(
          permState
              .getOrCreateNodeCache(DaemonicParserState.TARGET_NODE_CACHE_TYPE)
              .lookupComputedNode(
                  cells.getRootCell(), target, parser.getPermState().validationToken())
              .isPresent());
    }
  }

  @Test
  public void buildTargetHashCodePopulatesCorrectly() throws Exception {
    tempDir.newFolder("foo");

    AbsPath testFooBuckFile = tempDir.newFile("foo/BUCK");
    Files.write(
        testFooBuckFile.getPath(),
        "java_library(name = 'lib', visibility=['PUBLIC'])\n".getBytes(UTF_8));

    BuildTarget fooLibTarget = BuildTargetFactory.newInstance("//foo", "lib");

    // We can't precalculate the hash, since it depends on the buck version. Check for the presence
    // of a hash for the right key.
    HashCode hashCode = buildTargetGraphAndGetHashCodes(parser, fooLibTarget).get(fooLibTarget);

    assertNotNull(hashCode);
  }

  @Test
  public void readConfigReadsConfig() throws Exception {
    AbsPath buckFile = cellRoot.resolve("BUCK");
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//", "cake");
    Files.write(
        buckFile.getPath(),
        Joiner.on("")
            .join(
                ImmutableList.of(
                    "genrule(\n"
                        + "name = 'cake',\n"
                        + "out = read_config('foo', 'bar', 'default') + '.txt',\n"
                        + "cmd = 'touch $OUT'\n"
                        + ")\n"))
            .getBytes(UTF_8));

    BuckConfig config = FakeBuckConfig.builder().setFilesystem(filesystem).build();

    Cells cells = new TestCellBuilder().setFilesystem(filesystem).setBuckConfig(config).build();
    TargetNode<GenruleDescriptionArg> node =
        TargetNodes.castArg(
                parser.getTargetNodeAssertCompatible(
                    parsingContext.withCells(cells), buildTarget, DependencyStack.root()),
                GenruleDescriptionArg.class)
            .get();

    assertThat(node.getConstructorArg().getOut().get(), is(equalTo("default.txt")));
  }

  @Test
  public void emptyStringBuckConfigEntryDoesNotCauseInvalidation() throws Exception {
    AbsPath buckFile = cellRoot.resolve("BUCK");
    Files.write(
        buckFile.getPath(),
        Joiner.on("")
            .join(
                ImmutableList.of(
                    "read_config('foo', 'bar')\n",
                    "genrule(name = 'cake', out = 'file.txt', cmd = 'touch $OUT')\n"))
            .getBytes(UTF_8));

    BuckConfig config =
        FakeBuckConfig.builder()
            .setSections(ImmutableMap.of("foo", ImmutableMap.of("bar", "")))
            .setFilesystem(filesystem)
            .build();

    Cells cells = new TestCellBuilder().setFilesystem(filesystem).setBuckConfig(config).build();

    parser.getTargetNodeAssertCompatible(
        parsingContext.withCells(cells), buildTarget, DependencyStack.root());

    cells = new TestCellBuilder().setFilesystem(filesystem).setBuckConfig(config).build();

    parser.getTargetNodeAssertCompatible(
        parsingContext.withCells(cells), buildTarget, DependencyStack.root());

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should not have invalidated.", 1, counter.calls);
  }

  @Test
  public void defaultFlavorsInRuleArgsAppliedToTarget() throws Exception {
    // We depend on Xcode platforms for this test.
    assumeThat(Platform.detect(), is(Platform.MACOS));
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    AbsPath buckFile = cellRoot.resolve("lib/BUCK");
    Files.createDirectories(buckFile.getParent().getPath());
    Files.write(
        buckFile.getPath(),
        ("cxx_library("
                + "  name = 'lib', "
                + "  srcs=glob(['*.c']), "
                + "  defaults={'platform':'iphonesimulator-x86_64'}"
                + ")")
            .getBytes(UTF_8));

    ImmutableSet<BuildTarget> result =
        parser
            .buildTargetGraphWithTopLevelConfigurationTargets(
                ParsingContext.builder(cells, executorService)
                    .setApplyDefaultFlavorsMode(ApplyDefaultFlavorsMode.SINGLE)
                    .build(),
                ImmutableList.of(
                    BuildTargetSpec.from(
                        UnconfiguredBuildTargetFactoryForTests.newInstance("//lib", "lib"))),
                Optional.empty())
            .getBuildTargets();

    assertThat(
        result,
        hasItems(
            BuildTargetFactory.newInstance(
                "//lib",
                "lib",
                InternalFlavor.of("iphonesimulator-x86_64"),
                InternalFlavor.of("static"))));
  }

  @Test
  public void defaultFlavorsInConfigAppliedToTarget() throws Exception {
    // We depend on Xcode platforms for this test.
    assumeThat(Platform.detect(), is(Platform.MACOS));
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    AbsPath buckFile = cellRoot.resolve("lib/BUCK");
    Files.createDirectories(buckFile.getParent().getPath());
    Files.write(
        buckFile.getPath(),
        ("cxx_library(" + "  name = 'lib', " + "  srcs=glob(['*.c']) " + ")").getBytes(UTF_8));

    BuckConfig config =
        FakeBuckConfig.builder()
            .setFilesystem(filesystem)
            .setSections(
                ImmutableMap.of(
                    "defaults.cxx_library",
                    ImmutableMap.of("platform", "iphoneos-arm64", "type", "shared")))
            .build();

    cells = new TestCellBuilder().setFilesystem(filesystem).setBuckConfig(config).build();

    ImmutableSet<BuildTarget> result =
        parser
            .buildTargetGraphWithTopLevelConfigurationTargets(
                ParsingContext.builder(cells, executorService)
                    .setApplyDefaultFlavorsMode(ApplyDefaultFlavorsMode.SINGLE)
                    .build(),
                ImmutableList.of(
                    BuildTargetSpec.from(
                        UnconfiguredBuildTargetFactoryForTests.newInstance("//lib", "lib"))),
                Optional.empty())
            .getBuildTargets();

    assertThat(
        result,
        hasItems(
            BuildTargetFactory.newInstance(
                "//lib", "lib", InternalFlavor.of("iphoneos-arm64"), InternalFlavor.of("shared"))));
  }

  @Test
  public void defaultFlavorsInArgsOverrideDefaultsFromConfig() throws Exception {
    // We depend on Xcode platforms for this test.
    assumeThat(Platform.detect(), is(Platform.MACOS));

    AbsPath buckFile = cellRoot.resolve("lib/BUCK");
    Files.createDirectories(buckFile.getParent().getPath());
    Files.write(
        buckFile.getPath(),
        ("cxx_library("
                + "  name = 'lib', "
                + "  srcs=glob(['*.c']), "
                + "  defaults={'platform':'macosx-x86_64'}"
                + ")")
            .getBytes(UTF_8));

    BuckConfig config =
        FakeBuckConfig.builder()
            .setFilesystem(filesystem)
            .setSections(
                ImmutableMap.of(
                    "defaults.cxx_library",
                    ImmutableMap.of("platform", "iphoneos-arm64", "type", "shared")))
            .build();

    cells = new TestCellBuilder().setFilesystem(filesystem).setBuckConfig(config).build();

    ImmutableSet<BuildTarget> result =
        parser
            .buildTargetGraphWithTopLevelConfigurationTargets(
                ParsingContext.builder(cells, executorService)
                    .setApplyDefaultFlavorsMode(ApplyDefaultFlavorsMode.SINGLE)
                    .build(),
                ImmutableList.of(
                    BuildTargetSpec.from(
                        UnconfiguredBuildTargetFactoryForTests.newInstance("//lib", "lib"))),
                Optional.empty())
            .getBuildTargets();

    assertThat(
        result,
        hasItems(
            BuildTargetFactory.newInstance(
                "//lib", "lib", InternalFlavor.of("macosx-x86_64"), InternalFlavor.of("shared"))));
  }

  @Test
  public void testGetCacheReturnsSame() {
    assertEquals(
        parser.getPermState().getOrCreateNodeCache(DaemonicParserState.TARGET_NODE_CACHE_TYPE),
        parser.getPermState().getOrCreateNodeCache(DaemonicParserState.TARGET_NODE_CACHE_TYPE));
    assertNotEquals(
        parser.getPermState().getOrCreateNodeCache(DaemonicParserState.TARGET_NODE_CACHE_TYPE),
        parser.getPermState().getOrCreateNodeCache(DaemonicParserState.RAW_TARGET_NODE_CACHE_TYPE));
  }

  @Test
  public void testVisibilityGetsChecked() throws Exception {
    Path visibilityData = TestDataHelper.getTestDataScenario(this, "visibility");
    AbsPath visibilityBuckFile = cellRoot.resolve("BUCK");
    AbsPath visibilitySubBuckFile = cellRoot.resolve("sub/BUCK");
    Files.createDirectories(visibilityBuckFile.getParent().getPath());
    Files.createDirectories(visibilitySubBuckFile.getParent().getPath());
    Files.copy(visibilityData.resolve("BUCK.fixture"), visibilityBuckFile.getPath());
    Files.copy(visibilityData.resolve("sub/BUCK.fixture"), visibilitySubBuckFile.getPath());

    parser.buildTargetGraph(
        parsingContext, ImmutableSet.of(BuildTargetFactory.newInstance("//:should_pass")));
    parser.buildTargetGraph(
        parsingContext, ImmutableSet.of(BuildTargetFactory.newInstance("//:should_pass2")));
    try {
      parser.buildTargetGraph(
          parsingContext, ImmutableSet.of(BuildTargetFactory.newInstance("//:should_fail")));
      Assert.fail("did not expect to succeed parsing");
    } catch (Exception e) {
      assertThat(e, instanceOf(HumanReadableException.class));
      assertThat(
          e.getMessage(),
          containsString("//:should_fail depends on //sub:sub, which is not visible"));
    }
  }

  @Test
  public void testSkylarkSyntaxParsing() throws Exception {
    AbsPath buckFile = cellRoot.resolve("BUCK");
    Files.write(
        buckFile.getPath(),
        Joiner.on("\n")
            .join(
                ImmutableList.of(
                    "# BUILD FILE SYNTAX: SKYLARK",
                    "genrule(name = 'cake', out = 'file.txt', cmd = 'touch $OUT')",
                    "glob(['*.txt'])"))
            .getBytes(UTF_8));

    BuckConfig config =
        FakeBuckConfig.builder()
            .setFilesystem(filesystem)
            .setSections("[parser]", "polyglot_parsing_enabled_deprecated=true")
            .build();

    Cells cells = new TestCellBuilder().setFilesystem(filesystem).setBuckConfig(config).build();
    parser.getTargetNodeAssertCompatible(
        parsingContext.withCells(cells), buildTarget, DependencyStack.root());
  }

  @Test
  public void testSkylarkIsUsedWithoutPolyglotParsingEnabled() throws Exception {
    AbsPath buckFile = cellRoot.resolve("BUCK");
    Files.write(
        buckFile.getPath(),
        Joiner.on("\n")
            .join(
                ImmutableList.of("genrule(name = type(''), out = 'file.txt', cmd = 'touch $OUT')"))
            .getBytes(UTF_8));

    BuckConfig config = FakeBuckConfig.builder().setFilesystem(filesystem).build();

    Cells cells = new TestCellBuilder().setFilesystem(filesystem).setBuckConfig(config).build();

    TargetNode<?> targetNode =
        parser.getTargetNodeAssertCompatible(
            parsingContext.withCells(cells),
            BuildTargetFactory.newInstance("//:string"),
            DependencyStack.root());
    // in Skylark the type of str is "string" and in Python DSL it's "<type 'str'>"
    assertEquals(targetNode.getBuildTarget().getShortName(), "string");
  }

  private ActionGraphBuilder buildActionGraph(
      BuckEventBus eventBus, TargetGraph targetGraph, Cells cells) {
    return Objects.requireNonNull(
            new ActionGraphProviderBuilder()
                .withEventBus(eventBus)
                .withCellProvider(cells.getCellProvider())
                .build()
                .getFreshActionGraph(TestTargetGraphCreationResultFactory.create(targetGraph)))
        .getActionGraphBuilder();
  }

  /**
   * Populates the collection of known build targets that this Parser will use to construct an
   * action graph using all build files inside the given project root and returns an optionally
   * filtered set of build targets.
   *
   * @return The build targets in the project filtered by the given filter.
   */
  public static synchronized ImmutableSet<BuildTarget> filterAllTargetsInProject(
      Parser parser, ParsingContext parsingContext)
      throws BuildFileParseException, IOException, InterruptedException {
    return FluentIterable.from(
            parser
                .buildTargetGraphWithTopLevelConfigurationTargets(
                    parsingContext,
                    ImmutableList.of(
                        TargetNodePredicateSpec.of(
                            BuildFileSpec.fromRecursivePath(
                                CellRelativePath.of(
                                    parsingContext.getCells().getRootCell().getCanonicalName(),
                                    ForwardRelPath.of(""))))),
                    Optional.empty())
                .getTargetGraph()
                .getNodes())
        .transform(TargetNode::getBuildTarget)
        .toSet();
  }

  private ImmutableMap<BuildTarget, HashCode> buildTargetGraphAndGetHashCodes(
      Parser parser, BuildTarget... buildTargets) throws Exception {
    // Build the target graph so we can access the hash code cache.

    ImmutableSet<BuildTarget> buildTargetsList = ImmutableSet.copyOf(buildTargets);
    TargetGraph targetGraph =
        parser.buildTargetGraph(parsingContext, buildTargetsList).getTargetGraph();

    ImmutableMap<BuildTarget, Map<ParamNameOrSpecial, Object>> attributes =
        getRawTargetNodes(
            parser,
            typeCoercerFactory,
            eventBus,
            cells,
            knownRuleTypesProvider,
            parsingContext,
            executorService,
            executableFinder,
            buildTargets);

    ImmutableMap.Builder<BuildTarget, HashCode> toReturn = ImmutableMap.builder();
    for (TargetNode<?> node : targetGraph.getNodes()) {
      Hasher hasher = Hashing.sha1().newHasher();
      JsonObjectHashing.hashJsonObject(
          hasher,
          attributes.get(node.getBuildTarget()).entrySet().stream()
              .collect(
                  ImmutableMap.toImmutableMap(
                      e -> e.getKey().getSnakeCase(), Map.Entry::getValue)));
      toReturn.put(node.getBuildTarget(), hasher.hash());
    }

    return toReturn.build();
  }

  private ImmutableMap<BuildTarget, Map<ParamNameOrSpecial, Object>> getRawTargetNodes(
      Parser parser,
      TypeCoercerFactory typeCoercerFactory,
      BuckEventBus eventBus,
      Cells cells,
      KnownRuleTypesProvider knownRuleTypesProvider,
      ParsingContext parsingContext,
      ListeningExecutorService executor,
      ExecutableFinder executableFinder,
      BuildTarget... buildTargets)
      throws BuildFileParseException {
    ImmutableMap.Builder<BuildTarget, Map<ParamNameOrSpecial, Object>> attributesByTarget =
        ImmutableMap.builder();
    List<BuildTarget> buildTargetList = Lists.newArrayList(buildTargets);
    Map<Path, HashCode> hashes = new HashMap<>();
    buildTargetList.forEach(
        buildTarget ->
            hashes.put(
                buildTarget
                    .getCellRelativeBasePath()
                    .getPath()
                    .toPath(filesystem.getFileSystem())
                    .resolve("BUCK"),
                HashCode.fromBytes(buildTarget.getBaseName().toString().getBytes(UTF_8))));

    try (PerBuildState state =
        new PerBuildStateFactory(
                typeCoercerFactory,
                new DefaultConstructorArgMarshaller(),
                knownRuleTypesProvider,
                new ParserPythonInterpreterProvider(cells.getBuckConfig(), executableFinder),
                new WatchmanFactory.NullWatchman("test", WatchmanError.TEST),
                Optional.empty(),
                eventBus,
                new ParsingUnconfiguredBuildTargetViewFactory(),
                UnconfiguredTargetConfiguration.INSTANCE)
            .create(ParsingContext.builder(cells, executor).build(), parser.getPermState())) {
      for (BuildTarget buildTarget : buildTargets) {
        attributesByTarget.put(
            buildTarget,
            Preconditions.checkNotNull(
                parser.getTargetNodeRawAttributes(
                    state,
                    cells,
                    parser.getTargetNodeAssertCompatible(
                        parsingContext, buildTarget, DependencyStack.root()),
                    DependencyStack.root())));
      }

      return attributesByTarget.build();
    }
  }

  static class ParseEventStartedCounter {
    int calls = 0;

    // We know that the ProjectBuildFileParser emits a Started event when it parses a build file.
    @Subscribe
    public void call(ParseBuckFileEvent.Started parseEvent) {
      calls++;
    }
  }
}
