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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import com.facebook.buck.core.build.buildable.context.FakeBuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.context.FakeBuildContext;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.description.impl.DescriptionCache;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.TestBuildRuleParams;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.downwardapi.config.DownwardApiConfig;
import com.facebook.buck.io.filesystem.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.rules.keys.DefaultRuleKeyFactory;
import com.facebook.buck.rules.keys.TestDefaultRuleKeyFactory;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.RmStep;
import com.facebook.buck.step.isolatedsteps.shell.IsolatedShellStep;
import com.facebook.buck.util.cache.FileHashCacheMode;
import com.facebook.buck.util.cache.impl.StackedFileHashCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

public class GenAidlTest {

  private ProjectFilesystem stubFilesystem;
  private PathSourcePath pathToAidl;
  private BuildTarget target;
  private SourcePathResolverAdapter pathResolver;
  private Path pathToAidlExecutable;
  private Path pathToFrameworkAidl;
  private String importPath;

  @Before
  public void setUp() throws IOException {
    stubFilesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();
    Files.createDirectories(
        stubFilesystem.getRootPath().resolve("java/com/example/base").getPath());
    Files.createDirectories(
        stubFilesystem.getRootPath().resolve("java/com/example/other/import/path").getPath());
    Files.createDirectories(
        stubFilesystem.getRootPath().resolve("java/com/example/one/more/import/path").getPath());

    pathToAidl = FakeSourcePath.of(stubFilesystem, "java/com/example/base/IWhateverService.aidl");
    importPath = Paths.get("java/com/example/base").toString();

    pathToAidlExecutable = Paths.get("/usr/local/bin/aidl");
    pathToFrameworkAidl = Paths.get("/home/root/android/platforms/android-16/framework.aidl");

    target = BuildTargetFactory.newInstance("//java/com/example/base:IWhateverService");
    pathResolver = new TestActionGraphBuilder().getSourcePathResolver();
  }

  private GenAidl createGenAidlRule(
      ImmutableSortedSet<SourcePath> aidlSourceDeps, ImmutableList<String> importPaths) {
    BuildRuleParams params = TestBuildRuleParams.create();
    return new GenAidl(
        target,
        stubFilesystem,
        pathToAidlExecutable,
        pathToFrameworkAidl,
        params,
        pathToAidl,
        importPath,
        importPaths,
        aidlSourceDeps,
        false);
  }

  @Test
  public void testSimpleGenAidlRule() {
    GenAidl genAidlRule = createGenAidlRule(ImmutableSortedSet.of(), ImmutableList.of());
    GenAidlDescription description =
        new GenAidlDescription(DownwardApiConfig.of(FakeBuckConfig.empty()));
    assertEquals(
        DescriptionCache.getRuleType(GenAidlDescription.class),
        DescriptionCache.getRuleType(description));

    BuildContext buildContext =
        FakeBuildContext.withSourcePathResolver(pathResolver)
            .withBuildCellRootPath(stubFilesystem.getRootPath());
    List<Step> steps = genAidlRule.getBuildSteps(buildContext, new FakeBuildableContext());

    RelPath outputDirectory = BuildTargetPaths.getScratchPath(stubFilesystem, target, "__%s.aidl");
    assertEquals(
        RmStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                buildContext.getBuildCellRootPath(), stubFilesystem, outputDirectory),
            true),
        steps.get(2));
    assertEquals(
        MkdirStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                buildContext.getBuildCellRootPath(), stubFilesystem, outputDirectory)),
        steps.get(3));

    IsolatedShellStep aidlStep = (IsolatedShellStep) steps.get(4);
    assertEquals(
        "gen_aidl() should use the aidl binary to write .java files.",
        String.format(
            "(cd %s && %s -p%s -I%s -o%s %s)",
            stubFilesystem.getRootPath(),
            pathToAidlExecutable,
            pathToFrameworkAidl,
            stubFilesystem.resolve(importPath),
            stubFilesystem.resolve(outputDirectory),
            pathToAidl.getRelativePath()),
        aidlStep.getDescription(TestExecutionContext.newBuilder().build()));

    assertEquals(7, steps.size());
  }

  @Test
  public void testSimpleGenAidlRuleWithImports() {
    ImmutableList<String> importPaths =
        ImmutableList.of(
            Paths.get("java/com/example/one/more/import/path").toString(),
            Paths.get("java/com/example/other/import/path").toString());
    GenAidl genAidlRule = createGenAidlRule(ImmutableSortedSet.of(), importPaths);
    GenAidlDescription description =
        new GenAidlDescription(DownwardApiConfig.of(FakeBuckConfig.empty()));
    assertEquals(
        DescriptionCache.getRuleType(GenAidlDescription.class),
        DescriptionCache.getRuleType(description));

    BuildContext buildContext =
        FakeBuildContext.withSourcePathResolver(pathResolver)
            .withBuildCellRootPath(stubFilesystem.getRootPath());
    List<Step> steps = genAidlRule.getBuildSteps(buildContext, new FakeBuildableContext());

    RelPath outputDirectory = BuildTargetPaths.getScratchPath(stubFilesystem, target, "__%s.aidl");
    assertEquals(
        RmStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                buildContext.getBuildCellRootPath(), stubFilesystem, outputDirectory),
            true),
        steps.get(2));
    assertEquals(
        MkdirStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                buildContext.getBuildCellRootPath(), stubFilesystem, outputDirectory)),
        steps.get(3));

    StringBuilder sb = new StringBuilder();

    for (String path : importPaths) {
      sb.append(String.format("-I%s ", stubFilesystem.resolve(path)));
    }
    IsolatedShellStep aidlStep = (IsolatedShellStep) steps.get(4);
    assertEquals(
        "gen_aidl() should use the aidl binary to write .java files.",
        String.format(
            "(cd %s && %s -p%s -I%s %s-o%s %s)",
            stubFilesystem.getRootPath(),
            pathToAidlExecutable,
            pathToFrameworkAidl,
            stubFilesystem.resolve(importPath),
            sb.toString(),
            stubFilesystem.resolve(outputDirectory),
            pathToAidl.getRelativePath()),
        aidlStep.getDescription(TestExecutionContext.newBuilder().build()));

    assertEquals(7, steps.size());
  }

  @Test
  public void testTransitiveAidlDependenciesAffectTheRuleKey() throws IOException {
    SourcePathRuleFinder ruleFinder = new TestActionGraphBuilder();
    StackedFileHashCache hashCache =
        StackedFileHashCache.createDefaultHashCaches(
            stubFilesystem, FileHashCacheMode.LOADING_CACHE, false);
    DefaultRuleKeyFactory factory = new TestDefaultRuleKeyFactory(hashCache, ruleFinder);
    stubFilesystem.touch(
        stubFilesystem.getRootPath().resolve(pathToAidl.getRelativePath()).getPath());

    GenAidl genAidlRuleNoDeps = createGenAidlRule(ImmutableSortedSet.of(), ImmutableList.of());
    RuleKey ruleKey = factory.build(genAidlRuleNoDeps);

    // The rule key is different.
    GenAidl genAidlRuleNoDeps2 =
        createGenAidlRule(ImmutableSortedSet.of(pathToAidl), ImmutableList.of());
    RuleKey ruleKey2 = factory.build(genAidlRuleNoDeps2);
    assertNotEquals(ruleKey, ruleKey2);

    // And the rule key is stable.
    GenAidl genAidlRuleNoDeps3 =
        createGenAidlRule(ImmutableSortedSet.of(pathToAidl), ImmutableList.of());
    RuleKey ruleKey3 = factory.build(genAidlRuleNoDeps3);
    assertEquals(ruleKey2, ruleKey3);
  }
}
