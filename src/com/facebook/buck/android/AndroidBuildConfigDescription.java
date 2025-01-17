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

package com.facebook.buck.android;

import com.facebook.buck.android.build_config.BuildConfigFields;
import com.facebook.buck.command.config.BuildBuckConfig;
import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.description.attr.ImplicitDepsInferringDescription;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.facebook.buck.downwardapi.config.DownwardApiConfig;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.cd.params.RulesCDParams;
import com.facebook.buck.jvm.core.JavaAbis;
import com.facebook.buck.jvm.java.CalculateClassAbi;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.jvm.java.JavaCDBuckConfig;
import com.facebook.buck.jvm.java.JavaCDParams;
import com.facebook.buck.jvm.java.Javac;
import com.facebook.buck.jvm.java.JavacFactory;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.jvm.java.toolchain.JavacOptionsProvider;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
import java.util.Objects;
import java.util.Optional;
import org.immutables.value.Value;

public class AndroidBuildConfigDescription
    implements DescriptionWithTargetGraph<AndroidBuildConfigDescriptionArg>,
        ImplicitDepsInferringDescription<AndroidBuildConfigDescriptionArg> {

  public static final Flavor GEN_JAVA_FLAVOR = InternalFlavor.of("gen_java_android_build_config");

  private final JavacFactory javacFactory;
  private final DownwardApiConfig downwardApiConfig;
  private final JavaBuckConfig javaBuckConfig;
  private final JavaCDBuckConfig javaCDBuckConfig;

  public AndroidBuildConfigDescription(
      ToolchainProvider toolchainProvider,
      DownwardApiConfig downwardApiConfig,
      JavaBuckConfig javaBuckConfig,
      JavaCDBuckConfig javaCDBuckConfig) {
    javacFactory = JavacFactory.getDefault(toolchainProvider);
    this.downwardApiConfig = downwardApiConfig;
    this.javaBuckConfig = javaBuckConfig;
    this.javaCDBuckConfig = javaCDBuckConfig;
  }

  @Override
  public Class<AndroidBuildConfigDescriptionArg> getConstructorArgType() {
    return AndroidBuildConfigDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      AndroidBuildConfigDescriptionArg args) {
    ActionGraphBuilder graphBuilder = context.getActionGraphBuilder();
    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();
    if (JavaAbis.isClassAbiTarget(buildTarget)) {
      BuildTarget configTarget = JavaAbis.getLibraryTarget(buildTarget);
      BuildRule configRule = graphBuilder.requireRule(configTarget);
      return CalculateClassAbi.of(
          buildTarget,
          graphBuilder,
          projectFilesystem,
          Objects.requireNonNull(configRule.getSourcePathToOutput()));
    }

    return createBuildRule(
        buildTarget,
        projectFilesystem,
        args.getPackage(),
        args.getValues(),
        args.getValuesFile(),
        /* useConstantExpressions */ false,
        javacFactory.create(graphBuilder, null, buildTarget.getTargetConfiguration()),
        context
            .getToolchainProvider()
            .getByName(
                JavacOptionsProvider.DEFAULT_NAME,
                buildTarget.getTargetConfiguration(),
                JavacOptionsProvider.class)
            .getJavacOptions(),
        graphBuilder,
        downwardApiConfig.isEnabledForAndroid(),
        javaBuckConfig.getDelegate().getView(BuildBuckConfig.class).areExternalActionsEnabled(),
        JavaCDParams.get(javaBuckConfig, javaCDBuckConfig));
  }

  /**
   * @param values Collection whose entries identify fields for the generated {@code BuildConfig}
   *     class. The values for fields can be overridden by values from the {@code valuesFile} file,
   *     if present.
   * @param valuesFile Path to a file with values to override those in {@code values}.
   * @param graphBuilder Any intermediate rules introduced by this method will be added to this
   *     {@link ActionGraphBuilder}.
   */
  static AndroidBuildConfigJavaLibrary createBuildRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      String javaPackage,
      BuildConfigFields values,
      Optional<SourcePath> valuesFile,
      boolean useConstantExpressions,
      Javac javac,
      JavacOptions javacOptions,
      ActionGraphBuilder graphBuilder,
      boolean withDownwardApi,
      boolean shouldExecuteInSeparateProcess,
      RulesCDParams javaCDParams) {
    // Normally, the build target for an intermediate rule is a flavored version of the target for
    // the original rule. For example, if the build target for an android_build_config() were
    // //foo:bar, then the build target for the intermediate AndroidBuildConfig rule created by this
    // method would be //foo:bar#gen_java_android_build_config.
    //
    // However, in the case of an android_binary() with multiple android_build_config() rules in its
    // transitive deps, it must create one intermediate AndroidBuildConfigJavaLibrary for each
    // android_build_config() dependency. The primary difference is that in each of the new versions
    // of the AndroidBuildConfigJavaLibrary, constant expressions will be used so the values can be
    // inlined (whereas non-constant-expressions were used in the original versions). Because there
    // are multiple intermediate rules based on the same android_binary(), the flavor cannot just be
    // #gen_java_android_build_config because that would lead to build target collisions, so the
    // flavor must be parameterized by the java package to ensure it is unique.
    //
    // This fixes the issue, but deviates from the common pattern where a build rule has at most
    // one flavored version of itself for a given flavor.
    BuildTarget buildConfigBuildTarget;
    if (!buildTarget.isFlavored()) {
      // android_build_config() case.
      Preconditions.checkArgument(!useConstantExpressions);
      buildConfigBuildTarget = buildTarget.withFlavors(GEN_JAVA_FLAVOR);
    } else {
      // android_binary() graph enhancement case.
      Preconditions.checkArgument(useConstantExpressions);
      buildConfigBuildTarget =
          buildTarget.withFlavors(
              InternalFlavor.of(GEN_JAVA_FLAVOR.getName() + '_' + javaPackage.replace('.', '_')));
    }

    // Create one build rule to generate BuildConfig.java.
    AndroidBuildConfig androidBuildConfig =
        new AndroidBuildConfig(
            buildConfigBuildTarget,
            projectFilesystem,
            graphBuilder,
            javaPackage,
            values,
            valuesFile,
            useConstantExpressions,
            shouldExecuteInSeparateProcess);
    graphBuilder.addToIndex(androidBuildConfig);

    // Create a second build rule to compile BuildConfig.java and expose it as a JavaLibrary.
    return new AndroidBuildConfigJavaLibrary(
        buildTarget,
        projectFilesystem,
        graphBuilder,
        javac,
        javacOptions,
        androidBuildConfig,
        withDownwardApi,
        javaCDParams);
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellNameResolver cellRoots,
      AndroidBuildConfigDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    javacFactory.addParseTimeDeps(
        targetGraphOnlyDepsBuilder, null, buildTarget.getTargetConfiguration());
  }

  @RuleArg
  interface AbstractAndroidBuildConfigDescriptionArg extends BuildRuleArg {

    /** For R.java */
    String getPackage();

    @Value.Default
    default BuildConfigFields getValues() {
      return BuildConfigFields.of();
    }

    /** If present, contents of file can override those of {@link #getValues}. */
    Optional<SourcePath> getValuesFile();
  }
}
