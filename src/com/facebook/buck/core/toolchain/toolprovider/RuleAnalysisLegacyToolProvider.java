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

package com.facebook.buck.core.toolchain.toolprovider;

import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.analysis.context.DependencyOnlyRuleAnalysisContext;
import com.facebook.buck.core.rules.providers.lib.RunInfo;
import com.facebook.buck.core.toolchain.tool.Tool;

/**
 * Provide {@link RunInfo} from a {@link ToolProvider} so that legacy {@link Tool}s can be used in
 * the rule analysis graph
 */
public interface RuleAnalysisLegacyToolProvider {

  /** @return a {@link RunInfo} that will execute a given tool */
  RunInfo getRunInfo(
      DependencyOnlyRuleAnalysisContext context, TargetConfiguration targetConfiguration);
}
