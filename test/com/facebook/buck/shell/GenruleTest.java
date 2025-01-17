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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.buildable.context.FakeBuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.context.FakeBuildContext;
import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.cell.Cells;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.BuildTargetWithOutputs;
import com.facebook.buck.core.model.OutputLabel;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.impl.AbstractBuildRuleResolver;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.DefaultBuckEventBus;
import com.facebook.buck.io.filesystem.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.jvm.java.JavaBinaryRuleBuilder;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.parser.exceptions.NoSuchBuildTargetException;
import com.facebook.buck.remoteexecution.grpc.GrpcProtocol;
import com.facebook.buck.rules.keys.DefaultRuleKeyFactory;
import com.facebook.buck.rules.keys.InputBasedRuleKeyFactory;
import com.facebook.buck.rules.keys.TestDefaultRuleKeyFactory;
import com.facebook.buck.rules.keys.TestInputBasedRuleKeyFactory;
import com.facebook.buck.rules.macros.ClasspathMacro;
import com.facebook.buck.rules.macros.ExecutableMacro;
import com.facebook.buck.rules.macros.LocationMacro;
import com.facebook.buck.rules.macros.StringWithMacrosUtils;
import com.facebook.buck.rules.macros.WorkerMacro;
import com.facebook.buck.rules.modern.builders.ModernBuildRuleRemoteExecutionHelper;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.RmStep;
import com.facebook.buck.step.fs.SymlinkTreeStep;
import com.facebook.buck.testutil.DummyFileHashCache;
import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.ConsoleParams;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.cache.FileHashCache;
import com.facebook.buck.util.cache.FileHashCacheMode;
import com.facebook.buck.util.cache.impl.StackedFileHashCache;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.timing.FakeClock;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.HashCode;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.hamcrest.junit.ExpectedException;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class GenruleTest {

  @Rule public ExpectedException expectedThrownException = ExpectedException.none();

  private ProjectFilesystem filesystem;

  @Before
  public void newFakeFilesystem() {
    filesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();
  }

  /**
   * Quick class to create a self contained genrule (and the infra needed to get a rulekey), and to
   * get the rulekey. This doesn't let multiple targets in the same cache/graph, it's solely to help
   * generate standalone genrules
   */
  private static class StandaloneGenruleBuilder {

    private final ActionGraphBuilder graphBuilder;
    private final DefaultRuleKeyFactory ruleKeyFactory;
    final GenruleBuilder genruleBuilder;

    StandaloneGenruleBuilder(String targetName) {
      graphBuilder = new TestActionGraphBuilder();
      ruleKeyFactory = new TestDefaultRuleKeyFactory(new DummyFileHashCache(), graphBuilder);
      genruleBuilder = GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance(targetName));
    }

    RuleKey getRuleKey() {
      return ruleKeyFactory.build(genruleBuilder.build(graphBuilder));
    }
  }

  @Test
  public void testCreateAndRunGenrule() throws IOException, NoSuchBuildTargetException {
    /*
     * Programmatically build up a Genrule that corresponds to:
     *
     * genrule(
     *   name = 'katana_manifest',
     *   srcs = [
     *     'convert_to_katana.py',
     *     'AndroidManifest.xml',
     *   ],
     *   cmd = 'python $SRCDIR/* > $OUT',
     *   out = 'AndroidManifest.xml',
     * )
     */

    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolverAdapter pathResolver = graphBuilder.getSourcePathResolver();
    createSampleJavaBinaryRule(graphBuilder);

    BuildTarget buildTarget =
        BuildTargetFactory.newInstance("//src/com/facebook/katana:katana_manifest");
    Genrule genrule =
        GenruleBuilder.newGenruleBuilder(buildTarget)
            .setBash("python convert_to_katana.py AndroidManifest.xml > $OUT")
            .setCmdExe("python convert_to_katana.py AndroidManifest.xml > %OUT%")
            .setOut("AndroidManifest.xml")
            .setSrcs(
                ImmutableList.of(
                    PathSourcePath.of(
                        filesystem,
                        filesystem.getPath("src/com/facebook/katana/convert_to_katana.py")),
                    PathSourcePath.of(
                        filesystem,
                        filesystem.getPath("src/com/facebook/katana/AndroidManifest.xml"))))
            .build(graphBuilder, filesystem);

    // Verify all of the observers of the Genrule.
    assertEquals(
        BuildTargetPaths.getGenPath(filesystem.getBuckPaths(), buildTarget, "%s")
            .resolveRel("AndroidManifest.xml"),
        pathResolver.getCellUnsafeRelPath(genrule.getSourcePathToOutput()));

    SourcePath outputSourcePath = genrule.getSourcePathToOutput();
    AbsPath manifestPath = graphBuilder.getSourcePathResolver().getAbsolutePath(outputSourcePath);
    assertEquals(
        filesystem.resolve(
            BuildTargetPaths.getGenPath(filesystem.getBuckPaths(), buildTarget, "%s")
                .resolve("AndroidManifest.xml")),
        manifestPath.getPath());
    BuildContext buildContext =
        FakeBuildContext.withSourcePathResolver(pathResolver)
            .withBuildCellRootPath(filesystem.getRootPath());
    assertThat(
        pathResolver.filterInputsToCompareToOutput(genrule.getBuildable().srcs.getPaths()),
        containsInAnyOrder(
            filesystem.getPath("src/com/facebook/katana/convert_to_katana.py"),
            filesystem.getPath("src/com/facebook/katana/AndroidManifest.xml")));

    // Verify that the shell commands that the genrule produces are correct.
    List<Step> steps = genrule.getBuildSteps(buildContext, new FakeBuildableContext());

    MoreAsserts.assertStepsNames(
        "",
        ImmutableList.of(
            "delegated_rm",
            "delegated_rm",
            "delegated_mkdir",
            "delegated_rm",
            "delegated_mkdir",
            "delegated_rm",
            "delegated_mkdir",
            "delegated_rm",
            "delegated_mkdir",
            "genrule_srcs_link_tree",
            "genrule"),
        steps);

    StepExecutionContext executionContext = newEmptyExecutionContext();

    assertEquals(
        RmStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                buildContext.getBuildCellRootPath(),
                filesystem,
                BuildTargetPaths.getGenPath(filesystem.getBuckPaths(), buildTarget, "%s")
                    .resolve("AndroidManifest.xml")),
            true),
        steps.get(0));

    assertEquals(
        RmStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                buildContext.getBuildCellRootPath(),
                filesystem,
                BuildTargetPaths.getGenPath(filesystem.getBuckPaths(), buildTarget, "%s__")),
            true),
        steps.get(1));
    assertEquals(
        MkdirStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                buildContext.getBuildCellRootPath(),
                filesystem,
                BuildTargetPaths.getGenPath(filesystem.getBuckPaths(), buildTarget, "%s__"))),
        steps.get(2));

    assertEquals(
        RmStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                buildContext.getBuildCellRootPath(),
                filesystem,
                BuildTargetPaths.getScratchPath(filesystem, buildTarget, "%s__")),
            true),
        steps.get(3));
    assertEquals(
        MkdirStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                buildContext.getBuildCellRootPath(),
                filesystem,
                BuildTargetPaths.getScratchPath(filesystem, buildTarget, "%s__"))),
        steps.get(4));

    RelPath pathToOutDir =
        BuildTargetPaths.getGenPath(filesystem.getBuckPaths(), buildTarget, "%s");
    assertEquals(
        RmStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                buildContext.getBuildCellRootPath(), filesystem, pathToOutDir),
            true),
        steps.get(5));
    assertEquals(
        MkdirStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                buildContext.getBuildCellRootPath(), filesystem, pathToOutDir)),
        steps.get(6));

    RelPath pathToSrcDir =
        BuildTargetPaths.getGenPath(filesystem.getBuckPaths(), buildTarget, "%s__srcs");
    assertEquals(
        RmStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                buildContext.getBuildCellRootPath(), filesystem, pathToSrcDir),
            true),
        steps.get(7));
    assertEquals(
        MkdirStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                buildContext.getBuildCellRootPath(), filesystem, pathToSrcDir)),
        steps.get(8));

    assertEquals(
        new SymlinkTreeStep(
            "genrule_srcs",
            filesystem,
            pathToSrcDir.getPath(),
            ImmutableMap.of(
                filesystem.getPath("convert_to_katana.py"),
                filesystem.getPath("src/com/facebook/katana/convert_to_katana.py"),
                filesystem.getPath("AndroidManifest.xml"),
                filesystem.getPath("src/com/facebook/katana/AndroidManifest.xml"))),
        steps.get(9));

    Step genruleStep = steps.get(10);
    assertTrue(genruleStep instanceof AbstractGenruleStep);
    AbstractGenruleStep genruleCommand = (AbstractGenruleStep) genruleStep;
    assertEquals("genrule", genruleCommand.getShortName());
    assertEquals(
        ImmutableMap.<String, String>builder()
            .put(
                "OUT",
                filesystem
                    .resolve(
                        BuildTargetPaths.getGenPath(filesystem.getBuckPaths(), buildTarget, "%s")
                            .resolve("AndroidManifest.xml"))
                    .toString())
            .build(),
        genruleCommand.getEnvironmentVariables(executionContext.getPlatform()));
    Path scriptFilePath = genruleCommand.getScriptFilePath(executionContext);
    String scriptFileContents = genruleCommand.getScriptFileContents(executionContext);
    if (Platform.detect() == Platform.WINDOWS) {
      assertEquals(
          ImmutableList.of("cmd.exe", "/v:off", "/c", scriptFilePath.toString()),
          genruleCommand.getShellCommand(executionContext));
      assertEquals("python convert_to_katana.py AndroidManifest.xml > %OUT%", scriptFileContents);
    } else {
      assertEquals(
          ImmutableList.of("/bin/bash", "-e", scriptFilePath.toString()),
          genruleCommand.getShellCommand(executionContext));
      assertEquals("python convert_to_katana.py AndroidManifest.xml > $OUT", scriptFileContents);
    }
  }

  @Test
  public void testGenruleType() throws NoSuchBuildTargetException {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    BuildTarget buildTarget =
        BuildTargetFactory.newInstance("//src/com/facebook/katana:katana_manifest");
    BuildRule genrule =
        GenruleBuilder.newGenruleBuilder(buildTarget)
            .setOut("output.xml")
            .setType("xxxxx")
            .build(graphBuilder, filesystem);
    assertTrue(genrule.getType().contains("xxxxx"));
  }

  private GenruleBuilder createGenruleBuilderThatUsesWorkerMacro(ActionGraphBuilder graphBuilder)
      throws NoSuchBuildTargetException {
    /*
     * Produces a GenruleBuilder that when built produces a Genrule that uses a $(worker) macro
     * that corresponds to:
     *
     * genrule(
     *   name = 'genrule_with_worker',
     *   srcs = [],
     *   cmd = '$(worker :worker_rule) abc',
     *   out = 'output.txt',
     * )
     *
     * worker_tool(
     *   name = 'worker_rule',
     *   exe = ':my_exe',
     * )
     *
     * sh_binary(
     *   name = 'my_exe',
     *   main = 'bin/exe',
     * );
     */
    BuildRule shBinaryRule =
        new ShBinaryBuilder(BuildTargetFactory.newInstance("//:my_exe"))
            .setMain(FakeSourcePath.of("bin/exe"))
            .build(graphBuilder);

    DefaultWorkerToolRule workerToolRule =
        WorkerToolBuilder.newWorkerToolBuilder(BuildTargetFactory.newInstance("//:worker_rule"))
            .setExe(shBinaryRule.getBuildTarget())
            .build(graphBuilder);
    workerToolRule.getBuildOutputInitializer().setBuildOutputForTests(UUID.randomUUID());

    return GenruleBuilder.newGenruleBuilder(
            BuildTargetFactory.newInstance("//:genrule_with_worker"))
        .setCmd(
            StringWithMacrosUtils.format(
                "%s abc",
                WorkerMacro.of(
                    BuildTargetWithOutputs.of(
                        workerToolRule.getBuildTarget(), OutputLabel.defaultLabel()))))
        .setOut("output.txt");
  }

  @Test
  public void testGenruleWithWorkerMacroUsesSpecialShellStep() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    Genrule genrule = createGenruleBuilderThatUsesWorkerMacro(graphBuilder).build(graphBuilder);
    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    List<Step> steps =
        genrule.getBuildSteps(
            FakeBuildContext.withSourcePathResolver(graphBuilder.getSourcePathResolver()),
            new FakeBuildableContext());

    MoreAsserts.assertStepsNames(
        "",
        ImmutableList.of(
            "delegated_rm",
            "delegated_rm",
            "delegated_mkdir",
            "delegated_rm",
            "delegated_mkdir",
            "delegated_rm",
            "delegated_mkdir",
            "delegated_rm",
            "delegated_mkdir",
            "worker"),
        steps);

    Step step = steps.get(9);
    assertTrue(step instanceof WorkerShellStep);
    WorkerShellStep workerShellStep = (WorkerShellStep) step;
    assertThat(workerShellStep.getShortName(), equalTo("worker"));
    assertThat(
        workerShellStep.getEnvironmentVariables(),
        hasEntry(
            "OUT",
            filesystem
                .resolve(
                    BuildTargetPaths.getGenPath(
                            filesystem.getBuckPaths(), genrule.getBuildTarget(), "%s")
                        .resolve("output.txt"))
                .toString()));
  }

  @Test
  public void testIsWorkerGenruleReturnsTrue() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    Genrule genrule = createGenruleBuilderThatUsesWorkerMacro(graphBuilder).build(graphBuilder);
    assertTrue(genrule.getBuildable().isWorkerGenrule());
  }

  @Test
  public void testIsWorkerGenruleReturnsFalse() throws NoSuchBuildTargetException {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    Genrule genrule =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:genrule_no_worker"))
            .setCmd("echo hello >> $OUT")
            .setOut("output.txt")
            .build(graphBuilder, filesystem);
    assertFalse(genrule.getBuildable().isWorkerGenrule());
  }

  @Test
  public void testConstructingGenruleWithBadWorkerMacroThrows() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    GenruleBuilder genruleBuilder = createGenruleBuilderThatUsesWorkerMacro(graphBuilder);
    try {
      genruleBuilder.setBash("no worker macro here").build(graphBuilder);
    } catch (HumanReadableException e) {
      assertEquals(
          "You cannot use a worker macro in one of the cmd, bash, or "
              + "cmd_exe properties and not in the others for genrule //:genrule_with_worker.",
          e.getHumanReadableErrorMessage());
    }
  }

  @Test
  public void testGenruleWithWorkerMacroIncludesWorkerToolInDeps()
      throws NoSuchBuildTargetException {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();

    BuildRule shBinaryRule =
        new ShBinaryBuilder(BuildTargetFactory.newInstance("//:my_exe"))
            .setMain(FakeSourcePath.of("bin/exe"))
            .build(graphBuilder);

    BuildRule workerToolRule =
        WorkerToolBuilder.newWorkerToolBuilder(BuildTargetFactory.newInstance("//:worker_rule"))
            .setExe(shBinaryRule.getBuildTarget())
            .build(graphBuilder);

    BuildRule genrule =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:genrule_with_worker"))
            .setCmd(
                StringWithMacrosUtils.format(
                    "%s abs",
                    WorkerMacro.of(
                        BuildTargetWithOutputs.of(
                            workerToolRule.getBuildTarget(), OutputLabel.defaultLabel()))))
            .setOut("output.txt")
            .build(graphBuilder);

    assertThat(genrule.getBuildDeps(), hasItems(shBinaryRule, workerToolRule));
  }

  private StepExecutionContext newEmptyExecutionContext(Platform platform) {
    return TestExecutionContext.newBuilder()
        .setConsole(new Console(Verbosity.SILENT, System.out, System.err, Ansi.withoutTty()))
        .setPlatform(platform)
        .build();
  }

  private StepExecutionContext newEmptyExecutionContext() {
    return newEmptyExecutionContext(Platform.detect());
  }

  private void createSampleJavaBinaryRule(ActionGraphBuilder graphBuilder)
      throws NoSuchBuildTargetException {
    // Create a java_binary that depends on a java_library so it is possible to create a
    // java_binary rule with a classpath entry and a main class.
    BuildRule javaLibrary =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//java/com/facebook/util:util"))
            .addSrc(Paths.get("java/com/facebook/util/ManifestGenerator.java"))
            .build(graphBuilder);

    BuildTarget buildTarget =
        BuildTargetFactory.newInstance("//java/com/facebook/util:ManifestGenerator");
    new JavaBinaryRuleBuilder(buildTarget)
        .setDeps(ImmutableSortedSet.of(javaLibrary.getBuildTarget()))
        .setMainClass("com.facebook.util.ManifestGenerator")
        .build(graphBuilder);
  }

  @Test
  public void testGetShellCommand() throws Exception {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    BuildContext buildContext =
        FakeBuildContext.withSourcePathResolver(graphBuilder.getSourcePathResolver());
    String bash = "rm -rf /usr";
    String cmdExe = "rmdir /s /q C:\\Windows";
    String cmd = "echo \"Hello\"";
    String cmdForCmdExe = "echo ^\"Hello^\"";
    StepExecutionContext linuxExecutionContext = newEmptyExecutionContext(Platform.LINUX);
    StepExecutionContext windowsExecutionContext = newEmptyExecutionContext(Platform.WINDOWS);

    // Test platform-specific
    Genrule genrule =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//example:genrule1"))
            .setBash(bash)
            .setCmdExe(cmdExe)
            .setOut("out.txt")
            .build(graphBuilder);

    assertGenruleCommandAndScript(
        createGenruleStep(genrule, buildContext),
        linuxExecutionContext,
        ImmutableList.of("/bin/bash", "-e"),
        bash);

    assertGenruleCommandAndScript(
        createGenruleStep(genrule, buildContext),
        windowsExecutionContext,
        ImmutableList.of("cmd.exe", "/v:off", "/c"),
        cmdExe);

    // Test fallback
    genrule =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//example:genrule2"))
            .setCmd(cmd)
            .setOut("out.txt")
            .build(graphBuilder);
    assertGenruleCommandAndScript(
        createGenruleStep(genrule, buildContext),
        linuxExecutionContext,
        ImmutableList.of("/bin/bash", "-e"),
        cmd);

    assertGenruleCommandAndScript(
        createGenruleStep(genrule, buildContext),
        windowsExecutionContext,
        ImmutableList.of("cmd.exe", "/v:off", "/c"),
        cmdForCmdExe);

    // Test command absent
    genrule =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//example:genrule3"))
            .setOut("out.txt")
            .build(graphBuilder);
    try {
      createGenruleStep(genrule, buildContext).getShellCommand(linuxExecutionContext);
    } catch (HumanReadableException e) {
      assertEquals(
          "You must specify either bash or cmd for genrule.", e.getHumanReadableErrorMessage());
    }

    try {
      createGenruleStep(genrule, buildContext).getShellCommand(windowsExecutionContext);
    } catch (HumanReadableException e) {
      assertEquals(
          "You must specify either cmd_exe or cmd for genrule.", e.getHumanReadableErrorMessage());
    }
  }

  /**
   * Helper method to extract the generated Genrule step from a Genrule's list of generated steps.
   */
  private static AbstractGenruleStep createGenruleStep(Genrule genrule, BuildContext buildContext) {
    BuildableContext buildableContext = new FakeBuildableContext();
    ImmutableList<Step> steps = genrule.getBuildSteps(buildContext, buildableContext);

    Optional<Step> genruleStep =
        steps.stream().filter(s -> s instanceof AbstractGenruleStep).findFirst();
    assertTrue("Genrule did not generate a genrule step", genruleStep.isPresent());
    return (AbstractGenruleStep) genruleStep.get();
  }

  private void assertGenruleCommandAndScript(
      AbstractGenruleStep genruleStep,
      StepExecutionContext context,
      ImmutableList<String> expectedCommandPrefix,
      String expectedScriptFileContents)
      throws IOException {
    Path scriptFilePath = genruleStep.getScriptFilePath(context);
    String actualContents = genruleStep.getScriptFileContents(context);
    assertThat(actualContents, equalTo(expectedScriptFileContents));
    ImmutableList<String> expectedCommand =
        ImmutableList.<String>builder()
            .addAll(expectedCommandPrefix)
            .add(scriptFilePath.toString())
            .build();
    ImmutableList<String> actualCommand = genruleStep.getShellCommand(context);
    assertThat(actualCommand, equalTo(expectedCommand));
  }

  @Test
  public void testGetOutputNameMethod() {
    {
      String name = "out.txt";
      Genrule genrule =
          GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:test"))
              .setOut(name)
              .build(new TestActionGraphBuilder());
      assertEquals(name, genrule.getOutputName(OutputLabel.defaultLabel()));
    }
    {
      String name = "out/file.txt";
      Genrule genrule =
          GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:test"))
              .setOut(name)
              .build(new TestActionGraphBuilder());
      assertEquals(name, genrule.getOutputName(OutputLabel.defaultLabel()));
    }
    {
      String name = "out/file.txt";
      Genrule genrule =
          GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:test"))
              .setOuts(ImmutableMap.of("label", ImmutableSet.of(name)))
              .setDefaultOuts(ImmutableSet.of(name))
              .build(new TestActionGraphBuilder());
      assertEquals(name, genrule.getOutputName(OutputLabel.of("label")));
    }
  }

  @Test
  public void thatChangingOutChangesRuleKey() {
    StandaloneGenruleBuilder builder1 = new StandaloneGenruleBuilder("//:genrule1");
    StandaloneGenruleBuilder builder2 = new StandaloneGenruleBuilder("//:genrule1");

    builder1.genruleBuilder.setOut("foo");
    RuleKey key1 = builder1.getRuleKey();

    builder2.genruleBuilder.setOut("bar");
    RuleKey key2 = builder2.getRuleKey();

    // Verify that just the difference in output name is enough to make the rule key different.
    assertNotEquals(key1, key2);
  }

  @Test
  public void thatChangingCacheabilityChangesRuleKey() {
    StandaloneGenruleBuilder builder1 = new StandaloneGenruleBuilder("//:genrule1");
    StandaloneGenruleBuilder builder2 = new StandaloneGenruleBuilder("//:genrule1");

    builder1.genruleBuilder.setOut("foo").setCacheable(true);
    RuleKey key1 = builder1.getRuleKey();

    builder2.genruleBuilder.setOut("foo").setCacheable(false);
    RuleKey key2 = builder2.getRuleKey();

    assertNotEquals(key1, key2);
  }

  @Test
  public void inputBasedRuleKeyLocationMacro() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    GenruleBuilder ruleBuilder =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setCmd(
                StringWithMacrosUtils.format(
                    "run %s", LocationMacro.of(BuildTargetFactory.newInstance("//:dep"))))
            .setOut("output");

    // Create an initial input-based rule key
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathRuleFinder ruleFinder = graphBuilder;
    BuildRule dep =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setOut("dep.out")
            .setCmd("something")
            .build(graphBuilder);
    filesystem.writeContentsToPath(
        "something",
        ruleFinder.getSourcePathResolver().getCellUnsafeRelPath(dep.getSourcePathToOutput()));
    BuildRule rule = ruleBuilder.build(graphBuilder);
    DefaultRuleKeyFactory ruleKeyFactory =
        new TestDefaultRuleKeyFactory(
            StackedFileHashCache.createDefaultHashCaches(
                filesystem, FileHashCacheMode.DEFAULT, false),
            ruleFinder);
    InputBasedRuleKeyFactory inputBasedRuleKeyFactory =
        new TestInputBasedRuleKeyFactory(
            StackedFileHashCache.createDefaultHashCaches(
                filesystem, FileHashCacheMode.DEFAULT, false),
            ruleFinder);
    RuleKey originalRuleKey = ruleKeyFactory.build(rule);
    RuleKey originalInputRuleKey = inputBasedRuleKeyFactory.build(rule);

    // Change the genrule's command, which will change its normal rule key, but since we're keeping
    // its output the same, the input-based rule key for the consuming rule will stay the same.
    // This is because the input-based rule key for the consuming rule only cares about the contents
    // of the output this rule produces.
    graphBuilder = new TestActionGraphBuilder();
    GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep"))
        .setOut("dep.out")
        .setCmd("something else")
        .build(graphBuilder);
    rule = ruleBuilder.build(graphBuilder);
    ruleFinder = graphBuilder;
    ruleKeyFactory =
        new TestDefaultRuleKeyFactory(
            StackedFileHashCache.createDefaultHashCaches(
                filesystem, FileHashCacheMode.DEFAULT, false),
            ruleFinder);
    inputBasedRuleKeyFactory =
        new TestInputBasedRuleKeyFactory(
            StackedFileHashCache.createDefaultHashCaches(
                filesystem, FileHashCacheMode.DEFAULT, false),
            ruleFinder);
    RuleKey unchangedRuleKey = ruleKeyFactory.build(rule);
    RuleKey unchangedInputBasedRuleKey = inputBasedRuleKeyFactory.build(rule);
    assertThat(unchangedRuleKey, not(equalTo(originalRuleKey)));
    assertThat(unchangedInputBasedRuleKey, equalTo(originalInputRuleKey));

    // Make a change to the dep's output, which *should* affect the input-based rule key.
    graphBuilder = new TestActionGraphBuilder();
    ruleFinder = graphBuilder;
    dep =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setOut("dep.out")
            .setCmd("something")
            .build(graphBuilder);
    filesystem.writeContentsToPath(
        "something else",
        ruleFinder.getSourcePathResolver().getCellUnsafeRelPath(dep.getSourcePathToOutput()));
    rule = ruleBuilder.build(graphBuilder);
    inputBasedRuleKeyFactory =
        new TestInputBasedRuleKeyFactory(
            StackedFileHashCache.createDefaultHashCaches(
                filesystem, FileHashCacheMode.DEFAULT, false),
            ruleFinder);
    RuleKey changedInputBasedRuleKey = inputBasedRuleKeyFactory.build(rule);
    assertThat(changedInputBasedRuleKey, not(equalTo(originalInputRuleKey)));
  }

  @Test
  public void inputBasedRuleKeyExecutableMacro() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    GenruleBuilder ruleBuilder =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setCmd(
                StringWithMacrosUtils.format(
                    "run %s",
                    ExecutableMacro.of(
                        BuildTargetWithOutputs.of(
                            BuildTargetFactory.newInstance("//:dep"), OutputLabel.defaultLabel()))))
            .setOut("output");

    // Create an initial input-based rule key
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathRuleFinder ruleFinder = graphBuilder;
    SourcePathResolverAdapter pathResolver = ruleFinder.getSourcePathResolver();
    BuildRule dep =
        new ShBinaryBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setMain(PathSourcePath.of(filesystem, Paths.get("dep.exe")))
            .build(graphBuilder, filesystem);
    filesystem.writeContentsToPath("something", Paths.get("dep.exe"));
    filesystem.writeContentsToPath(
        "something", pathResolver.getCellUnsafeRelPath(dep.getSourcePathToOutput()));
    BuildRule rule = ruleBuilder.build(graphBuilder);
    InputBasedRuleKeyFactory inputBasedRuleKeyFactory =
        new TestInputBasedRuleKeyFactory(
            StackedFileHashCache.createDefaultHashCaches(
                filesystem, FileHashCacheMode.DEFAULT, false),
            ruleFinder);
    RuleKey originalInputRuleKey = inputBasedRuleKeyFactory.build(rule);

    // Change the dep's resource list, which will change its normal rule key, but since we're
    // keeping its output the same, the input-based rule key for the consuming rule will stay the
    // same.  This is because the input-based rule key for the consuming rule only cares about the
    // contents of the output this rule produces.
    graphBuilder = new TestActionGraphBuilder();
    Genrule extra =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:extra"))
            .setOut("something")
            .build(graphBuilder);
    new ShBinaryBuilder(BuildTargetFactory.newInstance("//:dep"))
        .setMain(PathSourcePath.of(filesystem, Paths.get("dep.exe")))
        .setDeps(ImmutableSortedSet.of(extra.getBuildTarget()))
        .build(graphBuilder, filesystem);
    rule = ruleBuilder.build(graphBuilder);
    ruleFinder = graphBuilder;
    pathResolver = ruleFinder.getSourcePathResolver();
    inputBasedRuleKeyFactory =
        new TestInputBasedRuleKeyFactory(
            StackedFileHashCache.createDefaultHashCaches(
                filesystem, FileHashCacheMode.DEFAULT, false),
            ruleFinder);
    RuleKey unchangedInputBasedRuleKey = inputBasedRuleKeyFactory.build(rule);
    assertThat(unchangedInputBasedRuleKey, equalTo(originalInputRuleKey));

    // Make a change to the dep's output, which *should* affect the input-based rule key.
    graphBuilder = new TestActionGraphBuilder();
    dep =
        new ShBinaryBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setMain(PathSourcePath.of(filesystem, Paths.get("dep.exe")))
            .build(graphBuilder, filesystem);
    filesystem.writeContentsToPath(
        "something else", pathResolver.getCellUnsafeRelPath(dep.getSourcePathToOutput()));
    rule = ruleBuilder.build(graphBuilder);
    ruleFinder = graphBuilder;
    inputBasedRuleKeyFactory =
        new TestInputBasedRuleKeyFactory(
            StackedFileHashCache.createDefaultHashCaches(
                filesystem, FileHashCacheMode.DEFAULT, false),
            ruleFinder);
    RuleKey changedInputBasedRuleKey = inputBasedRuleKeyFactory.build(rule);
    assertThat(changedInputBasedRuleKey, not(equalTo(originalInputRuleKey)));
  }

  @Test
  public void inputBasedRuleKeyClasspathMacro() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    GenruleBuilder ruleBuilder =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setCmd(
                StringWithMacrosUtils.format(
                    "run %s",
                    ClasspathMacro.of(
                        BuildTargetWithOutputs.of(
                            BuildTargetFactory.newInstance("//:dep"), OutputLabel.defaultLabel()))))
            .setOut("output");

    // Create an initial input-based rule key
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathRuleFinder ruleFinder = graphBuilder;
    SourcePathResolverAdapter pathResolver = ruleFinder.getSourcePathResolver();
    JavaLibrary dep =
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:dep"))
            .addSrc(Paths.get("source.java"))
            .build(graphBuilder, filesystem);
    filesystem.writeContentsToPath("something", Paths.get("source.java"));
    filesystem.writeContentsToPath(
        "something", pathResolver.getCellUnsafeRelPath(dep.getSourcePathToOutput()));
    BuildRule rule = ruleBuilder.build(graphBuilder);
    DefaultRuleKeyFactory defaultRuleKeyFactory =
        new TestDefaultRuleKeyFactory(
            StackedFileHashCache.createDefaultHashCaches(
                filesystem, FileHashCacheMode.DEFAULT, false),
            ruleFinder);
    InputBasedRuleKeyFactory inputBasedRuleKeyFactory =
        new TestInputBasedRuleKeyFactory(
            StackedFileHashCache.createDefaultHashCaches(
                filesystem, FileHashCacheMode.DEFAULT, false),
            ruleFinder);
    RuleKey originalRuleKey = defaultRuleKeyFactory.build(rule);
    RuleKey originalInputRuleKey = inputBasedRuleKeyFactory.build(rule);

    // Change the dep's resource root, which will change its normal rule key, but since we're
    // keeping its output JAR the same, the input-based rule key for the consuming rule will stay
    // the same.  This is because the input-based rule key for the consuming rule only cares about
    // the contents of the output this rule produces.
    graphBuilder = new TestActionGraphBuilder();
    JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:dep"))
        .addSrc(Paths.get("source.java"))
        .setResourcesRoot(Paths.get("resource_root"))
        .build(graphBuilder, filesystem);
    rule = ruleBuilder.build(graphBuilder);
    ruleFinder = graphBuilder;
    pathResolver = ruleFinder.getSourcePathResolver();
    defaultRuleKeyFactory =
        new TestDefaultRuleKeyFactory(
            StackedFileHashCache.createDefaultHashCaches(
                filesystem, FileHashCacheMode.DEFAULT, false),
            ruleFinder);
    inputBasedRuleKeyFactory =
        new TestInputBasedRuleKeyFactory(
            StackedFileHashCache.createDefaultHashCaches(
                filesystem, FileHashCacheMode.DEFAULT, false),
            ruleFinder);
    RuleKey unchangedRuleKey = defaultRuleKeyFactory.build(rule);
    RuleKey unchangedInputBasedRuleKey = inputBasedRuleKeyFactory.build(rule);
    assertThat(unchangedRuleKey, not(equalTo(originalRuleKey)));
    assertThat(unchangedInputBasedRuleKey, equalTo(originalInputRuleKey));

    // Make a change to the dep's output, which *should* affect the input-based rule key.
    graphBuilder = new TestActionGraphBuilder();
    dep =
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:dep"))
            .addSrc(Paths.get("source.java"))
            .build(graphBuilder, filesystem);
    filesystem.writeContentsToPath(
        "something else", pathResolver.getCellUnsafeRelPath(dep.getSourcePathToOutput()));
    rule = ruleBuilder.build(graphBuilder);
    ruleFinder = graphBuilder;
    inputBasedRuleKeyFactory =
        new TestInputBasedRuleKeyFactory(
            StackedFileHashCache.createDefaultHashCaches(
                filesystem, FileHashCacheMode.DEFAULT, false),
            ruleFinder);
    RuleKey changedInputBasedRuleKey = inputBasedRuleKeyFactory.build(rule);
    assertThat(changedInputBasedRuleKey, not(equalTo(originalInputRuleKey)));
  }

  @Test
  public void isCacheableIsRespected() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    BuildTarget buildTarget1 = BuildTargetFactory.newInstance("//katana:katana_manifest1");
    BuildTarget buildTarget2 = BuildTargetFactory.newInstance("//katana:katana_manifest2");
    BuildTarget buildTarget3 = BuildTargetFactory.newInstance("//katana:katana_manifest3");

    Genrule genrule1 =
        GenruleBuilder.newGenruleBuilder(buildTarget1)
            .setBash("python convert_to_katana.py AndroidManifest.xml > $OUT")
            .setOut("AndroidManifest.xml")
            .setSrcs(
                ImmutableList.of(
                    PathSourcePath.of(
                        filesystem, filesystem.getPath("katana/convert_to_katana.py")),
                    PathSourcePath.of(
                        filesystem, filesystem.getPath("katana/AndroidManifest.xml"))))
            .setCacheable(null)
            .build(graphBuilder, filesystem);

    Genrule genrule2 =
        GenruleBuilder.newGenruleBuilder(buildTarget2)
            .setBash("python convert_to_katana.py AndroidManifest.xml > $OUT")
            .setOut("AndroidManifest.xml")
            .setSrcs(
                ImmutableList.of(
                    PathSourcePath.of(
                        filesystem, filesystem.getPath("katana/convert_to_katana.py")),
                    PathSourcePath.of(
                        filesystem, filesystem.getPath("katana/AndroidManifest.xml"))))
            .setCacheable(true)
            .build(graphBuilder, filesystem);

    Genrule genrule3 =
        GenruleBuilder.newGenruleBuilder(buildTarget3)
            .setBash("python convert_to_katana.py AndroidManifest.xml > $OUT")
            .setOut("AndroidManifest.xml")
            .setSrcs(
                ImmutableList.of(
                    PathSourcePath.of(
                        filesystem, filesystem.getPath("katana/convert_to_katana.py")),
                    PathSourcePath.of(
                        filesystem, filesystem.getPath("katana/AndroidManifest.xml"))))
            .setCacheable(false)
            .build(graphBuilder, filesystem);

    assertTrue(genrule1.isCacheable());
    assertTrue(genrule2.isCacheable());
    assertFalse(genrule3.isCacheable());
  }

  /**
   * Tests that genrules aren't executed remotely. Ideally, this is a temporary thing while we work
   * out the issues involved.
   */
  @Test
  public void testNoRemoteRegardlessOfNoRemoteParameter() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//:genrule");
    Genrule genrule =
        GenruleBuilder.newGenruleBuilder(buildTarget).setOut("foo").build(graphBuilder);

    BuckEventBus eventBus = new DefaultBuckEventBus(FakeClock.doNotCare(), new BuildId("dontcare"));
    SourcePathRuleFinder ruleFinder =
        new AbstractBuildRuleResolver() {
          @Override
          public Optional<BuildRule> getRuleOptional(BuildTarget buildTarget) {
            return Optional.empty();
          }
        };
    Cells root = new TestCellBuilder().setFilesystem(new FakeProjectFilesystem()).build();
    ModernBuildRuleRemoteExecutionHelper mbrHelper =
        new ModernBuildRuleRemoteExecutionHelper(
            eventBus,
            new GrpcProtocol(),
            ruleFinder,
            root,
            new FileHashCache() {
              @Override
              public HashCode get(Path path) {
                return HashCode.fromInt(0);
              }

              @Override
              public long getSize(Path path) {
                return 0;
              }

              @Override
              public HashCode getForArchiveMember(Path relativeArchivePath, Path memberPath) {
                return HashCode.fromInt(0);
              }

              @Override
              public void invalidate(Path path) {}

              @Override
              public void invalidateAll() {}

              @Override
              public void set(Path path, HashCode hashCode) {}
            },
            ImmutableSet.of(),
            ConsoleParams.of(false, Verbosity.STANDARD_INFORMATION),
            false);

    assertFalse(mbrHelper.supportsRemoteExecution(genrule));
  }

  @Test
  public void canGetDefaultOutputGroup() {
    ProjectFilesystem fakeFileSystem = new FakeProjectFilesystem();
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolver sourcePathResolver = DefaultSourcePathResolver.from(graphBuilder);
    BuildTarget target = BuildTargetFactory.newInstance("//:test_genrule");
    Genrule genrule =
        GenruleBuilder.newGenruleBuilder(target)
            .setCmd("echo hello >> $OUT")
            .setOuts(
                ImmutableMap.of(
                    "label1",
                    ImmutableSet.of("output1a", "output1b"),
                    "label2",
                    ImmutableSet.of("output2a")))
            .setDefaultOuts(ImmutableSet.of("output3"))
            .build(graphBuilder, new FakeProjectFilesystem());

    ImmutableSet<Path> actual =
        convertSourcePathsToPaths(
            sourcePathResolver, genrule.getSourcePathToOutput(OutputLabel.defaultLabel()));
    assertThat(actual, contains(getExpectedPath(fakeFileSystem, target, "output3")));
  }

  @Test
  public void canGetMultipleOutputsForNamedGroups() {
    ProjectFilesystem fakeFileSystem = new FakeProjectFilesystem();
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolver sourcePathResolver = DefaultSourcePathResolver.from(graphBuilder);
    BuildTarget target = BuildTargetFactory.newInstance("//:test_genrule");
    Genrule genrule =
        GenruleBuilder.newGenruleBuilder(target)
            .setCmd("echo hello >> $OUT")
            .setOuts(
                ImmutableMap.of(
                    "label1",
                    ImmutableSet.of("output1a", "output1b"),
                    "label2",
                    ImmutableSet.of("output2a")))
            .setDefaultOuts(ImmutableSet.of("output2a"))
            .build(graphBuilder, new FakeProjectFilesystem());

    ImmutableSet<Path> actual =
        convertSourcePathsToPaths(
            sourcePathResolver, genrule.getSourcePathToOutput(OutputLabel.of("label1")));
    assertThat(
        actual,
        containsInAnyOrder(
            getExpectedPath(fakeFileSystem, target, "output1a"),
            getExpectedPath(fakeFileSystem, target, "output1b")));
  }

  @Test
  public void canGetOutputLabelsForMultipleOutputs() {
    BuildTarget target = BuildTargetFactory.newInstance("//:test_genrule");
    Genrule genrule =
        GenruleBuilder.newGenruleBuilder(target)
            .setCmd("echo hello >> $OUT")
            .setOuts(
                ImmutableMap.of(
                    "label1",
                    ImmutableSet.of("output1a", "output1b"),
                    "label2",
                    ImmutableSet.of("output2a")))
            .setDefaultOuts(ImmutableSet.of("output2a"))
            .build(new TestActionGraphBuilder(), new FakeProjectFilesystem());

    ImmutableSet<OutputLabel> actual = genrule.getOutputLabels();
    assertThat(
        actual,
        containsInAnyOrder(
            OutputLabel.of("label1"), OutputLabel.of("label2"), OutputLabel.defaultLabel()));
  }

  @Test
  public void canGetOutputLabelForSingleOutput() {
    BuildTarget target = BuildTargetFactory.newInstance("//:test_genrule");
    Genrule genrule =
        GenruleBuilder.newGenruleBuilder(target)
            .setCmd("echo hello >> $OUT")
            .setOut("expected")
            .build(new TestActionGraphBuilder(), new FakeProjectFilesystem());

    ImmutableSet<OutputLabel> actual = genrule.getOutputLabels();
    assertThat(actual, containsInAnyOrder(OutputLabel.defaultLabel()));
  }

  private Path getExpectedPath(ProjectFilesystem filesystem, BuildTarget target, String path) {
    return BuildTargetPaths.getGenPath(filesystem.getBuckPaths(), target, "%s").resolve(path);
  }

  private ImmutableSet<Path> convertSourcePathsToPaths(
      SourcePathResolver sourcePathResolver, ImmutableSet<SourcePath> sourcePaths) {
    return sourcePaths.stream()
        .map(sourcePathResolver::getCellUnsafeRelPath)
        .flatMap(Set::stream)
        .map(RelPath::getPath)
        .collect(ImmutableSet.toImmutableSet());
  }
}
