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

package com.facebook.buck.versions;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.targetgraph.AbstractNodeBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.AbstractMap;
import java.util.Map;

public class VersionedAliasBuilder
    extends AbstractNodeBuilder<
        VersionedAliasDescriptionArg.Builder,
        VersionedAliasDescriptionArg,
        VersionedAliasDescription,
        BuildRule> {

  public VersionedAliasBuilder(VersionedAliasDescription description, BuildTarget target) {
    super(description, target);
  }

  public VersionedAliasBuilder(BuildTarget target) {
    this(new VersionedAliasDescription(), target);
  }

  public VersionedAliasBuilder(String target) {
    this(BuildTargetFactory.newInstance(target));
  }

  public VersionedAliasBuilder setVersions(ImmutableMap<Version, BuildTarget> versions) {
    getArgForPopulating().setVersions(versions);
    return this;
  }

  public VersionedAliasBuilder setVersions(
      ImmutableList<Map.Entry<Version, BuildTarget>> versions) {
    return setVersions(ImmutableMap.<Version, BuildTarget>builder().putAll(versions).build());
  }

  public VersionedAliasBuilder setVersions(String version, String target) {
    return setVersions(
        ImmutableList.of(
            new AbstractMap.SimpleEntry<>(
                Version.of(version), BuildTargetFactory.newInstance(target))));
  }

  public VersionedAliasBuilder setVersions(String v1, String t1, String v2, String t2) {
    return setVersions(
        ImmutableList.of(
            new AbstractMap.SimpleEntry<>(Version.of(v1), BuildTargetFactory.newInstance(t1)),
            new AbstractMap.SimpleEntry<>(Version.of(v2), BuildTargetFactory.newInstance(t2))));
  }
}
