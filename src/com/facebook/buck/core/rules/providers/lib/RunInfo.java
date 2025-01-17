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

package com.facebook.buck.core.rules.providers.lib;

import com.facebook.buck.core.artifact.Artifact;
import com.facebook.buck.core.artifact.OutputArtifact;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.actions.lib.args.CommandLineArgs;
import com.facebook.buck.core.rules.actions.lib.args.CommandLineArgsFactory;
import com.facebook.buck.core.rules.providers.annotations.ImmutableInfo;
import com.facebook.buck.core.rules.providers.impl.BuiltInProvider;
import com.facebook.buck.core.rules.providers.impl.BuiltInProviderInfo;
import com.facebook.buck.core.starlark.compatible.BuckSkylarkTypes;
import com.facebook.buck.core.starlark.rule.args.CommandLineArgsBuilder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Printer;
import net.starlark.java.eval.StarlarkList;

/**
 * The standard {@link com.facebook.buck.core.rules.providers.Provider} that describes how to run a
 * given build rule's outputs.
 */
@ImmutableInfo(
    args = {"env", "args"},
    defaultSkylarkValues = {"{}", "[]"})
public abstract class RunInfo extends BuiltInProviderInfo<RunInfo> implements CommandLineArgs {

  public static final BuiltInProvider<RunInfo> PROVIDER =
      BuiltInProvider.of(ImmutableRunInfo.class);

  @Override
  public void repr(Printer printer) {
    printer.append("<command line arguments>");
  }

  @Override
  public boolean isImmutable() {
    /**
     * We already validate that the types added here are Immutable in {@link CommandLineArgsFactory}
     * there is no need to do further validation.
     *
     * <p>See also {@link AggregateCommandLineArgs}, {@link ListCommandLineArgs}, {@link
     * com.facebook.buck.core.rules.providers.lib.RunInfo}
     */
    return true;
  }

  /** @return any additional environment variables that should be used when executing */
  @AddToRuleKey
  public abstract ImmutableMap<String, String> env();

  /** @return the command line arguments to use when executing */
  @AddToRuleKey
  public abstract CommandLineArgs args();

  /**
   * Create an instance of RunInfo from skylark arguments.
   *
   * @param env environment variables to use when executing
   * @param args arguments used to execute this program. Must be one of {@link
   *     CommandLineArgsBuilder}, {@link CommandLineArgs} or {@link StarlarkList}.
   * @return An instance of {@link RunInfo} with immutable {@link #env()} and {@link #args()}
   * @throws EvalException the type passed in was incorrect
   */
  public static RunInfo instantiateFromSkylark(Dict<String, String> env, Object args)
      throws EvalException {
    Map<String, String> validatedEnv = Dict.cast(env, String.class, String.class, "environment");
    CommandLineArgs commandLineArgs;
    if (args instanceof CommandLineArgsBuilder) {
      commandLineArgs = ((CommandLineArgsBuilder) args).build();
    } else if (args instanceof CommandLineArgs) {
      commandLineArgs = (CommandLineArgs) args;
    } else if (args instanceof StarlarkList) {
      ImmutableList<Object> validatedArgs =
          BuckSkylarkTypes.toJavaList((StarlarkList<?>) args, Object.class, "args");
      commandLineArgs = CommandLineArgsFactory.from(validatedArgs);
    } else {
      throw new HumanReadableException(
          "%s must either be a list of arguments, or an args() object");
    }

    return new ImmutableRunInfo(validatedEnv, commandLineArgs);
  }

  @Override
  public ImmutableSortedMap<String, String> getEnvironmentVariables() {
    return ImmutableSortedMap.<String, String>naturalOrder()
        .putAll(env())
        .putAll(args().getEnvironmentVariables())
        .build();
  }

  @Override
  public Stream<ArgAndFormatString> getArgsAndFormatStrings() {
    return args().getArgsAndFormatStrings();
  }

  @Override
  public int getEstimatedArgsCount() {
    return args().getEstimatedArgsCount();
  }

  @Override
  public void visitInputsAndOutputs(Consumer<Artifact> inputs, Consumer<OutputArtifact> outputs) {
    args().visitInputsAndOutputs(inputs, outputs);
  }
}
