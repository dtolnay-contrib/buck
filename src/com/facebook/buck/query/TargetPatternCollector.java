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

import java.util.Collection;

public class TargetPatternCollector<NODE_TYPE> implements QueryExpression.Visitor<NODE_TYPE> {
  private final Collection<String> literals;

  TargetPatternCollector(Collection<String> literals) {
    this.literals = literals;
  }

  @Override
  public QueryExpression.VisitResult visit(QueryExpression<NODE_TYPE> exp) {
    if (exp instanceof TargetLiteral) {
      literals.add(((TargetLiteral<NODE_TYPE>) exp).getPattern());
    }

    return QueryExpression.VisitResult.CONTINUE;
  }
}
