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

package com.facebook.buck.rules.args;

import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * An abstraction for modeling the arguments that contribute to a command run by a {@link
 * BuildRule}, and also carry information for computing a rule key.
 */
public interface Arg extends AddsToRuleKey {

  static Optional<String> flattenToSpaceSeparatedString(
      Optional<Arg> arg, SourcePathResolverAdapter pathResolver) {
    return arg.map((input1) -> stringifyList(input1, pathResolver))
        .map(input -> Joiner.on(' ').join(input));
  }

  /**
   * Feed the contents of the Arg to the supplied consumer. This call may feed any number of
   * elements (including zero) into the consumer. This is only ever safe to call when the rule is
   * running, as it may do things like resolving source paths.
   */
  void appendToCommandLine(Consumer<String> consumer, SourcePathResolverAdapter pathResolver);

  /**
   * Resolve this argument to single string, fail if this arg corresponds to none or more than one
   * argument.
   */
  default String singleCommandLineArg(SourcePathResolverAdapter pathResolverAdapter) {
    String[] args = new String[1];
    appendToCommandLine(
        arg -> {
          Preconditions.checkState(
              args[0] == null, "arg must resolve to exactly one argument: %s", this);
          args[0] = arg;
        },
        pathResolverAdapter);
    Preconditions.checkState(args[0] != null, "arg must resolve to exactly one argument: %s", this);
    return args[0];
  }

  /** @return a {@link String} representation suitable to use for debugging. */
  @Override
  String toString();

  @Override
  boolean equals(Object other);

  @Override
  int hashCode();

  static ImmutableList<String> stringifyList(Arg input, SourcePathResolverAdapter pathResolver) {
    ImmutableList.Builder<String> builder = ImmutableList.builder();
    input.appendToCommandLine(builder::add, pathResolver);
    return builder.build();
  }

  static ImmutableList<String> stringify(
      Iterable<? extends Arg> args, SourcePathResolverAdapter pathResolver) {
    ImmutableList.Builder<String> builder = ImmutableList.builder();
    for (Arg arg : args) {
      // TODO(cjhopman): This should probably use the single-Arg stringify below such that each Arg
      // expands to one entry in the final list.
      arg.appendToCommandLine(builder::add, pathResolver);
    }
    return builder.build();
  }

  /** Converts an Arg to a String by concatenating all the command-line appended strings. */
  static String stringify(Arg arg, SourcePathResolverAdapter pathResolver) {
    StringBuilder builder = new StringBuilder();
    arg.appendToCommandLine(builder::append, pathResolver);
    return builder.toString();
  }

  static <K> ImmutableMap<K, String> stringify(
      ImmutableMap<K, ? extends Arg> argMap, SourcePathResolverAdapter pathResolver) {
    ImmutableMap.Builder<K, String> stringMap = ImmutableMap.builder();
    for (Map.Entry<K, ? extends Arg> ent : argMap.entrySet()) {
      ImmutableList.Builder<String> builder = ImmutableList.builder();
      ent.getValue().appendToCommandLine(builder::add, pathResolver);
      stringMap.put(ent.getKey(), Joiner.on(" ").join(builder.build()));
    }
    return stringMap.build();
  }
}
