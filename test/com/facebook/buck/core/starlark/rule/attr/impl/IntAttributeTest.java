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

package com.facebook.buck.core.starlark.rule.attr.impl;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.cell.TestCellPathResolver;
import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.filesystems.ForwardRelPath;
import com.facebook.buck.core.model.ConstantHostTargetConfigurationResolver;
import com.facebook.buck.core.model.UnconfiguredTargetConfiguration;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.rules.coercer.CoerceFailedException;
import com.google.common.collect.ImmutableList;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class IntAttributeTest {

  private final FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
  private final CellNameResolver cellNameResolver =
      TestCellPathResolver.get(filesystem).getCellNameResolver();

  @Rule public ExpectedException expected = ExpectedException.none();

  @Test
  public void coercesIntegersProperly() throws CoerceFailedException {

    IntAttribute attr = ImmutableIntAttribute.of(4, "", true, ImmutableList.of());
    Integer coerced =
        attr.getValue(
            cellNameResolver,
            filesystem,
            ForwardRelPath.of(""),
            UnconfiguredTargetConfiguration.INSTANCE,
            new ConstantHostTargetConfigurationResolver(UnconfiguredTargetConfiguration.INSTANCE),
            5);

    assertEquals(5, coerced.intValue());
  }

  @Test
  public void failsMandatoryCoercionProperly() throws CoerceFailedException {
    expected.expect(CoerceFailedException.class);

    IntAttribute attr = ImmutableIntAttribute.of(4, "", true, ImmutableList.of());

    attr.getValue(
        cellNameResolver,
        filesystem,
        ForwardRelPath.of(""),
        UnconfiguredTargetConfiguration.INSTANCE,
        new ConstantHostTargetConfigurationResolver(UnconfiguredTargetConfiguration.INSTANCE),
        "foo");
  }

  @Test
  public void succeedsIfValueInArray() throws CoerceFailedException {

    IntAttribute attr = ImmutableIntAttribute.of(4, "", true, ImmutableList.of(1, 2, 3, 4));
    int value =
        attr.getValue(
            cellNameResolver,
            filesystem,
            ForwardRelPath.of(""),
            UnconfiguredTargetConfiguration.INSTANCE,
            new ConstantHostTargetConfigurationResolver(UnconfiguredTargetConfiguration.INSTANCE),
            3);

    assertEquals(3, value);
  }

  @Test
  public void allowsAnyValueIfValuesIsEmptyList() throws CoerceFailedException {
    IntAttribute attr = ImmutableIntAttribute.of(4, "", true, ImmutableList.of());
    int value =
        attr.getValue(
            cellNameResolver,
            filesystem,
            ForwardRelPath.of(""),
            UnconfiguredTargetConfiguration.INSTANCE,
            new ConstantHostTargetConfigurationResolver(UnconfiguredTargetConfiguration.INSTANCE),
            3);

    assertEquals(3, value);
  }

  @Test
  public void failsIfValueNotInArray() throws CoerceFailedException {
    expected.expect(CoerceFailedException.class);
    expected.expectMessage("must be one of '1', '2', '4' instead of '3'");

    IntAttribute attr = ImmutableIntAttribute.of(4, "", true, ImmutableList.of(1, 2, 4));

    attr.getValue(
        cellNameResolver,
        filesystem,
        ForwardRelPath.of(""),
        UnconfiguredTargetConfiguration.INSTANCE,
        new ConstantHostTargetConfigurationResolver(UnconfiguredTargetConfiguration.INSTANCE),
        3);
  }
}
