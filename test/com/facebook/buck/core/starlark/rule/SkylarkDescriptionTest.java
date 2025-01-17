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
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.artifact.Artifact;
import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.impl.BuildPaths;
import com.facebook.buck.core.model.label.LabelSyntaxException;
import com.facebook.buck.core.rules.analysis.impl.RuleAnalysisContextImpl;
import com.facebook.buck.core.rules.providers.collect.ProviderInfoCollection;
import com.facebook.buck.core.rules.providers.lib.DefaultInfo;
import com.facebook.buck.core.rules.providers.lib.ImmutableDefaultInfo;
import com.facebook.buck.core.starlark.testutil.TestStarlarkThread;
import com.facebook.buck.event.DefaultBuckEventBus;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.rules.param.ParamName;
import com.facebook.buck.skylark.function.FakeSkylarkUserDefinedRuleFactory;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.util.timing.FakeClock;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.nio.file.Path;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkList;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class SkylarkDescriptionTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private BuildTarget target;
  private RuleAnalysisContextImpl context;
  private final SkylarkDescription description = new SkylarkDescription();

  @Before
  public void setUp() {
    this.target = BuildTargetFactory.newInstance("//foo:bar");
    this.context =
        new RuleAnalysisContextImpl(
            target,
            ImmutableMap.of(),
            TestProjectFilesystems.createProjectFilesystem(tmp.getRoot()),
            new DefaultBuckEventBus(FakeClock.doNotCare(), new BuildId("1234-5678")));
  }

  @Test
  public void returnsDefaultInfoContainingUsedArtifactsIfNothingReturnedFromImpl()
      throws LabelSyntaxException, EvalException {
    SkylarkUserDefinedRule rule =
        FakeSkylarkUserDefinedRuleFactory.createSimpleRuleFromCallable(
            ctx -> {
              try {
                Artifact f =
                    ctx.getActions()
                        .declareFile("baz.sh", TestStarlarkThread.dummyStarlarkThread());
                ctx.getActions()
                    .write(f, "content", false, TestStarlarkThread.dummyStarlarkThread());
              } catch (EvalException e) {
                throw new RuntimeException(e);
              }
              return Starlark.NONE;
            });

    SkylarkDescriptionArg args = new SkylarkDescriptionArg(rule);
    args.setPostCoercionValue(ParamName.bySnakeCase("name"), "a");
    args.setPostCoercionValue(ParamName.bySnakeCase("baz"), "");
    args.setPostCoercionValue(ParamName.bySnakeCase("labels"), ImmutableSortedSet.of());
    args.setPostCoercionValue(ParamName.bySnakeCase("licenses"), ImmutableSortedSet.of());
    args.build();
    ProviderInfoCollection infos = description.ruleImpl(context, target, args);
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    Path expectedShortPath =
        BuildPaths.getBaseDir(filesystem, target)
            .resolve("baz.sh")
            .toPath(filesystem.getFileSystem());

    DefaultInfo info = infos.get(DefaultInfo.PROVIDER).get();
    Artifact artifact = Iterables.getOnlyElement(info.defaultOutputs());

    assertTrue(info.namedOutputs().isEmpty());
    assertEquals(expectedShortPath.toString(), artifact.getShortPath());
  }

  @Test
  public void returnsDefaultInfoFromImplIfImplReturnsOne()
      throws LabelSyntaxException, EvalException {
    SkylarkUserDefinedRule rule =
        FakeSkylarkUserDefinedRuleFactory.createSimpleRuleFromCallable(
            ctx -> {
              try {
                Artifact f =
                    ctx.getActions()
                        .declareFile("baz1.sh", TestStarlarkThread.dummyStarlarkThread());
                ctx.getActions()
                    .write(f, "content", false, TestStarlarkThread.dummyStarlarkThread());
                Artifact g =
                    ctx.getActions()
                        .declareFile("baz2.sh", TestStarlarkThread.dummyStarlarkThread());
                ctx.getActions()
                    .write(g, "content", false, TestStarlarkThread.dummyStarlarkThread());
                return StarlarkList.immutableCopyOf(
                    ImmutableList.of(
                        new ImmutableDefaultInfo(
                            Dict.empty(), StarlarkList.immutableCopyOf(ImmutableList.of(f)))));
              } catch (EvalException e) {
                throw new RuntimeException(e);
              }
            });

    SkylarkDescriptionArg args = new SkylarkDescriptionArg(rule);
    args.setPostCoercionValue(ParamName.bySnakeCase("name"), "a");
    args.setPostCoercionValue(ParamName.bySnakeCase("baz"), "");
    args.setPostCoercionValue(ParamName.bySnakeCase("labels"), ImmutableSortedSet.of());
    args.setPostCoercionValue(ParamName.bySnakeCase("licenses"), ImmutableSortedSet.of());
    args.build();
    ProviderInfoCollection infos = description.ruleImpl(context, target, args);
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    Path expectedShortPath =
        BuildPaths.getBaseDir(filesystem, target)
            .resolve("baz1.sh")
            .toPath(filesystem.getFileSystem());

    DefaultInfo info = infos.get(DefaultInfo.PROVIDER).get();
    Artifact artifact = Iterables.getOnlyElement(info.defaultOutputs());

    assertTrue(info.namedOutputs().isEmpty());
    assertEquals(expectedShortPath.toString(), artifact.getShortPath());
  }

  @Test
  public void hasCorrectName() throws LabelSyntaxException, EvalException {
    SkylarkUserDefinedRule rule =
        FakeSkylarkUserDefinedRuleFactory.createSimpleRuleFromCallable(
            ctx -> {
              try {
                Artifact f =
                    ctx.getActions()
                        .declareFile("baz.sh", TestStarlarkThread.dummyStarlarkThread());
                ctx.getActions()
                    .write(f, "content", false, TestStarlarkThread.dummyStarlarkThread());
              } catch (EvalException e) {
                throw new RuntimeException(e);
              }
              return Starlark.NONE;
            });

    SkylarkDescriptionArg args = new SkylarkDescriptionArg(rule);
    args.setPostCoercionValue(ParamName.bySnakeCase("name"), "a");
    args.setPostCoercionValue(ParamName.bySnakeCase("baz"), "");
    args.setPostCoercionValue(ParamName.bySnakeCase("labels"), ImmutableSortedSet.of());
    args.setPostCoercionValue(ParamName.bySnakeCase("licenses"), ImmutableSortedSet.of());
    args.build();

    assertEquals("//foo:bar.bzl:some_rule", description.getRuleName(args));
  }
}
