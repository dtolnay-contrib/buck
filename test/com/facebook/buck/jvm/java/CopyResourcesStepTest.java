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

package com.facebook.buck.jvm.java;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.context.FakeBuildContext;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.jvm.core.JavaPackageFinder;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import com.facebook.buck.step.isolatedsteps.common.MkdirIsolatedStep;
import com.facebook.buck.step.isolatedsteps.common.SymlinkIsolatedStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.util.List;
import java.util.Optional;
import org.junit.Test;

public class CopyResourcesStepTest {

  @Test
  public void testAddResourceCommandsWithBuildFileParentOfSrcDirectory() {
    SourcePathRuleFinder ruleFinder = new TestActionGraphBuilder();
    // Files:
    // android/java/BUCK
    // android/java/src/com/facebook/base/data.json
    // android/java/src/com/facebook/common/util/data.json
    ProjectFilesystem filesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//android/java:resources");
    JavaPackageFinder javaPackageFinder = createJavaPackageFinder(filesystem);

    BuildContext buildContext =
        FakeBuildContext.withSourcePathResolver(ruleFinder.getSourcePathResolver())
            .withJavaPackageFinder(javaPackageFinder)
            .withBuildCellRootPath(filesystem.getRootPath());

    ImmutableList<IsolatedStep> steps =
        CopyResourcesStep.of(
            CopyResourcesStep.getResourcesMap(
                buildContext,
                filesystem,
                filesystem
                    .getBuckPaths()
                    .getScratchDir()
                    .resolve("android/java/lib__resources__classes"),
                ResourcesParameters.of(
                    ResourcesParameters.getNamedResources(
                        ruleFinder,
                        filesystem,
                        ImmutableSortedSet.of(
                            FakeSourcePath.of(
                                filesystem, "android/java/src/com/facebook/base/data.json"),
                            FakeSourcePath.of(
                                filesystem,
                                "android/java/src/com/facebook/common/util/data.json"))),
                    Optional.empty()),
                buildTarget));

    RelPath target =
        filesystem
            .getBuckPaths()
            .getScratchDir()
            .resolveRel("android/java/lib__resources__classes/com/facebook/common/util/data.json");
    RelPath target1 =
        filesystem
            .getBuckPaths()
            .getScratchDir()
            .resolveRel("android/java/lib__resources__classes/com/facebook/base/data.json");
    List<IsolatedStep> expected =
        ImmutableList.of(
            MkdirIsolatedStep.of(target1.getParent()),
            SymlinkIsolatedStep.of(
                filesystem.relativize(
                    filesystem.resolve("android/java/src/com/facebook/base/data.json")),
                target1),
            MkdirIsolatedStep.of(target.getParent()),
            SymlinkIsolatedStep.of(
                filesystem.relativize(
                    filesystem.resolve("android/java/src/com/facebook/common/util/data.json")),
                target));
    assertEquals(expected, steps);
  }

  @Test
  public void testAddResourceCommandsWithBuildFileParentOfJavaPackage() {
    // Files:
    // android/java/src/BUCK
    // android/java/src/com/facebook/base/data.json
    // android/java/src/com/facebook/common/util/data.json
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//android/java/src:resources");
    ProjectFilesystem filesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();
    JavaPackageFinder javaPackageFinder = createJavaPackageFinder(filesystem);

    SourcePathRuleFinder ruleFinder = new TestActionGraphBuilder();
    BuildContext buildContext =
        FakeBuildContext.withSourcePathResolver(ruleFinder.getSourcePathResolver())
            .withJavaPackageFinder(javaPackageFinder)
            .withBuildCellRootPath(filesystem.getRootPath());

    ImmutableList<IsolatedStep> steps =
        CopyResourcesStep.of(
            CopyResourcesStep.getResourcesMap(
                buildContext,
                filesystem,
                filesystem
                    .getBuckPaths()
                    .getScratchDir()
                    .resolve("android/java/src/lib__resources__classes"),
                ResourcesParameters.of(
                    ResourcesParameters.getNamedResources(
                        ruleFinder,
                        filesystem,
                        ImmutableSortedSet.of(
                            FakeSourcePath.of(
                                filesystem, "android/java/src/com/facebook/base/data.json"),
                            FakeSourcePath.of(
                                filesystem,
                                "android/java/src/com/facebook/common/util/data.json"))),
                    Optional.empty()),
                buildTarget));

    RelPath target =
        filesystem
            .getBuckPaths()
            .getScratchDir()
            .resolveRel(
                "android/java/src/lib__resources__classes/com/facebook/common/util/data.json");
    RelPath target1 =
        filesystem
            .getBuckPaths()
            .getScratchDir()
            .resolveRel("android/java/src/lib__resources__classes/com/facebook/base/data.json");
    List<IsolatedStep> expected =
        ImmutableList.of(
            MkdirIsolatedStep.of(target1.getParent()),
            SymlinkIsolatedStep.of(
                filesystem.relativize(
                    filesystem.resolve("android/java/src/com/facebook/base/data.json")),
                target1),
            MkdirIsolatedStep.of(target.getParent()),
            SymlinkIsolatedStep.of(
                filesystem.relativize(
                    filesystem.resolve("android/java/src/com/facebook/common/util/data.json")),
                target));
    assertEquals(expected, steps);
  }

  @Test
  public void testAddResourceCommandsWithBuildFileInJavaPackage() {
    // Files:
    // android/java/src/com/facebook/BUCK
    // android/java/src/com/facebook/base/data.json
    // android/java/src/com/facebook/common/util/data.json
    BuildTarget buildTarget =
        BuildTargetFactory.newInstance("//android/java/src/com/facebook:resources");
    ProjectFilesystem filesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();
    JavaPackageFinder javaPackageFinder = createJavaPackageFinder(filesystem);

    SourcePathRuleFinder ruleFinder = new TestActionGraphBuilder();
    BuildContext buildContext =
        FakeBuildContext.withSourcePathResolver(ruleFinder.getSourcePathResolver())
            .withJavaPackageFinder(javaPackageFinder)
            .withBuildCellRootPath(filesystem.getRootPath());

    ImmutableList<IsolatedStep> steps =
        CopyResourcesStep.of(
            CopyResourcesStep.getResourcesMap(
                buildContext,
                filesystem,
                filesystem
                    .getBuckPaths()
                    .getScratchDir()
                    .resolve("android/java/src/com/facebook/lib__resources__classes"),
                ResourcesParameters.of(
                    ResourcesParameters.getNamedResources(
                        ruleFinder,
                        filesystem,
                        ImmutableSortedSet.of(
                            FakeSourcePath.of(
                                filesystem, "android/java/src/com/facebook/base/data.json"),
                            FakeSourcePath.of(
                                filesystem,
                                "android/java/src/com/facebook/common/util/data.json"))),
                    Optional.empty()),
                buildTarget));

    RelPath target =
        filesystem
            .getBuckPaths()
            .getScratchDir()
            .resolveRel(
                "android/java/src/com/facebook/lib__resources__classes/"
                    + "com/facebook/common/util/data.json");
    RelPath target1 =
        filesystem
            .getBuckPaths()
            .getScratchDir()
            .resolveRel(
                "android/java/src/com/facebook/lib__resources__classes/"
                    + "com/facebook/base/data.json");
    List<IsolatedStep> expected =
        ImmutableList.of(
            MkdirIsolatedStep.of(target1.getParent()),
            SymlinkIsolatedStep.of(
                filesystem.relativize(
                    filesystem.resolve("android/java/src/com/facebook/base/data.json")),
                target1),
            MkdirIsolatedStep.of(target.getParent()),
            SymlinkIsolatedStep.of(
                filesystem.relativize(
                    filesystem.resolve("android/java/src/com/facebook/common/util/data.json")),
                target));
    assertEquals(expected, steps);
  }

  private JavaPackageFinder createJavaPackageFinder(ProjectFilesystem projectFilesystem) {
    return DefaultJavaPackageFinder.createDefaultJavaPackageFinder(
        projectFilesystem, ImmutableSet.of("/android/java/src"));
  }
}
