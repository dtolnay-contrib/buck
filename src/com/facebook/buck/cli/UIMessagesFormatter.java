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

package com.facebook.buck.cli;

import com.facebook.buck.command.config.ConfigDifference;
import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.support.cli.args.GlobalCliOptions;
import com.facebook.buck.util.config.Configs;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/** Formats messages that will be displayed in console */
class UIMessagesFormatter {

  static final String COMPARISON_MESSAGE_PREFIX =
      "Running with reused config, some configuration changes would not be applied: ";
  static final String USING_ADDITIONAL_CONFIGURATION_OPTIONS_PREFIX =
      "Using additional configuration options from ";

  /** Formats comparison of {@link BuckConfig}s into UI message */
  static Optional<String> reusedConfigWarning(
      CanonicalCellName cellName,
      ImmutableMap<ConfigDifference.ConfigKey, ConfigDifference.ConfigChange> diff) {
    if (diff.isEmpty()) {
      return Optional.empty();
    }
    StringBuilder diffBuilder = new StringBuilder(COMPARISON_MESSAGE_PREFIX);
    diffBuilder
        .append(System.lineSeparator())
        .append(ConfigDifference.formatConfigDiffShort(cellName, diff, 4));
    return Optional.of(diffBuilder.toString());
  }

  static String reuseConfigPropertyProvidedMessage() {
    return String.format(
        "`%s` parameter provided. Reusing previously defined config.",
        GlobalCliOptions.REUSE_CURRENT_CONFIG_ARG);
  }

  static Optional<String> useSpecificOverridesMessage(
      AbsPath root, ImmutableSet<Path> overridesToIgnore) throws IOException {

    Path mainConfigPath = Configs.getMainConfigurationFile(root.getPath());
    String userSpecifiedOverrides =
        Configs.getDefaultConfigurationFiles(root).stream()
            .filter(path -> isValidPath(path, overridesToIgnore, mainConfigPath))
            .map(path -> path.startsWith(root.getPath()) ? root.relativize(path) : path)
            .map(Objects::toString)
            .distinct()
            .sorted(Comparator.naturalOrder())
            .collect(Collectors.joining(", "));

    return Optional.of(userSpecifiedOverrides)
        .filter(Predicates.not(String::isEmpty))
        .map(USING_ADDITIONAL_CONFIGURATION_OPTIONS_PREFIX::concat);
  }

  private static boolean isValidPath(
      Path path, ImmutableSet<Path> overridesToIgnore, Path mainConfigPath) {
    return !overridesToIgnore.contains(path.getFileName()) && !mainConfigPath.equals(path);
  }
}
