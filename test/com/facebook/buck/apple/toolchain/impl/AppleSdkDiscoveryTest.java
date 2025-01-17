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

package com.facebook.buck.apple.toolchain.impl;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.apple.AppleConfig;
import com.facebook.buck.apple.toolchain.ApplePlatform;
import com.facebook.buck.apple.toolchain.AppleSdk;
import com.facebook.buck.apple.toolchain.AppleSdkPaths;
import com.facebook.buck.apple.toolchain.AppleToolchain;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.CreateSymlinksForTests;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class AppleSdkDiscoveryTest {

  @Rule public TemporaryPaths temp = new TemporaryPaths();

  @Rule public ExpectedException thrown = ExpectedException.none();

  private AppleToolchain getDefaultToolchain(Path path) {
    return AppleToolchain.builder()
        .setIdentifier("com.apple.dt.toolchain.XcodeDefault")
        .setPath(path.resolve("Toolchains/XcodeDefault.xctoolchain"))
        .setVersion("1")
        .build();
  }

  @Test
  public void shouldReturnAnEmptyMapIfNoPlatformsFound() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sdk-discovery-empty", temp);
    workspace.setUp();
    Path path = workspace.getPath("");

    ImmutableMap<String, AppleToolchain> toolchains =
        ImmutableMap.of("com.apple.dt.toolchain.XcodeDefault", getDefaultToolchain(path));
    ImmutableMap<AppleSdk, AppleSdkPaths> sdks =
        AppleSdkDiscovery.discoverAppleSdkPaths(
            Optional.of(path),
            ImmutableList.of(),
            toolchains,
            FakeBuckConfig.empty().getView(AppleConfig.class),
            workspace.getProjectFileSystem());

    assertEquals(0, sdks.size());
  }

  @Test
  public void shouldResolveSdkVersionConflicts() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "sdk-discovery-conflict", temp.newFolder("conflict"));
    workspace.setUp();
    Path root = workspace.getPath("Platforms");

    ProjectWorkspace emptyWorkspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "sdk-discovery-empty", temp.newFolder("empty"));
    emptyWorkspace.setUp();
    Path path = emptyWorkspace.getPath("");

    ImmutableMap<String, AppleToolchain> toolchains =
        ImmutableMap.of("com.apple.dt.toolchain.XcodeDefault", getDefaultToolchain(path));

    AppleSdk macosxReleaseSdk =
        AppleSdk.builder()
            .setName("macosx")
            .setVersion("10.9")
            .setApplePlatform(ApplePlatform.MACOSX)
            .addArchitectures("i386", "x86_64", "arm64")
            .addAllToolchains(toolchains.values())
            .build();
    AppleSdk macosxDebugSdk =
        AppleSdk.builder()
            .setName("macosx-Debug")
            .setVersion("10.9")
            .setApplePlatform(ApplePlatform.MACOSX)
            .addArchitectures("i386", "x86_64", "arm64")
            .addAllToolchains(toolchains.values())
            .build();
    Path macosxPlatformPath = root.resolve("MacOSX.platform");
    SourcePath macosxPlatformSourcePath =
        PathSourcePath.of(workspace.getProjectFileSystem(), macosxPlatformPath);
    AppleSdkPaths macosxReleasePaths =
        AppleSdkPaths.builder()
            .setDeveloperPath(path)
            .addToolchainPaths(path.resolve("Toolchains/XcodeDefault.xctoolchain"))
            .setPlatformPath(macosxPlatformPath)
            .setPlatformSourcePath(macosxPlatformSourcePath)
            .setSdkPath(root.resolve("MacOSX.platform/Developer/SDKs/MacOSX10.9.sdk"))
            .setSdkSourcePath(
                PathSourcePath.of(
                    workspace.getProjectFileSystem(),
                    root.resolve("MacOSX.platform/Developer/SDKs/MacOSX10.9.sdk")))
            .build();
    AppleSdkPaths macosxDebugPaths =
        AppleSdkPaths.builder()
            .setDeveloperPath(path)
            .addToolchainPaths(path.resolve("Toolchains/XcodeDefault.xctoolchain"))
            .setPlatformPath(macosxPlatformPath)
            .setPlatformSourcePath(macosxPlatformSourcePath)
            .setSdkPath(root.resolve("MacOSX.platform/Developer/SDKs/MacOSX-Debug10.9.sdk"))
            .setSdkSourcePath(
                PathSourcePath.of(
                    workspace.getProjectFileSystem(),
                    root.resolve("MacOSX.platform/Developer/SDKs/MacOSX-Debug10.9.sdk")))
            .build();

    ImmutableMap<AppleSdk, AppleSdkPaths> expected =
        ImmutableMap.<AppleSdk, AppleSdkPaths>builder()
            .put(macosxReleaseSdk, macosxReleasePaths)
            .put(macosxDebugSdk, macosxDebugPaths)
            .build();

    assertThat(
        AppleSdkDiscovery.discoverAppleSdkPaths(
            Optional.of(path),
            ImmutableList.of(root),
            toolchains,
            FakeBuckConfig.empty().getView(AppleConfig.class),
            workspace.getProjectFileSystem()),
        equalTo(expected));
  }

  @Test
  public void shouldFindPlatformsInExtraPlatformDirectories() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "sdk-discovery-minimal", temp.newFolder("minimal"));
    workspace.setUp();
    Path root = workspace.getPath("Platforms");

    ProjectWorkspace emptyWorkspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "sdk-discovery-empty", temp.newFolder("empty"));
    emptyWorkspace.setUp();
    Path path = emptyWorkspace.getPath("");

    ImmutableMap<String, AppleToolchain> toolchains =
        ImmutableMap.of("com.apple.dt.toolchain.XcodeDefault", getDefaultToolchain(path));

    AppleSdk macosx109Sdk =
        AppleSdk.builder()
            .setName("macosx10.9")
            .setVersion("10.9")
            .setApplePlatform(ApplePlatform.MACOSX)
            .addArchitectures("i386", "x86_64", "arm64")
            .addAllToolchains(toolchains.values())
            .build();
    Path macosxPlatformPath = root.resolve("MacOSX.platform");
    AppleSdkPaths macosx109Paths =
        AppleSdkPaths.builder()
            .setDeveloperPath(path)
            .addToolchainPaths(path.resolve("Toolchains/XcodeDefault.xctoolchain"))
            .setPlatformPath(macosxPlatformPath)
            .setPlatformSourcePath(
                PathSourcePath.of(workspace.getProjectFileSystem(), macosxPlatformPath))
            .setSdkPath(root.resolve("MacOSX.platform/Developer/SDKs/MacOSX10.9.sdk"))
            .setSdkSourcePath(
                PathSourcePath.of(
                    workspace.getProjectFileSystem(),
                    root.resolve("MacOSX.platform/Developer/SDKs/MacOSX10.9.sdk")))
            .build();

    ImmutableMap<AppleSdk, AppleSdkPaths> expected =
        ImmutableMap.<AppleSdk, AppleSdkPaths>builder()
            .put(macosx109Sdk, macosx109Paths)
            .put(macosx109Sdk.withName("macosx"), macosx109Paths)
            .build();

    assertThat(
        AppleSdkDiscovery.discoverAppleSdkPaths(
            Optional.of(path),
            ImmutableList.of(root),
            toolchains,
            FakeBuckConfig.empty().getView(AppleConfig.class),
            workspace.getProjectFileSystem()),
        equalTo(expected));
  }

  @Test
  public void ignoresInvalidExtraPlatformDirectories() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sdk-discovery-minimal", temp);
    workspace.setUp();
    Path root = workspace.getPath("");

    Path path = Paths.get("invalid");

    ImmutableMap<String, AppleToolchain> toolchains =
        ImmutableMap.of("com.apple.dt.toolchain.XcodeDefault", getDefaultToolchain(root));

    AppleSdk macosx109Sdk =
        AppleSdk.builder()
            .setName("macosx10.9")
            .setVersion("10.9")
            .setApplePlatform(ApplePlatform.MACOSX)
            .addArchitectures("i386", "x86_64", "arm64")
            .addAllToolchains(toolchains.values())
            .build();
    Path macosxPlatformPath = root.resolve("Platforms/MacOSX.platform");
    AppleSdkPaths macosx109Paths =
        AppleSdkPaths.builder()
            .setDeveloperPath(root)
            .addToolchainPaths(root.resolve("Toolchains/XcodeDefault.xctoolchain"))
            .setPlatformPath(macosxPlatformPath)
            .setPlatformSourcePath(
                PathSourcePath.of(workspace.getProjectFileSystem(), macosxPlatformPath))
            .setSdkPath(root.resolve("Platforms/MacOSX.platform/Developer/SDKs/MacOSX10.9.sdk"))
            .setSdkSourcePath(
                PathSourcePath.of(
                    workspace.getProjectFileSystem(),
                    root.resolve("Platforms/MacOSX.platform/Developer/SDKs/MacOSX10.9.sdk")))
            .build();

    ImmutableMap<AppleSdk, AppleSdkPaths> expected =
        ImmutableMap.<AppleSdk, AppleSdkPaths>builder()
            .put(macosx109Sdk, macosx109Paths)
            .put(macosx109Sdk.withName("macosx"), macosx109Paths)
            .build();

    assertThat(
        AppleSdkDiscovery.discoverAppleSdkPaths(
            Optional.of(root),
            ImmutableList.of(path),
            toolchains,
            FakeBuckConfig.empty().getView(AppleConfig.class),
            workspace.getProjectFileSystem()),
        equalTo(expected));
  }

  @Test
  public void shouldNotIgnoreSdkWithUnrecognizedPlatform() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "sdk-unknown-platform-discovery", temp);
    workspace.setUp();
    Path root = workspace.getPath("");

    ImmutableMap<String, AppleToolchain> toolchains =
        ImmutableMap.of("com.apple.dt.toolchain.XcodeDefault", getDefaultToolchain(root));
    ImmutableMap<AppleSdk, AppleSdkPaths> sdks =
        AppleSdkDiscovery.discoverAppleSdkPaths(
            Optional.of(root),
            ImmutableList.of(),
            toolchains,
            FakeBuckConfig.empty().getView(AppleConfig.class),
            workspace.getProjectFileSystem());

    assertEquals(2, sdks.size());
  }

  @Test
  public void shouldIgnoreSdkWithBadSymlink() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sdk-discovery-symlink", temp);
    workspace.setUp();
    Path root = workspace.getPath("");

    Path sdksDir = root.resolve("Platforms/MacOSX.platform/Developer/SDKs");
    Files.createDirectories(sdksDir);

    // Create a dangling symlink
    File toDelete = File.createTempFile("foo", "bar");
    Path symlink = sdksDir.resolve("NonExistent1.0.sdk");
    CreateSymlinksForTests.createSymLink(symlink, toDelete.toPath());
    assertTrue(toDelete.delete());

    // Also create a working symlink
    Path actualSdkPath = root.resolve("MacOSX10.9.sdk");
    CreateSymlinksForTests.createSymLink(sdksDir.resolve("MacOSX10.9.sdk"), actualSdkPath);

    ImmutableMap<String, AppleToolchain> toolchains =
        ImmutableMap.of("com.apple.dt.toolchain.XcodeDefault", getDefaultToolchain(root));

    AppleSdk macosx109Sdk =
        AppleSdk.builder()
            .setName("macosx10.9")
            .setVersion("10.9")
            .setApplePlatform(ApplePlatform.MACOSX)
            .addArchitectures("i386", "x86_64", "arm64")
            .addAllToolchains(toolchains.values())
            .build();
    Path macosxPlatformPath = root.resolve("Platforms/MacOSX.platform");
    AppleSdkPaths macosx109Paths =
        AppleSdkPaths.builder()
            .setDeveloperPath(root)
            .addToolchainPaths(root.resolve("Toolchains/XcodeDefault.xctoolchain"))
            .setPlatformPath(macosxPlatformPath)
            .setPlatformSourcePath(
                PathSourcePath.of(workspace.getProjectFileSystem(), macosxPlatformPath))
            .setSdkPath(actualSdkPath)
            .setSdkSourcePath(PathSourcePath.of(workspace.getProjectFileSystem(), actualSdkPath))
            .build();

    ImmutableMap<AppleSdk, AppleSdkPaths> expected =
        ImmutableMap.<AppleSdk, AppleSdkPaths>builder()
            .put(macosx109Sdk, macosx109Paths)
            .put(macosx109Sdk.withName("macosx"), macosx109Paths)
            .build();

    ImmutableMap<AppleSdk, AppleSdkPaths> discoveredSdks =
        AppleSdkDiscovery.discoverAppleSdkPaths(
            Optional.of(root),
            ImmutableList.of(),
            toolchains,
            FakeBuckConfig.empty().getView(AppleConfig.class),
            workspace.getProjectFileSystem());

    assertThat(discoveredSdks, equalTo(expected));
  }

  @Test
  public void appleSdkPathsBuiltFromDirectory() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sdk-discovery", temp);
    workspace.setUp();
    Path root = workspace.getPath("");
    createSymLinkIosSdks(root, "8.0");
    createSymLinkWatchosSdks(root, "2.0");
    createSymLinkAppletvosSdks(root, "9.1");

    AppleSdk macosx109Sdk =
        AppleSdk.builder()
            .setName("macosx10.9")
            .setVersion("10.9")
            .setApplePlatform(ApplePlatform.MACOSX)
            .addArchitectures("i386", "x86_64", "arm64")
            .addToolchains(getDefaultToolchain(root))
            .build();
    Path macosxPlatformPath = root.resolve("Platforms/MacOSX.platform");
    AppleSdkPaths macosx109Paths =
        AppleSdkPaths.builder()
            .setDeveloperPath(root)
            .addToolchainPaths(root.resolve("Toolchains/XcodeDefault.xctoolchain"))
            .setPlatformPath(macosxPlatformPath)
            .setPlatformSourcePath(
                PathSourcePath.of(workspace.getProjectFileSystem(), macosxPlatformPath))
            .setSdkPath(root.resolve("Platforms/MacOSX.platform/Developer/SDKs/MacOSX10.9.sdk"))
            .setSdkSourcePath(
                PathSourcePath.of(
                    workspace.getProjectFileSystem(),
                    root.resolve("Platforms/MacOSX.platform/Developer/SDKs/MacOSX10.9.sdk")))
            .build();

    AppleSdk iphoneos80Sdk =
        AppleSdk.builder()
            .setName("iphoneos8.0")
            .setVersion("8.0")
            .setApplePlatform(ApplePlatform.IPHONEOS)
            .addArchitectures("armv7", "arm64")
            .addToolchains(getDefaultToolchain(root))
            .build();
    Path iphonePlatformPath = root.resolve("Platforms/iPhoneOS.platform");
    AppleSdkPaths iphoneos80Paths =
        AppleSdkPaths.builder()
            .setDeveloperPath(root)
            .addToolchainPaths(root.resolve("Toolchains/XcodeDefault.xctoolchain"))
            .setPlatformPath(iphonePlatformPath)
            .setPlatformSourcePath(
                PathSourcePath.of(workspace.getProjectFileSystem(), iphonePlatformPath))
            .setSdkPath(root.resolve("Platforms/iPhoneOS.platform/Developer/SDKs/iPhoneOS.sdk"))
            .setSdkSourcePath(
                PathSourcePath.of(
                    workspace.getProjectFileSystem(),
                    root.resolve("Platforms/iPhoneOS.platform/Developer/SDKs/iPhoneOS.sdk")))
            .build();

    AppleSdk iphonesimulator80Sdk =
        AppleSdk.builder()
            .setName("iphonesimulator8.0")
            .setVersion("8.0")
            .setApplePlatform(ApplePlatform.IPHONESIMULATOR)
            .addArchitectures("arm64", "i386", "x86_64")
            .addToolchains(getDefaultToolchain(root))
            .build();
    Path iphonesimulatorPlatformPath = root.resolve("Platforms/iPhoneSimulator.platform");
    AppleSdkPaths iphonesimulator80Paths =
        AppleSdkPaths.builder()
            .setDeveloperPath(root)
            .addToolchainPaths(root.resolve("Toolchains/XcodeDefault.xctoolchain"))
            .setPlatformPath(iphonesimulatorPlatformPath)
            .setPlatformSourcePath(
                PathSourcePath.of(workspace.getProjectFileSystem(), iphonesimulatorPlatformPath))
            .setSdkPath(
                root.resolve(
                    "Platforms/iPhoneSimulator.platform/Developer/SDKs/iPhoneSimulator.sdk"))
            .setSdkSourcePath(
                PathSourcePath.of(
                    workspace.getProjectFileSystem(),
                    root.resolve(
                        "Platforms/iPhoneSimulator.platform/Developer/SDKs/iPhoneSimulator.sdk")))
            .build();

    AppleSdk watchos20Sdk =
        AppleSdk.builder()
            .setName("watchos2.0")
            .setVersion("2.0")
            .setApplePlatform(ApplePlatform.WATCHOS)
            .addArchitectures("armv7k", "arm64_32")
            .addToolchains(getDefaultToolchain(root))
            .build();
    Path watchosPlatformPath = root.resolve("Platforms/WatchOS.platform");
    AppleSdkPaths watchos20Paths =
        AppleSdkPaths.builder()
            .setDeveloperPath(root)
            .addToolchainPaths(root.resolve("Toolchains/XcodeDefault.xctoolchain"))
            .setPlatformPath(watchosPlatformPath)
            .setPlatformSourcePath(
                PathSourcePath.of(workspace.getProjectFileSystem(), watchosPlatformPath))
            .setSdkPath(root.resolve("Platforms/WatchOS.platform/Developer/SDKs/WatchOS.sdk"))
            .setSdkSourcePath(
                PathSourcePath.of(
                    workspace.getProjectFileSystem(),
                    root.resolve("Platforms/WatchOS.platform/Developer/SDKs/WatchOS.sdk")))
            .build();

    AppleSdk watchsimulator20Sdk =
        AppleSdk.builder()
            .setName("watchsimulator2.0")
            .setVersion("2.0")
            .setApplePlatform(ApplePlatform.WATCHSIMULATOR)
            .addArchitectures("arm64", "i386", "x86_64")
            .addToolchains(getDefaultToolchain(root))
            .build();
    Path watchsimulatorPlatformPath = root.resolve("Platforms/WatchSimulator.platform");
    AppleSdkPaths watchsimulator20Paths =
        AppleSdkPaths.builder()
            .setDeveloperPath(root)
            .addToolchainPaths(root.resolve("Toolchains/XcodeDefault.xctoolchain"))
            .setPlatformPath(watchsimulatorPlatformPath)
            .setPlatformSourcePath(
                PathSourcePath.of(workspace.getProjectFileSystem(), watchsimulatorPlatformPath))
            .setSdkPath(
                root.resolve("Platforms/WatchSimulator.platform/Developer/SDKs/WatchSimulator.sdk"))
            .setSdkSourcePath(
                PathSourcePath.of(
                    workspace.getProjectFileSystem(),
                    root.resolve(
                        "Platforms/WatchSimulator.platform/Developer/SDKs/WatchSimulator.sdk")))
            .build();

    AppleSdk appletvos91Sdk =
        AppleSdk.builder()
            .setName("appletvos9.1")
            .setVersion("9.1")
            .setApplePlatform(ApplePlatform.APPLETVOS)
            .addArchitectures("arm64")
            .addToolchains(getDefaultToolchain(root))
            .build();
    Path appletvosPlatformPath = root.resolve("Platforms/AppleTVOS.platform");
    AppleSdkPaths appletvos91Paths =
        AppleSdkPaths.builder()
            .setDeveloperPath(root)
            .addToolchainPaths(root.resolve("Toolchains/XcodeDefault.xctoolchain"))
            .setPlatformPath(appletvosPlatformPath)
            .setPlatformSourcePath(
                PathSourcePath.of(workspace.getProjectFileSystem(), appletvosPlatformPath))
            .setSdkPath(root.resolve("Platforms/AppleTVOS.platform/Developer/SDKs/AppleTVOS.sdk"))
            .setSdkSourcePath(
                PathSourcePath.of(
                    workspace.getProjectFileSystem(),
                    root.resolve("Platforms/AppleTVOS.platform/Developer/SDKs/AppleTVOS.sdk")))
            .build();

    AppleSdk appletvsimulator91Sdk =
        AppleSdk.builder()
            .setName("appletvsimulator9.1")
            .setVersion("9.1")
            .setApplePlatform(ApplePlatform.APPLETVSIMULATOR)
            .addArchitectures("arm64", "x86_64")
            .addToolchains(getDefaultToolchain(root))
            .build();
    Path appletvsimulatorPlatformPath = root.resolve("Platforms/AppleTVSimulator.platform");
    AppleSdkPaths appletvsimulator91Paths =
        AppleSdkPaths.builder()
            .setDeveloperPath(root)
            .addToolchainPaths(root.resolve("Toolchains/XcodeDefault.xctoolchain"))
            .setPlatformPath(appletvsimulatorPlatformPath)
            .setPlatformSourcePath(
                PathSourcePath.of(workspace.getProjectFileSystem(), appletvsimulatorPlatformPath))
            .setSdkPath(
                root.resolve(
                    "Platforms/AppleTVSimulator.platform/Developer/SDKs/AppleTVSimulator.sdk"))
            .setSdkSourcePath(
                PathSourcePath.of(
                    workspace.getProjectFileSystem(),
                    root.resolve(
                        "Platforms/AppleTVSimulator.platform/Developer/SDKs/AppleTVSimulator.sdk")))
            .build();

    ImmutableMap<String, AppleToolchain> toolchains =
        ImmutableMap.of("com.apple.dt.toolchain.XcodeDefault", getDefaultToolchain(root));

    ImmutableMap<AppleSdk, AppleSdkPaths> expected =
        ImmutableMap.<AppleSdk, AppleSdkPaths>builder()
            .put(macosx109Sdk, macosx109Paths)
            .put(macosx109Sdk.withName("macosx"), macosx109Paths)
            .put(iphoneos80Sdk, iphoneos80Paths)
            .put(iphoneos80Sdk.withName("iphoneos"), iphoneos80Paths)
            .put(iphonesimulator80Sdk, iphonesimulator80Paths)
            .put(iphonesimulator80Sdk.withName("iphonesimulator"), iphonesimulator80Paths)
            .put(watchos20Sdk, watchos20Paths)
            .put(watchos20Sdk.withName("watchos"), watchos20Paths)
            .put(watchsimulator20Sdk, watchsimulator20Paths)
            .put(watchsimulator20Sdk.withName("watchsimulator"), watchsimulator20Paths)
            .put(appletvos91Sdk, appletvos91Paths)
            .put(appletvos91Sdk.withName("appletvos"), appletvos91Paths)
            .put(appletvsimulator91Sdk, appletvsimulator91Paths)
            .put(appletvsimulator91Sdk.withName("appletvsimulator"), appletvsimulator91Paths)
            .build();

    assertThat(
        AppleSdkDiscovery.discoverAppleSdkPaths(
            Optional.of(root),
            ImmutableList.of(),
            toolchains,
            FakeBuckConfig.empty().getView(AppleConfig.class),
            workspace.getProjectFileSystem()),
        equalTo(expected));
  }

  @Test
  public void noAppleSdksFoundIfDefaultPlatformMissing() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sdk-discovery", temp);
    workspace.setUp();
    Path root = workspace.getPath("");

    ImmutableMap<String, AppleToolchain> toolchains = ImmutableMap.of();

    assertThat(
        AppleSdkDiscovery.discoverAppleSdkPaths(
                Optional.of(root),
                ImmutableList.of(),
                toolchains,
                FakeBuckConfig.empty().getView(AppleConfig.class),
                workspace.getProjectFileSystem())
            .entrySet(),
        empty());
  }

  @Test
  public void multipleAppleSdkPathsPerPlatformBuiltFromDirectory() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sdk-multi-version-discovery", temp);
    workspace.setUp();
    Path root = workspace.getPath("");

    createSymLinkIosSdks(root, "8.1");

    AppleSdk macosx109Sdk =
        AppleSdk.builder()
            .setName("macosx10.9")
            .setVersion("10.9")
            .setApplePlatform(ApplePlatform.MACOSX)
            .addArchitectures("i386", "x86_64", "arm64")
            .addToolchains(getDefaultToolchain(root))
            .build();
    Path macosxPlatformPath = root.resolve("Platforms/MacOSX.platform");
    AppleSdkPaths macosx109Paths =
        AppleSdkPaths.builder()
            .setDeveloperPath(root)
            .addToolchainPaths(root.resolve("Toolchains/XcodeDefault.xctoolchain"))
            .setPlatformPath(macosxPlatformPath)
            .setPlatformSourcePath(
                PathSourcePath.of(workspace.getProjectFileSystem(), macosxPlatformPath))
            .setSdkPath(root.resolve("Platforms/MacOSX.platform/Developer/SDKs/MacOSX10.9.sdk"))
            .setSdkSourcePath(
                PathSourcePath.of(
                    workspace.getProjectFileSystem(),
                    root.resolve("Platforms/MacOSX.platform/Developer/SDKs/MacOSX10.9.sdk")))
            .build();

    AppleSdk iphoneos80Sdk =
        AppleSdk.builder()
            .setName("iphoneos8.0")
            .setVersion("8.0")
            .setApplePlatform(ApplePlatform.IPHONEOS)
            .addArchitectures("armv7", "arm64")
            .addToolchains(getDefaultToolchain(root))
            .build();
    Path iphoneosPlatformPath = root.resolve("Platforms/iPhoneOS.platform");
    PathSourcePath iphoneosPlatformSourcePath =
        PathSourcePath.of(workspace.getProjectFileSystem(), iphoneosPlatformPath);
    AppleSdkPaths iphoneos80Paths =
        AppleSdkPaths.builder()
            .setDeveloperPath(root)
            .addToolchainPaths(root.resolve("Toolchains/XcodeDefault.xctoolchain"))
            .setPlatformPath(iphoneosPlatformPath)
            .setPlatformSourcePath(iphoneosPlatformSourcePath)
            .setSdkPath(root.resolve("Platforms/iPhoneOS.platform/Developer/SDKs/iPhoneOS8.0.sdk"))
            .setSdkSourcePath(
                PathSourcePath.of(
                    workspace.getProjectFileSystem(),
                    root.resolve("Platforms/iPhoneOS.platform/Developer/SDKs/iPhoneOS8.0.sdk")))
            .build();

    AppleSdk iphonesimulator80Sdk =
        AppleSdk.builder()
            .setName("iphonesimulator8.0")
            .setVersion("8.0")
            .setApplePlatform(ApplePlatform.IPHONESIMULATOR)
            .addArchitectures("arm64", "i386", "x86_64")
            .addToolchains(getDefaultToolchain(root))
            .build();
    Path iphonesimulatorPlatformPath = root.resolve("Platforms/iPhoneSimulator.platform");
    PathSourcePath iphonesimulatorPlatformSourcePath =
        PathSourcePath.of(workspace.getProjectFileSystem(), iphonesimulatorPlatformPath);
    AppleSdkPaths iphonesimulator80Paths =
        AppleSdkPaths.builder()
            .setDeveloperPath(root)
            .addToolchainPaths(root.resolve("Toolchains/XcodeDefault.xctoolchain"))
            .setPlatformPath(iphonesimulatorPlatformPath)
            .setPlatformSourcePath(iphonesimulatorPlatformSourcePath)
            .setSdkPath(
                root.resolve(
                    "Platforms/iPhoneSimulator.platform/Developer/SDKs/iPhoneSimulator8.0.sdk"))
            .setSdkSourcePath(
                PathSourcePath.of(
                    workspace.getProjectFileSystem(),
                    root.resolve(
                        "Platforms/iPhoneSimulator.platform/Developer/SDKs/iPhoneSimulator8.0.sdk")))
            .build();

    AppleSdk iphoneos81Sdk =
        AppleSdk.builder()
            .setName("iphoneos8.1")
            .setVersion("8.1")
            .setApplePlatform(ApplePlatform.IPHONEOS)
            .addArchitectures("armv7", "arm64")
            .addToolchains(getDefaultToolchain(root))
            .build();
    AppleSdkPaths iphoneos81Paths =
        AppleSdkPaths.builder()
            .setDeveloperPath(root)
            .addToolchainPaths(root.resolve("Toolchains/XcodeDefault.xctoolchain"))
            .setPlatformPath(iphoneosPlatformPath)
            .setPlatformSourcePath(iphoneosPlatformSourcePath)
            .setSdkPath(root.resolve("Platforms/iPhoneOS.platform/Developer/SDKs/iPhoneOS.sdk"))
            .setSdkSourcePath(
                PathSourcePath.of(
                    workspace.getProjectFileSystem(),
                    root.resolve("Platforms/iPhoneOS.platform/Developer/SDKs/iPhoneOS.sdk")))
            .build();

    AppleSdk iphonesimulator81Sdk =
        AppleSdk.builder()
            .setName("iphonesimulator8.1")
            .setVersion("8.1")
            .setApplePlatform(ApplePlatform.IPHONESIMULATOR)
            .addArchitectures("arm64", "i386", "x86_64")
            .addToolchains(getDefaultToolchain(root))
            .build();
    AppleSdkPaths iphonesimulator81Paths =
        AppleSdkPaths.builder()
            .setDeveloperPath(root)
            .addToolchainPaths(root.resolve("Toolchains/XcodeDefault.xctoolchain"))
            .setPlatformPath(iphonesimulatorPlatformPath)
            .setPlatformSourcePath(iphonesimulatorPlatformSourcePath)
            .setSdkPath(
                root.resolve(
                    "Platforms/iPhoneSimulator.platform/Developer/SDKs/iPhoneSimulator.sdk"))
            .setSdkSourcePath(
                PathSourcePath.of(
                    workspace.getProjectFileSystem(),
                    root.resolve(
                        "Platforms/iPhoneSimulator.platform/Developer/SDKs/iPhoneSimulator.sdk")))
            .build();

    ImmutableMap<AppleSdk, AppleSdkPaths> expected =
        ImmutableMap.<AppleSdk, AppleSdkPaths>builder()
            .put(macosx109Sdk, macosx109Paths)
            .put(macosx109Sdk.withName("macosx"), macosx109Paths)
            .put(iphoneos80Sdk, iphoneos80Paths)
            .put(iphonesimulator80Sdk, iphonesimulator80Paths)
            .put(iphoneos81Sdk, iphoneos81Paths)
            .put(iphoneos81Sdk.withName("iphoneos"), iphoneos81Paths)
            .put(iphonesimulator81Sdk, iphonesimulator81Paths)
            .put(iphonesimulator81Sdk.withName("iphonesimulator"), iphonesimulator81Paths)
            .build();

    ImmutableMap<String, AppleToolchain> toolchains =
        ImmutableMap.of("com.apple.dt.toolchain.XcodeDefault", getDefaultToolchain(root));

    assertThat(
        AppleSdkDiscovery.discoverAppleSdkPaths(
            Optional.of(root),
            ImmutableList.of(),
            toolchains,
            FakeBuckConfig.empty().getView(AppleConfig.class),
            workspace.getProjectFileSystem()),
        equalTo(expected));
  }

  @Test
  public void shouldDiscoverRealSdkThroughAbsoluteSymlink() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sdk-discovery-symlink", temp);
    workspace.setUp();
    Path root = workspace.getPath("");

    Path actualSdkPath = root.resolve("MacOSX10.9.sdk");
    Path sdksDir = root.resolve("Platforms/MacOSX.platform/Developer/SDKs");

    Files.createDirectories(sdksDir);
    CreateSymlinksForTests.createSymLink(sdksDir.resolve("MacOSX10.9.sdk"), actualSdkPath);

    ImmutableMap<String, AppleToolchain> toolchains =
        ImmutableMap.of("com.apple.dt.toolchain.XcodeDefault", getDefaultToolchain(root));

    AppleSdk macosx109Sdk =
        AppleSdk.builder()
            .setName("macosx10.9")
            .setVersion("10.9")
            .setApplePlatform(ApplePlatform.MACOSX)
            .addArchitectures("i386", "x86_64", "arm64")
            .addAllToolchains(toolchains.values())
            .build();
    Path macosxPlatformPath = root.resolve("Platforms/MacOSX.platform");
    AppleSdkPaths macosx109Paths =
        AppleSdkPaths.builder()
            .setDeveloperPath(root)
            .addToolchainPaths(root.resolve("Toolchains/XcodeDefault.xctoolchain"))
            .setPlatformPath(macosxPlatformPath)
            .setPlatformSourcePath(
                PathSourcePath.of(workspace.getProjectFileSystem(), macosxPlatformPath))
            .setSdkPath(actualSdkPath)
            .setSdkSourcePath(PathSourcePath.of(workspace.getProjectFileSystem(), actualSdkPath))
            .build();

    ImmutableMap<AppleSdk, AppleSdkPaths> expected =
        ImmutableMap.<AppleSdk, AppleSdkPaths>builder()
            .put(macosx109Sdk, macosx109Paths)
            .put(macosx109Sdk.withName("macosx"), macosx109Paths)
            .build();

    assertThat(
        AppleSdkDiscovery.discoverAppleSdkPaths(
            Optional.of(root),
            ImmutableList.of(root),
            toolchains,
            FakeBuckConfig.empty().getView(AppleConfig.class),
            workspace.getProjectFileSystem()),
        equalTo(expected));
  }

  @Test
  public void shouldScanRealDirectoryOnlyOnce() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sdk-discovery-symlink", temp);
    workspace.setUp();
    Path root = workspace.getPath("");
    FileSystem fileSystem = root.getFileSystem();

    Path actualSdkPath = root.resolve("MacOSX10.9.sdk");
    Path sdksDir = root.resolve("Platforms/MacOSX.platform/Developer/SDKs");
    Files.createDirectories(sdksDir);

    // create relative symlink

    CreateSymlinksForTests.createSymLink(
        sdksDir.resolve("MacOSX10.9.sdk"), fileSystem.getPath("MacOSX.sdk"));

    // create absolute symlink
    CreateSymlinksForTests.createSymLink(sdksDir.resolve("MacOSX.sdk"), actualSdkPath);

    ImmutableMap<String, AppleToolchain> toolchains =
        ImmutableMap.of("com.apple.dt.toolchain.XcodeDefault", getDefaultToolchain(root));

    ImmutableMap<AppleSdk, AppleSdkPaths> actual =
        AppleSdkDiscovery.discoverAppleSdkPaths(
            Optional.of(root),
            ImmutableList.of(root),
            toolchains,
            FakeBuckConfig.empty().getView(AppleConfig.class),
            workspace.getProjectFileSystem());

    // if both symlinks were to be visited, exception would have been thrown during discovery
    assertThat(actual.size(), is(2));
  }

  @Test
  public void shouldNotCrashOnBrokenSymlink() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sdk-discovery-symlink", temp);
    workspace.setUp();
    Path root = workspace.getPath("");
    FileSystem fileSystem = root.getFileSystem();

    Path sdksDir = root.resolve("Platforms/MacOSX.platform/Developer/SDKs");
    Files.createDirectories(sdksDir);
    CreateSymlinksForTests.createSymLink(
        sdksDir.resolve("MacOSX.sdk"), fileSystem.getPath("does_not_exist"));

    ImmutableMap<String, AppleToolchain> toolchains =
        ImmutableMap.of("com.apple.dt.toolchain.XcodeDefault", getDefaultToolchain(root));

    ImmutableMap<AppleSdk, AppleSdkPaths> actual =
        AppleSdkDiscovery.discoverAppleSdkPaths(
            Optional.of(root),
            ImmutableList.of(root),
            toolchains,
            FakeBuckConfig.empty().getView(AppleConfig.class),
            workspace.getProjectFileSystem());

    assertThat(actual.size(), is(0));
  }

  @Test
  public void overrideToolchains() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sdk-discovery-minimal", temp);
    workspace.setUp();
    Path root = workspace.getPath("");

    String toolchainName1 = "toolchainoverride.1";
    String toolchainPath1 = "Toolchains/" + toolchainName1;
    AppleToolchain overrideToolchain1 =
        AppleToolchain.builder()
            .setIdentifier(toolchainName1)
            .setPath(root.resolve(toolchainPath1))
            .setVersion("1")
            .build();

    String toolchainName2 = "toolchainoverride.2";
    String toolchainPath2 = "Toolchains/" + toolchainName2;
    AppleToolchain overrideToolchain2 =
        AppleToolchain.builder()
            .setIdentifier(toolchainName2)
            .setPath(root.resolve(toolchainPath2))
            .setVersion("1")
            .build();

    ImmutableMap<String, AppleToolchain> allToolchains =
        ImmutableMap.of(
            "com.apple.dt.toolchain.XcodeDefault",
            getDefaultToolchain(root),
            toolchainName1,
            overrideToolchain1,
            toolchainName2,
            overrideToolchain2);

    AppleSdk macosx109Sdk =
        AppleSdk.builder()
            .setName("macosx10.9")
            .setVersion("10.9")
            .setApplePlatform(ApplePlatform.MACOSX)
            .addArchitectures("i386", "x86_64", "arm64")
            .addAllToolchains(ImmutableList.of(overrideToolchain1, overrideToolchain2))
            .build();
    Path macosxPlatformPath = root.resolve("Platforms/MacOSX.platform");
    AppleSdkPaths macosx109Paths =
        AppleSdkPaths.builder()
            .setDeveloperPath(root)
            .addToolchainPaths(root.resolve(toolchainPath1), root.resolve(toolchainPath2))
            .setPlatformPath(macosxPlatformPath)
            .setPlatformSourcePath(
                PathSourcePath.of(workspace.getProjectFileSystem(), macosxPlatformPath))
            .setSdkPath(root.resolve("Platforms/MacOSX.platform/Developer/SDKs/MacOSX10.9.sdk"))
            .setSdkSourcePath(
                PathSourcePath.of(
                    workspace.getProjectFileSystem(),
                    root.resolve("Platforms/MacOSX.platform/Developer/SDKs/MacOSX10.9.sdk")))
            .build();

    ImmutableMap<AppleSdk, AppleSdkPaths> expected =
        ImmutableMap.<AppleSdk, AppleSdkPaths>builder()
            .put(macosx109Sdk, macosx109Paths)
            .put(macosx109Sdk.withName("macosx"), macosx109Paths)
            .build();

    AppleConfig fakeAppleConfig =
        FakeBuckConfig.builder()
            .setSections(
                "[apple]",
                "  macosx10.9_toolchains_override = " + toolchainName1 + "," + toolchainName2,
                "  macosx_toolchains_override = " + toolchainName1 + "," + toolchainName2)
            .build()
            .getView(AppleConfig.class);

    assertThat(
        AppleSdkDiscovery.discoverAppleSdkPaths(
            Optional.of(root),
            ImmutableList.of(root),
            allToolchains,
            fakeAppleConfig,
            workspace.getProjectFileSystem()),
        equalTo(expected));
  }

  private void createSymLinkIosSdks(Path root, String version) throws IOException {
    createSymLinkSdks(ImmutableSet.of("iPhoneOS", "iPhoneSimulator"), root, version);
  }

  private void createSymLinkWatchosSdks(Path root, String version) throws IOException {
    createSymLinkSdks(ImmutableSet.of("WatchOS", "WatchSimulator"), root, version);
  }

  private void createSymLinkAppletvosSdks(Path root, String version) throws IOException {
    createSymLinkSdks(ImmutableSet.of("AppleTVOS", "AppleTVSimulator"), root, version);
  }

  private void createSymLinkSdks(Iterable<String> sdks, Path root, String version)
      throws IOException {
    for (String sdk : sdks) {
      Path sdkDir = root.resolve(String.format("Platforms/%s.platform/Developer/SDKs", sdk));

      if (!Files.exists(sdkDir)) {
        System.out.println(sdkDir);
        continue;
      }

      Path actual = sdkDir.resolve(String.format("%s.sdk", sdk));
      Path link = sdkDir.resolve(String.format("%s%s.sdk", sdk, version));
      CreateSymlinksForTests.createSymLink(link, actual);
    }
  }
}
