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

package com.facebook.buck.cxx;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.build.context.FakeBuildContext;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.ForwardRelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.common.BuildRules;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.SourceWithFlags;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.test.rule.ExternalTestRunnerTestSpec;
import com.facebook.buck.core.test.rule.TestRule;
import com.facebook.buck.cxx.config.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.parser.exceptions.NoSuchBuildTargetException;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.coercer.SourceSortedSet;
import com.facebook.buck.rules.keys.DefaultRuleKeyFactory;
import com.facebook.buck.rules.keys.TestDefaultRuleKeyFactory;
import com.facebook.buck.rules.macros.LocationMacro;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.rules.macros.StringWithMacrosUtils;
import com.facebook.buck.shell.Genrule;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.test.TestRunningOptions;
import com.facebook.buck.test.selectors.TestSelectorList;
import com.facebook.buck.util.cache.FileHashCacheMode;
import com.facebook.buck.util.cache.impl.DefaultFileHashCache;
import com.facebook.buck.util.cache.impl.StackedFileHashCache;
import com.facebook.buck.util.hashing.FileHashLoader;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.hamcrest.Matchers;
import org.junit.Test;

public class CxxTestDescriptionTest {

  private final CxxBuckConfig cxxBuckConfig = new CxxBuckConfig(FakeBuckConfig.empty());

  private void addFramework(ActionGraphBuilder graphBuilder, ProjectFilesystem filesystem)
      throws NoSuchBuildTargetException {
    GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:framework_rule"))
        .setOut("out")
        .build(graphBuilder, filesystem);
  }

  private CxxTestBuilder createTestBuilder() throws NoSuchBuildTargetException {
    return createTestBuilder("//:test");
  }

  private CxxTestBuilder createTestBuilder(String target) throws NoSuchBuildTargetException {
    return new CxxTestBuilder(
        BuildTargetFactory.newInstance(target),
        cxxBuckConfig,
        CxxPlatformUtils.DEFAULT_UNRESOLVED_PLATFORM,
        CxxTestUtils.createDefaultPlatforms());
  }

  @Test
  public void findDepsFromParams() {
    BuildTarget gtest = BuildTargetFactory.newInstance("//:gtest");
    BuildTarget gtestMain = BuildTargetFactory.newInstance("//:gtest_main");

    CxxBuckConfig cxxBuckConfig =
        new CxxBuckConfig(
            FakeBuckConfig.builder()
                .setSections(
                    ImmutableMap.of(
                        "cxx",
                        ImmutableMap.of(
                            "gtest_dep", gtest.toString(),
                            "gtest_default_test_main_dep", gtestMain.toString())))
                .build());

    BuildTarget target = BuildTargetFactory.newInstance("//:target");
    CxxTestBuilder builder =
        new CxxTestBuilder(target, cxxBuckConfig)
            .setFramework(CxxTestType.GTEST)
            .setUseDefaultTestMain(true);
    ImmutableSortedSet<BuildTarget> implicit = builder.findImplicitDeps();

    assertThat(implicit, hasItem(gtest));
    assertThat(implicit, hasItem(gtestMain));
  }

  @Test
  public void environmentIsPropagated() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolverAdapter pathResolver = graphBuilder.getSourcePathResolver();
    addFramework(graphBuilder, filesystem);
    BuildRule someRule =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:some_rule"))
            .setOut("someRule")
            .build(graphBuilder);
    CxxTestBuilder builder =
        createTestBuilder()
            .setEnv(
                ImmutableMap.of(
                    "TEST",
                    StringWithMacrosUtils.format(
                        "value %s", LocationMacro.of(someRule.getBuildTarget()))));
    CxxTest cxxTest = builder.build(graphBuilder);
    TestRunningOptions options =
        TestRunningOptions.builder().setTestSelectorList(TestSelectorList.empty()).build();
    ImmutableList<Step> steps =
        cxxTest.runTests(
            TestExecutionContext.newInstance(),
            options,
            FakeBuildContext.withSourcePathResolver(pathResolver),
            TestRule.NOOP_REPORTING_CALLBACK);
    CxxTestStep testStep = getTestStep(steps);
    assertThat(
        testStep.getEnv(),
        Matchers.equalTo(
            Optional.of(
                ImmutableMap.of(
                    "TEST",
                    "value "
                        + pathResolver.getAbsolutePath(
                            Objects.requireNonNull(someRule.getSourcePathToOutput()))))));
  }

  @Test
  public void testArgsArePropagated() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolverAdapter pathResolver = graphBuilder.getSourcePathResolver();
    addFramework(graphBuilder, filesystem);
    BuildRule someRule =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:some_rule"))
            .setOut("someRule")
            .build(graphBuilder);
    CxxTestBuilder builder =
        createTestBuilder()
            .setArgs(
                ImmutableList.of(
                    StringWithMacrosUtils.format(
                        "value %s", LocationMacro.of(someRule.getBuildTarget()))));
    CxxTest cxxTest = builder.build(graphBuilder);
    TestRunningOptions testOptions =
        TestRunningOptions.builder()
            .setShufflingTests(false)
            .setTestSelectorList(TestSelectorList.empty())
            .build();
    ImmutableList<Step> steps =
        cxxTest.runTests(
            TestExecutionContext.newInstance(),
            testOptions,
            FakeBuildContext.withSourcePathResolver(pathResolver),
            TestRule.NOOP_REPORTING_CALLBACK);
    CxxTestStep testStep = getTestStep(steps);
    assertThat(
        testStep.getCommand(),
        hasItem(
            "value "
                + pathResolver.getAbsolutePath(
                    Objects.requireNonNull(someRule.getSourcePathToOutput()))));
  }

  @Test
  public void runTestSeparately() {
    for (CxxTestType framework : CxxTestType.values()) {
      ProjectFilesystem filesystem = new FakeProjectFilesystem();
      ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
      addFramework(graphBuilder, filesystem);
      CxxTestBuilder builder =
          createTestBuilder()
              .setRunTestSeparately(true)
              .setUseDefaultTestMain(true)
              .setFramework(framework);
      CxxTest cxxTest = builder.build(graphBuilder);
      assertTrue(cxxTest.runTestSeparately());
    }
  }

  @Test
  public void runtimeDepOnDeps() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildTarget cxxBinaryTarget = BuildTargetFactory.newInstance("//:dep");
    BuildTarget cxxLibraryTarget = BuildTargetFactory.newInstance("//:lib");
    CxxBinaryBuilder cxxBinaryBuilder = new CxxBinaryBuilder(cxxBinaryTarget);
    CxxLibraryBuilder cxxLibraryBuilder =
        new CxxLibraryBuilder(cxxLibraryTarget).setDeps(ImmutableSortedSet.of(cxxBinaryTarget));
    ActionGraphBuilder graphBuilder =
        new TestActionGraphBuilder(
            TargetGraphFactory.newInstance(cxxLibraryBuilder.build(), cxxBinaryBuilder.build()));
    addFramework(graphBuilder, filesystem);
    BuildRule cxxBinary = cxxBinaryBuilder.build(graphBuilder, filesystem);
    cxxLibraryBuilder.build(graphBuilder, filesystem);
    CxxTestBuilder cxxTestBuilder =
        createTestBuilder().setDeps(ImmutableSortedSet.of(cxxLibraryTarget));
    CxxTest cxxTest = cxxTestBuilder.build(graphBuilder, filesystem);
    assertThat(
        BuildRules.getTransitiveRuntimeDeps(cxxTest, graphBuilder),
        hasItem(cxxBinary.getBuildTarget()));
  }

  @Test
  public void locationMacro() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    Genrule dep =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setOut("out")
            .build(graphBuilder);
    CxxTestBuilder builder =
        createTestBuilder()
            .setLinkerFlags(
                ImmutableList.of(
                    StringWithMacrosUtils.format(
                        "--linker-script=%s", LocationMacro.of(dep.getBuildTarget()))));
    addFramework(graphBuilder, filesystem);
    assertThat(builder.build().getExtraDeps(), hasItem(dep.getBuildTarget()));
    CxxTest test = builder.build(graphBuilder);
    CxxLink binary =
        (CxxLink)
            graphBuilder.getRule(
                CxxDescriptionEnhancer.createCxxLinkTarget(
                    test.getBuildTarget(), Optional.empty()));
    SourcePath outputSourcePath = dep.getSourcePathToOutput();
    AbsPath absoluteLinkerScriptPath =
        graphBuilder.getSourcePathResolver().getAbsolutePath(outputSourcePath);
    assertThat(
        Arg.stringify(binary.getArgs(), graphBuilder.getSourcePathResolver()),
        hasItem(String.format("--linker-script=%s", absoluteLinkerScriptPath)));
    assertThat(binary.getBuildDeps(), hasItem(dep));
  }

  @Test
  public void linkerFlagsLocationMacro() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    Genrule dep =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setOut("out")
            .build(graphBuilder);
    CxxTestBuilder builder =
        createTestBuilder("//:rule")
            .setLinkerFlags(
                ImmutableList.of(
                    StringWithMacrosUtils.format(
                        "--linker-script=%s", LocationMacro.of(dep.getBuildTarget()))));
    assertThat(builder.build().getExtraDeps(), hasItem(dep.getBuildTarget()));
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    addFramework(graphBuilder, filesystem);
    CxxTest test = builder.build(graphBuilder);
    CxxLink binary =
        (CxxLink)
            graphBuilder.getRule(
                CxxDescriptionEnhancer.createCxxLinkTarget(
                    test.getBuildTarget(), Optional.empty()));
    assertThat(binary, Matchers.instanceOf(CxxLink.class));
    SourcePath outputSourcePath = dep.getSourcePathToOutput();
    AbsPath absoluteLinkerScriptPath =
        graphBuilder.getSourcePathResolver().getAbsolutePath(outputSourcePath);
    assertThat(
        Arg.stringify(binary.getArgs(), graphBuilder.getSourcePathResolver()),
        hasItem(String.format("--linker-script=%s", absoluteLinkerScriptPath)));
    assertThat(binary.getBuildDeps(), hasItem(dep));
  }

  @Test
  public void platformLinkerFlagsLocationMacroWithMatch() {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    Genrule dep =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setOut("out")
            .build(graphBuilder);
    CxxTestBuilder builder =
        createTestBuilder()
            .setPlatformLinkerFlags(
                new PatternMatchedCollection.Builder<ImmutableList<StringWithMacros>>()
                    .add(
                        Pattern.compile(
                            Pattern.quote(CxxPlatformUtils.DEFAULT_PLATFORM_FLAVOR.toString())),
                        ImmutableList.of(
                            StringWithMacrosUtils.format(
                                "--linker-script=%s", LocationMacro.of(dep.getBuildTarget()))))
                    .build());
    addFramework(graphBuilder, filesystem);
    assertThat(builder.build().getExtraDeps(), hasItem(dep.getBuildTarget()));
    CxxTest test = builder.build(graphBuilder);
    CxxLink binary =
        (CxxLink)
            graphBuilder.getRule(
                CxxDescriptionEnhancer.createCxxLinkTarget(
                    test.getBuildTarget(), Optional.empty()));
    SourcePath outputSourcePath = dep.getSourcePathToOutput();
    AbsPath absoluteLinkerScriptPath =
        graphBuilder.getSourcePathResolver().getAbsolutePath(outputSourcePath);
    assertThat(
        Arg.stringify(binary.getArgs(), graphBuilder.getSourcePathResolver()),
        hasItem(String.format("--linker-script=%s", absoluteLinkerScriptPath)));
    assertThat(binary.getBuildDeps(), hasItem(dep));
  }

  @Test
  public void platformLinkerFlagsLocationMacroWithoutMatch() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    Genrule dep =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setOut("out")
            .build(graphBuilder);
    addFramework(graphBuilder, filesystem);
    CxxTestBuilder builder =
        createTestBuilder()
            .setPlatformLinkerFlags(
                new PatternMatchedCollection.Builder<ImmutableList<StringWithMacros>>()
                    .add(
                        Pattern.compile("nothing matches this string"),
                        ImmutableList.of(
                            StringWithMacrosUtils.format(
                                "--linker-script=%s", LocationMacro.of(dep.getBuildTarget()))))
                    .build());
    assertThat(builder.build().getExtraDeps(), hasItem(dep.getBuildTarget()));
    CxxTest test = builder.build(graphBuilder);
    CxxLink binary =
        (CxxLink)
            graphBuilder.getRule(
                CxxDescriptionEnhancer.createCxxLinkTarget(
                    test.getBuildTarget(), Optional.empty()));
    SourcePath outputSourcePath = dep.getSourcePathToOutput();
    AbsPath absoluteLinkerScriptPath =
        graphBuilder.getSourcePathResolver().getAbsolutePath(outputSourcePath);
    assertThat(
        Arg.stringify(binary.getArgs(), graphBuilder.getSourcePathResolver()),
        Matchers.not(hasItem(String.format("--linker-script=%s", absoluteLinkerScriptPath))));
    assertThat(binary.getBuildDeps(), Matchers.not(hasItem(dep)));
  }

  @Test
  public void resourcesAffectRuleKey() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    Path resource = filesystem.getPath("resource");
    filesystem.touch(resource);
    for (CxxTestType framework : CxxTestType.values()) {
      // Create a test rule without resources attached.
      ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
      addFramework(graphBuilder, filesystem);
      CxxTestBuilder builder = createTestBuilder().setFramework(framework);
      CxxTest cxxTestWithoutResources = builder.build(graphBuilder, filesystem);
      RuleKey ruleKeyWithoutResource = getRuleKey(graphBuilder, cxxTestWithoutResources);

      // Create a rule with a resource attached.
      graphBuilder = new TestActionGraphBuilder();
      addFramework(graphBuilder, filesystem);
      builder =
          createTestBuilder()
              .setFramework(framework)
              .setResources(
                  SourceSortedSet.ofUnnamedSources(
                      ImmutableSortedSet.of(FakeSourcePath.of(resource))));
      CxxTest cxxTestWithResources = builder.build(graphBuilder, filesystem);
      RuleKey ruleKeyWithResource = getRuleKey(graphBuilder, cxxTestWithResources);

      // Verify that their rule keys are different.
      assertThat(ruleKeyWithoutResource, Matchers.not(Matchers.equalTo(ruleKeyWithResource)));
    }
  }

  @Test
  public void resourcesAreInputs() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    Path resource = filesystem.getPath("resource");
    filesystem.touch(resource);
    for (CxxTestType framework : CxxTestType.values()) {
      TargetNode<?> cxxTestWithResources =
          createTestBuilder()
              .setFramework(framework)
              .setResources(
                  SourceSortedSet.ofUnnamedSources(
                      ImmutableSortedSet.of(FakeSourcePath.of(resource))))
              .build();
      assertThat(cxxTestWithResources.getInputs(), hasItem(ForwardRelPath.ofPath(resource)));
    }
  }

  @Test
  public void externalTestSpecBinaryInRequiredPaths() {
    for (CxxTestType framework : CxxTestType.values()) {
      ProjectFilesystem filesystem = new FakeProjectFilesystem();
      TargetNode<?> test =
          createTestBuilder()
              .setLinkStyle(Linker.LinkableDepType.SHARED)
              .setUseDefaultTestMain(false)
              .setFramework(framework)
              .build();
      ActionGraphBuilder graphBuilder =
          new TestActionGraphBuilder(TargetGraphFactory.newInstance(test), filesystem);
      CxxTest cxxTest = (CxxTest) graphBuilder.requireRule(test.getBuildTarget());
      ExternalTestRunnerTestSpec spec =
          cxxTest.getExternalTestRunnerSpec(
              TestExecutionContext.newInstance(),
              TestRunningOptions.builder().build(),
              FakeBuildContext.withSourcePathResolver(graphBuilder.getSourcePathResolver()));
      assertThat(
          spec.getRequiredPaths(),
          hasItem(
              graphBuilder
                  .getSourcePathResolver()
                  .getAbsolutePath(cxxTest.getBinary().getSourcePathToOutput())
                  .getPath()));
    }
  }

  @Test
  public void externalTestSpecSharedLibTreeInRequiredPaths() {
    for (CxxTestType framework : CxxTestType.values()) {
      ProjectFilesystem filesystem = new FakeProjectFilesystem();
      TargetNode<?> library =
          new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:lib"))
              .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of("foo.cpp"))))
              .build();
      TargetNode<?> test =
          createTestBuilder()
              .setLinkStyle(Linker.LinkableDepType.SHARED)
              .setUseDefaultTestMain(false)
              .setDeps(ImmutableSortedSet.of(library.getBuildTarget()))
              .setFramework(framework)
              .build();
      ActionGraphBuilder graphBuilder =
          new TestActionGraphBuilder(TargetGraphFactory.newInstance(library, test), filesystem);
      CxxTest cxxTest = (CxxTest) graphBuilder.requireRule(test.getBuildTarget());
      ExternalTestRunnerTestSpec spec =
          cxxTest.getExternalTestRunnerSpec(
              TestExecutionContext.newInstance(),
              TestRunningOptions.builder().build(),
              FakeBuildContext.withSourcePathResolver(graphBuilder.getSourcePathResolver()));
      assertThat(
          spec.getRequiredPaths(),
          hasItem(
              filesystem.resolve(
                  CxxDescriptionEnhancer.getSharedLibrarySymlinkTreePath(
                          filesystem,
                          cxxTest.getBinary().getBuildTarget(),
                          CxxPlatformUtils.DEFAULT_PLATFORM.getFlavor())
                      .getPath())));
    }
  }

  @Test
  public void externalTestSpecArgLocationMacroInRequiredPaths() {
    for (CxxTestType framework : CxxTestType.values()) {
      ProjectFilesystem filesystem = new FakeProjectFilesystem();
      TargetNode<?> genrule =
          GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:gen"))
              .setOut("foo.txt")
              .build();
      TargetNode<?> test =
          createTestBuilder()
              .setLinkStyle(Linker.LinkableDepType.SHARED)
              .setUseDefaultTestMain(false)
              .setArgs(
                  ImmutableList.of(
                      StringWithMacrosUtils.format(
                          "--foo=%s", LocationMacro.of(genrule.getBuildTarget()))))
              .setFramework(framework)
              .build();
      ActionGraphBuilder graphBuilder =
          new TestActionGraphBuilder(TargetGraphFactory.newInstance(genrule, test), filesystem);
      CxxTest cxxTest = (CxxTest) graphBuilder.requireRule(test.getBuildTarget());
      ExternalTestRunnerTestSpec spec =
          cxxTest.getExternalTestRunnerSpec(
              TestExecutionContext.newInstance(),
              TestRunningOptions.builder().build(),
              FakeBuildContext.withSourcePathResolver(graphBuilder.getSourcePathResolver()));
      assertThat(
          spec.getRequiredPaths(),
          hasItem(
              graphBuilder
                  .getSourcePathResolver()
                  .getAbsolutePath(
                      graphBuilder.requireRule(genrule.getBuildTarget()).getSourcePathToOutput())
                  .getPath()));
    }
  }

  @Test
  public void externalTestSpecEnvLocationMacroInRequiredPaths() {
    for (CxxTestType framework : CxxTestType.values()) {
      ProjectFilesystem filesystem = new FakeProjectFilesystem();
      TargetNode<?> genrule =
          GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:gen"))
              .setOut("foo.txt")
              .build();
      TargetNode<?> test =
          createTestBuilder()
              .setLinkStyle(Linker.LinkableDepType.SHARED)
              .setUseDefaultTestMain(false)
              .setEnv(
                  ImmutableMap.of(
                      "FOO",
                      StringWithMacrosUtils.format(
                          "%s", LocationMacro.of(genrule.getBuildTarget()))))
              .setFramework(framework)
              .build();
      ActionGraphBuilder graphBuilder =
          new TestActionGraphBuilder(TargetGraphFactory.newInstance(genrule, test), filesystem);
      CxxTest cxxTest = (CxxTest) graphBuilder.requireRule(test.getBuildTarget());
      ExternalTestRunnerTestSpec spec =
          cxxTest.getExternalTestRunnerSpec(
              TestExecutionContext.newInstance(),
              TestRunningOptions.builder().build(),
              FakeBuildContext.withSourcePathResolver(graphBuilder.getSourcePathResolver()));
      assertThat(
          spec.getRequiredPaths(),
          hasItem(
              graphBuilder
                  .getSourcePathResolver()
                  .getAbsolutePath(
                      graphBuilder.requireRule(genrule.getBuildTarget()).getSourcePathToOutput())
                  .getPath()));
    }
  }

  @Test
  public void externalTestSpecResourcesInRequiredPaths() {
    for (CxxTestType framework : CxxTestType.values()) {
      ProjectFilesystem filesystem = new FakeProjectFilesystem();
      TargetNode<?> test =
          createTestBuilder()
              .setLinkStyle(Linker.LinkableDepType.SHARED)
              .setUseDefaultTestMain(false)
              .setResources(
                  SourceSortedSet.ofUnnamedSources(
                      ImmutableSortedSet.of(
                          FakeSourcePath.of(filesystem.getPath("foo", "resource.dat")))))
              .setFramework(framework)
              .build();
      ActionGraphBuilder graphBuilder =
          new TestActionGraphBuilder(TargetGraphFactory.newInstance(test), filesystem);
      CxxTest cxxTest = (CxxTest) graphBuilder.requireRule(test.getBuildTarget());
      ExternalTestRunnerTestSpec spec =
          cxxTest.getExternalTestRunnerSpec(
              TestExecutionContext.newInstance(),
              TestRunningOptions.builder().build(),
              FakeBuildContext.withSourcePathResolver(graphBuilder.getSourcePathResolver()));
      assertThat(
          spec.getRequiredPaths(),
          hasItem(filesystem.resolve(filesystem.getPath("foo", "resource.dat"))));
    }
  }

  private RuleKey getRuleKey(BuildRuleResolver resolver, BuildRule rule) {
    FileHashLoader fileHashLoader =
        new StackedFileHashCache(
            ImmutableList.of(
                DefaultFileHashCache.createDefaultFileHashCache(
                    rule.getProjectFilesystem(), FileHashCacheMode.DEFAULT, false)));
    DefaultRuleKeyFactory factory = new TestDefaultRuleKeyFactory(fileHashLoader, resolver);
    return factory.build(rule);
  }

  private CxxTestStep getTestStep(Iterable<? extends Step> steps) {
    for (Step s : steps) {
      if (s instanceof CxxTestStep) {
        return (CxxTestStep) s;
      }
    }
    throw new IllegalStateException("Can't find CxxTestStep");
  }
}
