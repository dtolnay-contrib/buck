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

package com.facebook.buck.core.starlark.rule.attr.impl;

import com.facebook.buck.core.rules.analysis.RuleAnalysisContext;
import com.facebook.buck.core.starlark.rule.attr.Attribute;
import com.facebook.buck.core.starlark.rule.attr.PostCoercionTransform;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.rules.coercer.CoerceFailedException;
import com.facebook.buck.rules.coercer.TypeCoercer;
import com.google.common.collect.ImmutableList;
import com.google.common.reflect.TypeToken;
import net.starlark.java.eval.Printer;
import net.starlark.java.eval.StarlarkInt;
import net.starlark.java.eval.StarlarkList;

/** Class that represents a list of integers */
@BuckStyleValue
public abstract class IntListAttribute extends Attribute<ImmutableList<Integer>> {

  private static final TypeCoercer<?, ImmutableList<Integer>> coercer =
      TypeCoercerFactoryForStarlark.typeCoercerForType(new TypeToken<ImmutableList<Integer>>() {});

  @Override
  public abstract ImmutableList<Integer> getPreCoercionDefaultValue();

  @Override
  public abstract String getDoc();

  @Override
  public abstract boolean getMandatory();

  /** Whether or not the list can be empty */
  public abstract boolean getAllowEmpty();

  @Override
  public void repr(Printer printer) {
    printer.append("<attr.int_list>");
  }

  @Override
  public TypeCoercer<?, ImmutableList<Integer>> getTypeCoercer() {
    return coercer;
  }

  @Override
  public void validateCoercedValue(ImmutableList<Integer> paths) throws CoerceFailedException {
    if (!getAllowEmpty() && paths.isEmpty()) {
      throw new CoerceFailedException("List of ints may not be empty");
    }
  }

  @Override
  public PostCoercionTransform<
          RuleAnalysisContext, ImmutableList<Integer>, StarlarkList<StarlarkInt>>
      getPostCoercionTransform() {
    return (coercedValue, analysisContext) -> postCoercionTransform(coercedValue);
  }

  private StarlarkList<StarlarkInt> postCoercionTransform(ImmutableList<Integer> coercedValue) {
    return StarlarkList.immutableCopyOf(
        coercedValue.stream().map(StarlarkInt::of).collect(ImmutableList.toImmutableList()));
  }

  public static IntListAttribute of(
      ImmutableList<Integer> preCoercionDefaultValue,
      String doc,
      boolean mandatory,
      boolean allowEmpty) {
    return ImmutableIntListAttribute.ofImpl(preCoercionDefaultValue, doc, mandatory, allowEmpty);
  }
}
