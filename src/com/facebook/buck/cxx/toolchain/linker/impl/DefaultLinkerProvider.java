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

package com.facebook.buck.cxx.toolchain.linker.impl;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.toolprovider.ToolProvider;
import com.facebook.buck.cxx.toolchain.BuildRuleResolverCacheByTargetConfiguration;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.linker.LinkerProvider;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;

public class DefaultLinkerProvider implements LinkerProvider {

  private final Type type;
  private final ToolProvider toolProvider;
  private final boolean shouldCacheLinks;
  private final boolean shouldUploadToCache;
  private final boolean useFocusedDebugging;
  private final boolean usePathNormalizationArgs;

  private final LoadingCache<BuildRuleResolver, BuildRuleResolverCacheByTargetConfiguration<Linker>>
      cache =
          CacheBuilder.newBuilder()
              .weakKeys()
              .build(
                  new CacheLoader<
                      BuildRuleResolver, BuildRuleResolverCacheByTargetConfiguration<Linker>>() {
                    @Override
                    public BuildRuleResolverCacheByTargetConfiguration<Linker> load(
                        BuildRuleResolver buildRuleResolver) {
                      return new BuildRuleResolverCacheByTargetConfiguration<>(
                          buildRuleResolver,
                          toolProvider,
                          tool ->
                              build(
                                  type,
                                  tool,
                                  shouldCacheLinks,
                                  shouldUploadToCache,
                                  useFocusedDebugging,
                                  usePathNormalizationArgs));
                    }
                  });

  public DefaultLinkerProvider(
      Type type,
      ToolProvider toolProvider,
      boolean shouldCacheLinks,
      boolean shouldUploadToCache,
      boolean useFocusedDebugging,
      boolean usePathNormalizationArgs) {
    this.type = type;
    this.toolProvider = toolProvider;
    this.shouldCacheLinks = shouldCacheLinks;
    this.shouldUploadToCache = shouldUploadToCache;
    this.useFocusedDebugging = useFocusedDebugging;
    this.usePathNormalizationArgs = usePathNormalizationArgs;
  }

  private static Linker build(
      Type type,
      Tool tool,
      boolean shouldCacheLinks,
      boolean shouldUploadToCache,
      boolean useFocusedDebugging,
      boolean usePathNormalizationArgs) {
    switch (type) {
      case DARWIN:
        return new DarwinLinker(
            tool,
            shouldCacheLinks,
            shouldUploadToCache,
            useFocusedDebugging,
            usePathNormalizationArgs);
      case GNU:
        return new GnuLinker(tool);
      case WINDOWS:
        return new WindowsLinker(tool);
      case UNKNOWN:
      default:
        throw new IllegalStateException("unexpected type: " + type);
    }
  }

  @Override
  public synchronized Linker resolve(
      BuildRuleResolver resolver, TargetConfiguration targetConfiguration) {
    return cache.getUnchecked(resolver).get(targetConfiguration);
  }

  @Override
  public Type getType() {
    return type;
  }

  @Override
  public Iterable<BuildTarget> getParseTimeDeps(TargetConfiguration targetConfiguration) {
    return toolProvider.getParseTimeDeps(targetConfiguration);
  }
}
