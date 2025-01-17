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
import static org.junit.Assert.fail;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.impl.FakeBuildRule;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import org.junit.Test;

public class DefaultBuildTargetSourcePathTest {

  private final BuildTarget target = BuildTargetFactory.newInstance("//example:target");

  @Test
  public void shouldThrowAnExceptionIfRuleDoesNotHaveAnOutput() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    FakeBuildRule rule = new FakeBuildRule(target);
    rule.setOutputFile(null);
    graphBuilder.addToIndex(rule);
    SourcePath path = DefaultBuildTargetSourcePath.of(target);

    try {
      graphBuilder.getSourcePathResolver().getCellUnsafeRelPath(path);
      fail();
    } catch (HumanReadableException e) {
      assertEquals("No known output for: " + target.getFullyQualifiedName(), e.getMessage());
    }
  }

  @Test
  public void mustUseProjectFilesystemToResolvePathToFile() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    FakeBuildRule rule = new FakeBuildRule(target);
    rule.setOutputFile("cheese");
    graphBuilder.addToIndex(rule);

    SourcePath path = rule.getSourcePathToOutput();

    RelPath resolved = graphBuilder.getSourcePathResolver().getCellUnsafeRelPath(path);

    assertEquals(RelPath.get("cheese"), resolved);
  }

  @Test
  public void shouldReturnTheBuildTarget() {
    BuildTarget target = BuildTargetFactory.newInstance("//foo/bar:baz");
    DefaultBuildTargetSourcePath path = DefaultBuildTargetSourcePath.of(target);

    assertEquals(target, path.getTarget());
  }
}
