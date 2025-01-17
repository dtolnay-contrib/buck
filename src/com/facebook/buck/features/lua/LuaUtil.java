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

package com.facebook.buck.features.lua;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.coercer.SourceSortedSet;
import com.facebook.buck.util.stream.RichStream;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import java.io.File;
import java.util.Collection;
import java.util.Optional;

public class LuaUtil {

  private LuaUtil() {}

  public static ImmutableMap<String, SourcePath> toModuleMap(
      BuildTarget target,
      SourcePathResolverAdapter resolver,
      String parameter,
      String baseModule,
      Iterable<SourceSortedSet> inputs) {

    ImmutableMap.Builder<String, SourcePath> moduleNamesAndSourcePaths = ImmutableMap.builder();

    for (SourceSortedSet input : inputs) {
      ImmutableMap<String, SourcePath> namesAndSourcePaths =
          input.match(
              new SourceSortedSet.Matcher<ImmutableMap<String, SourcePath>>() {
                @Override
                public ImmutableMap<String, SourcePath> named(
                    ImmutableSortedMap<String, SourcePath> named) {
                  return named;
                }

                @Override
                public ImmutableMap<String, SourcePath> unnamed(
                    ImmutableSortedSet<SourcePath> unnamed) {
                  return resolver.getSourcePathNames(target, parameter, unnamed);
                }
              });
      for (ImmutableMap.Entry<String, SourcePath> entry : namesAndSourcePaths.entrySet()) {
        String name = entry.getKey();
        if (!baseModule.isEmpty()) {
          name = baseModule + '/' + name;
        }
        moduleNamesAndSourcePaths.put(name, entry.getValue());
      }
    }

    return moduleNamesAndSourcePaths.build();
  }

  public static String getBaseModule(BuildTarget target, Optional<String> override) {
    return override
        .map(s -> s.replace('.', File.separatorChar))
        .orElseGet(() -> target.getCellRelativeBasePath().getPath().toString());
  }

  public static ImmutableList<BuildTarget> getDeps(
      CxxPlatform cxxPlatform,
      ImmutableSortedSet<BuildTarget> deps,
      PatternMatchedCollection<ImmutableSortedSet<BuildTarget>> platformDeps) {
    return RichStream.from(deps)
        .concat(
            platformDeps.getMatchingValues(cxxPlatform.getFlavor().toString()).stream()
                .flatMap(Collection::stream))
        .toImmutableList();
  }
}
