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

package com.facebook.buck.android;

import com.facebook.buck.android.toolchain.AndroidPlatformTarget;
import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.description.arg.HasDeclaredDeps;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.facebook.buck.downwardapi.config.DownwardApiConfig;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import org.immutables.value.Value;

public class GenAidlDescription implements DescriptionWithTargetGraph<GenAidlDescriptionArg> {

  private final DownwardApiConfig downwardApiConfig;

  public GenAidlDescription(DownwardApiConfig downwardApiConfig) {
    this.downwardApiConfig = downwardApiConfig;
  }

  @Override
  public Class<GenAidlDescriptionArg> getConstructorArgType() {
    return GenAidlDescriptionArg.class;
  }

  @Override
  public GenAidl createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      GenAidlDescriptionArg args) {
    AndroidPlatformTarget androidPlatformTarget =
        context
            .getToolchainProvider()
            .getByName(
                AndroidPlatformTarget.DEFAULT_NAME,
                buildTarget.getTargetConfiguration(),
                AndroidPlatformTarget.class);

    return new GenAidl(
        buildTarget,
        context.getProjectFilesystem(),
        androidPlatformTarget.getAidlExecutable(),
        androidPlatformTarget.getAndroidFrameworkIdlFile(),
        params,
        args.getAidl(),
        args.getImportPath(),
        args.getImportPaths(),
        args.getAidlSrcs(),
        downwardApiConfig.isEnabledForAndroid());
  }

  @RuleArg
  abstract static class AbstractGenAidlDescriptionArg implements BuildRuleArg, HasDeclaredDeps {
    abstract SourcePath getAidl();

    // import_path is an anomaly: it is a path that is relative to the project root rather than
    // relative to the build file directory.
    abstract String getImportPath();

    // List of additional import paths to pass to the aidl tool, optional
    @Value.Default
    ImmutableList<String> getImportPaths() {
      return ImmutableList.of();
    }

    // Imported *.aidl files.
    @Value.Default
    ImmutableSortedSet<SourcePath> getAidlSrcs() {
      return ImmutableSortedSet.of();
    }
  }
}
