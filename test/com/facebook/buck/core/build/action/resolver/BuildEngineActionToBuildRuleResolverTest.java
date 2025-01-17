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

package com.facebook.buck.core.build.action.resolver;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.build.action.BuildEngineAction;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.impl.FakeBuildRule;
import org.junit.Test;

public class BuildEngineActionToBuildRuleResolverTest {

  @Test
  public void resolvesBuildEngineActionToBuildRule() {
    BuildTarget target = BuildTargetFactory.newInstance("//my:foo");
    BuildRule rule = new FakeBuildRule(target);

    BuildEngineActionToBuildRuleResolver ruleResolver = new BuildEngineActionToBuildRuleResolver();

    BuildEngineAction resolved = ruleResolver.resolve(rule);
    assertEquals(rule, resolved);
  }
}
