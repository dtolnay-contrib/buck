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

package com.facebook.buck.core.parser.buildtargetparser;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.model.BuildTargetFactory;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

public class SingletonBuildTargetMatcherTest {

  private static final Path ROOT = Paths.get("/opt/src/buck");

  @Test
  public void testApply() {
    SingletonBuildTargetMatcher pattern =
        ImmutableSingletonBuildTargetMatcher.ofImpl(
            BuildTargetFactory.newInstance("//src/com/facebook/buck:buck")
                .getUnconfiguredBuildTarget());

    assertTrue(
        pattern.matches(
            BuildTargetFactory.newInstance("//src/com/facebook/buck", "buck")
                .getUnconfiguredBuildTarget()));
    assertFalse(
        pattern.matches(
            BuildTargetFactory.newInstance("//src/com/facebook/buck", "otherTarget")
                .getUnconfiguredBuildTarget()));
    assertFalse(
        pattern.matches(
            BuildTargetFactory.newInstance("//src/com/facebook/foo", "foo")
                .getUnconfiguredBuildTarget()));
    assertFalse(
        pattern.matches(
            BuildTargetFactory.newInstance("//src/com/facebook/buck/bar", "bar")
                .getUnconfiguredBuildTarget()));
  }
}
