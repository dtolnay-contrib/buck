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

package com.facebook.buck.features.ocaml;

import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.description.arg.HasDeclaredDeps;
import com.facebook.buck.core.description.attr.ImplicitDepsInferringDescription;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.Flavored;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.facebook.buck.cxx.CxxDeps;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableInput;
import com.facebook.buck.downwardapi.config.DownwardApiConfig;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.coercer.SourceSet;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.versions.VersionPropagator;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.nio.file.Path;
import java.util.Optional;
import org.immutables.value.Value;

public class OcamlLibraryDescription
    implements DescriptionWithTargetGraph<OcamlLibraryDescriptionArg>,
        ImplicitDepsInferringDescription<
            OcamlLibraryDescription.AbstractOcamlLibraryDescriptionArg>,
        VersionPropagator<OcamlLibraryDescriptionArg>,
        Flavored {

  private final DownwardApiConfig downwardApiConfig;
  private final ToolchainProvider toolchainProvider;

  public OcamlLibraryDescription(
      DownwardApiConfig downwardApiConfig, ToolchainProvider toolchainProvider) {
    this.downwardApiConfig = downwardApiConfig;
    this.toolchainProvider = toolchainProvider;
  }

  @Override
  public Class<OcamlLibraryDescriptionArg> getConstructorArgType() {
    return OcamlLibraryDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      OcamlLibraryDescriptionArg args) {

    CxxDeps allDeps =
        CxxDeps.builder().addDeps(args.getDeps()).addPlatformDeps(args.getPlatformDeps()).build();

    OcamlToolchain ocamlToolchain =
        toolchainProvider.getByName(
            OcamlToolchain.DEFAULT_NAME,
            buildTarget.getTargetConfiguration(),
            OcamlToolchain.class);
    FlavorDomain<UnresolvedOcamlPlatform> ocamlPlatforms = ocamlToolchain.getOcamlPlatforms();
    Optional<OcamlPlatform> ocamlPlatform =
        ocamlPlatforms
            .getValue(buildTarget)
            .map(
                platform ->
                    platform.resolve(
                        context.getActionGraphBuilder(), buildTarget.getTargetConfiguration()));
    if (ocamlPlatform.isPresent()) {
      ImmutableList<SourcePath> srcs =
          args.getSrcs().isPresent() ? args.getSrcs().get().getPaths() : ImmutableList.of();

      ImmutableList<Arg> flags =
          OcamlRuleBuilder.getFlags(
              buildTarget,
              context.getCellPathResolver(),
              context.getActionGraphBuilder(),
              ocamlPlatform.get(),
              ImmutableList.copyOf(
                  Iterables.concat(
                      args.getCompilerFlags(),
                      Iterables.concat(
                          args.getPlatformCompilerFlags()
                              .getMatchingValues(ocamlPlatform.get().getFlavor().toString())))),
              args.getWarningsFlags());

      ImmutableList<Arg> ocamldepFlags =
          OcamlDescriptionEnhancer.toStringWithMacrosArgs(
              buildTarget,
              context.getCellPathResolver(),
              context.getActionGraphBuilder(),
              args.getOcamldepFlags());

      BuildTarget compileBuildTarget = OcamlRuleBuilder.createStaticLibraryBuildTarget(buildTarget);

      if (OcamlRuleBuilder.shouldUseFineGrainedRules(context.getActionGraphBuilder(), srcs)) {
        OcamlGeneratedBuildRules result =
            OcamlRuleBuilder.createFineGrainedBuildRules(
                buildTarget,
                ocamlPlatform.get(),
                compileBuildTarget,
                context.getProjectFilesystem(),
                params,
                context.getActionGraphBuilder(),
                allDeps.get(context.getActionGraphBuilder(), ocamlPlatform.get().getCxxPlatform()),
                srcs,
                /* isLibrary */ true,
                args.getBytecodeOnly(),
                flags,
                ocamldepFlags,
                !args.getBytecodeOnly() && args.getNativePlugin(),
                downwardApiConfig.isEnabledForOCaml());
        return new OcamlStaticLibrary(
            buildTarget,
            context.getProjectFilesystem(),
            params,
            OcamlDescriptionEnhancer.toStringWithMacrosArgs(
                buildTarget,
                context.getCellPathResolver(),
                context.getActionGraphBuilder(),
                args.getLinkerFlags()),
            result.getObjectFiles(),
            result.getOcamlContext(),
            result.getRules().get(0),
            result.getNativeCompileDeps(),
            result.getBytecodeCompileDeps(),
            ImmutableSortedSet.<BuildRule>naturalOrder()
                .add(result.getBytecodeLink())
                .addAll(
                    context.getActionGraphBuilder().filterBuildRuleInputs(result.getObjectFiles()))
                .build(),
            result.getRules().stream()
                .map(BuildRule::getBuildTarget)
                .collect(ImmutableList.toImmutableList()));

      } else {
        OcamlBuild ocamlLibraryBuild =
            OcamlRuleBuilder.createBulkCompileRule(
                buildTarget,
                ocamlPlatform.get(),
                compileBuildTarget,
                context.getProjectFilesystem(),
                params,
                context.getActionGraphBuilder(),
                allDeps.get(context.getActionGraphBuilder(), ocamlPlatform.get().getCxxPlatform()),
                srcs,
                /* isLibrary */ true,
                args.getBytecodeOnly(),
                flags,
                ocamldepFlags,
                downwardApiConfig.isEnabledForOCaml());
        return new OcamlStaticLibrary(
            buildTarget,
            context.getProjectFilesystem(),
            params,
            OcamlDescriptionEnhancer.toStringWithMacrosArgs(
                buildTarget,
                context.getCellPathResolver(),
                context.getActionGraphBuilder(),
                args.getLinkerFlags()),
            srcs.stream()
                .map(
                    sourcePath ->
                        context
                            .getActionGraphBuilder()
                            .getSourcePathResolver()
                            .getAbsolutePath(sourcePath)
                            .getPath())
                .filter(OcamlUtil.ext(OcamlCompilables.OCAML_C))
                .map(ocamlLibraryBuild.getOcamlContext()::getCOutput)
                .map(input -> ExplicitBuildTargetSourcePath.of(compileBuildTarget, input))
                .collect(ImmutableList.toImmutableList()),
            ocamlLibraryBuild.getOcamlContext(),
            ocamlLibraryBuild,
            ImmutableSortedSet.of(ocamlLibraryBuild),
            ImmutableSortedSet.of(ocamlLibraryBuild),
            ImmutableSortedSet.of(ocamlLibraryBuild),
            ImmutableList.of(ocamlLibraryBuild.getBuildTarget()));
      }
    }

    // Platform-agnostic wrapper for Ocaml library rules.  Ideally, the inner library rules, which
    // are created on-demand for the given passed in platform would use a different rule type or,
    // better yet, be non-build-rule types provided by metadata.
    return new OcamlLibrary(buildTarget, context.getProjectFilesystem(), params) {

      private OcamlLibrary getWrapped(OcamlPlatform platform) {
        return (OcamlLibrary)
            context
                .getActionGraphBuilder()
                .requireRule(getBuildTarget().withAppendedFlavors(platform.getFlavor()));
      }

      @Override
      public Path getIncludeLibDir(OcamlPlatform platform) {
        return getWrapped(platform).getIncludeLibDir(platform);
      }

      @Override
      public Iterable<String> getBytecodeIncludeDirs(OcamlPlatform platform) {
        return getWrapped(platform).getBytecodeIncludeDirs(platform);
      }

      @Override
      public ImmutableSortedSet<BuildRule> getNativeCompileDeps(OcamlPlatform platform) {
        return getWrapped(platform).getNativeCompileDeps(platform);
      }

      @Override
      public ImmutableSortedSet<BuildRule> getBytecodeCompileDeps(OcamlPlatform platform) {
        return getWrapped(platform).getBytecodeCompileDeps(platform);
      }

      @Override
      public ImmutableSortedSet<BuildRule> getBytecodeLinkDeps(OcamlPlatform platform) {
        return getWrapped(platform).getBytecodeLinkDeps(platform);
      }

      @Override
      public NativeLinkableInput getNativeLinkableInput(OcamlPlatform platform) {
        return getWrapped(platform).getNativeLinkableInput(platform);
      }

      @Override
      public NativeLinkableInput getBytecodeLinkableInput(OcamlPlatform platform) {
        return getWrapped(platform).getBytecodeLinkableInput(platform);
      }

      @Override
      public Iterable<BuildRule> getOcamlLibraryDeps(
          BuildRuleResolver buildRuleResolver, OcamlPlatform platform) {
        return allDeps.get(buildRuleResolver, platform.getCxxPlatform());
      }
    };
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellNameResolver cellRoots,
      AbstractOcamlLibraryDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    for (UnresolvedOcamlPlatform platform :
        toolchainProvider
            .getByName(
                OcamlToolchain.DEFAULT_NAME,
                buildTarget.getTargetConfiguration(),
                OcamlToolchain.class)
            .getOcamlPlatforms()
            .getValues()) {
      targetGraphOnlyDepsBuilder.addAll(
          platform.getParseTimeDeps(buildTarget.getTargetConfiguration()));
    }
  }

  @Override
  public boolean hasFlavors(
      ImmutableSet<Flavor> flavors, TargetConfiguration toolchainTargetConfiguration) {
    return flavors.equals(
        ImmutableSet.of(
            toolchainProvider
                .getByName(
                    OcamlToolchain.DEFAULT_NAME, toolchainTargetConfiguration, OcamlToolchain.class)
                .getDefaultOcamlPlatform()
                .getFlavor()));
  }

  @RuleArg
  interface AbstractOcamlLibraryDescriptionArg extends BuildRuleArg, HasDeclaredDeps {
    Optional<SourceSet> getSrcs();

    ImmutableList<StringWithMacros> getCompilerFlags();

    @Value.Default
    default PatternMatchedCollection<ImmutableList<StringWithMacros>> getPlatformCompilerFlags() {
      return PatternMatchedCollection.of();
    }

    ImmutableList<StringWithMacros> getOcamldepFlags();

    ImmutableList<StringWithMacros> getLinkerFlags();

    Optional<String> getWarningsFlags();

    @Value.Default
    default boolean getBytecodeOnly() {
      return false;
    }

    @Value.Default
    default boolean getNativePlugin() {
      return false;
    }

    @Value.Default
    default PatternMatchedCollection<ImmutableSortedSet<BuildTarget>> getPlatformDeps() {
      return PatternMatchedCollection.of();
    }
  }
}
