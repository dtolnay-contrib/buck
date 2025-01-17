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

package com.facebook.buck.shell;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.OutputLabel;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.common.BuildableSupport;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.hamcrest.Matchers;
import org.junit.Test;

public class ShBinaryDescriptionTest {

  @Test
  public void mainIsIncludedInCommand() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    PathSourcePath main = FakeSourcePath.of("main.sh");
    ShBinary shBinary =
        new ShBinaryBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setMain(main)
            .build(graphBuilder);
    assertThat(
        BuildableSupport.deriveInputs(shBinary.getExecutableCommand(OutputLabel.defaultLabel()))
            .collect(ImmutableList.toImmutableList()),
        Matchers.hasItem(main));
  }

  @Test
  public void resourcesAreIncludedInCommand() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    PathSourcePath main = FakeSourcePath.of("main.sh");
    PathSourcePath resource = FakeSourcePath.of("resource.dat");
    ShBinary shBinary =
        new ShBinaryBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setMain(main)
            .setResources(ImmutableSet.of(resource))
            .build(graphBuilder);
    assertThat(
        BuildableSupport.deriveInputs(shBinary.getExecutableCommand(OutputLabel.defaultLabel()))
            .collect(ImmutableList.toImmutableList()),
        Matchers.hasItem(resource));
  }

  @Test
  public void verifyCellErrorMatcher() {
    assertTrue(
        ShBinary.CELL_ERROR_MATCHER
            .matcher(
                "cd \"$BUCK_TMP_ROOT\"\n"
                    + "  # Create symlink to the cells in the folder containing this script.\n"
                    + "  CELLS_NAMES=(\n"
                    + "  header \"__default__\"\n"
                    + "  header \"buck\"\n"
                    + "  header \"other_cell\"\n"
                    + "  )\n"
                    + "  CELLS_PATHS=("
                    + "...")
            .find());

    assertTrue(
        ShBinary.CELL_ERROR_MATCHER
            .matcher(
                "cd \"$BUCK_TMP_ROOT\"\n"
                    + "  # Create symlink to the cells in the folder containing this script.\n"
                    + "  CELLS_NAMES=(\n"
                    + "  header \"__default__\"\n"
                    + "  header \"buck\"\n"
                    + "  header \"other_cell\"\n"
                    + "  )\n"
                    + "  CELLS_PATHS=("
                    + "...")
            .find());

    assertTrue(
        ShBinary.CELL_ERROR_MATCHER
            .matcher(
                "cd \"$BUCK_TMP_ROOT\"\n"
                    + "  # Create symlink to the cells in the folder containing this script.\n"
                    + "  CELLS_NAMES=(\n"
                    + "  \"__default__\"\n"
                    + "  header \"buck\"\n"
                    + "  \"other_cell\"\n"
                    + "  )\n"
                    + "  CELLS_PATHS=("
                    + "...")
            .find());

    assertFalse(
        ShBinary.CELL_ERROR_MATCHER
            .matcher(
                "cd \"$BUCK_TMP_ROOT\"\n"
                    + "  CELLS_NOT_NAMES=(\n"
                    + "  \"__default__\"\n"
                    + "  header \"buck\"\n"
                    + "  \"other_cell\"\n"
                    + "  )\n"
                    + "  CELLS_PATHS=("
                    + "...")
            .find());

    assertFalse(
        ShBinary.CELL_ERROR_MATCHER
            .matcher(
                "cd \"$BUCK_TMP_ROOT\"\n"
                    + "  CELLS_NAMES=(\n"
                    + "  \"__default__\"\n"
                    + "  \"other_cell\"\n"
                    + "  )\n"
                    + "  CELLS_PATHS=("
                    + "...")
            .find());

    assertFalse(
        ShBinary.CELL_ERROR_MATCHER
            .matcher(
                "cd \"$BUCK_TMP_ROOT\"\n"
                    + "  CELLS_NAMES=(\n"
                    + "  \"__default__\"\n"
                    + "  \"other_cell\"\n"
                    + "  )\n"
                    + "  CELLS_NOT_NAMES=("
                    + "  header \"buck\"\n"
                    + "  )\n"
                    + "...")
            .find());
  }
}
