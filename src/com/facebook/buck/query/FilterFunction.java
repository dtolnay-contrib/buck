/*
 * Portions Copyright (c) Meta Platforms, Inc. and affiliates.
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

// Copyright 2014 Google Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.facebook.buck.query;

import com.facebook.buck.query.QueryEnvironment.Argument;
import com.facebook.buck.query.QueryEnvironment.ArgumentType;
import com.google.common.collect.ImmutableList;

/**
 * A filter(pattern, argument) expression, evaluates its argument and filters the resulting targets
 * by applying the given regular expression pattern to the targets' names.
 *
 * <pre>expr ::= FILTER '(' WORD ',' expr ')'</pre>
 */
public class FilterFunction<NODE_TYPE> extends RegexFilterFunction<NODE_TYPE> {

  private static final ImmutableList<ArgumentType> ARGUMENT_TYPES =
      ImmutableList.of(ArgumentType.WORD, ArgumentType.EXPRESSION);

  public FilterFunction() {}

  @Override
  public String getName() {
    return "filter";
  }

  @Override
  public int getMandatoryArguments() {
    return 2;
  }

  @Override
  public ImmutableList<ArgumentType> getArgumentTypes() {
    return ARGUMENT_TYPES;
  }

  @Override
  protected QueryExpression<NODE_TYPE> getExpressionToEval(
      ImmutableList<Argument<NODE_TYPE>> args) {
    return args.get(1).getExpression();
  }

  @Override
  protected String getPattern(ImmutableList<Argument<NODE_TYPE>> args) {
    return args.get(0).getWord();
  }

  @Override
  protected String getStringToFilter(
      QueryEnvironment<NODE_TYPE> env, ImmutableList<Argument<NODE_TYPE>> args, NODE_TYPE target) {
    return target.toString();
  }
}
