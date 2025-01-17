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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.android.toolchain.AndroidPlatformTarget;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.context.FakeBuildContext;
import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.toolchain.tool.impl.testutil.SimpleTool;
import com.facebook.buck.core.toolchain.toolprovider.impl.ConstantToolProvider;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.ProjectFilesystemUtils;
import com.facebook.buck.rules.coercer.ManifestEntries;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.Verbosity;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/** Test generation of command line flags based on creation parameters */
public class AaptStepTest {

  private final AbsPath basePath =
      AbsPath.of(Paths.get("/java/com/facebook/buck/example").toAbsolutePath());
  private final Path proguardConfig = basePath.resolve("mock_proguard.txt").getPath();

  private AaptStep buildAaptStep(
      Path pathToGeneratedProguardConfig,
      boolean isCrunchFiles,
      boolean includesVectorDrawables,
      ManifestEntries manifestEntries) {
    return buildAaptStep(
        pathToGeneratedProguardConfig,
        isCrunchFiles,
        includesVectorDrawables,
        manifestEntries,
        ImmutableList.of());
  }

  /**
   * Build an AaptStep that can be used to generate a shell command. Should only be used for
   * checking the generated command, since it does not refer to useful directories (so it can't be
   * executed).
   */
  private AaptStep buildAaptStep(
      Path pathToGeneratedProguardConfig,
      boolean isCrunchFiles,
      boolean includesVectorDrawables,
      ManifestEntries manifestEntries,
      ImmutableList<String> additionalAaptParams) {

    AndroidPlatformTarget androidPlatformTarget =
        AndroidPlatformTarget.of(
            "android",
            basePath.resolve("mock_android.jar").getPath(),
            /* bootclasspathEntries= */ ImmutableList.of(),
            () -> new SimpleTool("mock_aapt_bin"),
            /* aapt2ToolProvider= */ new ConstantToolProvider(new SimpleTool("")),
            /* adbExecutable= */ Paths.get(""),
            /* aidlExecutable= */ Paths.get(""),
            /* zipalignToolProvider= */ new ConstantToolProvider(new SimpleTool("")),
            /* dxExecutable= */ Paths.get(""),
            /* d8Executable= */ Paths.get(""),
            /* androidFrameworkIdlFile= */ Paths.get(""),
            /* proguardJar= */ Paths.get(""),
            /* proguardConfig= */ Paths.get(""),
            /* optimizedProguardConfig= */ Paths.get(""));
    AbsPath rootPath = FakeProjectFilesystem.createJavaOnlyFilesystem().getRootPath();
    BuildContext buildContext = FakeBuildContext.NOOP_CONTEXT.withBuildCellRootPath(rootPath);

    return new AaptStep(
        /* workingDirectory= */ basePath,
        ProjectFilesystemUtils.relativize(rootPath, buildContext.getBuildCellRootPath()),
        /* manifestDirectory= */ basePath.resolve("AndroidManifest.xml").getPath(),
        /* resDirectories= */ ImmutableList.of(),
        /* assetsDirectories= */ ImmutableSortedSet.of(),
        /* pathToAndroidJar= */ androidPlatformTarget.getAndroidJar(),
        /* pathToOutputApk= */ basePath.resolve("build").resolve("out.apk").getPath(),
        /* pathToRDotDText= */ basePath.resolve("r").getPath(),
        pathToGeneratedProguardConfig,
        ImmutableList.of(),
        isCrunchFiles,
        includesVectorDrawables,
        manifestEntries,
        androidPlatformTarget
            .getAaptExecutable()
            .get()
            .getCommandPrefix(new TestActionGraphBuilder().getSourcePathResolver()),
        additionalAaptParams,
        false);
  }

  /**
   * Create an execution context with the given verbosity level. The execution context will yield
   * fake values relative to the base path for all target queries. The mock context returned has not
   * been replayed, so the calling code may add additional expectations, and is responsible for
   * calling replay().
   */
  private StepExecutionContext createTestExecutionContext(Verbosity verbosity) {
    return TestExecutionContext.newBuilder().setConsole(new TestConsole(verbosity)).build();
  }

  @Test
  public void shouldEmitVerbosityFlagWithVerboseContext() {
    AaptStep aaptStep = buildAaptStep(proguardConfig, false, false, ManifestEntries.empty());
    StepExecutionContext executionContext = createTestExecutionContext(Verbosity.ALL);

    ImmutableList<String> command = aaptStep.getShellCommandInternal(executionContext);
    assertTrue(command.contains("-v"));
  }

  @Test
  public void shouldNotEmitVerbosityFlagWithQuietContext() {
    AaptStep aaptStep = buildAaptStep(proguardConfig, false, false, ManifestEntries.empty());
    StepExecutionContext executionContext = createTestExecutionContext(Verbosity.SILENT);

    ImmutableList<String> command = aaptStep.getShellCommandInternal(executionContext);
    assertFalse(command.contains("-v"));
  }

  @Test
  public void shouldEmitGFlagIfProguardConfigPresent() {
    AaptStep aaptStep = buildAaptStep(proguardConfig, false, false, ManifestEntries.empty());
    StepExecutionContext executionContext = createTestExecutionContext(Verbosity.ALL);

    ImmutableList<String> command = aaptStep.getShellCommandInternal(executionContext);

    assertTrue(command.contains("-G"));
    String proguardConfigPath = MorePaths.pathWithPlatformSeparators(proguardConfig);
    assertTrue(command.contains(proguardConfigPath));
  }

  @Test
  public void shouldEmitNoCrunchFlagIfNotCrunch() {
    AaptStep aaptStep = buildAaptStep(proguardConfig, false, false, ManifestEntries.empty());
    StepExecutionContext executionContext = createTestExecutionContext(Verbosity.ALL);

    ImmutableList<String> command = aaptStep.getShellCommandInternal(executionContext);

    assertTrue(command.contains("--no-crunch"));
  }

  @Test
  public void shouldNotEmitNoCrunchFlagIfCrunch() {
    AaptStep aaptStep = buildAaptStep(proguardConfig, true, false, ManifestEntries.empty());
    StepExecutionContext executionContext = createTestExecutionContext(Verbosity.ALL);

    ImmutableList<String> command = aaptStep.getShellCommandInternal(executionContext);

    assertFalse(command.contains("--no-crunch"));
  }

  @Test
  public void shouldEmitNoVersionVectorsFlagIfRequested() {
    AaptStep aaptStep = buildAaptStep(proguardConfig, false, true, ManifestEntries.empty());
    StepExecutionContext executionContext = createTestExecutionContext(Verbosity.ALL);

    ImmutableList<String> command = aaptStep.getShellCommandInternal(executionContext);

    assertTrue(command.contains("--no-version-vectors"));
  }

  @Test
  public void shouldNotEmitNoVersionVectorsFlagIfNotRequested() {
    AaptStep aaptStep = buildAaptStep(proguardConfig, false, false, ManifestEntries.empty());
    StepExecutionContext executionContext = createTestExecutionContext(Verbosity.ALL);

    ImmutableList<String> command = aaptStep.getShellCommandInternal(executionContext);

    assertFalse(command.contains("--no-version-vectors"));
  }

  @Test
  public void shouldEmitFlagsForManifestEntries() {
    ManifestEntries entries =
        ManifestEntries.builder()
            .setMinSdkVersion(3)
            .setTargetSdkVersion(5)
            .setVersionCode(7)
            .setVersionName("eleven")
            .setDebugMode(true)
            .build();
    AaptStep aaptStep = buildAaptStep(proguardConfig, true, false, entries);
    StepExecutionContext executionContext = createTestExecutionContext(Verbosity.ALL);
    ImmutableList<String> command = aaptStep.getShellCommandInternal(executionContext);

    assertTrue(command.contains("--min-sdk-version"));
    assertEquals("3", command.get(command.indexOf("--min-sdk-version") + 1));

    assertTrue(command.contains("--target-sdk-version"));
    assertEquals("5", command.get(command.indexOf("--target-sdk-version") + 1));

    assertTrue(command.contains("--version-code"));
    assertEquals("7", command.get(command.indexOf("--version-code") + 1));

    assertTrue(command.contains("--version-name"));
    assertEquals("eleven", command.get(command.indexOf("--version-name") + 1));

    assertTrue(command.contains("--debug-mode"));
    // This should be present because we've emitted > 0 manifest-changing flags.
    assertTrue(command.contains("--error-on-failed-insert"));
  }

  @Test
  public void shouldNotEmitFailOnInsertWithoutManifestEntries() {
    AaptStep aaptStep = buildAaptStep(proguardConfig, true, false, ManifestEntries.empty());
    StepExecutionContext executionContext = createTestExecutionContext(Verbosity.ALL);
    ImmutableList<String> command = aaptStep.getShellCommandInternal(executionContext);
    assertFalse(command.contains("--error-on-failed-insert"));
  }

  @Test
  public void shouldEmitAdditionalAaptParams() {
    AaptStep aaptStep =
        buildAaptStep(
            proguardConfig,
            false,
            false,
            ManifestEntries.empty(),
            ImmutableList.of("--shared-lib"));
    StepExecutionContext executionContext = createTestExecutionContext(Verbosity.ALL);
    ImmutableList<String> command = aaptStep.getShellCommandInternal(executionContext);
    assertTrue(command.contains("--shared-lib"));
  }
}
