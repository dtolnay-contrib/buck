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

package com.facebook.buck.core.starlark.rule;

import com.facebook.buck.core.exceptions.BuckUncheckedExecutionException;
import com.facebook.buck.core.rules.analysis.RuleAnalysisContext;
import com.facebook.buck.core.rules.providers.collect.ProviderInfoCollection;
import com.facebook.buck.core.starlark.rule.attr.Attribute;
import com.facebook.buck.rules.param.ParamName;
import com.google.common.base.Preconditions;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import javax.annotation.Nullable;
import net.starlark.java.eval.Printer;
import net.starlark.java.eval.Structure;

/**
 * The struct representing the 'attr' property of the 'ctx' struct passed to a user defined rule's
 * implementation function
 */
public class SkylarkRuleContextAttr extends Structure {

  private final String methodName;
  private final Map<String, Attribute<?>> attributes;
  private final LoadingCache<String, Object> postCoercionTransformValues;

  /**
   * @param methodName the name of the implementation method in the extension file
   * @param methodParameters a mapping of field names to values for a given rule
   * @param attributes a mapping of field names to attributes for a given rule
   *     com.facebook.buck.core.artifact.Artifact}s
   * @param context mapping of build targets to {@link ProviderInfoCollection} for rules that this
   *     rule
   */
  private SkylarkRuleContextAttr(
      String methodName,
      Map<ParamName, Object> methodParameters,
      Map<ParamName, Attribute<?>> attributes,
      RuleAnalysisContext context) {
    this.methodName = methodName;
    this.attributes =
        attributes.entrySet().stream()
            .collect(
                ImmutableMap.toImmutableMap(e -> e.getKey().getSnakeCase(), Map.Entry::getValue));
    ImmutableMap<String, Object> methodParametersByString =
        methodParameters.entrySet().stream()
            .collect(
                ImmutableMap.toImmutableMap(e -> e.getKey().getSnakeCase(), Map.Entry::getValue));
    this.postCoercionTransformValues =
        CacheBuilder.newBuilder()
            .build(
                new CacheLoader<String, Object>() {
                  @Override
                  public Object load(String paramName) {
                    Object coercedValue =
                        Preconditions.checkNotNull(methodParametersByString.get(paramName));
                    return Preconditions.checkNotNull(
                            SkylarkRuleContextAttr.this.attributes.get(paramName))
                        .getPostCoercionTransform()
                        .postCoercionTransformUnchecked(coercedValue, context);
                  }
                });
  }

  static SkylarkRuleContextAttr of(
      String methodName,
      Map<ParamName, Object> methodParameters,
      Map<ParamName, Attribute<?>> attributes,
      RuleAnalysisContext context) {
    Preconditions.checkState(
        attributes.keySet().equals(methodParameters.keySet()),
        "Coerced attr values should have the same keys as rule attrs");

    return new SkylarkRuleContextAttr(methodName, methodParameters, attributes, context);
  }

  @Nullable
  @Override
  public Object getField(String name) {
    if (!attributes.containsKey(name)) {
      // loading cache can't store null, so we exit early
      return null;
    }
    try {
      return postCoercionTransformValues.get(name);
    } catch (ExecutionException e) {
      throw new BuckUncheckedExecutionException(e);
    }
  }

  @Override
  public ImmutableCollection<String> getFieldNames() {
    return ImmutableSortedSet.copyOf(attributes.keySet());
  }

  @Nullable
  @Override
  public String getErrorMessageForUnknownField(String field) {
    return String.format("Parameter %s not defined for method %s", field, methodName);
  }

  @Override
  public void repr(Printer printer) {
    printer.append("<attr>");
  }
}
