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

package com.facebook.buck.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.jvm.java.FakeJavaLibrary;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.hash.HashCode;
import org.junit.Test;

public class DexWithClassesTest {

  @Test
  public void testIntermediateDexRuleToDexWithClasses() {
    BuildTarget javaLibraryTarget = BuildTargetFactory.newInstance("//java/com/example:lib");
    JavaLibrary javaLibrary = new FakeJavaLibrary(javaLibraryTarget);

    BuildTarget buildTarget = BuildTargetFactory.newInstance("//java/com/example:lib#d8");
    DexProducedFromJavaLibrary dexFromJavaLibrary =
        new DexProducedFromJavaLibrary(
            buildTarget,
            new FakeProjectFilesystem(),
            new TestActionGraphBuilder(),
            TestAndroidPlatformTargetFactory.create(),
            javaLibrary,
            false);
    dexFromJavaLibrary
        .getBuildOutputInitializer()
        .setBuildOutputForTests(
            new DexProducedFromJavaLibrary.BuildOutput(
                /* weightEstimate */ 1600,
                /* classNamesToHashes */ ImmutableSortedMap.of(
                    "com/example/Main", HashCode.fromString(Strings.repeat("cafebabe", 5))),
                ImmutableList.of()));

    SourcePathRuleFinder ruleFinder = new TestActionGraphBuilder();
    DexWithClasses dexWithClasses = DexWithClasses.TO_DEX_WITH_CLASSES.apply(dexFromJavaLibrary);
    assertEquals(
        BuildTargetPaths.getGenPath(
            javaLibrary.getProjectFilesystem().getBuckPaths(), buildTarget, "%s/dex.jar"),
        ruleFinder
            .getSourcePathResolver()
            .getCellUnsafeRelPath(dexWithClasses.getSourcePathToDexFile()));
    assertEquals(ImmutableSet.of("com/example/Main"), dexWithClasses.getClassNames());
    assertEquals(1600, dexWithClasses.getWeightEstimate());
  }

  @Test
  public void testIntermediateDexRuleToDexWithClassesWhenIntermediateDexHasNoClasses() {
    BuildTarget javaLibraryTarget = BuildTargetFactory.newInstance("//java/com/example:lib");
    JavaLibrary javaLibrary = new FakeJavaLibrary(javaLibraryTarget);

    BuildTarget buildTarget = BuildTargetFactory.newInstance("//java/com/example:lib#d8");
    DexProducedFromJavaLibrary dexFromJavaLibrary =
        new DexProducedFromJavaLibrary(
            buildTarget,
            new FakeProjectFilesystem(),
            new TestActionGraphBuilder(),
            TestAndroidPlatformTargetFactory.create(),
            javaLibrary,
            false);
    dexFromJavaLibrary
        .getBuildOutputInitializer()
        .setBuildOutputForTests(
            new DexProducedFromJavaLibrary.BuildOutput(
                /* weightEstimate */ 1600,
                /* classNamesToHashes */ ImmutableSortedMap.of(),
                ImmutableList.of()));

    DexWithClasses dexWithClasses = DexWithClasses.TO_DEX_WITH_CLASSES.apply(dexFromJavaLibrary);
    assertNull(
        "If the JavaLibraryRule does not produce any .class files, "
            + "then DexWithClasses.TO_DEX_WITH_CLASSES should return null.",
        dexWithClasses);
  }
}
