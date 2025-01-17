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

package com.facebook.buck.core.toolchain.toolprovider.impl;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.OutputLabel;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.analysis.context.DependencyOnlyRuleAnalysisContext;
import com.facebook.buck.core.rules.providers.collect.ProviderInfoCollection;
import com.facebook.buck.core.rules.providers.lib.RunInfo;
import com.facebook.buck.core.rules.tool.BinaryBuildRule;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.toolprovider.RuleAnalysisLegacyToolProvider;
import com.facebook.buck.core.toolchain.toolprovider.ToolProvider;
import com.google.common.collect.ImmutableList;
import java.util.Optional;

/**
 * A {@link ToolProvider} which provides the {@link Tool} object of the {@link BinaryBuildRule}
 * references by a given {@link BuildTarget}.
 */
public class BinaryBuildRuleToolProvider implements ToolProvider, RuleAnalysisLegacyToolProvider {

  private final UnconfiguredBuildTarget target;
  private final String source;

  public BinaryBuildRuleToolProvider(UnconfiguredBuildTarget target, String source) {
    this.target = target;
    this.source = source;
  }

  @Override
  public Tool resolve(BuildRuleResolver resolver, TargetConfiguration targetConfiguration) {
    Optional<BuildRule> rule = resolver.getRuleOptional(target.configure(targetConfiguration));
    if (!rule.isPresent()) {
      throw new HumanReadableException("%s: no rule found for %s", source, target);
    }
    if (!(rule.get() instanceof BinaryBuildRule)) {
      throw new HumanReadableException("%s: %s must be an executable rule", source, target);
    }
    return ((BinaryBuildRule) rule.get()).getExecutableCommand(OutputLabel.defaultLabel());
  }

  @Override
  public Iterable<BuildTarget> getParseTimeDeps(TargetConfiguration targetConfiguration) {
    return ImmutableList.of(target.configure(targetConfiguration));
  }

  @Override
  public RunInfo getRunInfo(
      DependencyOnlyRuleAnalysisContext context, TargetConfiguration targetConfiguration) {
    ProviderInfoCollection dep = context.resolveDep(target.configure(targetConfiguration));

    Optional<RunInfo> info = dep.get(RunInfo.PROVIDER);
    if (!info.isPresent()) {
      throw new HumanReadableException("%s: %s must be an executable rule", source, target);
    }
    return info.get();
  }
}
