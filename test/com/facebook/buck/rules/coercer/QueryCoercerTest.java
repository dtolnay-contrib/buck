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

package com.facebook.buck.rules.coercer;

import static com.facebook.buck.core.cell.TestCellBuilder.createCellRoots;
import static org.hamcrest.MatcherAssert.assertThat;

import com.facebook.buck.core.model.BaseName;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.UnconfiguredTargetConfiguration;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.rules.query.Query;
import com.facebook.buck.rules.query.UnconfiguredQuery;
import java.util.ArrayList;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.Test;

public class QueryCoercerTest {

  @Test
  public void traverseUnconfiguredBuildTargets() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    QueryCoercer coercer = new QueryCoercer();
    UnconfiguredQuery query = UnconfiguredQuery.of("deps(//:a)", BaseName.ROOT);
    List<Object> traversed = new ArrayList<>();
    coercer.traverseUnconfigured(
        createCellRoots(filesystem).getCellNameResolver(), query, traversed::add);
    assertThat(traversed, Matchers.contains(BuildTargetFactory.newUnconfiguredInstance("//:a")));
  }

  @Test
  public void traverseBuildTargets() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    QueryCoercer coercer = new QueryCoercer();
    Query query = Query.of("deps(//:a)", UnconfiguredTargetConfiguration.INSTANCE, BaseName.ROOT);
    List<Object> traversed = new ArrayList<>();
    coercer.traverse(createCellRoots(filesystem).getCellNameResolver(), query, traversed::add);
    assertThat(traversed, Matchers.contains(BuildTargetFactory.newInstance("//:a")));
  }
}
