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

package com.facebook.buck.rules.keys.config.impl;

import com.facebook.buck.command.config.BuildBuckConfig;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.module.impl.NoOpBuckModuleHashStrategy;
import com.facebook.buck.rules.keys.config.RuleKeyConfiguration;

/** Creates {@link RuleKeyConfiguration} using information from {@link BuckConfig}. */
public class ConfigRuleKeyConfigurationFactory {

  /** Creates the configuration. */
  public static RuleKeyConfiguration create(BuckConfig buckConfig) {
    long inputKeySizeLimit =
        buckConfig.getView(BuildBuckConfig.class).getBuildInputRuleKeyFileSizeLimit();
    return RuleKeyConfiguration.of(
        buckConfig.getView(BuildBuckConfig.class).getKeySeed(),
        BuckVersion.getVersion(),
        inputKeySizeLimit,
        new NoOpBuckModuleHashStrategy());
  }
}
