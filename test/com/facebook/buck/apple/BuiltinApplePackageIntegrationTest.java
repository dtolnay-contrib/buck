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

package com.facebook.buck.apple;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.apple.toolchain.ApplePlatform;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.io.filesystem.impl.DefaultProjectFilesystemFactory;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.testutil.integration.ZipInspector;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.types.Pair;
import com.facebook.buck.util.unarchive.ArchiveFormat;
import com.facebook.buck.util.unarchive.ExistingFileMode;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import org.hamcrest.Matchers;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class BuiltinApplePackageIntegrationTest {
  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private ProjectFilesystem filesystem;

  @Before
  public void setUp() {
    Assume.assumeThat(Platform.detect(), Matchers.is(Platform.MACOS));
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    filesystem = TestProjectFilesystems.createProjectFilesystem(tmp.getRoot());
  }

  private static boolean isDirEmpty(Path directory) throws IOException {
    try (DirectoryStream<Path> dirStream = Files.newDirectoryStream(directory)) {
      return !dirStream.iterator().hasNext();
    }
  }

  @Test
  public void packageHasProperStructure() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "simple_application_bundle_no_debug", tmp);
    workspace.setUp();
    workspace.enableDirCache();

    BuildTarget appTarget =
        BuildTargetFactory.newInstance("//:DemoApp#no-debug,no-include-frameworks");
    workspace
        .runBuckCommand("build", appTarget.getUnflavoredBuildTarget().getFullyQualifiedName())
        .assertSuccess();

    workspace.getBuildLog().assertTargetBuiltLocally(appTarget);

    workspace.runBuckCommand("clean", "--keep-cache").assertSuccess();

    BuildTarget packageTarget = BuildTargetFactory.newInstance("//:DemoAppPackage");
    workspace.runBuckCommand("build", packageTarget.getFullyQualifiedName()).assertSuccess();

    workspace.getBuildLog().assertTargetWasFetchedFromCache(appTarget);
    workspace.getBuildLog().assertTargetBuiltLocally(packageTarget);

    Path templateDir =
        TestDataHelper.getTestDataScenario(this, "simple_application_bundle_no_debug");

    ZipInspector zipInspector =
        new ZipInspector(
            workspace.getPath(
                BuildTargetPaths.getGenPath(filesystem.getBuckPaths(), packageTarget, "%s.ipa")));
    zipInspector.assertFileExists("Payload/DemoApp.app/DemoApp");
    zipInspector.assertFileDoesNotExist("WatchKitSupport");
    zipInspector.assertFileDoesNotExist("WatchKitSupport2");
    zipInspector.assertFileContents(
        "Payload/DemoApp.app/PkgInfo",
        new String(
            Files.readAllBytes(
                templateDir.resolve("DemoApp_output.expected/DemoApp.app/PkgInfo.expected")),
            UTF_8));
  }

  @Test
  public void packageHasProperStructureForSwift() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "simple_application_bundle_swift_no_debug", tmp);
    workspace.setUp();
    workspace.enableDirCache();

    BuildTarget packageTarget = BuildTargetFactory.newInstance("//:DemoAppPackage");
    workspace.runBuckCommand("build", packageTarget.getFullyQualifiedName()).assertSuccess();

    workspace.getBuildLog().assertTargetBuiltLocally(packageTarget);

    ZipInspector zipInspector =
        new ZipInspector(
            workspace.getPath(
                BuildTargetPaths.getGenPath(filesystem.getBuckPaths(), packageTarget, "%s.ipa")));
    zipInspector.assertFileExists("SwiftSupport/iphonesimulator/libswiftCore.dylib");
  }

  @Test
  public void swiftSupportIsOnlyAddedIfPackageContainsSwiftCode() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "simple_application_bundle_no_debug", tmp);
    workspace.setUp();
    workspace.enableDirCache();

    BuildTarget packageTarget = BuildTargetFactory.newInstance("//:DemoAppPackage");
    workspace.runBuckCommand("build", packageTarget.getFullyQualifiedName()).assertSuccess();

    workspace.getBuildLog().assertTargetBuiltLocally(packageTarget);

    ZipInspector zipInspector =
        new ZipInspector(
            workspace.getPath(
                BuildTargetPaths.getGenPath(filesystem.getBuckPaths(), packageTarget, "%s.ipa")));
    zipInspector.assertFileDoesNotExist("SwiftSupport");
  }

  @Test
  public void packageHasProperStructureForWatch20() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "watch_application_bundle", tmp);
    workspace.setUp();
    workspace.addBuckConfigLocalOption("apple", "watchsimulator_target_sdk_version", "2.0");
    workspace.addBuckConfigLocalOption("cxx", "link_path_normalization_args_enabled", "true");
    packageHasProperStructureForWatchHelper(workspace, true);
  }

  @Test
  public void packageHasProperStructureForWatch21() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "watch_application_bundle", tmp);
    workspace.setUp();
    workspace.addBuckConfigLocalOption("apple", "watchsimulator_target_sdk_version", "2.1");
    workspace.addBuckConfigLocalOption("cxx", "link_path_normalization_args_enabled", "true");
    packageHasProperStructureForWatchHelper(workspace, false);
  }

  private void packageHasProperStructureForWatchHelper(
      ProjectWorkspace workspace, boolean shouldHaveStubInsideBundle) throws IOException {
    BuildTarget packageTarget = BuildTargetFactory.newInstance("//:DemoAppPackage");
    workspace.runBuckCommand("build", packageTarget.getFullyQualifiedName()).assertSuccess();

    Path destination = workspace.getDestPath();

    ArchiveFormat.ZIP
        .getUnarchiver()
        .extractArchive(
            new DefaultProjectFilesystemFactory(),
            workspace.getPath(
                BuildTargetPaths.getGenPath(filesystem.getBuckPaths(), packageTarget, "%s.ipa")),
            destination,
            ExistingFileMode.OVERWRITE_AND_CLEAN_DIRECTORIES);

    Path stubOutsideBundle = destination.resolve("WatchKitSupport2/WK");
    assertTrue(Files.isExecutable(stubOutsideBundle));
    assertTrue(Files.isDirectory(destination.resolve("Symbols")));
    assertTrue(isDirEmpty(destination.resolve("Symbols")));

    if (shouldHaveStubInsideBundle) {
      Path stubInsideBundle =
          destination.resolve("Payload/DemoApp.app/Watch/DemoWatchApp.app/_WatchKitStub/WK");
      assertTrue(Files.exists(stubInsideBundle));
      assertEquals(
          new String(Files.readAllBytes(stubInsideBundle)),
          new String(Files.readAllBytes(stubOutsideBundle)));
    }
  }

  @Test(timeout = 150 * 1_000)
  public void watchAppHasProperArch() throws IOException, InterruptedException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "watch_application_bundle", tmp);
    workspace.setUp();
    workspace.addBuckConfigLocalOption("apple", "watchsimulator_target_sdk_version", "2.0");

    ImmutableList<Pair<String, ImmutableList<String>>> platforms =
        ImmutableList.of(
            new Pair<>("watchos-armv7k", ImmutableList.of("armv7k")),
            new Pair<>("watchos-arm64_32", ImmutableList.of("arm64_32")),
            new Pair<>("watchos-arm64_32,watchos-armv7k", ImmutableList.of("arm64_32", "armv7k")));
    for (Pair<String, ImmutableList<String>> platformInfo : platforms) {
      BuildTarget target =
          BuildTargetFactory.newInstance("//:DemoWatchApp#" + platformInfo.getFirst());
      workspace
          .runBuckCommand(
              "build", "-c", "apple.dry_run_code_signing=true", target.getFullyQualifiedName())
          .assertSuccess();
      Path outputPath =
          workspace.getPath(
              BuildTargetPaths.getGenPath(
                  filesystem.getBuckPaths(),
                  target.withAppendedFlavors(
                      InternalFlavor.of("dwarf-and-dsym"),
                      InternalFlavor.of("no-include-frameworks")),
                  "%s"));

      Path binaryPath = outputPath.resolve("DemoWatchApp.app/DemoWatchApp");
      ImmutableList<String> command =
          ImmutableList.of("lipo", binaryPath.toString(), "-verify_arch");
      ImmutableList.Builder<String> fullCommandBuilder = ImmutableList.builder();
      ImmutableList<String> fullCommand =
          fullCommandBuilder.addAll(command).addAll(platformInfo.getSecond()).build();
      System.err.printf("%s%n", fullCommand.toString());
      ProcessExecutor.Result result = workspace.runCommand(fullCommand);
      assertEquals(0, result.getExitCode());

      Path extensionPath =
          outputPath.resolve(
              "DemoWatchApp.app/PlugIns/DemoWatchAppExtension.appex/DemoWatchAppExtension");
      command = ImmutableList.of("lipo", extensionPath.toString(), "-verify_arch");
      fullCommandBuilder = ImmutableList.builder();
      fullCommand = fullCommandBuilder.addAll(command).addAll(platformInfo.getSecond()).build();
      result = workspace.runCommand(fullCommand);
      assertEquals(0, result.getExitCode());

      Path stubInsideBundle = outputPath.resolve("DemoWatchApp.app/_WatchKitStub/WK");
      assertTrue(Files.exists(stubInsideBundle));
    }
  }

  @Test
  public void packageSupportsFatBinaries() throws IOException, InterruptedException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "simple_application_bundle_no_debug", tmp);
    workspace.setUp();

    BuildTarget packageTarget =
        BuildTargetFactory.newInstance(
            "//:DemoAppPackage#iphonesimulator-i386,iphonesimulator-x86_64");
    workspace.runBuckCommand("build", packageTarget.getFullyQualifiedName()).assertSuccess();

    ArchiveFormat.ZIP
        .getUnarchiver()
        .extractArchive(
            new DefaultProjectFilesystemFactory(),
            workspace.getPath(
                BuildTargetPaths.getGenPath(filesystem.getBuckPaths(), packageTarget, "%s.ipa")),
            workspace.getDestPath(),
            ExistingFileMode.OVERWRITE_AND_CLEAN_DIRECTORIES);

    ProcessExecutor executor = new DefaultProcessExecutor(new TestConsole());

    ProcessExecutorParams processExecutorParams =
        ProcessExecutorParams.builder()
            .setCommand(
                ImmutableList.of(
                    "lipo",
                    "-info",
                    workspace.getDestPath().resolve("Payload/DemoApp.app/DemoApp").toString()))
            .build();

    // Specify that stdout is expected, or else output may be wrapped in Ansi escape chars.
    Set<ProcessExecutor.Option> options =
        EnumSet.of(ProcessExecutor.Option.EXPECTING_STD_OUT, ProcessExecutor.Option.IS_SILENT);

    ProcessExecutor.Result result =
        executor.launchAndExecute(
            processExecutorParams,
            options,
            /* stdin */ Optional.empty(),
            /* timeOutMs */ Optional.empty(),
            /* timeOutHandler */ Optional.empty());

    assertEquals(result.getExitCode(), 0);
    assertTrue(result.getStdout().isPresent());
    String output = result.getStdout().get();
    assertTrue(output.contains("i386"));
    assertTrue(output.contains("x86_64"));
  }

  @Test
  public void testDisablingPackageCaching() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "simple_application_bundle_no_debug", tmp);
    workspace.setUp();

    workspace.enableDirCache();
    workspace
        .runBuckBuild("-c", "apple.cache_bundles_and_packages=false", "//:DemoAppPackage")
        .assertSuccess();
    workspace.runBuckCommand("clean", "--keep-cache");
    workspace
        .runBuckBuild("-c", "apple.cache_bundles_and_packages=false", "//:DemoAppPackage")
        .assertSuccess();
    workspace.getBuildLog().assertTargetBuiltLocally("//:DemoAppPackage");
  }

  @Test
  public void testPackageWithoutDefaultPlatform() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "default_platform_in_rules", tmp);
    workspace.setUp();

    BuildTarget packageTarget = BuildTargetFactory.newInstance("//:DemoAppPackage");
    workspace.runBuckCommand("build", packageTarget.getFullyQualifiedName()).assertSuccess();
  }

  @Test
  public void testPackageWithoutDefaultPlatformAndFlavorOverride() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "default_platform_in_rules", tmp);
    workspace.setUp();

    BuildTarget packageTarget = BuildTargetFactory.newInstance("//:DemoAppPackage#thisshouldfail");
    // Assert a flavor on the target wins
    workspace.runBuckCommand("build", packageTarget.getFullyQualifiedName()).assertFailure();

    packageTarget = BuildTargetFactory.newInstance("//:DemoAppPackage");
    // Assert a flavor from configuration the target loses
    workspace
        .runBuckCommand(
            "build",
            packageTarget.getFullyQualifiedName(),
            "--config",
            "cxx.default_platform=doesnotexist")
        .assertSuccess();
  }
}
