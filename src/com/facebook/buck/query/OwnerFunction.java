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

package com.facebook.buck.query;

import com.facebook.buck.query.QueryEnvironment.Argument;
import com.facebook.buck.query.QueryEnvironment.ArgumentType;
import com.facebook.buck.query.QueryEnvironment.QueryFunction;
import com.google.common.collect.ImmutableList;
import java.util.Set;

/**
 * A "owner" query expression, which computes the rules that own the given files.
 *
 * <pre>expr ::= OWNER '(' WORD ')'</pre>
 */
public class OwnerFunction<NODE_TYPE> implements QueryFunction<NODE_TYPE> {

  private static final ImmutableList<ArgumentType> ARGUMENT_TYPES =
      ImmutableList.of(ArgumentType.WORD);

  public OwnerFunction() {}

  @Override
  public String getName() {
    return "owner";
  }

  @Override
  public int getMandatoryArguments() {
    return 1;
  }

  @Override
  public ImmutableList<ArgumentType> getArgumentTypes() {
    return ARGUMENT_TYPES;
  }

  @Override
  public Set<NODE_TYPE> eval(
      QueryEvaluator<NODE_TYPE> evaluator,
      QueryEnvironment<NODE_TYPE> env,
      ImmutableList<Argument<NODE_TYPE>> args)
      throws QueryException {
    // NOTE: If we ever decide to make this work with nested expressions, we need to handle
    //       CWD relative paths. (e.g. `owner(inputs(//foo:bar))` would not work properly from
    //       a subdirectory)
    return env.getFileOwners(ImmutableList.of(args.get(0).getWord()));
  }
}
