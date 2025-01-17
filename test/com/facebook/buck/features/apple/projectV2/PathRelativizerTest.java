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

package com.facebook.buck.features.apple.projectV2;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import java.nio.file.Paths;
import org.junit.Before;
import org.junit.Test;

public class PathRelativizerTest {
  private PathRelativizer pathRelativizer;

  @Before
  public void setUp() {
    pathRelativizer = AppleProjectHelper.defaultPathRelativizer("output0/output1");
  }

  @Test
  public void testOutputPathToBuildTargetPath() {
    assertEquals(
        Paths.get("../../foo/bar"), pathRelativizer.outputDirToRootRelative(Paths.get("foo/bar")));
  }

  @Test
  public void testOutputPathToSourcePath() {
    assertEquals(
        Paths.get("../../source/path/foo.h"),
        pathRelativizer.outputPathToSourcePath(FakeSourcePath.of("source/path/foo.h")));
  }

  @Test
  public void testOutputDirToRootRelative() {
    assertEquals(
        Paths.get("../../foo/bar"),
        pathRelativizer.outputPathToBuildTargetPath(
            BuildTargetFactory.newInstance("//foo/bar:baz")));
  }

  @Test
  public void testOutputDirToRootRelativeDoesNotAddExtraDotDots() {
    assertEquals(
        Paths.get("something"),
        pathRelativizer.outputDirToRootRelative(Paths.get("output0/output1/something")));
  }

  @Test
  public void testOutputDirToRootRelativeWorksForCurrentDir() {
    assertEquals(
        Paths.get("."), pathRelativizer.outputDirToRootRelative(Paths.get("output0/output1")));
  }

  @Test
  public void testOutputDirToRootRelativeWorksForParentDir() {
    assertEquals(Paths.get(".."), pathRelativizer.outputDirToRootRelative(Paths.get("output0")));
  }
}
