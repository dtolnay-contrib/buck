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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import com.facebook.buck.core.artifact.Artifact;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.parser.buildtargetparser.ParsingUnconfiguredBuildTargetViewFactory;
import com.facebook.buck.core.rules.analysis.RuleAnalysisContext;
import com.facebook.buck.core.rules.analysis.impl.FakeRuleAnalysisContextImpl;
import com.facebook.buck.core.rules.providers.collect.ProviderInfoCollection;
import com.facebook.buck.core.rules.providers.collect.impl.TestProviderInfoCollectionImpl;
import com.facebook.buck.core.starlark.rule.attr.Attribute;
import com.facebook.buck.core.starlark.rule.attr.PostCoercionTransform;
import com.facebook.buck.core.starlark.rule.attr.impl.StringAttribute;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystemFactory;
import com.facebook.buck.rules.coercer.BuildTargetTypeCoercer;
import com.facebook.buck.rules.coercer.TypeCoercer;
import com.facebook.buck.rules.coercer.UnconfiguredBuildTargetTypeCoercer;
import com.facebook.buck.rules.param.ParamName;
import com.facebook.buck.step.impl.TestActionExecutionRunner;
import com.facebook.buck.util.types.Pair;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Paths;
import net.starlark.java.eval.Printer;
import org.junit.Before;
import org.junit.Test;

public class SkylarkRuleContextAttrTest {

  private final StringAttribute placeholderStringAttr =
      StringAttribute.of("", "", true, ImmutableList.of());
  private TestActionExecutionRunner runner;

  static class TestAttribute extends Attribute<BuildTarget> {

    @Override
    public Object getPreCoercionDefaultValue() {
      return "//foo:bar";
    }

    @Override
    public String getDoc() {
      return "";
    }

    @Override
    public boolean getMandatory() {
      return false;
    }

    @Override
    public TypeCoercer<?, BuildTarget> getTypeCoercer() {
      return new BuildTargetTypeCoercer(
          new UnconfiguredBuildTargetTypeCoercer(new ParsingUnconfiguredBuildTargetViewFactory()));
    }

    @Override
    public void repr(Printer printer) {
      printer.append("<test_attr>");
    }

    @Override
    public PostCoercionTransform<RuleAnalysisContext, BuildTarget, Pair<Artifact, BuildTarget>>
        getPostCoercionTransform() {
      return (coercedValue, analysisContext) ->
          new Pair<>(
              analysisContext.actionRegistry().declareArtifact(Paths.get("out.txt")), coercedValue);
    }
  }

  @Before
  public void setUp() {
    runner =
        new TestActionExecutionRunner(
            new FakeProjectFilesystemFactory(),
            FakeProjectFilesystem.createJavaOnlyFilesystem(),
            BuildTargetFactory.newInstance("//some:rule"));
  }

  @Test
  public void getsValue() {
    SkylarkRuleContextAttr attr =
        SkylarkRuleContextAttr.of(
            "some_method",
            ImmutableMap.of(ParamName.bySnakeCase("foo"), "foo_value"),
            ImmutableMap.of(ParamName.bySnakeCase("foo"), placeholderStringAttr),
            new FakeRuleAnalysisContextImpl(ImmutableMap.of()));

    assertEquals("foo_value", attr.getField("foo"));
    assertNull(attr.getField("bar"));
  }

  @Test
  public void returnsAllFieldsInSortedOrder() {
    SkylarkRuleContextAttr attr =
        SkylarkRuleContextAttr.of(
            "some_method",
            ImmutableMap.of(
                ParamName.bySnakeCase("foo"),
                "foo_value",
                ParamName.bySnakeCase("bar"),
                "bar_value"),
            ImmutableMap.of(
                ParamName.bySnakeCase("foo"),
                placeholderStringAttr,
                ParamName.bySnakeCase("bar"),
                placeholderStringAttr),
            new FakeRuleAnalysisContextImpl(ImmutableMap.of()));

    assertEquals(ImmutableSet.of("bar", "foo"), attr.getFieldNames());
  }

  @Test
  public void performsPostCoercionTransformsOnFieldsIfRequested() {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    ProviderInfoCollection providerInfos = TestProviderInfoCollectionImpl.builder().build();
    TestAttribute attr = new TestAttribute();
    SkylarkRuleContextAttr ctxAttr =
        SkylarkRuleContextAttr.of(
            "some_method",
            ImmutableMap.of(
                ParamName.bySnakeCase("foo"), "foo_value", ParamName.bySnakeCase("bar"), target),
            ImmutableMap.of(
                ParamName.bySnakeCase("foo"),
                placeholderStringAttr,
                ParamName.bySnakeCase("bar"),
                attr),
            new FakeRuleAnalysisContextImpl(ImmutableMap.of(target, providerInfos)));

    assertEquals("foo_value", ctxAttr.getField("foo"));
    assertEquals(target, ((Pair) ctxAttr.getField("bar")).getSecond());
    Artifact createdArtifact = ((Artifact) ((Pair) ctxAttr.getField("bar")).getFirst());
    assertFalse(createdArtifact.isBound());
    assertEquals("out.txt", createdArtifact.getBasename());
  }
}
