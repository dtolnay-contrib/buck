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

package com.facebook.buck.core.sourcepath;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.impl.FakeBuildRule;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import org.junit.Before;
import org.junit.Test;

public class ForwardingBuildTargetSourcePathTest {

  private ActionGraphBuilder graphBuilder;
  private SourcePathResolverAdapter pathResolver;

  @Before
  public void setUp() {
    graphBuilder = new TestActionGraphBuilder();
    pathResolver = graphBuilder.getSourcePathResolver();
  }

  @Test
  public void forwardsToPathSourcePath() {
    BuildTarget target = BuildTargetFactory.newInstance("//package:name");
    RelPath relativePath = RelPath.get("foo/bar");
    ForwardingBuildTargetSourcePath sourcePath =
        com.facebook.buck.core.sourcepath.ForwardingBuildTargetSourcePath.of(
            target, PathSourcePath.of(new FakeProjectFilesystem(), relativePath));
    assertEquals(target, sourcePath.getTarget());
    assertEquals(relativePath, pathResolver.getCellUnsafeRelPath(sourcePath));
  }

  @Test
  public void forwardsToDefaultBuildTargetSourcePath() {
    BuildTarget target = BuildTargetFactory.newInstance("//package:name");
    FakeBuildRule rule = new FakeBuildRule(target);
    rule.setOutputFile("foo/bar");
    graphBuilder.addToIndex(rule);
    ForwardingBuildTargetSourcePath sourcePath =
        com.facebook.buck.core.sourcepath.ForwardingBuildTargetSourcePath.of(
            target, DefaultBuildTargetSourcePath.of(target));
    assertEquals(target, sourcePath.getTarget());
    assertEquals(rule.getOutputFile(), pathResolver.getCellUnsafeRelPath(sourcePath).getPath());
  }

  @Test
  public void forwardsToExplicitBuildTargetSourcePath() {
    BuildTarget target = BuildTargetFactory.newInstance("//package:name");
    FakeBuildRule rule = new FakeBuildRule(target);
    graphBuilder.addToIndex(rule);
    RelPath relativePath = RelPath.get("foo/bar");
    ForwardingBuildTargetSourcePath sourcePath =
        com.facebook.buck.core.sourcepath.ForwardingBuildTargetSourcePath.of(
            target, ExplicitBuildTargetSourcePath.of(target, relativePath));
    assertEquals(target, sourcePath.getTarget());
    assertEquals(relativePath, pathResolver.getCellUnsafeRelPath(sourcePath));
  }

  @Test
  public void chains() {
    BuildTarget target1 = BuildTargetFactory.newInstance("//package:name");
    FakeBuildRule rule1 = new FakeBuildRule(target1);
    graphBuilder.addToIndex(rule1);

    BuildTarget target2 = BuildTargetFactory.newInstance("//package2:name2");
    FakeBuildRule rule2 = new FakeBuildRule(target2);
    graphBuilder.addToIndex(rule2);

    RelPath relativePath = RelPath.get("foo/bar");

    ForwardingBuildTargetSourcePath sourcePath =
        com.facebook.buck.core.sourcepath.ForwardingBuildTargetSourcePath.of(
            target1,
            com.facebook.buck.core.sourcepath.ForwardingBuildTargetSourcePath.of(
                target2, ExplicitBuildTargetSourcePath.of(target2, relativePath)));
    assertEquals(target1, sourcePath.getTarget());
    assertEquals(relativePath, pathResolver.getCellUnsafeRelPath(sourcePath));
  }
}
