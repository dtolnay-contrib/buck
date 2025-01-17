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

package com.facebook.buck.parser.targetnode;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.facebook.buck.core.cell.Cells;
import com.facebook.buck.core.cell.DefaultCellNameResolverProvider;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.filesystems.ForwardRelPath;
import com.facebook.buck.core.graph.transformation.impl.FakeComputationEnvironment;
import com.facebook.buck.core.model.BaseName;
import com.facebook.buck.core.model.ConstantHostTargetConfigurationResolver;
import com.facebook.buck.core.model.FlavorSet;
import com.facebook.buck.core.model.RuleType;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.model.UnconfiguredTargetConfiguration;
import com.facebook.buck.core.model.impl.MultiPlatformTargetConfigurationTransformer;
import com.facebook.buck.core.model.platform.TargetPlatformResolver;
import com.facebook.buck.core.model.platform.impl.UnconfiguredPlatform;
import com.facebook.buck.core.model.targetgraph.impl.ImmutableUnconfiguredTargetNode;
import com.facebook.buck.core.model.targetgraph.impl.TargetNodeFactory;
import com.facebook.buck.core.model.targetgraph.raw.UnconfiguredTargetNode;
import com.facebook.buck.core.model.targetgraph.raw.UnconfiguredTargetNodeWithDeps;
import com.facebook.buck.core.model.targetgraph.raw.UnconfiguredTargetNodeWithDepsPackage;
import com.facebook.buck.core.parser.buildtargetpattern.UnconfiguredBuildTargetParser;
import com.facebook.buck.core.plugin.impl.BuckPluginManagerFactory;
import com.facebook.buck.core.rules.knowntypes.TestKnownRuleTypesProvider;
import com.facebook.buck.core.select.TestSelectableResolver;
import com.facebook.buck.core.select.impl.DefaultSelectorListResolver;
import com.facebook.buck.parser.NoopCellBoundaryChecker;
import com.facebook.buck.parser.NoopPackageBoundaryChecker;
import com.facebook.buck.parser.UnconfiguredTargetNodeToTargetNodeFactory;
import com.facebook.buck.parser.api.BuildFileManifest;
import com.facebook.buck.parser.api.RawTargetNode;
import com.facebook.buck.parser.manifest.BuildPackagePathToBuildFileManifestKey;
import com.facebook.buck.rules.coercer.DefaultConstructorArgMarshaller;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.util.collect.TwoArraysImmutableHashMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Paths;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Test;

public class BuildPackagePathToUnconfiguredTargetNodePackageComputationTest {

  @Test
  public void canParseDeps() {
    Cells cells = new TestCellBuilder().build();

    TypeCoercerFactory typeCoercerFactory = new DefaultTypeCoercerFactory();
    TargetPlatformResolver targetPlatformResolver =
        (configuration, dependencyStack) -> UnconfiguredPlatform.INSTANCE;
    UnconfiguredTargetNodeToTargetNodeFactory unconfiguredTargetNodeToTargetNodeFactory =
        new UnconfiguredTargetNodeToTargetNodeFactory(
            cells,
            typeCoercerFactory,
            TestKnownRuleTypesProvider.create(BuckPluginManagerFactory.createPluginManager()),
            new DefaultConstructorArgMarshaller(),
            new TargetNodeFactory(typeCoercerFactory, new DefaultCellNameResolverProvider(cells)),
            new NoopPackageBoundaryChecker(),
            new NoopCellBoundaryChecker(),
            (file, targetNode) -> {},
            new DefaultSelectorListResolver(new TestSelectableResolver()),
            targetPlatformResolver,
            new MultiPlatformTargetConfigurationTransformer(targetPlatformResolver),
            new ConstantHostTargetConfigurationResolver(UnconfiguredTargetConfiguration.INSTANCE),
            cells.getRootCell().getBuckConfig(),
            Optional.empty());

    ImmutableMap<String, Object> rawAttributes1 =
        ImmutableMap.of(
            "name",
            "target1",
            "deps",
            ImmutableList.of(UnconfiguredBuildTargetParser.parse("//:target2")));
    UnconfiguredBuildTarget unconfiguredBuildTarget1 =
        UnconfiguredBuildTarget.of(
            cells.getRootCell().getCanonicalName(),
            BaseName.of("//"),
            "target1",
            FlavorSet.NO_FLAVORS);
    UnconfiguredTargetNode unconfiguredTargetNode1 =
        ImmutableUnconfiguredTargetNode.of(
            unconfiguredBuildTarget1.getUnflavoredBuildTarget(),
            RuleType.of("java_library", RuleType.Kind.BUILD),
            rawAttributes1,
            ImmutableSet.of(),
            ImmutableSet.of(),
            Optional.empty(),
            Optional.empty(),
            ImmutableList.of());

    ImmutableMap<String, Object> rawAttributes2 = ImmutableMap.of("name", "target2");
    UnconfiguredBuildTarget unconfiguredBuildTarget2 =
        UnconfiguredBuildTarget.of(
            cells.getRootCell().getCanonicalName(),
            BaseName.of("//"),
            "target2",
            FlavorSet.NO_FLAVORS);
    UnconfiguredTargetNode unconfiguredTargetNode2 =
        ImmutableUnconfiguredTargetNode.of(
            unconfiguredBuildTarget2.getUnflavoredBuildTarget(),
            RuleType.of("java_library", RuleType.Kind.BUILD),
            rawAttributes2,
            ImmutableSet.of(),
            ImmutableSet.of(),
            Optional.empty(),
            Optional.empty(),
            ImmutableList.of());

    BuildFileManifest buildFileManifest =
        BuildFileManifest.of(
            TwoArraysImmutableHashMap.of(
                "target1",
                RawTargetNode.copyOf(
                    ForwardRelPath.EMPTY,
                    "java_library",
                    ImmutableList.of(),
                    ImmutableList.of(),
                    TwoArraysImmutableHashMap.copyOf(rawAttributes1)),
                "target2",
                RawTargetNode.copyOf(
                    ForwardRelPath.EMPTY,
                    "java_library",
                    ImmutableList.of(),
                    ImmutableList.of(),
                    TwoArraysImmutableHashMap.copyOf(rawAttributes2))),
            ImmutableSortedSet.of(),
            ImmutableMap.of(),
            ImmutableList.of(),
            ImmutableList.of());

    BuildPackagePathToUnconfiguredTargetNodePackageComputation transformer =
        BuildPackagePathToUnconfiguredTargetNodePackageComputation.of(
            unconfiguredTargetNodeToTargetNodeFactory, cells, cells.getRootCell(), false);
    UnconfiguredTargetNodeWithDepsPackage unconfiguredTargetNodeWithDepsPackage =
        transformer.transform(
            ImmutableBuildPackagePathToUnconfiguredTargetNodePackageKey.of(Paths.get("")),
            new FakeComputationEnvironment(
                ImmutableMap.of(
                    BuildPackagePathToBuildFileManifestKey.of(Paths.get("")),
                    buildFileManifest,
                    ImmutableBuildTargetToUnconfiguredTargetNodeKey.ofImpl(
                        unconfiguredBuildTarget1, Paths.get("")),
                    unconfiguredTargetNode1,
                    ImmutableBuildTargetToUnconfiguredTargetNodeKey.ofImpl(
                        unconfiguredBuildTarget2, Paths.get("")),
                    unconfiguredTargetNode2)));

    ImmutableMap<String, UnconfiguredTargetNodeWithDeps> allTargets =
        unconfiguredTargetNodeWithDepsPackage.getUnconfiguredTargetNodesWithDeps();

    assertThat(allTargets.entrySet(), Matchers.hasSize(2));

    UnconfiguredTargetNodeWithDeps target1 = allTargets.get("target1");
    assertNotNull(target1);

    UnconfiguredTargetNodeWithDeps target2 = allTargets.get("target2");
    assertNotNull(target2);

    ImmutableSet<UnconfiguredBuildTarget> deps = target1.getDeps();
    assertEquals(1, deps.size());

    UnconfiguredBuildTarget dep = deps.iterator().next();
    assertEquals("//", dep.getBaseName().toString());
    assertEquals("target2", dep.getName());
  }
}
