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

import static com.facebook.buck.jvm.java.JavaCompilationConstants.ANDROID_JAVAC_OPTIONS;
import static com.facebook.buck.jvm.java.JavaCompilationConstants.DEFAULT_JAVAC;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.build.buildable.context.FakeBuildableContext;
import com.facebook.buck.core.build.context.FakeBuildContext;
import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.BuckPaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.jvm.core.BuildTargetValue;
import com.facebook.buck.jvm.java.CompilerOutputPaths;
import com.facebook.buck.jvm.java.CompilerOutputPathsValue;
import com.facebook.buck.jvm.java.CompilerParameters;
import com.facebook.buck.jvm.java.ExtraClasspathProvider;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.jvm.java.JavacPluginParams;
import com.facebook.buck.jvm.java.JavacStep;
import com.facebook.buck.jvm.java.JavacToJarStepFactory;
import com.facebook.buck.jvm.java.ResolvedJavacOptions;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.MoreAsserts;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.Test;

public class DummyRDotJavaTest {

  @Test
  public void testBuildSteps() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    AndroidResource resourceRule1 =
        graphBuilder.addToIndex(
            AndroidResourceRuleBuilder.newBuilder()
                .setRuleFinder(graphBuilder)
                .setBuildTarget(BuildTargetFactory.newInstance("//android_res/com/example:res1"))
                .setRDotJavaPackage("com.facebook")
                .setRes(FakeSourcePath.of("android_res/com/example/res1"))
                .build());
    setAndroidResourceBuildOutput(resourceRule1);
    AndroidResource resourceRule2 =
        graphBuilder.addToIndex(
            AndroidResourceRuleBuilder.newBuilder()
                .setRuleFinder(graphBuilder)
                .setBuildTarget(BuildTargetFactory.newInstance("//android_res/com/example:res2"))
                .setRDotJavaPackage("com.facebook")
                .setRes(FakeSourcePath.of("android_res/com/example/res2"))
                .build());
    setAndroidResourceBuildOutput(resourceRule2);

    BuildTarget buildTarget = BuildTargetFactory.newInstance("//java/base:rule");
    DummyRDotJava dummyRDotJava =
        new DummyRDotJava(
            buildTarget,
            filesystem,
            graphBuilder,
            ImmutableSet.of(resourceRule1, resourceRule2),
            new JavacToJarStepFactory(ANDROID_JAVAC_OPTIONS, ExtraClasspathProvider.EMPTY, false),
            DEFAULT_JAVAC,
            Optional.empty());

    FakeBuildableContext buildableContext = new FakeBuildableContext();
    List<Step> steps = dummyRDotJava.getBuildSteps(FakeBuildContext.NOOP_CONTEXT, buildableContext);
    assertEquals("DummyRDotJava returns an incorrect number of Steps.", 13, steps.size());

    BuildTarget target = dummyRDotJava.getBuildTarget();
    RelPath rDotJavaSrcFolder = DummyRDotJava.getRDotJavaSrcFolder(target, filesystem);
    BuckPaths buckPaths = filesystem.getBuckPaths();
    RelPath rDotJavaBinFolder = CompilerOutputPaths.of(target, buckPaths).getClassesDir();
    RelPath rDotJavaOutputFolder = DummyRDotJava.getPathToOutputDir(target, filesystem);
    Path rDotJavaAnnotationFolder =
        CompilerOutputPaths.of(target, buckPaths).getAnnotationPath().getPath();

    String rDotJavaOutputJar =
        MorePaths.pathWithPlatformSeparators(
            String.format(
                "%s/%s.jar", rDotJavaOutputFolder, target.getShortNameAndFlavorPostfix()));
    String genFolder =
        BuildTargetPaths.getGenPath(buckPaths, buildTarget, "%s").getParent().toString();

    List<String> sortedSymbolsFiles =
        Stream.of(resourceRule1, resourceRule2)
            .map(Object::toString)
            .collect(ImmutableList.toImmutableList());
    ImmutableSortedSet<RelPath> javaSourceFiles =
        ImmutableSortedSet.orderedBy(RelPath.comparator())
            .add(rDotJavaSrcFolder.resolveRel("com/facebook/R.java"))
            .build();

    AbsPath rootPath = filesystem.getRootPath();
    StepExecutionContext stepExecutionContext = TestExecutionContext.newInstance(rootPath);
    SourcePathResolverAdapter sourcePathResolver = graphBuilder.getSourcePathResolver();
    BuildTargetValue buildTargetValue = BuildTargetValue.of(target);
    List<String> expectedStepDescriptions =
        new ImmutableList.Builder<String>()
            .addAll(makeCleanDirDescription(rootPath, rDotJavaSrcFolder.getPath()))
            .add("android-res-merge " + Joiner.on(' ').join(sortedSymbolsFiles))
            .addAll(makeCleanDirDescription(rootPath, rDotJavaBinFolder.getPath()))
            .addAll(makeCleanDirDescription(rootPath, rDotJavaOutputFolder.getPath()))
            .add(String.format("mkdir -p %s", genFolder))
            .addAll(makeCleanDirDescription(rootPath, rDotJavaAnnotationFolder))
            .add(
                new JavacStep(
                        DEFAULT_JAVAC.resolve(sourcePathResolver, rootPath),
                        ResolvedJavacOptions.of(
                            JavacOptions.builder(ANDROID_JAVAC_OPTIONS)
                                .setJavaAnnotationProcessorParams(JavacPluginParams.EMPTY)
                                .build(),
                            sourcePathResolver,
                            rootPath),
                        buildTargetValue,
                        buckPaths.getConfiguredBuckOut(),
                        CompilerOutputPathsValue.of(buckPaths, target),
                        CompilerParameters.builder()
                            .setOutputPaths(
                                CompilerOutputPaths.of(
                                    target, dummyRDotJava.getProjectFilesystem().getBuckPaths()))
                            .setSourceFilePaths(javaSourceFiles)
                            .setClasspathEntries(ImmutableSortedSet.of())
                            .build(),
                        null,
                        null,
                        false,
                        ImmutableMap.of())
                    .getDescription(stepExecutionContext))
            .add(String.format("jar cf %s  %s", rDotJavaOutputJar, rDotJavaBinFolder))
            .add(String.format("check_dummy_r_jar_not_empty %s", rDotJavaOutputJar))
            .build();

    MoreAsserts.assertSteps(
        "DummyRDotJava.getBuildSteps() must return these exact steps.",
        expectedStepDescriptions,
        steps,
        stepExecutionContext);

    assertEquals(
        ImmutableSet.of(
            rDotJavaBinFolder.getPath(), Paths.get(rDotJavaOutputJar), rDotJavaAnnotationFolder),
        buildableContext.getRecordedArtifacts());
  }

  @Test
  public void testRDotJavaBinFolder() {
    SourcePathRuleFinder ruleFinder = new TestActionGraphBuilder();
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//java/com/example:library");
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    DummyRDotJava dummyRDotJava =
        new DummyRDotJava(
            buildTarget,
            filesystem,
            ruleFinder,
            ImmutableSet.of(),
            new JavacToJarStepFactory(ANDROID_JAVAC_OPTIONS, ExtraClasspathProvider.EMPTY, false),
            DEFAULT_JAVAC,
            Optional.empty());
    assertEquals(
        BuildTargetPaths.getScratchPath(
            dummyRDotJava.getProjectFilesystem(),
            dummyRDotJava.getBuildTarget(),
            "lib__%s__scratch/classes"),
        dummyRDotJava.getRDotJavaBinFolder());
  }

  private static ImmutableList<String> makeCleanDirDescription(AbsPath rootPath, Path dirname) {
    return ImmutableList.of(
        String.format("rm -f -r %s", rootPath.resolve(dirname)),
        String.format("mkdir -p %s", dirname));
  }

  private void setAndroidResourceBuildOutput(BuildRule resourceRule) {
    if (resourceRule instanceof AndroidResource) {
      ((AndroidResource) resourceRule).getBuildOutputInitializer();
    }
  }
}
