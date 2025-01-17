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

package com.facebook.buck.features.js;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.in;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.rules.keys.TestDefaultRuleKeyFactory;
import com.facebook.buck.rules.macros.LocationMacro;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.stream.RichStream;
import com.facebook.buck.util.types.Pair;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.HashCode;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;

@SuppressWarnings("PMD.TestClassWithoutTestCases")
@RunWith(Enclosed.class)
public class JsBundleDescriptionTest {
  private static final BuildTarget bundleTarget = BuildTargetFactory.newInstance("//bundle:target");

  public static class TestsWithSetup {

    private static final BuildTarget directDependencyTarget =
        BuildTargetFactory.newInstance("//libs:direct")
            .withAppendedFlavors(JsFlavors.TRANSFORM_PROFILE_DEFAULT);
    private static final BuildTarget level2 =
        BuildTargetFactory.newInstance("//libs:level2")
            .withAppendedFlavors(JsFlavors.TRANSFORM_PROFILE_DEFAULT);
    private static final BuildTarget level1_1 =
        BuildTargetFactory.newInstance("//libs:level1.1")
            .withAppendedFlavors(JsFlavors.TRANSFORM_PROFILE_DEFAULT);
    private static final BuildTarget level1_2 =
        BuildTargetFactory.newInstance("//libs:level1.2")
            .withAppendedFlavors(JsFlavors.TRANSFORM_PROFILE_DEFAULT);

    private JsTestScenario.Builder scenarioBuilder;
    private JsTestScenario scenario;

    static Collection<BuildTarget> allLibraryTargets(Flavor... flavors) {
      return Stream.of(level2, level1_1, level1_2, directDependencyTarget)
          .map(t -> t.withAppendedFlavors(flavors))
          .collect(Collectors.toList());
    }

    @Before
    public void setUp() {
      scenarioBuilder = JsTestScenario.builder();
      scenarioBuilder
          .library(level2)
          .library(level1_1, level2)
          .library(level1_2, level2)
          .library(directDependencyTarget, level1_1, level1_2)
          .bundleWithDeps(bundleTarget, directDependencyTarget);
      scenario = scenarioBuilder.build();
    }

    @Test
    public void testTransitiveLibraryDependencies() {
      BuildRule jsBundle = scenario.graphBuilder.requireRule(bundleTarget);
      assertThat(allLibraryTargets(), everyItem(in(dependencyTargets(jsBundle))));
    }

    @Test
    public void testTransitiveLibraryDependenciesWithFlavors() {
      Flavor[] flavors = {JsFlavors.IOS, JsFlavors.RELEASE};
      BuildRule jsBundle = scenario.graphBuilder.requireRule(bundleTarget.withFlavors(flavors));
      assertThat(allLibraryTargets(flavors), everyItem(in(dependencyTargets(jsBundle))));
    }

    @Test
    public void testFlavoredBundleDoesNotDependOnUnflavoredLibs() {
      BuildRule jsBundle =
          scenario.graphBuilder.requireRule(
              bundleTarget.withFlavors(JsFlavors.IOS, JsFlavors.RELEASE));
      assertThat(allLibraryTargets(), everyItem(not(in(dependencyTargets(jsBundle)))));
    }

    @Test
    public void testFlavoredBundleWithoutReleaseFlavorDependOnFlavoredLibs() {
      Flavor[] flavors = {JsFlavors.IOS, JsFlavors.RAM_BUNDLE_INDEXED};
      BuildRule jsBundle = scenario.graphBuilder.requireRule((bundleTarget.withFlavors(flavors)));
      assertThat(allLibraryTargets(JsFlavors.IOS), everyItem(in(dependencyTargets(jsBundle))));
      assertThat(allLibraryTargets(flavors), everyItem(not(in(dependencyTargets(jsBundle)))));
    }

    @Test
    public void testFlavoredReleaseBundleDoesNotPropagateRamBundleFlavors() {
      Flavor[] bundleFlavors = {JsFlavors.IOS, JsFlavors.RAM_BUNDLE_INDEXED, JsFlavors.RELEASE};
      Flavor[] flavorsToBePropagated = {JsFlavors.IOS, JsFlavors.RELEASE};
      BuildRule jsBundle =
          scenario.graphBuilder.requireRule((bundleTarget.withFlavors(bundleFlavors)));
      assertThat(
          allLibraryTargets(flavorsToBePropagated), everyItem(in(dependencyTargets(jsBundle))));
      assertThat(allLibraryTargets(bundleFlavors), everyItem(not(in(dependencyTargets(jsBundle)))));
    }

    @Test
    public void testFlavoredReleaseBundleDoesNotPropagateRamBundleFlavorsAndroid() {
      Flavor[] bundleFlavors = {JsFlavors.ANDROID, JsFlavors.RAM_BUNDLE_INDEXED, JsFlavors.RELEASE};
      Flavor[] flavorsToBePropagated = {JsFlavors.ANDROID, JsFlavors.RELEASE};
      BuildRule jsBundle =
          scenario.graphBuilder.requireRule((bundleTarget.withFlavors(bundleFlavors)));
      assertThat(
          allLibraryTargets(flavorsToBePropagated), everyItem(in(dependencyTargets(jsBundle))));
      assertThat(allLibraryTargets(bundleFlavors), everyItem(not(in(dependencyTargets(jsBundle)))));
    }

    @Test
    public void testTransitiveLibraryDependenciesWithFlavorsForAndroid() {
      Flavor[] flavors = {JsFlavors.ANDROID, JsFlavors.RELEASE};
      BuildRule jsBundle = scenario.graphBuilder.requireRule(bundleTarget.withFlavors(flavors));
      assertThat(allLibraryTargets(flavors), everyItem(in(dependencyTargets(jsBundle))));
    }

    @Test
    public void testSourceMapExport() {
      BuildRule map =
          scenario.graphBuilder.requireRule(
              bundleTarget.withFlavors(JsFlavors.IOS, JsFlavors.SOURCE_MAP));
      JsBundleOutputs bundle =
          scenario.graphBuilder.getRuleWithType(
              bundleTarget.withFlavors(JsFlavors.IOS), JsBundleOutputs.class);

      SourcePathResolverAdapter pathResolver = scenario.graphBuilder.getSourcePathResolver();
      assertEquals(
          pathResolver.getCellUnsafeRelPath(map.getSourcePathToOutput()),
          pathResolver.getCellUnsafeRelPath(bundle.getSourcePathToSourceMap()));
    }

    @Test
    public void testJsLibraryInDeps() {
      BuildTarget bundleTarget = BuildTargetFactory.newInstance("//the:bundle");
      JsTestScenario testScenario =
          JsTestScenario.builder(scenario)
              .bundleWithDeps(bundleTarget, directDependencyTarget)
              .build();

      BuildRule jsBundle = testScenario.graphBuilder.requireRule(bundleTarget);
      assertThat(allLibraryTargets(), everyItem(in(dependencyTargets(jsBundle))));
    }

    @Test
    public void testTransitiveDependenciesAcrossSubGraph() {
      Assume.assumeThat(Platform.detect(), not(Platform.WINDOWS));
      BuildTarget firstLevelA =
          BuildTargetFactory.newInstance("//:firstA")
              .withAppendedFlavors(JsFlavors.TRANSFORM_PROFILE_DEFAULT);
      BuildTarget firstLevelB =
          BuildTargetFactory.newInstance("//:firstB")
              .withAppendedFlavors(JsFlavors.TRANSFORM_PROFILE_DEFAULT);
      BuildTarget secondLevelA =
          BuildTargetFactory.newInstance("//:secondA")
              .withAppendedFlavors(JsFlavors.TRANSFORM_PROFILE_DEFAULT);
      BuildTarget secondLevelB =
          BuildTargetFactory.newInstance("//:secondB")
              .withAppendedFlavors(JsFlavors.TRANSFORM_PROFILE_DEFAULT);
      BuildTarget bundleTarget = BuildTargetFactory.newInstance("//the:bundle");

      JsTestScenario.Builder builder = JsTestScenario.builder(scenario);
      JsTestScenario testScenario =
          builder
              .appleLibraryWithDeps(firstLevelA, level1_1)
              .library(secondLevelA)
              .appleLibraryWithDeps(secondLevelB, level1_2, secondLevelA)
              .appleLibraryWithDeps(firstLevelB, secondLevelB)
              .bundleWithDeps(bundleTarget, firstLevelA, firstLevelB)
              .build();

      Flavor[] flavors = {JsFlavors.IOS, JsFlavors.RELEASE};
      BuildRule jsBundle = testScenario.graphBuilder.requireRule(bundleTarget.withFlavors(flavors));
      List<BuildTarget> expectedLibDeps =
          Stream.of(level1_1, level1_2, level2, secondLevelA)
              .map(t -> t.withAppendedFlavors(flavors))
              .collect(Collectors.toList());

      assertThat(expectedLibDeps, everyItem(in(dependencyTargets(jsBundle))));
    }
  }

  public static class TestsWithoutSetup {

    @Test
    public void rulesReferencedFromLocationMacrosInfluenceRuleKey() {
      BuildTarget referencedTarget = BuildTargetFactory.newInstance("//:ref");
      PathSourcePath referencedSource = FakeSourcePath.of("referenced/file");

      JsTestScenario scenario =
          JsTestScenario.builder().exportFile(referencedTarget, referencedSource).build();
      JsBundle bundle =
          scenario.createBundle(
              "//:bundle",
              builder -> builder.setExtraJson("[\"1 %s 2\"]", LocationMacro.of(referencedTarget)));

      Function<HashCode, RuleKey> calc =
          (refHash) ->
              new TestDefaultRuleKeyFactory(
                      new FakeFileHashCache(
                          ImmutableMap.of(
                              scenario
                                  .graphBuilder
                                  .getSourcePathResolver()
                                  .getAbsolutePath(referencedSource)
                                  .getPath(),
                              refHash)),
                      scenario.graphBuilder)
                  .build(bundle);

      assertThat(
          calc.apply(HashCode.fromInt(12345)), not(equalTo(calc.apply(HashCode.fromInt(67890)))));
    }

    @Test
    public void locationMacrosInExtraJsonAddBuildDeps() {
      BuildTarget referencedTarget = BuildTargetFactory.newInstance("//:ref");
      JsTestScenario scenario = JsTestScenario.builder().arbitraryRule(referencedTarget).build();
      JsBundle bundle =
          scenario.createBundle(
              "//:bundle",
              builder -> builder.setExtraJson("[\"%s\"]", LocationMacro.of(referencedTarget)));
      assertThat(scenario.graphBuilder.getRule(referencedTarget), in(bundle.getBuildDeps()));
    }

    @Test
    public void fallbackTransformProfilePropagatesAsLibraryFlavor() {
      BuildTarget libraryTarget = BuildTargetFactory.newInstance("//libs:a");
      JsTestScenario scenario = JsTestScenario.builder().library(libraryTarget).build();
      JsBundle bundle =
          scenario.createBundle(
              "//:bundle",
              builder ->
                  builder
                      .setDeps(ImmutableSortedSet.of(libraryTarget))
                      .setFallbackTransformProfile("hermes-stable"));
      assertThat(
          libraryTarget.withAppendedFlavors(JsFlavors.HERMES_STABLE),
          in(dependencyTargets(bundle)));
    }

    @Test
    public void fallbackTransformProfileDoesNotOverrideExplicitBundleFlavor() {
      BuildTarget bundleTarget =
          BuildTargetFactory.newInstance("//:bundle").withAppendedFlavors(JsFlavors.HERMES_CANARY);
      BuildTarget libraryTarget = BuildTargetFactory.newInstance("//libs:a");
      JsTestScenario scenario = JsTestScenario.builder().library(libraryTarget).build();
      JsBundle bundle =
          scenario.createBundle(
              bundleTarget,
              builder ->
                  builder
                      .setDeps(ImmutableSortedSet.of(libraryTarget))
                      .setFallbackTransformProfile("hermes-stable"));
      assertThat(
          libraryTarget.withAppendedFlavors(JsFlavors.HERMES_CANARY),
          in(dependencyTargets(bundle)));
      assertThat(
          libraryTarget.withAppendedFlavors(JsFlavors.HERMES_STABLE),
          not(in(dependencyTargets(bundle))));
    }

    @Test
    public void bundleRulesDependOnGeneratedSources() {
      BuildTarget a = BuildTargetFactory.newInstance("//:node_modules");
      BuildTarget b = BuildTargetFactory.newInstance("//generated:dep");
      BuildTarget libA = BuildTargetFactory.newInstance("//:node-modules-library");
      BuildTarget libB = BuildTargetFactory.newInstance("//generated:lib");

      JsTestScenario scenario =
          JsTestScenario.builder()
              .arbitraryRule(a)
              .arbitraryRule(b)
              .library(libA, null, new Pair<>(DefaultBuildTargetSourcePath.of(a), "left-pad.js"))
              .library(
                  libB,
                  ImmutableList.of(libA),
                  ImmutableList.of(DefaultBuildTargetSourcePath.of(b)))
              .bundle(bundleTarget, ImmutableSortedSet.of(libB))
              .build();

      BuildRule bundle = scenario.graphBuilder.getRule(bundleTarget);

      ImmutableSortedSet<BuildRule> generatedSourcesRules =
          scenario.graphBuilder.getAllRules(ImmutableList.of(a, b));

      assertThat(generatedSourcesRules, everyItem(in(bundle.getBuildDeps())));
    }
  }

  private static Collection<BuildTarget> dependencyTargets(BuildRule rule) {
    if (rule instanceof JsBundleAndroid) {
      JsBundle jsBundle =
          RichStream.from(rule.getBuildDeps()).filter(JsBundle.class).findFirst().get();
      return dependencyTargets(jsBundle);
    } else {
      return rule.getBuildDeps().stream()
          .map(BuildRule::getBuildTarget)
          .collect(Collectors.toSet());
    }
  }
}
