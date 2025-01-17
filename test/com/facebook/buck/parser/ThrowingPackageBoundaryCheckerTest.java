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

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.ForwardRelPath;
import com.facebook.buck.core.model.BuildFileTree;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.impl.InMemoryBuildFileTree;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableSet;
import java.util.Collections;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class ThrowingPackageBoundaryCheckerTest {

  @Rule public ExpectedException thrown = ExpectedException.none();

  @Test
  public void testEnforceFailsWhenPathReferencesParentDirectory() {
    LoadingCache<Cell, BuildFileTree> buildFileTrees =
        CacheBuilder.newBuilder()
            .build(
                new CacheLoader<Cell, BuildFileTree>() {
                  @Override
                  public BuildFileTree load(Cell cell) {
                    return new InMemoryBuildFileTree(Collections.emptyList());
                  }
                });
    ThrowingPackageBoundaryChecker boundaryChecker =
        new ThrowingPackageBoundaryChecker(buildFileTrees);

    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(
        Matchers.matchesRegex("'..[/\\\\]Test.java' in '//a/b:c' refers to a parent directory."));

    boundaryChecker.enforceBuckPackageBoundaries(
        new TestCellBuilder().build().getRootCell(),
        BuildTargetFactory.newInstance("//a/b:c"),
        ImmutableSet.of(ForwardRelPath.of("a/Test.java")));
  }

  @Test
  public void testEnforceSkippedWhenNotConfigured() {
    LoadingCache<Cell, BuildFileTree> buildFileTrees =
        CacheBuilder.newBuilder()
            .build(
                new CacheLoader<Cell, BuildFileTree>() {
                  @Override
                  public BuildFileTree load(Cell cell) {
                    return new InMemoryBuildFileTree(Collections.emptyList());
                  }
                });
    ThrowingPackageBoundaryChecker boundaryChecker =
        new ThrowingPackageBoundaryChecker(buildFileTrees);

    boundaryChecker.enforceBuckPackageBoundaries(
        new TestCellBuilder()
            .setBuckConfig(
                FakeBuckConfig.builder()
                    .setSections("[project]", "check_package_boundary = false")
                    .build())
            .build()
            .getRootCell(),
        BuildTargetFactory.newInstance("//a/b:c"),
        ImmutableSet.of(ForwardRelPath.of("a/Test.java")));
  }

  @Test
  public void testEnforceFailsWhenPathDoesntBelongToPackage() {
    LoadingCache<Cell, BuildFileTree> buildFileTrees =
        CacheBuilder.newBuilder()
            .build(
                new CacheLoader<Cell, BuildFileTree>() {
                  @Override
                  public BuildFileTree load(Cell cell) {
                    return new InMemoryBuildFileTree(Collections.emptyList()) {
                      @Override
                      public Optional<ForwardRelPath> getBasePathOfAncestorTarget(
                          ForwardRelPath filePath) {
                        return Optional.empty();
                      }
                    };
                  }
                });
    ThrowingPackageBoundaryChecker boundaryChecker =
        new ThrowingPackageBoundaryChecker(buildFileTrees);

    thrown.expect(IllegalStateException.class);
    thrown.expectMessage(
        "Target '//a/b:c' refers to file 'a/b/Test.java', which doesn't belong to any package."
            + " More info at:\nhttps://dev.buck.build/about/overview.html\n");
    boundaryChecker.enforceBuckPackageBoundaries(
        new TestCellBuilder().build().getRootCell(),
        BuildTargetFactory.newInstance("//a/b:c"),
        ImmutableSet.of(ForwardRelPath.of("a/b/Test.java")));
  }

  @Test
  public void testEnforceFailsWhenAncestorNotEqualsToBasePath() {
    LoadingCache<Cell, BuildFileTree> buildFileTrees =
        CacheBuilder.newBuilder()
            .build(
                new CacheLoader<Cell, BuildFileTree>() {
                  @Override
                  public BuildFileTree load(Cell cell) {
                    return new InMemoryBuildFileTree(Collections.emptyList()) {
                      @Override
                      public Optional<ForwardRelPath> getBasePathOfAncestorTarget(
                          ForwardRelPath filePath) {
                        return Optional.of(ForwardRelPath.of("d"));
                      }
                    };
                  }
                });
    ThrowingPackageBoundaryChecker boundaryChecker =
        new ThrowingPackageBoundaryChecker(buildFileTrees);

    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(
        Matchers.matchesRegex(
            "The target '//a/b:c' tried to reference 'a[/\\\\]b[/\\\\]Test.java'.\n"
                + "This is not allowed because 'a[/\\\\]b[/\\\\]Test.java' "
                + "can only be referenced from 'd[/\\\\]BUCK' \n"
                + "which is its closest parent 'BUCK' file.\n\n"
                + "You should find or create a rule in 'd[/\\\\]BUCK' that references\n'"
                + "a[/\\\\]b[/\\\\]Test.java' and use that in '//a/b:c'\n"
                + "instead of directly referencing 'a[/\\\\]b[/\\\\]Test.java'.\n"
                + "More info at:\n"
                + "https://dev.buck.build/concept/build_rule.html\n\n"
                + "This issue might also be caused by a bug in buckd's caching.\n"
                + "Please check whether using `buck kill` resolves it."));

    boundaryChecker.enforceBuckPackageBoundaries(
        new TestCellBuilder().build().getRootCell(),
        BuildTargetFactory.newInstance("//a/b:c"),
        ImmutableSet.of(ForwardRelPath.of("a/b/Test.java")));
  }

  @Test
  public void testEnforceDoesntFailWhenPathsAreValid() {
    LoadingCache<Cell, BuildFileTree> buildFileTrees =
        CacheBuilder.newBuilder()
            .build(
                new CacheLoader<Cell, BuildFileTree>() {
                  @Override
                  public BuildFileTree load(Cell cell) {
                    return new InMemoryBuildFileTree(
                        Collections.singleton(ForwardRelPath.of("a/b")));
                  }
                });
    ThrowingPackageBoundaryChecker boundaryChecker =
        new ThrowingPackageBoundaryChecker(buildFileTrees);

    boundaryChecker.enforceBuckPackageBoundaries(
        new TestCellBuilder().setFilesystem(new FakeProjectFilesystem()).build().getRootCell(),
        BuildTargetFactory.newInstance("//a/b:c"),
        ImmutableSet.of(ForwardRelPath.of("a/b/Test.java")));
  }
}
