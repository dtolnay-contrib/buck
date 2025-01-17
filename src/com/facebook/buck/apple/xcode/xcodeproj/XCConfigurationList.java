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

package com.facebook.buck.apple.xcode.xcodeproj;

import com.facebook.buck.apple.xcode.AbstractPBXObjectFactory;
import com.facebook.buck.apple.xcode.XcodeprojSerializer;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/** List of build configurations. */
public class XCConfigurationList extends PBXProjectItem {
  private List<XCBuildConfiguration> buildConfigurations;
  private Optional<String> defaultConfigurationName;
  private boolean defaultConfigurationIsVisible;

  private final LoadingCache<String, XCBuildConfiguration> buildConfigurationsByName;

  public XCConfigurationList(AbstractPBXObjectFactory objectFactory) {
    buildConfigurations = new ArrayList<>();
    defaultConfigurationName = Optional.empty();
    defaultConfigurationIsVisible = false;

    buildConfigurationsByName =
        CacheBuilder.newBuilder()
            .build(
                new CacheLoader<String, XCBuildConfiguration>() {
                  @Override
                  public XCBuildConfiguration load(String key) {
                    XCBuildConfiguration configuration =
                        objectFactory.createBuildConfiguration(key);
                    buildConfigurations.add(configuration);
                    return configuration;
                  }
                });
  }

  public LoadingCache<String, XCBuildConfiguration> getBuildConfigurationsByName() {
    return buildConfigurationsByName;
  }

  @Override
  public String isa() {
    return "XCConfigurationList";
  }

  @Override
  public void serializeInto(XcodeprojSerializer s) {
    super.serializeInto(s);

    Collections.sort(buildConfigurations, (o1, o2) -> o1.getName().compareTo(o2.getName()));
    s.addField("buildConfigurations", buildConfigurations);

    if (defaultConfigurationName.isPresent()) {
      s.addField("defaultConfigurationName", defaultConfigurationName.get());
    }
    s.addField("defaultConfigurationIsVisible", defaultConfigurationIsVisible);
  }
}
