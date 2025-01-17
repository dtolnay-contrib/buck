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

package com.facebook.buck.jvm.java;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.OutputLabel;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.attr.HasMultipleOutputs;
import com.facebook.buck.core.rules.impl.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.Step;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import javax.annotation.Nullable;

public class Keystore extends AbstractBuildRuleWithDeclaredAndExtraDeps
    implements HasMultipleOutputs {
  private static final OutputLabel KEYSTORE_LABEL = OutputLabel.of("keystore");
  private static final OutputLabel PROPERTIES_LABEL = OutputLabel.of("properties");

  @AddToRuleKey private final SourcePath pathToStore;
  @AddToRuleKey private final SourcePath pathToProperties;

  public Keystore(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      SourcePath store,
      SourcePath properties) {
    super(buildTarget, projectFilesystem, params);
    this.pathToStore = store;
    this.pathToProperties = properties;
  }

  @Override
  public ImmutableSet<OutputLabel> getOutputLabels() {
    return ImmutableSet.of(KEYSTORE_LABEL, PROPERTIES_LABEL);
  }

  @Nullable
  @Override
  public SourcePath getSourcePathToOutput() {
    return null;
  }

  @Nullable
  @Override
  public ImmutableSortedSet<SourcePath> getSourcePathToOutput(OutputLabel outputLabel) {
    if (outputLabel.equals(KEYSTORE_LABEL)) {
      return ImmutableSortedSet.of(pathToStore);
    } else if (outputLabel.equals(PROPERTIES_LABEL)) {
      return ImmutableSortedSet.of(pathToProperties);
    } else {
      return ImmutableSortedSet.of();
    }
  }

  public SourcePath getPathToStore() {
    return pathToStore;
  }

  public SourcePath getPathToPropertiesFile() {
    return pathToProperties;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    // Nothing to build: this is like a glorified exported_deps() rule.
    return ImmutableList.of();
  }
}
