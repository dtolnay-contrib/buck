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

package com.facebook.buck.features.d;

import static com.facebook.buck.features.d.DDescriptionUtils.SOURCE_LINK_TREE;

import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.description.arg.HasDeclaredDeps;
import com.facebook.buck.core.description.arg.HasTestTimeout;
import com.facebook.buck.core.description.attr.ImplicitDepsInferringDescription;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.core.rules.impl.SymlinkTree;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.facebook.buck.cxx.config.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.downwardapi.config.DownwardApiConfig;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.coercer.SourceSortedSet;
import com.facebook.buck.test.config.TestBuckConfig;
import com.facebook.buck.versions.VersionRoot;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import java.util.Optional;

public class DTestDescription
    implements DescriptionWithTargetGraph<DTestDescriptionArg>,
        ImplicitDepsInferringDescription<DTestDescription.AbstractDTestDescriptionArg>,
        VersionRoot<DTestDescriptionArg> {

  private final ToolchainProvider toolchainProvider;
  private final DBuckConfig dBuckConfig;
  private final CxxBuckConfig cxxBuckConfig;
  private final DownwardApiConfig downwardApiConfig;

  public DTestDescription(
      ToolchainProvider toolchainProvider,
      DBuckConfig dBuckConfig,
      CxxBuckConfig cxxBuckConfig,
      DownwardApiConfig downwardApiConfig) {
    this.toolchainProvider = toolchainProvider;
    this.dBuckConfig = dBuckConfig;
    this.cxxBuckConfig = cxxBuckConfig;
    this.downwardApiConfig = downwardApiConfig;
  }

  @Override
  public Class<DTestDescriptionArg> getConstructorArgType() {
    return DTestDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      DTestDescriptionArg args) {

    ActionGraphBuilder graphBuilder = context.getActionGraphBuilder();
    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();

    if (buildTarget.getFlavors().contains(SOURCE_LINK_TREE)) {
      return DDescriptionUtils.createSourceSymlinkTree(
          buildTarget, projectFilesystem, graphBuilder, args.getSrcs());
    }

    SymlinkTree sourceTree =
        (SymlinkTree) graphBuilder.requireRule(DDescriptionUtils.getSymlinkTreeTarget(buildTarget));

    CxxPlatform cxxPlatform =
        DDescriptionUtils.getCxxPlatform(
            graphBuilder, toolchainProvider, dBuckConfig, buildTarget.getTargetConfiguration());

    // Create a helper rule to build the test binary.
    // The rule needs its own target so that we can depend on it without creating cycles.
    BuildTarget binaryTarget =
        DDescriptionUtils.createBuildTargetForFile(
            buildTarget, "build-", buildTarget.getFullyQualifiedName(), cxxPlatform);

    BuildRule binaryRule =
        DDescriptionUtils.createNativeLinkable(
            context.getCellPathResolver(),
            binaryTarget,
            projectFilesystem,
            params,
            graphBuilder,
            cxxPlatform,
            dBuckConfig,
            cxxBuckConfig,
            downwardApiConfig,
            ImmutableList.of("-unittest"),
            args.getSrcs(),
            args.getLinkerFlags(),
            ImmutableDIncludes.ofImpl(
                sourceTree.getSourcePathToOutput(), args.getSrcs().getPaths()));
    graphBuilder.addToIndex(binaryRule);

    return new DTest(
        buildTarget,
        projectFilesystem,
        params.copyAppendingExtraDeps(ImmutableList.of(binaryRule)),
        binaryRule,
        args.getContacts(),
        args.getLabels(),
        args.getTestRuleTimeoutMs()
            .map(Optional::of)
            .orElse(
                dBuckConfig
                    .getDelegate()
                    .getView(TestBuckConfig.class)
                    .getDefaultTestRuleTimeoutMs()),
        downwardApiConfig.isEnabledForTests());
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellNameResolver cellRoots,
      AbstractDTestDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    extraDepsBuilder.addAll(
        DDescriptionUtils.getUnresolvedCxxPlatform(
                toolchainProvider, buildTarget.getTargetConfiguration(), dBuckConfig)
            .getParseTimeDeps(buildTarget.getTargetConfiguration()));
  }

  @RuleArg
  interface AbstractDTestDescriptionArg extends BuildRuleArg, HasDeclaredDeps, HasTestTimeout {
    SourceSortedSet getSrcs();

    ImmutableList<String> getLinkerFlags();
  }
}
