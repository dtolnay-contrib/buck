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

package com.facebook.buck.rules.macros;

import static org.hamcrest.MatcherAssert.assertThat;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.cell.TestCellPathResolver;
import com.facebook.buck.core.model.BaseName;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.versions.FixedTargetNodeTranslator;
import com.facebook.buck.versions.TargetNodeTranslator;
import com.google.common.collect.ImmutableMap;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Test;

public class LocationMacroTest {

  private static final CellPathResolver CELL_PATH_RESOLVER =
      TestCellPathResolver.get(new FakeProjectFilesystem());

  @Test
  public void translateTargets() {
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    BuildTarget otherTarget = BuildTargetFactory.newInstance("//:other");
    BuildTarget newTarget = BuildTargetFactory.newInstance("//something:else");
    TargetNodeTranslator translator =
        new FixedTargetNodeTranslator(
            new DefaultTypeCoercerFactory(),
            ImmutableMap.of(target, newTarget),
            new TestCellBuilder().build());
    assertThat(
        translator.translate(
            CELL_PATH_RESOLVER.getCellNameResolver(), BaseName.ROOT, LocationMacro.of(otherTarget)),
        Matchers.equalTo(Optional.empty()));
    assertThat(
        translator.translate(
            CELL_PATH_RESOLVER.getCellNameResolver(), BaseName.ROOT, LocationMacro.of(target)),
        Matchers.equalTo(Optional.of(LocationMacro.of(newTarget))));
  }
}
