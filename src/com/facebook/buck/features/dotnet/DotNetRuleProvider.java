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

package com.facebook.buck.features.dotnet;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.description.Description;
import com.facebook.buck.core.description.DescriptionCreationContext;
import com.facebook.buck.core.model.targetgraph.BuiltInProviderProvider;
import com.facebook.buck.core.model.targetgraph.DescriptionProvider;
import com.facebook.buck.core.rules.analysis.config.RuleAnalysisComputationMode;
import com.facebook.buck.core.rules.analysis.config.RuleAnalysisConfig;
import com.facebook.buck.core.rules.providers.impl.BuiltInProvider;
import com.facebook.buck.downwardapi.config.DownwardApiConfig;
import java.util.Arrays;
import java.util.Collection;
import org.pf4j.Extension;

@Extension
public class DotNetRuleProvider implements DescriptionProvider, BuiltInProviderProvider {
  @Override
  public Collection<Description<?>> getDescriptions(DescriptionCreationContext context) {
    BuckConfig buckConfig = context.getBuckConfig();
    RuleAnalysisConfig ruleAnalysisConfig = buckConfig.getView(RuleAnalysisConfig.class);
    DownwardApiConfig downwardApiConfig = buckConfig.getView(DownwardApiConfig.class);

    if (ruleAnalysisConfig
        .getComputationMode()
        .equals(RuleAnalysisComputationMode.PROVIDER_COMPATIBLE)) {
      return Arrays.asList(
          new CsharpLibraryDescription(downwardApiConfig),
          new PrebuiltDotnetLibraryRuleDescription());
    }
    return Arrays.asList(
        new CsharpLibraryDescription(downwardApiConfig), new PrebuiltDotnetLibraryDescription());
  }

  @Override
  public Collection<BuiltInProvider<?>> getBuiltInProviders() {
    return Arrays.asList(DotnetLibraryProviderInfo.PROVIDER, DotnetLegacyToolchainInfo.PROVIDER);
  }
}
