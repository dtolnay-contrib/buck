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

package com.facebook.buck.shell;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.description.arg.HasDeclaredDeps;
import com.facebook.buck.core.description.arg.HasTestTimeout;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.core.rules.common.BuildableSupport;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.facebook.buck.downwardapi.config.DownwardApiConfig;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.macros.ClasspathMacroExpander;
import com.facebook.buck.rules.macros.ExecutableMacro;
import com.facebook.buck.rules.macros.ExecutableMacroExpander;
import com.facebook.buck.rules.macros.ExecutableTargetMacro;
import com.facebook.buck.rules.macros.LocationMacroExpander;
import com.facebook.buck.rules.macros.Macro;
import com.facebook.buck.rules.macros.MacroExpander;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.rules.macros.StringWithMacrosConverter;
import com.facebook.buck.test.config.TestBuckConfig;
import com.facebook.buck.util.stream.RichStream;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;
import java.util.Optional;
import java.util.stream.Stream;
import org.immutables.value.Value;

public class ShTestDescription implements DescriptionWithTargetGraph<ShTestDescriptionArg> {

  private static final ImmutableList<MacroExpander<? extends Macro, ?>> MACRO_EXPANDERS =
      ImmutableList.of(
          LocationMacroExpander.INSTANCE,
          new ClasspathMacroExpander(),
          new ExecutableMacroExpander<>(ExecutableMacro.class),
          new ExecutableMacroExpander<>(ExecutableTargetMacro.class));

  private final BuckConfig buckConfig;
  private final DownwardApiConfig downwardApiConfig;

  public ShTestDescription(BuckConfig buckConfig) {
    this.buckConfig = buckConfig;
    this.downwardApiConfig = buckConfig.getView(DownwardApiConfig.class);
  }

  @Override
  public Class<ShTestDescriptionArg> getConstructorArgType() {
    return ShTestDescriptionArg.class;
  }

  @Override
  public ShTest createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      ShTestDescriptionArg args) {
    ActionGraphBuilder graphBuilder = context.getActionGraphBuilder();
    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();
    StringWithMacrosConverter macrosConverter =
        StringWithMacrosConverter.of(
            buildTarget,
            context.getCellPathResolver().getCellNameResolver(),
            graphBuilder,
            MACRO_EXPANDERS);
    ImmutableList<Arg> testArgs =
        Stream.concat(
                RichStream.from(args.getTest()).map(SourcePathArg::of),
                args.getArgs().stream().map(macrosConverter::convert))
            .collect(ImmutableList.toImmutableList());
    ImmutableMap<String, Arg> testEnv =
        ImmutableMap.copyOf(Maps.transformValues(args.getEnv(), macrosConverter::convert));
    return new ShTest(
        buildTarget,
        projectFilesystem,
        params.copyAppendingExtraDeps(
            () ->
                FluentIterable.from(testArgs)
                    .append(testEnv.values())
                    .transformAndConcat(
                        arg -> BuildableSupport.getDepsCollection(arg, graphBuilder))),
        testArgs,
        testEnv,
        args.getResources(),
        args.getTestRuleTimeoutMs()
            .map(Optional::of)
            .orElse(buckConfig.getView(TestBuckConfig.class).getDefaultTestRuleTimeoutMs()),
        args.getRunTestSeparately(),
        args.getLabels(),
        args.getType(),
        args.getContacts(),
        downwardApiConfig.isEnabledForTests());
  }

  @Override
  public boolean producesCacheableSubgraph() {
    return true;
  }

  @RuleArg
  interface AbstractShTestDescriptionArg extends BuildRuleArg, HasDeclaredDeps, HasTestTimeout {
    Optional<SourcePath> getTest();

    ImmutableList<StringWithMacros> getArgs();

    Optional<String> getType();

    @Value.Default
    default boolean getRunTestSeparately() {
      return false;
    }

    @Value.NaturalOrder
    ImmutableSortedSet<SourcePath> getResources();

    ImmutableMap<String, StringWithMacros> getEnv();

    /** The following are used for future test specs and ignored for now */
    Optional<ImmutableMap<String, String>> getRunEnv();

    Optional<ImmutableList<String>> getRunArgs();

    Optional<ImmutableMap<String, String>> getListEnv();

    Optional<ImmutableList<String>> getListArgs();
  }
}
