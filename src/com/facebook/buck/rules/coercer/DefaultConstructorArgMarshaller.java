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

package com.facebook.buck.rules.coercer;

import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.description.arg.ConstructorArg;
import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.TargetConfigurationResolver;
import com.facebook.buck.core.model.TargetConfigurationTransformer;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.model.platform.TargetPlatformResolver;
import com.facebook.buck.core.rules.config.ConfigurationRuleArg;
import com.facebook.buck.core.select.CompatibleWithUtil;
import com.facebook.buck.core.select.LabelledAnySelectable;
import com.facebook.buck.core.select.SelectableConfigurationContext;
import com.facebook.buck.core.select.Selector;
import com.facebook.buck.core.select.SelectorList;
import com.facebook.buck.core.select.SelectorListResolved;
import com.facebook.buck.core.select.SelectorListResolver;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.param.ParamName;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.util.Map;
import javax.annotation.Nullable;

public class DefaultConstructorArgMarshaller implements ConstructorArgMarshaller {

  public DefaultConstructorArgMarshaller() {}

  private void collectDeclaredDeps(
      CellNameResolver cellNameResolver,
      @Nullable ParamInfo<?> deps,
      ImmutableSet.Builder<BuildTarget> declaredDeps,
      Object dto) {
    if (deps != null && deps.isDep()) {
      deps.traverse(
          cellNameResolver,
          object -> {
            if (!(object instanceof BuildTarget)) {
              return;
            }
            declaredDeps.add((BuildTarget) object);
          },
          dto);
    }
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T extends ConstructorArg> T populate(
      CellNameResolver cellNameResolver,
      ProjectFilesystem filesystem,
      SelectorListResolver selectorListResolver,
      SelectableConfigurationContext configurationContext,
      TargetConfigurationTransformer targetConfigurationTransformer,
      TargetPlatformResolver platformResolver,
      BuildTarget buildTarget,
      TargetConfigurationResolver hostConfigurationResolver,
      DependencyStack dependencyStack,
      DataTransferObjectDescriptor<T> constructorArgDescriptor,
      ImmutableSet.Builder<BuildTarget> declaredDeps,
      ImmutableSet.Builder<BuildTarget> configurationDeps,
      Map<ParamName, ?> attributes,
      LabelledAnySelectable compatibleWith) {

    ParamsInfo allParamInfo = constructorArgDescriptor.getParamsInfo();

    boolean isConfigurationRule =
        ConfigurationRuleArg.class.isAssignableFrom(constructorArgDescriptor.objectClass());

    Object builder = constructorArgDescriptor.getBuilderFactory().get();
    for (ParamInfo<?> info : allParamInfo.getParamInfosSorted()) {
      try {
        Object attribute = attributes.get(info.getName());
        if (attribute == null) {
          /**
           * For any implicit attributes that were missing, grab their default values from the
           * parameter map. The two places that this can happen are:
           *
           * <p>- The parser omitted the value because it was 'None'.
           *
           * <p>- The value is '_' prefixed. As that value is defined at rule definition time and
           * not unique for each target, we do not serialize it in the RawTargetNode, and instead
           * use the single in-memory value.
           */
          Object implicitPreCoercionValue = info.getImplicitPreCoercionValue();
          if (implicitPreCoercionValue != null) {
            attribute =
                info.getTypeCoercer()
                    .coerceToUnconfigured(
                        cellNameResolver,
                        filesystem,
                        buildTarget.getCellRelativeBasePath().getPath(),
                        implicitPreCoercionValue);
          }
          if (attribute == null) {
            continue;
          }
        }
        Object attributeValue;

        TargetConfiguration paramTargetConfiguration =
            info.execConfiguration()
                ? hostConfigurationResolver.getTargetConfiguration(
                    buildTarget.getUnconfiguredBuildTarget())
                : buildTarget.getTargetConfiguration();

        if (info.splitConfiguration()
            && targetConfigurationTransformer.needsTransformation(
                paramTargetConfiguration, dependencyStack)) {
          Preconditions.checkState(
              info.getTypeCoercer().supportsConcatenation(),
              "coercer must support concatenation to do split configuration: " + info.getName());
          attributeValue =
              createAttributeWithConfigurationTransformation(
                  cellNameResolver,
                  filesystem,
                  selectorListResolver,
                  targetConfigurationTransformer,
                  configurationContext,
                  platformResolver,
                  buildTarget,
                  hostConfigurationResolver,
                  dependencyStack,
                  paramTargetConfiguration,
                  configurationDeps,
                  (ParamInfo<Object>) info,
                  (TypeCoercer<Object, Object>) info.getTypeCoercer(),
                  isConfigurationRule,
                  attribute,
                  compatibleWith);
        } else {
          attributeValue =
              createAttribute(
                  cellNameResolver,
                  filesystem,
                  selectorListResolver,
                  configurationContext,
                  buildTarget,
                  dependencyStack,
                  paramTargetConfiguration,
                  hostConfigurationResolver,
                  configurationDeps,
                  (ParamInfo<Object>) info,
                  (TypeCoercer<Object, Object>) info.getTypeCoercer(),
                  isConfigurationRule,
                  attribute,
                  compatibleWith);
        }
        if (attributeValue != null) {
          info.setCoercedValue(builder, attributeValue);
        }
      } catch (CoerceFailedException e) {
        throw e.withAttrResolutionContext(
            info.getName(), buildTarget.toStringWithConfiguration(), dependencyStack);
      }
    }
    T dto = constructorArgDescriptor.build(builder, buildTarget);
    collectDeclaredDeps(
        cellNameResolver, allParamInfo.getByStarlarkName("deps"), declaredDeps, dto);
    return dto;
  }

  @Nullable
  private <U, T> T createAttributeWithConfigurationTransformation(
      CellNameResolver cellNameResolver,
      ProjectFilesystem filesystem,
      SelectorListResolver selectorListResolver,
      TargetConfigurationTransformer targetConfigurationTransformer,
      SelectableConfigurationContext configurationContext,
      TargetPlatformResolver platformResolver,
      BuildTarget buildTarget,
      TargetConfigurationResolver hostConfigurationResolver,
      DependencyStack dependencyStack,
      TargetConfiguration targetConfiguration,
      ImmutableSet.Builder<BuildTarget> configurationDeps,
      ParamInfo<T> info,
      TypeCoercer<U, T> coercer,
      boolean isConfigurationRule,
      Object attribute,
      LabelledAnySelectable compatibleWith)
      throws CoerceFailedException {
    ImmutableList.Builder<T> valuesForConcatenation = ImmutableList.builder();
    for (TargetConfiguration nestedTargetConfiguration :
        targetConfigurationTransformer.transform(targetConfiguration, dependencyStack)) {
      T configuredAttributeValue =
          createAttribute(
              cellNameResolver,
              filesystem,
              selectorListResolver,
              configurationContext.withPlatform(
                  platformResolver.getTargetPlatform(
                      nestedTargetConfiguration, dependencyStack.child(nestedTargetConfiguration))),
              buildTarget.getUnconfiguredBuildTarget().configure(nestedTargetConfiguration),
              dependencyStack,
              nestedTargetConfiguration,
              hostConfigurationResolver,
              configurationDeps,
              info,
              coercer,
              isConfigurationRule,
              attribute,
              compatibleWith);
      if (configuredAttributeValue != null) {
        valuesForConcatenation.add(configuredAttributeValue);
      }
    }
    return coercer.concat(valuesForConcatenation.build());
  }

  @Nullable
  @SuppressWarnings("unchecked")
  private <U, T> T createAttribute(
      CellNameResolver cellNameResolver,
      ProjectFilesystem filesystem,
      SelectorListResolver selectorListResolver,
      SelectableConfigurationContext configurationContext,
      BuildTarget buildTarget,
      DependencyStack dependencyStack,
      TargetConfiguration targetConfiguration,
      TargetConfigurationResolver hostConfigurationResolver,
      ImmutableSet.Builder<BuildTarget> configurationDeps,
      ParamInfo<T> info,
      TypeCoercer<U, T> coercer,
      boolean isConfigurationRule,
      Object attribute,
      LabelledAnySelectable compatibleWith)
      throws CoerceFailedException {
    if (isConfigurationRule) {
      if (info.isConfigurable()) {
        throw new IllegalStateException("configurable param in configuration rule");
      }
    }

    // When an attribute value contains an instance of {@link ListWithSelects} it's coerced by a
    // coercer for {@link SelectorList}.
    // The reason why we cannot use coercer from {@code argumentInfo} because {@link
    // ListWithSelects} is not generic class, but an instance contains all necessary information
    // to coerce the value into an instance of {@link SelectorList} which is a generic class.
    if (attribute instanceof SelectorList<?>) {
      if (!info.isConfigurable()) {
        throw new HumanReadableException(
            "%s: attribute '%s' cannot be configured using select", buildTarget, info.getName());
      }

      SelectorList<T> attributeWithSelectableValue =
          ((SelectorList<U>) attribute)
              .mapValuesThrowing(
                  v ->
                      coerce(
                          cellNameResolver,
                          filesystem,
                          buildTarget,
                          (info.execConfiguration() && (v instanceof UnconfiguredBuildTarget))
                              ? hostConfigurationResolver.getTargetConfiguration(
                                  (UnconfiguredBuildTarget) v)
                              : targetConfiguration,
                          hostConfigurationResolver,
                          info,
                          coercer,
                          v));
      return configureAttributeValue(
          configurationContext,
          selectorListResolver,
          buildTarget,
          dependencyStack,
          configurationDeps,
          info,
          attributeWithSelectableValue,
          compatibleWith);
    } else {
      return coerce(
          cellNameResolver,
          filesystem,
          buildTarget,
          (info.execConfiguration() && (attribute instanceof UnconfiguredBuildTarget))
              ? hostConfigurationResolver.getTargetConfiguration(
                  (UnconfiguredBuildTarget) attribute)
              : targetConfiguration,
          hostConfigurationResolver,
          info,
          coercer,
          (U) attribute);
    }
  }

  private <U, T> T coerce(
      CellNameResolver cellNameResolver,
      ProjectFilesystem filesystem,
      BuildTarget buildTarget,
      TargetConfiguration targetConfiguration,
      TargetConfigurationResolver hostConfigurationResolver,
      ParamInfo<T> paramInfo,
      TypeCoercer<U, T> coercer,
      U attribute)
      throws CoerceFailedException {
    try {
      return coercer.coerce(
          cellNameResolver,
          filesystem,
          buildTarget.getCellRelativeBasePath().getPath(),
          targetConfiguration,
          hostConfigurationResolver,
          attribute);
    } catch (ClassCastException e) {
      // diagnostics for tests, this should not happen in production
      throw new RuntimeException(
          String.format(
              "invorrect value in configured graph, "
                  + "param: %s, value type: %s, expected unconfigured type: %s",
              paramInfo.getName(), attribute.getClass().getName(), coercer.getUnconfiguredType()),
          e);
    }
  }

  @Nullable
  private <T> T configureAttributeValue(
      SelectableConfigurationContext configurationContext,
      SelectorListResolver selectorListResolver,
      BuildTarget buildTarget,
      DependencyStack dependencyStack,
      ImmutableSet.Builder<BuildTarget> configurationDeps,
      ParamInfo<T> paramInfo,
      SelectorList<T> selectorList,
      LabelledAnySelectable compatibleWith) {
    String attributeName = paramInfo.getName().getSnakeCase();
    SelectorListResolved<T> selectorListResolved;
    try {
      selectorListResolved =
          selectorListResolver.resolveSelectorList(selectorList, dependencyStack);

      CompatibleWithUtil.checkCompatibleWithIsSubsetOfSelectKeys(
          compatibleWith, selectorListResolved);
    } catch (HumanReadableException e) {
      throw new HumanReadableException(
          e,
          dependencyStack,
          "When checking configurable attribute \"%s\" in %s: %s",
          attributeName,
          buildTarget.getUnflavoredBuildTarget(),
          e.getMessage());
    }

    T value =
        selectorListResolved.eval(
            configurationContext,
            paramInfo.getTypeCoercer(),
            buildTarget,
            attributeName,
            dependencyStack);
    addSelectorListConfigurationDepsToBuilder(configurationDeps, selectorList);
    return value;
  }

  private <T> void addSelectorListConfigurationDepsToBuilder(
      ImmutableSet.Builder<BuildTarget> configurationDeps, SelectorList<T> selectorList) {
    for (Selector<T> selector : selectorList.getSelectors()) {
      selector.getConditions().keySet().stream()
          .filter(selectorKey -> !selectorKey.isReserved())
          .forEach(s -> s.getBuildTarget().ifPresent(configurationDeps::add));
    }
  }
}
