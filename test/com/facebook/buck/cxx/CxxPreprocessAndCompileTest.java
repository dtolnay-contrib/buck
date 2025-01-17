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

package com.facebook.buck.cxx;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;

import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.context.FakeBuildContext;
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.TestCellPathResolver;
import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.ForwardRelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.CellRelativePath;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.impl.DependencyAggregation;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.toolchain.impl.ToolchainProviderBuilder;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.tool.impl.CommandTool;
import com.facebook.buck.core.toolchain.tool.impl.HashedFileTool;
import com.facebook.buck.cxx.toolchain.Compiler;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.cxx.toolchain.DefaultCompiler;
import com.facebook.buck.cxx.toolchain.GccCompiler;
import com.facebook.buck.cxx.toolchain.GccPreprocessor;
import com.facebook.buck.cxx.toolchain.Preprocessor;
import com.facebook.buck.cxx.toolchain.ToolType;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.io.pathformat.PathFormatter;
import com.facebook.buck.rules.args.AddsToRuleKeyFunction;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.rules.keys.TestDefaultRuleKeyFactory;
import com.facebook.buck.rules.modern.SerializationTestHelper;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.testutil.PathNormalizer;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreType;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.Test;

public class CxxPreprocessAndCompileTest {
  private static class PreprocessorWithColorSupport extends GccPreprocessor {
    static final String COLOR_FLAG = "-use-color-in-preprocessor";

    public PreprocessorWithColorSupport(Tool tool) {
      super(tool);
    }
  }

  private static class CompilerWithColorSupport extends DefaultCompiler {

    static final String COLOR_FLAG = "-use-color-in-compiler";

    public CompilerWithColorSupport(Tool tool) {
      super(tool, false);
    }

    @Override
    public Optional<ImmutableList<String>> getFlagsForColorDiagnostics() {
      return Optional.of(ImmutableList.of(COLOR_FLAG));
    }
  }

  private static final Optional<Boolean> DEFAULT_USE_ARG_FILE = Optional.empty();
  private static final CxxToolFlags DEFAULT_TOOL_FLAGS =
      CxxToolFlags.explicitBuilder()
          .addPlatformFlags(StringArg.of("-fsanitize=address"))
          .addRuleFlags(StringArg.of("-O3"))
          .build();
  private static final String DEFAULT_OUTPUT = "test.o";
  private static final SourcePath DEFAULT_INPUT = FakeSourcePath.of("test.cpp");
  private static final CxxSource.Type DEFAULT_INPUT_TYPE = CxxSource.Type.CXX;
  private static final PathSourcePath DEFAULT_WORKING_DIR =
      FakeSourcePath.of(System.getProperty("user.dir"));
  private static final AddsToRuleKeyFunction<FrameworkPath, Optional<Path>>
      DEFAULT_FRAMEWORK_PATH_SEARCH_PATH_FUNCTION = new DefaultFramworkPathSearchPathFunction();

  private static class DefaultFramworkPathSearchPathFunction
      implements AddsToRuleKeyFunction<FrameworkPath, Optional<Path>> {

    @Override
    public Optional<Path> apply(FrameworkPath input) {
      return Optional.of(Paths.get("test", "framework", "path", input.toString()));
    }
  }

  private final ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();

  private final Preprocessor DEFAULT_PREPROCESSOR =
      new GccPreprocessor(
          new HashedFileTool(
              () ->
                  PathSourcePath.of(
                      projectFilesystem,
                      PathNormalizer.toWindowsPathIfNeeded(Paths.get("/root/preprocessor")))));
  private final Compiler DEFAULT_COMPILER =
      new GccCompiler(
          new HashedFileTool(
              () ->
                  PathSourcePath.of(
                      projectFilesystem,
                      PathNormalizer.toWindowsPathIfNeeded(Paths.get("/root/compiler")))),
          ToolType.CXX,
          false,
          false);
  private final Preprocessor PREPROCESSOR_WITH_COLOR_SUPPORT =
      new PreprocessorWithColorSupport(
          new HashedFileTool(
              () ->
                  PathSourcePath.of(
                      projectFilesystem,
                      PathNormalizer.toWindowsPathIfNeeded(Paths.get("/root/preprocessor")))));
  private final Compiler COMPILER_WITH_COLOR_SUPPORT =
      new CompilerWithColorSupport(
          new HashedFileTool(
              () ->
                  PathSourcePath.of(
                      projectFilesystem,
                      PathNormalizer.toWindowsPathIfNeeded(Paths.get("/root/compiler")))));

  @Test
  public void inputChangesCauseRuleKeyChangesForCompilation() {
    SourcePathRuleFinder ruleFinder = new TestActionGraphBuilder();
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    FakeFileHashCache hashCache =
        FakeFileHashCache.createFromStrings(
            ImmutableMap.<String, String>builder()
                .put(
                    PathNormalizer.toWindowsPathIfNeeded(Paths.get("/root/preprocessor"))
                        .toString(),
                    Strings.repeat("a", 40))
                .put(
                    PathNormalizer.toWindowsPathIfNeeded(Paths.get("/root/compiler")).toString(),
                    Strings.repeat("a", 40))
                .put(
                    PathNormalizer.toWindowsPathIfNeeded(Paths.get("test.o")).toString(),
                    Strings.repeat("b", 40))
                .put(
                    PathNormalizer.toWindowsPathIfNeeded(Paths.get("test.cpp")).toString(),
                    Strings.repeat("c", 40))
                .put(
                    PathNormalizer.toWindowsPathIfNeeded(Paths.get("/root/different")).toString(),
                    Strings.repeat("d", 40))
                .put(
                    PathNormalizer.toWindowsPathIfNeeded(Paths.get("foo/test.h")).toString(),
                    Strings.repeat("e", 40))
                .put(
                    PathNormalizer.toWindowsPathIfNeeded(Paths.get("path/to/a/plugin.so"))
                        .toString(),
                    Strings.repeat("f", 40))
                .put(
                    PathNormalizer.toWindowsPathIfNeeded(Paths.get("path/to/a/different/plugin.so"))
                        .toString(),
                    Strings.repeat("a0", 40))
                .build());

    // Generate a rule key for the defaults.

    RuleKey defaultRuleKey =
        new TestDefaultRuleKeyFactory(hashCache, ruleFinder)
            .build(
                CxxPreprocessAndCompile.compile(
                    target,
                    projectFilesystem,
                    ruleFinder,
                    new CompilerDelegate(
                        CxxPlatformUtils.DEFAULT_COMPILER_DEBUG_PATH_SANITIZER,
                        DEFAULT_COMPILER,
                        DEFAULT_TOOL_FLAGS,
                        DEFAULT_USE_ARG_FILE),
                    DEFAULT_OUTPUT,
                    DEFAULT_INPUT,
                    DEFAULT_INPUT_TYPE,
                    CxxPlatformUtils.DEFAULT_COMPILER_DEBUG_PATH_SANITIZER,
                    false));

    // Verify that changing the compiler causes a rulekey change.

    RuleKey compilerChange =
        new TestDefaultRuleKeyFactory(hashCache, ruleFinder)
            .build(
                CxxPreprocessAndCompile.compile(
                    target,
                    projectFilesystem,
                    ruleFinder,
                    new CompilerDelegate(
                        CxxPlatformUtils.DEFAULT_COMPILER_DEBUG_PATH_SANITIZER,
                        new GccCompiler(
                            new HashedFileTool(
                                PathSourcePath.of(
                                    projectFilesystem,
                                    PathNormalizer.toWindowsPathIfNeeded(
                                        Paths.get("/root/different")))),
                            ToolType.CXX,
                            false),
                        DEFAULT_TOOL_FLAGS,
                        DEFAULT_USE_ARG_FILE),
                    DEFAULT_OUTPUT,
                    DEFAULT_INPUT,
                    DEFAULT_INPUT_TYPE,
                    CxxPlatformUtils.DEFAULT_COMPILER_DEBUG_PATH_SANITIZER,
                    false));
    assertNotEquals(defaultRuleKey, compilerChange);

    // Verify that changing the operation causes a rulekey change.

    RuleKey operationChange =
        new TestDefaultRuleKeyFactory(hashCache, ruleFinder)
            .build(
                CxxPreprocessAndCompile.preprocessAndCompile(
                    target,
                    projectFilesystem,
                    ruleFinder,
                    new PreprocessorDelegate(
                        CxxPlatformUtils.DEFAULT_PLATFORM.getHeaderVerification(),
                        DEFAULT_WORKING_DIR,
                        DEFAULT_PREPROCESSOR,
                        PreprocessorFlags.builder().build(),
                        DEFAULT_FRAMEWORK_PATH_SEARCH_PATH_FUNCTION,
                        /* leadingIncludePaths */ Optional.empty(),
                        ImmutableList.of(
                            new DependencyAggregation(
                                target.withFlavors(InternalFlavor.of("deps")),
                                projectFilesystem,
                                ImmutableList.of())),
                        ImmutableSortedSet.of()),
                    new CompilerDelegate(
                        CxxPlatformUtils.DEFAULT_COMPILER_DEBUG_PATH_SANITIZER,
                        DEFAULT_COMPILER,
                        DEFAULT_TOOL_FLAGS,
                        DEFAULT_USE_ARG_FILE),
                    DEFAULT_OUTPUT,
                    DEFAULT_INPUT,
                    DEFAULT_INPUT_TYPE,
                    Optional.empty(),
                    CxxPlatformUtils.DEFAULT_COMPILER_DEBUG_PATH_SANITIZER,
                    false));
    assertNotEquals(defaultRuleKey, operationChange);

    // Verify that changing the platform flags causes a rulekey change.

    RuleKey platformFlagsChange =
        new TestDefaultRuleKeyFactory(hashCache, ruleFinder)
            .build(
                CxxPreprocessAndCompile.compile(
                    target,
                    projectFilesystem,
                    ruleFinder,
                    new CompilerDelegate(
                        CxxPlatformUtils.DEFAULT_COMPILER_DEBUG_PATH_SANITIZER,
                        DEFAULT_COMPILER,
                        CxxToolFlags.explicitBuilder()
                            .addPlatformFlags(StringArg.of("-different"))
                            .setRuleFlags(DEFAULT_TOOL_FLAGS.getRuleFlags())
                            .build(),
                        DEFAULT_USE_ARG_FILE),
                    DEFAULT_OUTPUT,
                    DEFAULT_INPUT,
                    DEFAULT_INPUT_TYPE,
                    CxxPlatformUtils.DEFAULT_COMPILER_DEBUG_PATH_SANITIZER,
                    false));
    assertNotEquals(defaultRuleKey, platformFlagsChange);

    // Verify that changing the rule flags causes a rulekey change.

    RuleKey ruleFlagsChange =
        new TestDefaultRuleKeyFactory(hashCache, ruleFinder)
            .build(
                CxxPreprocessAndCompile.compile(
                    target,
                    projectFilesystem,
                    ruleFinder,
                    new CompilerDelegate(
                        CxxPlatformUtils.DEFAULT_COMPILER_DEBUG_PATH_SANITIZER,
                        DEFAULT_COMPILER,
                        CxxToolFlags.explicitBuilder()
                            .setPlatformFlags(DEFAULT_TOOL_FLAGS.getPlatformFlags())
                            .addAllRuleFlags(StringArg.from("-other", "flags"))
                            .build(),
                        DEFAULT_USE_ARG_FILE),
                    DEFAULT_OUTPUT,
                    DEFAULT_INPUT,
                    DEFAULT_INPUT_TYPE,
                    CxxPlatformUtils.DEFAULT_COMPILER_DEBUG_PATH_SANITIZER,
                    false));
    assertNotEquals(defaultRuleKey, ruleFlagsChange);

    // Verify that changing the input causes a rulekey change.

    RuleKey inputChange =
        new TestDefaultRuleKeyFactory(hashCache, ruleFinder)
            .build(
                CxxPreprocessAndCompile.compile(
                    target,
                    projectFilesystem,
                    ruleFinder,
                    new CompilerDelegate(
                        CxxPlatformUtils.DEFAULT_COMPILER_DEBUG_PATH_SANITIZER,
                        DEFAULT_COMPILER,
                        DEFAULT_TOOL_FLAGS,
                        DEFAULT_USE_ARG_FILE),
                    DEFAULT_OUTPUT,
                    FakeSourcePath.of(
                        PathNormalizer.toWindowsPathIfNeeded(Paths.get("/root/different"))),
                    DEFAULT_INPUT_TYPE,
                    CxxPlatformUtils.DEFAULT_COMPILER_DEBUG_PATH_SANITIZER,
                    false));
    assertNotEquals(defaultRuleKey, inputChange);
  }

  @Test
  public void preprocessorFlagsRuleKeyChangesCauseRuleKeyChangesForPreprocessing() {
    SourcePathRuleFinder ruleFinder = new TestActionGraphBuilder();
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    FakeFileHashCache hashCache =
        FakeFileHashCache.createFromStrings(
            ImmutableMap.<String, String>builder()
                .put(
                    PathNormalizer.toWindowsPathIfNeeded(Paths.get("/root/preprocessor"))
                        .toString(),
                    Strings.repeat("a", 40))
                .put(
                    PathNormalizer.toWindowsPathIfNeeded(Paths.get("/root/compiler")).toString(),
                    Strings.repeat("a", 40))
                .put(
                    PathNormalizer.toWindowsPathIfNeeded(Paths.get("test.o")).toString(),
                    Strings.repeat("b", 40))
                .put(
                    PathNormalizer.toWindowsPathIfNeeded(Paths.get("test.cpp")).toString(),
                    Strings.repeat("c", 40))
                .put(
                    PathNormalizer.toWindowsPathIfNeeded(Paths.get("/root/different")).toString(),
                    Strings.repeat("d", 40))
                .put(
                    PathNormalizer.toWindowsPathIfNeeded(Paths.get("foo/test.h")).toString(),
                    Strings.repeat("e", 40))
                .put(
                    PathNormalizer.toWindowsPathIfNeeded(Paths.get("path/to/a/plugin.so"))
                        .toString(),
                    Strings.repeat("f", 40))
                .put(
                    PathNormalizer.toWindowsPathIfNeeded(Paths.get("path/to/a/different/plugin.so"))
                        .toString(),
                    Strings.repeat("a0", 40))
                .build());

    class TestData {
      public RuleKey generate(PreprocessorFlags flags) {
        return new TestDefaultRuleKeyFactory(hashCache, ruleFinder)
            .build(
                CxxPreprocessAndCompile.preprocessAndCompile(
                    target,
                    projectFilesystem,
                    ruleFinder,
                    new PreprocessorDelegate(
                        CxxPlatformUtils.DEFAULT_PLATFORM.getHeaderVerification(),
                        DEFAULT_WORKING_DIR,
                        DEFAULT_PREPROCESSOR,
                        flags,
                        DEFAULT_FRAMEWORK_PATH_SEARCH_PATH_FUNCTION,
                        /* leadingIncludePaths */ Optional.empty(),
                        ImmutableList.of(
                            new DependencyAggregation(
                                target.withFlavors(InternalFlavor.of("deps")),
                                projectFilesystem,
                                ImmutableList.of())),
                        ImmutableSortedSet.of()),
                    new CompilerDelegate(
                        CxxPlatformUtils.DEFAULT_COMPILER_DEBUG_PATH_SANITIZER,
                        DEFAULT_COMPILER,
                        CxxToolFlags.of(),
                        DEFAULT_USE_ARG_FILE),
                    DEFAULT_OUTPUT,
                    DEFAULT_INPUT,
                    DEFAULT_INPUT_TYPE,
                    Optional.empty(),
                    CxxPlatformUtils.DEFAULT_COMPILER_DEBUG_PATH_SANITIZER,
                    false));
      }
    }
    TestData testData = new TestData();

    PreprocessorFlags defaultFlags = PreprocessorFlags.builder().build();
    PreprocessorFlags alteredFlags =
        defaultFlags.withFrameworkPaths(
            ImmutableList.of(
                FrameworkPath.ofSourcePath(
                    FakeSourcePath.of(
                        PathNormalizer.toWindowsPathIfNeeded(Paths.get("/root/different"))))));
    assertNotEquals(testData.generate(defaultFlags), testData.generate(alteredFlags));
  }

  @Test
  public void usesCorrectCommandForCompile() {
    // Setup some dummy values for inputs to the CxxPreprocessAndCompile.
    SourcePathRuleFinder ruleFinder = new TestActionGraphBuilder();
    BuildContext context =
        FakeBuildContext.withSourcePathResolver(ruleFinder.getSourcePathResolver());
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    CxxToolFlags flags =
        CxxToolFlags.explicitBuilder()
            .addPlatformFlags(StringArg.of("-ffunction-sections"))
            .addRuleFlags(StringArg.of("-O3"))
            .build();
    String outputName = "test.o";
    Path input = Paths.get("test.ii");

    CxxPreprocessAndCompile buildRule =
        CxxPreprocessAndCompile.compile(
            target,
            projectFilesystem,
            ruleFinder,
            new CompilerDelegate(
                NoopDebugPathSanitizer.INSTANCE, DEFAULT_COMPILER, flags, DEFAULT_USE_ARG_FILE),
            outputName,
            FakeSourcePath.of(input.toString()),
            DEFAULT_INPUT_TYPE,
            NoopDebugPathSanitizer.INSTANCE,
            false);

    ImmutableList<String> expectedCompileCommand =
        ImmutableList.<String>builder()
            .add(PathNormalizer.toWindowsPathIfNeeded(Paths.get("/root/compiler")).toString())
            .add("-x", "c++")
            .add("-ffunction-sections")
            .add("-O3")
            .add(
                "-o",
                BuildTargetPaths.getGenPath(projectFilesystem.getBuckPaths(), target, "%s__/test.o")
                    .toString())
            .add("-c")
            .add(input.toString())
            .build();
    ImmutableList<String> actualCompileCommand =
        buildRule.makeMainStep(context, false).getCommand();
    assertEquals(expectedCompileCommand, actualCompileCommand);
  }

  @Test
  public void compilerAndPreprocessorAreAlwaysReturnedFromGetInputsAfterBuildingLocally()
      throws Exception {
    CellPathResolver cellPathResolver = TestCellPathResolver.get(projectFilesystem);
    SourcePath preprocessor = FakeSourcePath.of(projectFilesystem, "preprocessor");
    Tool preprocessorTool = new CommandTool.Builder().addInput(preprocessor).build();

    SourcePath compiler = FakeSourcePath.of(projectFilesystem, "compiler");
    Tool compilerTool = new CommandTool.Builder().addInput(compiler).build();

    SourcePathRuleFinder ruleFinder = new TestActionGraphBuilder();
    SourcePathResolverAdapter pathResolver = ruleFinder.getSourcePathResolver();
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    BuildContext context = FakeBuildContext.withSourcePathResolver(pathResolver);

    projectFilesystem.writeContentsToPath(
        "test.o: " + pathResolver.getCellUnsafeRelPath(DEFAULT_INPUT) + " ",
        BuildTargetPaths.getGenPath(
                projectFilesystem.getBuckPaths(),
                BuildTargetFactory.newInstance("//foo:bar"),
                "%s__")
            .resolve("test.o.dep"));
    PathSourcePath fakeInput = FakeSourcePath.of(projectFilesystem, "test.cpp");

    CxxPreprocessAndCompile cxxPreprocess =
        CxxPreprocessAndCompile.preprocessAndCompile(
            target,
            projectFilesystem,
            ruleFinder,
            new PreprocessorDelegate(
                CxxPlatformUtils.DEFAULT_PLATFORM.getHeaderVerification(),
                DEFAULT_WORKING_DIR,
                new GccPreprocessor(preprocessorTool),
                PreprocessorFlags.builder().build(),
                DEFAULT_FRAMEWORK_PATH_SEARCH_PATH_FUNCTION,
                /* leadingIncludePaths */ Optional.empty(),
                ImmutableList.of(
                    new DependencyAggregation(
                        target.withFlavors(InternalFlavor.of("deps")),
                        projectFilesystem,
                        ImmutableList.of())),
                ImmutableSortedSet.of()),
            new CompilerDelegate(
                CxxPlatformUtils.DEFAULT_COMPILER_DEBUG_PATH_SANITIZER,
                DEFAULT_COMPILER,
                CxxToolFlags.of(),
                DEFAULT_USE_ARG_FILE),
            DEFAULT_OUTPUT,
            fakeInput,
            DEFAULT_INPUT_TYPE,
            Optional.empty(),
            CxxPlatformUtils.DEFAULT_COMPILER_DEBUG_PATH_SANITIZER,
            false);
    assertThat(
        cxxPreprocess.getInputsAfterBuildingLocally(context, cellPathResolver),
        not(hasItem(preprocessor)));
    assertFalse(cxxPreprocess.getCoveredByDepFilePredicate(pathResolver).test(preprocessor));

    CxxPreprocessAndCompile cxxCompile =
        CxxPreprocessAndCompile.compile(
            target,
            projectFilesystem,
            ruleFinder,
            new CompilerDelegate(
                CxxPlatformUtils.DEFAULT_COMPILER_DEBUG_PATH_SANITIZER,
                new GccCompiler(compilerTool, ToolType.CXX, false),
                CxxToolFlags.of(),
                DEFAULT_USE_ARG_FILE),
            DEFAULT_OUTPUT,
            fakeInput,
            DEFAULT_INPUT_TYPE,
            CxxPlatformUtils.DEFAULT_COMPILER_DEBUG_PATH_SANITIZER,
            false);
    assertThat(
        cxxCompile.getInputsAfterBuildingLocally(context, cellPathResolver),
        not(hasItem(compiler)));
    assertFalse(cxxCompile.getCoveredByDepFilePredicate(pathResolver).test(compiler));
  }

  @Test
  public void usesColorFlagForCompilationWhenRequested() {
    SourcePathRuleFinder ruleFinder = new TestActionGraphBuilder();
    BuildContext context =
        FakeBuildContext.withSourcePathResolver(ruleFinder.getSourcePathResolver());
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    String output = "test.o";
    Path input = Paths.get("test.ii");

    CompilerDelegate compilerDelegate =
        new CompilerDelegate(
            CxxPlatformUtils.DEFAULT_COMPILER_DEBUG_PATH_SANITIZER,
            COMPILER_WITH_COLOR_SUPPORT,
            CxxToolFlags.of(),
            DEFAULT_USE_ARG_FILE);

    CxxPreprocessAndCompile buildRule =
        CxxPreprocessAndCompile.compile(
            target,
            projectFilesystem,
            ruleFinder,
            compilerDelegate,
            output,
            FakeSourcePath.of(input.toString()),
            DEFAULT_INPUT_TYPE,
            CxxPlatformUtils.DEFAULT_COMPILER_DEBUG_PATH_SANITIZER,
            false);

    ImmutableList<String> command =
        buildRule.makeMainStep(context, false).getArguments(/* allowColorsInDiagnostics */ false);
    assertThat(command, not(hasItem(CompilerWithColorSupport.COLOR_FLAG)));

    command =
        buildRule.makeMainStep(context, false).getArguments(/* allowColorsInDiagnostics */ true);
    assertThat(command, hasItem(CompilerWithColorSupport.COLOR_FLAG));
  }

  @Test
  public void usesColorFlagForPreprocessingWhenRequested() {
    SourcePathRuleFinder ruleFinder = new TestActionGraphBuilder();
    BuildContext context =
        FakeBuildContext.withSourcePathResolver(ruleFinder.getSourcePathResolver());
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    String output = "test.ii";
    Path input = Paths.get("test.cpp");

    CxxPreprocessAndCompile buildRule =
        CxxPreprocessAndCompile.preprocessAndCompile(
            target,
            projectFilesystem,
            ruleFinder,
            new PreprocessorDelegate(
                CxxPlatformUtils.DEFAULT_PLATFORM.getHeaderVerification(),
                DEFAULT_WORKING_DIR,
                PREPROCESSOR_WITH_COLOR_SUPPORT,
                PreprocessorFlags.builder().build(),
                DEFAULT_FRAMEWORK_PATH_SEARCH_PATH_FUNCTION,
                /* leadingIncludePaths */ Optional.empty(),
                ImmutableList.of(
                    new DependencyAggregation(
                        target.withFlavors(InternalFlavor.of("deps")),
                        projectFilesystem,
                        ImmutableList.of())),
                ImmutableSortedSet.of()),
            new CompilerDelegate(
                CxxPlatformUtils.DEFAULT_COMPILER_DEBUG_PATH_SANITIZER,
                COMPILER_WITH_COLOR_SUPPORT,
                CxxToolFlags.of(),
                DEFAULT_USE_ARG_FILE),
            output,
            FakeSourcePath.of(input.toString()),
            DEFAULT_INPUT_TYPE,
            Optional.empty(),
            CxxPlatformUtils.DEFAULT_COMPILER_DEBUG_PATH_SANITIZER,
            false);

    ImmutableList<String> command =
        buildRule.makeMainStep(context, false).getArguments(/* allowColorsInDiagnostics */ false);
    assertThat(command, not(hasItem(PreprocessorWithColorSupport.COLOR_FLAG)));

    command =
        buildRule.makeMainStep(context, false).getArguments(/* allowColorsInDiagnostics */ true);
    assertThat(command, hasItem(CompilerWithColorSupport.COLOR_FLAG));
  }

  @Test
  public void testGetGcnoFile() {
    AbsPath input =
        projectFilesystem.resolve(
            PathNormalizer.toWindowsPathIfNeeded(Paths.get("foo/bar.m.o")).toString());
    AbsPath output = CxxPreprocessAndCompile.getGcnoPath(input);
    assertEquals(
        projectFilesystem.resolve(
            PathNormalizer.toWindowsPathIfNeeded(Paths.get("foo/bar.m.gcno"))),
        output.getPath());
  }

  @Test
  public void usesUnixPathSeparatorForCompile() {
    // Setup some dummy values for inputs to the CxxPreprocessAndCompile.
    SourcePathRuleFinder ruleFinder = new TestActionGraphBuilder();
    BuildContext context =
        FakeBuildContext.withSourcePathResolver(ruleFinder.getSourcePathResolver());
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    Path includePath = PathNormalizer.toWindowsPathIfNeeded(Paths.get("/foo/bar/zap"));
    String includedPathStr = PathFormatter.pathWithUnixSeparators(includePath);

    CxxToolFlags flags =
        CxxToolFlags.explicitBuilder()
            .addPlatformFlags(StringArg.of("-ffunction-sections"))
            .addRuleFlags(StringArg.of("-O3"))
            .addRuleFlags(StringArg.of("-I " + includedPathStr))
            .build();

    String slash = File.separator;
    String outputName = "baz" + slash + "test.o";
    Path input = Paths.get("foo" + slash + "test.ii");

    CxxPreprocessAndCompile buildRule =
        CxxPreprocessAndCompile.compile(
            target,
            projectFilesystem,
            ruleFinder,
            new CompilerDelegate(
                NoopDebugPathSanitizer.INSTANCE,
                new GccCompiler(
                    new HashedFileTool(
                        () ->
                            PathSourcePath.of(
                                projectFilesystem,
                                PathNormalizer.toWindowsPathIfNeeded(Paths.get("/root/compiler")))),
                    ToolType.CXX,
                    false),
                flags,
                DEFAULT_USE_ARG_FILE),
            outputName,
            FakeSourcePath.of(input.toString()),
            DEFAULT_INPUT_TYPE,
            NoopDebugPathSanitizer.INSTANCE,
            false);

    ImmutableList<String> expectedCompileCommand =
        ImmutableList.<String>builder()
            .add(PathNormalizer.toWindowsPathIfNeeded(Paths.get("/root/compiler")).toString())
            .add("-x", "c++")
            .add("-ffunction-sections")
            .add("-O3")
            .add("-I " + PathFormatter.pathWithUnixSeparators(includePath))
            .add(
                "-o",
                "buck-out/gen/"
                    + BuildTargetPaths.getBasePath(
                        projectFilesystem
                            .getBuckPaths()
                            .shouldIncludeTargetConfigHash(
                                CellRelativePath.of(
                                    CanonicalCellName.rootCell(), ForwardRelPath.of("foo"))),
                        BuildTargetFactory.newInstance("//foo:bar"),
                        "%s__")
                    + "/baz/test.o")
            .add("-c")
            .add(PathFormatter.pathWithUnixSeparators(input.toString()))
            .build();
    ImmutableList<String> actualCompileCommand =
        buildRule.makeMainStep(context, false).getCommand();
    assertEquals(expectedCompileCommand, actualCompileCommand);
  }

  @JsonIgnoreType
  abstract static class FileSystemMixIn {}

  abstract static class MemoizingSupplierMixIn<T> {
    @JsonIgnore Supplier<T> delegate;
  }

  abstract static class PreprocessorDelegateMixIn {
    @JsonIgnore Optional<BuildRule> aggregatedDeps;
  }

  @Test
  public void testSerialization() throws IOException, ClassNotFoundException {
    SourcePathRuleFinder ruleFinder = new TestActionGraphBuilder();
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    Path includePath = PathNormalizer.toWindowsPathIfNeeded(Paths.get("/foo/bar/zap"));
    String includedPathStr = PathFormatter.pathWithUnixSeparators(includePath);
    CxxToolFlags cxxToolFlags =
        CxxToolFlags.explicitBuilder()
            .addPlatformFlags(StringArg.of("-ffunction-sections"))
            .addRuleFlags(StringArg.of("-O3"))
            .addRuleFlags(StringArg.of("-I " + includedPathStr))
            .build();
    PreprocessorFlags preprocessorFlags =
        PreprocessorFlags.builder()
            .addIncludes(
                CxxHeadersDir.of(
                    CxxPreprocessables.IncludeType.SYSTEM, FakeSourcePath.of("foo/bar")),
                CxxHeadersDir.of(CxxPreprocessables.IncludeType.SYSTEM, FakeSourcePath.of("test")))
            .build()
            .withFrameworkPaths(
                ImmutableList.of(
                    FrameworkPath.ofSourcePath(
                        FakeSourcePath.of(
                            PathNormalizer.toWindowsPathIfNeeded(Paths.get("root/different"))))));
    Optional<CxxIncludePaths> leadingIncludePaths =
        Optional.of(preprocessorFlags.getCxxIncludePaths());
    PreprocessorDelegate preprocessorDelegate =
        new PreprocessorDelegate(
            CxxPlatformUtils.DEFAULT_PLATFORM.getHeaderVerification(),
            FakeSourcePath.of("working/dir"),
            new GccPreprocessor(
                new HashedFileTool(
                    () ->
                        PathSourcePath.of(
                            projectFilesystem,
                            PathNormalizer.toWindowsPathIfNeeded(Paths.get("repo/preprocessor"))))),
            preprocessorFlags,
            DEFAULT_FRAMEWORK_PATH_SEARCH_PATH_FUNCTION,
            leadingIncludePaths,
            ImmutableList.of(
                new DependencyAggregation(
                    target.withFlavors(InternalFlavor.of("deps")),
                    projectFilesystem,
                    ImmutableList.of())),
            ImmutableSortedSet.of("white", "list"));
    CompilerDelegate compilerDelegate =
        new CompilerDelegate(
            CxxPlatformUtils.DEFAULT_COMPILER_DEBUG_PATH_SANITIZER,
            new GccCompiler(
                new HashedFileTool(
                    () ->
                        PathSourcePath.of(
                            projectFilesystem,
                            PathNormalizer.toWindowsPathIfNeeded(Paths.get("repo/compiler")))),
                ToolType.CXX,
                false,
                false),
            cxxToolFlags,
            DEFAULT_USE_ARG_FILE);
    CxxPrecompiledHeader precompiledHeader =
        new CxxPrecompiledHeader(
            /* canPrecompile */ false,
            target,
            new FakeProjectFilesystem(),
            ImmutableSortedSet.of(),
            Paths.get("dir/foo.hash1.hash2.hpp"),
            preprocessorDelegate,
            compilerDelegate,
            CxxToolFlags.of(),
            FakeSourcePath.of("foo.h"),
            CxxSource.Type.C,
            CxxPlatformUtils.DEFAULT_COMPILER_DEBUG_PATH_SANITIZER,
            false);

    CxxPreprocessAndCompile.Impl cxxPreprocessAndCompile =
        new CxxPreprocessAndCompile.Impl(
            target,
            Optional.of(preprocessorDelegate),
            compilerDelegate,
            DEFAULT_OUTPUT,
            DEFAULT_INPUT,
            Optional.of(precompiledHeader),
            DEFAULT_INPUT_TYPE,
            CxxPlatformUtils.DEFAULT_COMPILER_DEBUG_PATH_SANITIZER,
            false);

    CxxPreprocessAndCompile.Impl reconstructed =
        SerializationTestHelper.serializeAndDeserialize(
            cxxPreprocessAndCompile,
            ruleFinder,
            TestCellPathResolver.get(projectFilesystem),
            ruleFinder.getSourcePathResolver(),
            new ToolchainProviderBuilder().build(),
            cellPath -> projectFilesystem);

    ObjectMapper objectMapper = ObjectMappers.legacyCreate();
    // TODO(nga): must not configure own mapper
    objectMapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
    objectMapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
    objectMapper.addMixIn(FileSystem.class, FileSystemMixIn.class);
    objectMapper.addMixIn(
        Class.forName("com.google.common.base.Suppliers$NonSerializableMemoizingSupplier"),
        MemoizingSupplierMixIn.class);
    objectMapper.addMixIn(PreprocessorDelegate.class, PreprocessorDelegateMixIn.class);

    String originalStr =
        objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(cxxPreprocessAndCompile);
    String reconstructedSir =
        objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(reconstructed);

    assertEquals(originalStr, reconstructedSir);
  }
}
