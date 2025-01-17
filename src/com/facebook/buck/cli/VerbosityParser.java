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

import com.facebook.buck.support.cli.args.GlobalCliOptions;
import com.facebook.buck.util.Verbosity;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class VerbosityParser {

  @VisibleForTesting static final Verbosity DEFAULT_VERBOSITY = Verbosity.STANDARD_INFORMATION;

  private static final Pattern VERBOSE_ARG_PATTERN =
      Pattern.compile(
          "(?:"
              + GlobalCliOptions.VERBOSE_LONG_ARG
              + "|"
              + GlobalCliOptions.VERBOSE_SHORT_ARG
              + ")=(\\d+)");

  private VerbosityParser() {}

  public static Verbosity parse(ImmutableList<String> args) {
    for (int i = 0; i < args.size() && !"--".equals(args.get(i)); i++) {
      String arg = args.get(i);
      if ((GlobalCliOptions.VERBOSE_LONG_ARG.equals(arg)
              || GlobalCliOptions.VERBOSE_SHORT_ARG.equals(arg))
          && i < args.size() - 1) {
        String nextArg = args.get(i + 1);
        int verbosityLevel = Integer.parseInt(nextArg, /* radix */ 10);
        return getVerbosityForLevel(verbosityLevel);
      }
      Matcher matcher = VERBOSE_ARG_PATTERN.matcher(arg);
      if (matcher.matches()) {
        int verbosityLevel = Integer.parseInt(matcher.group(1), /* radix */ 10);
        return getVerbosityForLevel(verbosityLevel);
      }
    }
    return DEFAULT_VERBOSITY;
  }

  public static Verbosity getVerbosityForLevel(int verbosityLevel) {
    if (verbosityLevel >= 8) {
      return Verbosity.ALL;
    } else if (verbosityLevel >= 5) {
      return Verbosity.COMMANDS_AND_OUTPUT;
    } else if (verbosityLevel >= 4) {
      return Verbosity.COMMANDS_AND_SPECIAL_OUTPUT;
    } else if (verbosityLevel >= 3) {
      return Verbosity.COMMANDS;
    } else if (verbosityLevel >= 2) {
      return Verbosity.BINARY_OUTPUTS;
    } else if (verbosityLevel >= 1) {
      return Verbosity.STANDARD_INFORMATION;
    } else {
      return Verbosity.SILENT;
    }
  }
}
