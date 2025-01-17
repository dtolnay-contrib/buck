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

package com.facebook.buck.features.project.intellij;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.UnconfiguredTargetConfiguration;
import com.facebook.buck.io.pathformat.PathFormatter;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

public abstract class Util {
  private static final HashFunction hashFunction = Hashing.murmur3_32();

  public static String hash(String s) {
    return hashFunction.hashString(s, StandardCharsets.UTF_8).toString();
  }

  public static String intelliJModuleNameFromPath(Path path) {
    String name = PathFormatter.pathWithUnixSeparators(path);
    if (name.isEmpty()) {
      return "project_root";
    } else {
      // For module names, replacing "_" with "__" will avoid the name conflict between
      // foo/bar/BUCK module and foo_bar/BUCk module
      return Util.normalizeIntelliJName(name.replace("_", "__"));
    }
  }

  public static String intelliJLibraryName(BuildTarget target) {
    if (target.getTargetConfiguration() instanceof UnconfiguredTargetConfiguration) {
      return target.getFullyQualifiedName();
    }
    return target.toStringWithConfiguration();
  }

  public static String normalizeIntelliJName(String name) {
    return name.replace('.', '_')
        .replace('-', '_')
        .replace(':', '_')
        .replace(' ', '_')
        .replace('/', '_')
        .replace('#', '_')
        .replace('(', '_')
        .replace(')', '_');
  }

  /** A quick but not accurate way to determine if the name is a valid package name or not */
  public static boolean isValidPackageName(String name) {
    return name.matches("[0-9a-zA-Z._]+");
  }
}
