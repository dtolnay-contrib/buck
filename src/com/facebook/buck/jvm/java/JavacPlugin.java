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

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.impl.NoopBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.io.filesystem.ProjectFilesystem;

abstract class JavacPlugin extends NoopBuildRuleWithDeclaredAndExtraDeps {

  @AddToRuleKey private final JavacPluginProperties properties;
  private final ResolvedJavacPluginProperties resolvedProperties;

  public JavacPlugin(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      JavacPluginProperties properties,
      SourcePathResolverAdapter resolver) {
    super(buildTarget, projectFilesystem, params);
    this.properties = properties;
    this.resolvedProperties =
        ResolvedJavacPluginProperties.of(properties, resolver, projectFilesystem.getRootPath());
  }

  public JavacPluginProperties getUnresolvedProperties() {
    return properties; // Shut up PMD
  }

  public ResolvedJavacPluginProperties getPluginProperties() {
    return resolvedProperties;
  }
}
