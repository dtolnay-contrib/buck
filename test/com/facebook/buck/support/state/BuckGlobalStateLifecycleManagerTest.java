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

package com.facebook.buck.support.state;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assume.assumeThat;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.android.toolchain.AndroidSdkLocation;
import com.facebook.buck.apple.AppleNativeIntegrationTestUtils;
import com.facebook.buck.apple.toolchain.ApplePlatform;
import com.facebook.buck.core.cell.CellConfig;
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.Cells;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.cell.TestCellPathResolver;
import com.facebook.buck.core.cell.impl.DefaultCellPathResolver;
import com.facebook.buck.core.cell.impl.LocalCellProviderFactory;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.TargetConfigurationSerializer;
import com.facebook.buck.core.model.UnconfiguredTargetConfiguration;
import com.facebook.buck.core.model.impl.JsonTargetConfigurationSerializer;
import com.facebook.buck.core.model.label.LabelSyntaxException;
import com.facebook.buck.core.parser.buildtargetparser.ParsingUnconfiguredBuildTargetViewFactory;
import com.facebook.buck.core.parser.buildtargetparser.UnconfiguredBuildTargetViewFactory;
import com.facebook.buck.core.plugin.impl.BuckPluginManagerFactory;
import com.facebook.buck.core.rules.knowntypes.TestKnownRuleTypesProvider;
import com.facebook.buck.core.rules.knowntypes.provider.KnownRuleTypesProvider;
import com.facebook.buck.core.starlark.rule.SkylarkUserDefinedRule;
import com.facebook.buck.core.toolchain.impl.EmptyToolchainProviderFactory;
import com.facebook.buck.io.file.MostFiles;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.io.filesystem.impl.DefaultProjectFilesystemFactory;
import com.facebook.buck.io.watchman.FakeWatchmanClient;
import com.facebook.buck.io.watchman.FakeWatchmanFactory;
import com.facebook.buck.io.watchman.Watchman;
import com.facebook.buck.io.watchman.WatchmanClient;
import com.facebook.buck.io.watchman.WatchmanError;
import com.facebook.buck.io.watchman.WatchmanFactory;
import com.facebook.buck.skylark.function.FakeSkylarkUserDefinedRuleFactory;
import com.facebook.buck.support.cli.config.CliConfig;
import com.facebook.buck.support.state.BuckGlobalStateLifecycleManager.LifecycleStatus;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.FakeProcess;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.config.Config;
import com.facebook.buck.util.config.Configs;
import com.facebook.buck.util.config.RawConfig;
import com.facebook.buck.util.environment.Architecture;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.timing.Clock;
import com.facebook.buck.util.timing.FakeClock;
import com.facebook.buck.util.timing.SettableFakeClock;
import com.facebook.buck.util.types.Pair;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.function.Supplier;
import net.starlark.java.eval.EvalException;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.pf4j.PluginManager;

public class BuckGlobalStateLifecycleManagerTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private ProjectFilesystem filesystem;
  private BuckGlobalStateLifecycleManager buckGlobalStateLifecycleManager;
  private Supplier<KnownRuleTypesProvider> knownRuleTypesProviderFactory;
  private KnownRuleTypesProvider knownRuleTypesProvider;
  private BuckConfig buckConfig;
  private Clock clock;
  private PluginManager pluginManager;
  private WatchmanClient watchmanClient;
  private Watchman watchman;
  private UnconfiguredBuildTargetViewFactory unconfiguredBuildTargetFactory;
  private TargetConfigurationSerializer targetConfigurationSerializer;

  @Before
  public void setUp() {
    filesystem = TestProjectFilesystems.createProjectFilesystem(tmp.getRoot());
    buckConfig = FakeBuckConfig.empty();
    buckGlobalStateLifecycleManager = new BuckGlobalStateLifecycleManager();
    pluginManager = BuckPluginManagerFactory.createPluginManager();
    knownRuleTypesProvider = TestKnownRuleTypesProvider.create(pluginManager);
    knownRuleTypesProviderFactory = () -> knownRuleTypesProvider;
    clock = FakeClock.doNotCare();
    watchmanClient = new FakeWatchmanClient(0, ImmutableMap.of());
    watchman =
        FakeWatchmanFactory.createWatchman(
            watchmanClient,
            filesystem.getRootPath().getPath(),
            filesystem.getRootPath().getPath(),
            "watch");
    unconfiguredBuildTargetFactory = new ParsingUnconfiguredBuildTargetViewFactory();
    CellPathResolver cellPathResolver = TestCellPathResolver.get(filesystem);
    targetConfigurationSerializer =
        new JsonTargetConfigurationSerializer(
            buildTarget ->
                unconfiguredBuildTargetFactory.create(
                    buildTarget, cellPathResolver.getCellNameResolver()));
  }

  @Test
  public void whenBuckConfigChangesParserInvalidated() {
    buckGlobalStateLifecycleManager.resetBuckGlobalState();
    Pair<BuckGlobalState, LifecycleStatus> buckStateResult1 =
        buckGlobalStateLifecycleManager.getBuckGlobalState(
            new TestCellBuilder()
                .setBuckConfig(
                    FakeBuckConfig.builder()
                        .setSections(
                            ImmutableMap.of(
                                "somesection", ImmutableMap.of("somename", "somevalue")))
                        .build())
                .setFilesystem(filesystem)
                .build(),
            knownRuleTypesProviderFactory,
            watchman,
            Console.createNullConsole(),
            clock,
            unconfiguredBuildTargetFactory,
            targetConfigurationSerializer);

    Pair<BuckGlobalState, LifecycleStatus> buckStateResult2 =
        buckGlobalStateLifecycleManager.getBuckGlobalState(
            new TestCellBuilder()
                .setBuckConfig(
                    FakeBuckConfig.builder()
                        .setSections(
                            ImmutableMap.of(
                                "somesection", ImmutableMap.of("somename", "somevalue")))
                        .build())
                .setFilesystem(filesystem)
                .build(),
            knownRuleTypesProviderFactory,
            watchman,
            Console.createNullConsole(),
            clock,
            unconfiguredBuildTargetFactory,
            targetConfigurationSerializer);

    Pair<BuckGlobalState, LifecycleStatus> buckStateResult3 =
        buckGlobalStateLifecycleManager.getBuckGlobalState(
            new TestCellBuilder()
                .setBuckConfig(
                    FakeBuckConfig.builder()
                        .setSections(
                            ImmutableMap.of(
                                "somesection", ImmutableMap.of("somename", "someothervalue")))
                        .build())
                .setFilesystem(filesystem)
                .build(),
            knownRuleTypesProviderFactory,
            watchman,
            Console.createNullConsole(),
            clock,
            unconfiguredBuildTargetFactory,
            targetConfigurationSerializer);

    assertEquals(
        "Daemon should not be replaced when config equal.",
        buckStateResult1.getFirst(),
        buckStateResult2.getFirst());

    assertNotEquals(
        "Daemon should be replaced when config not equal.",
        buckStateResult1.getFirst(),
        buckStateResult3.getFirst());

    assertEquals(LifecycleStatus.NEW, buckStateResult1.getSecond());
    assertEquals(LifecycleStatus.REUSED, buckStateResult2.getSecond());
    assertEquals(LifecycleStatus.INVALIDATED_BUCK_CONFIG_CHANGED, buckStateResult3.getSecond());
  }

  @Test
  public void whenAndroidNdkVersionChangesParserInvalidated() {

    BuckConfig buckConfig1 =
        FakeBuckConfig.builder()
            .setSections(ImmutableMap.of("ndk", ImmutableMap.of("ndk_version", "something")))
            .build();

    BuckConfig buckConfig2 =
        FakeBuckConfig.builder()
            .setSections(ImmutableMap.of("ndk", ImmutableMap.of("ndk_version", "different")))
            .build();

    Object buckGlobalState =
        buckGlobalStateLifecycleManager.getBuckGlobalState(
            new TestCellBuilder().setBuckConfig(buckConfig1).setFilesystem(filesystem).build(),
            knownRuleTypesProviderFactory,
            watchman,
            Console.createNullConsole(),
            clock,
            unconfiguredBuildTargetFactory,
            targetConfigurationSerializer);

    assertNotEquals(
        "Daemon state should be replaced when not equal.",
        buckGlobalState,
        buckGlobalStateLifecycleManager.getBuckGlobalState(
            new TestCellBuilder().setBuckConfig(buckConfig2).setFilesystem(filesystem).build(),
            knownRuleTypesProviderFactory,
            watchman,
            Console.createNullConsole(),
            clock,
            unconfiguredBuildTargetFactory,
            targetConfigurationSerializer));
  }

  @Test
  public void testAppleSdkChangesParserInvalidated() throws IOException {
    assumeThat(Platform.detect(), is(Platform.MACOS));
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    BuckConfig buckConfig = FakeBuckConfig.empty();

    Object buckGlobalState1 =
        buckGlobalStateLifecycleManager
            .getBuckGlobalState(
                new TestCellBuilder().setBuckConfig(buckConfig).setFilesystem(filesystem).build(),
                knownRuleTypesProviderFactory,
                watchman,
                Console.createNullConsole(),
                clock,
                unconfiguredBuildTargetFactory,
                targetConfigurationSerializer)
            .getFirst();

    Object buckGlobalState2 =
        buckGlobalStateLifecycleManager
            .getBuckGlobalState(
                new TestCellBuilder().setBuckConfig(buckConfig).setFilesystem(filesystem).build(),
                knownRuleTypesProviderFactory,
                watchman,
                Console.createNullConsole(),
                clock,
                unconfiguredBuildTargetFactory,
                targetConfigurationSerializer)
            .getFirst();
    assertEquals("Apple SDK should still be not found", buckGlobalState1, buckGlobalState2);

    Path appleDeveloperDirectoryPath = tmp.newFolder("android-sdk").getPath();

    BuckConfig buckConfigWithDeveloperDirectory =
        FakeBuckConfig.builder()
            .setSections(
                "[apple]", "xcode_developer_dir = " + appleDeveloperDirectoryPath.toAbsolutePath())
            .build();

    Object buckGlobalState3 =
        buckGlobalStateLifecycleManager
            .getBuckGlobalState(
                new TestCellBuilder()
                    .setBuckConfig(buckConfigWithDeveloperDirectory)
                    .setFilesystem(filesystem)
                    .build(),
                knownRuleTypesProviderFactory,
                watchman,
                Console.createNullConsole(),
                clock,
                unconfiguredBuildTargetFactory,
                targetConfigurationSerializer)
            .getFirst();
    assertNotEquals("Apple SDK should be found", buckGlobalState2, buckGlobalState3);

    Object buckGlobalState4 =
        buckGlobalStateLifecycleManager
            .getBuckGlobalState(
                new TestCellBuilder()
                    .setBuckConfig(buckConfigWithDeveloperDirectory)
                    .setFilesystem(filesystem)
                    .build(),
                knownRuleTypesProviderFactory,
                watchman,
                Console.createNullConsole(),
                clock,
                unconfiguredBuildTargetFactory,
                targetConfigurationSerializer)
            .getFirst();
    assertEquals("Apple SDK should still be found", buckGlobalState3, buckGlobalState4);
  }

  @Test
  public void testAndroidSdkChangesParserInvalidated() throws IOException {
    // Disable the test on Windows for now since it's failing to find python.
    assumeThat(Platform.detect(), not(Platform.WINDOWS));

    BuckConfig buckConfig = FakeBuckConfig.empty();
    Builder<Entry<ProcessExecutorParams, FakeProcess>> fakeProcessesBuilder =
        ImmutableList.builder();
    ProcessExecutorParams processExecutorParams =
        ProcessExecutorParams.builder()
            .setCommand(ImmutableList.of("xcode-select", "--print-path"))
            .build();
    // First KnownBuildRuleTypes resolution.
    fakeProcessesBuilder.add(
        new SimpleImmutableEntry<>(processExecutorParams, new FakeProcess(0, "/dev/null", "")));
    // Check SDK.
    fakeProcessesBuilder.add(
        new SimpleImmutableEntry<>(processExecutorParams, new FakeProcess(0, "/dev/null", "")));
    // Check SDK.
    fakeProcessesBuilder.add(
        new SimpleImmutableEntry<>(processExecutorParams, new FakeProcess(0, "/dev/null", "")));
    // KnownBuildRuleTypes resolution.
    fakeProcessesBuilder.add(
        new SimpleImmutableEntry<>(processExecutorParams, new FakeProcess(0, "/dev/null", "")));
    // Check SDK.
    fakeProcessesBuilder.add(
        new SimpleImmutableEntry<>(processExecutorParams, new FakeProcess(0, "/dev/null", "")));

    Object buckGlobalState1 =
        buckGlobalStateLifecycleManager
            .getBuckGlobalState(
                new TestCellBuilder().setBuckConfig(buckConfig).setFilesystem(filesystem).build(),
                knownRuleTypesProviderFactory,
                watchman,
                Console.createNullConsole(),
                clock,
                unconfiguredBuildTargetFactory,
                targetConfigurationSerializer)
            .getFirst();
    Object buckGlobalState2 =
        buckGlobalStateLifecycleManager
            .getBuckGlobalState(
                new TestCellBuilder().setBuckConfig(buckConfig).setFilesystem(filesystem).build(),
                knownRuleTypesProviderFactory,
                watchman,
                Console.createNullConsole(),
                clock,
                unconfiguredBuildTargetFactory,
                targetConfigurationSerializer)
            .getFirst();
    assertEquals(
        "Android SDK should be the same initial location", buckGlobalState1, buckGlobalState2);

    Path androidSdkPath = tmp.newFolder("android-sdk").getPath();

    Cells cell = createCellWithAndroidSdk(androidSdkPath);

    Object buckGlobalState3 =
        buckGlobalStateLifecycleManager
            .getBuckGlobalState(
                cell,
                knownRuleTypesProviderFactory,
                watchman,
                Console.createNullConsole(),
                clock,
                unconfiguredBuildTargetFactory,
                targetConfigurationSerializer)
            .getFirst();

    assertEquals("Daemon should not be re-created", buckGlobalState2, buckGlobalState3);
    Object buckGlobalState4 =
        buckGlobalStateLifecycleManager
            .getBuckGlobalState(
                cell,
                knownRuleTypesProviderFactory,
                watchman,
                Console.createNullConsole(),
                clock,
                unconfiguredBuildTargetFactory,
                targetConfigurationSerializer)
            .getFirst();

    assertEquals(
        "Android SDK should be the same other location", buckGlobalState3, buckGlobalState4);
  }

  @Test
  public void testAndroidSdkChangesParserInvalidatedWhenToolchainsPresent() throws IOException {
    // Disable the test on Windows for now since it's failing to find python.
    assumeThat(Platform.detect(), not(Platform.WINDOWS));

    BuckConfig buckConfig = FakeBuckConfig.empty();
    Builder<Entry<ProcessExecutorParams, FakeProcess>> fakeProcessesBuilder =
        ImmutableList.builder();
    ProcessExecutorParams processExecutorParams =
        ProcessExecutorParams.builder()
            .setCommand(ImmutableList.of("xcode-select", "--print-path"))
            .build();
    // First KnownBuildRuleTypes resolution.
    fakeProcessesBuilder.add(
        new SimpleImmutableEntry<>(processExecutorParams, new FakeProcess(0, "/dev/null", "")));
    // Check SDK.
    fakeProcessesBuilder.add(
        new SimpleImmutableEntry<>(processExecutorParams, new FakeProcess(0, "/dev/null", "")));
    // Check SDK.
    fakeProcessesBuilder.add(
        new SimpleImmutableEntry<>(processExecutorParams, new FakeProcess(0, "/dev/null", "")));
    // KnownBuildRuleTypes resolution.
    fakeProcessesBuilder.add(
        new SimpleImmutableEntry<>(processExecutorParams, new FakeProcess(0, "/dev/null", "")));
    // Check SDK.
    fakeProcessesBuilder.add(
        new SimpleImmutableEntry<>(processExecutorParams, new FakeProcess(0, "/dev/null", "")));

    Object buckGlobalState1 =
        buckGlobalStateLifecycleManager
            .getBuckGlobalState(
                new TestCellBuilder().setBuckConfig(buckConfig).setFilesystem(filesystem).build(),
                knownRuleTypesProviderFactory,
                watchman,
                Console.createNullConsole(),
                clock,
                unconfiguredBuildTargetFactory,
                targetConfigurationSerializer)
            .getFirst();
    Object buckGlobalState2 =
        buckGlobalStateLifecycleManager
            .getBuckGlobalState(
                new TestCellBuilder().setBuckConfig(buckConfig).setFilesystem(filesystem).build(),
                knownRuleTypesProviderFactory,
                watchman,
                Console.createNullConsole(),
                clock,
                unconfiguredBuildTargetFactory,
                targetConfigurationSerializer)
            .getFirst();
    assertEquals(
        "Android SDK should be the same initial location", buckGlobalState1, buckGlobalState2);

    Path androidSdkPath = tmp.newFolder("android-sdk").getPath();

    Cells cell = createCellWithAndroidSdk(androidSdkPath);
    cell.getRootCell()
        .getToolchainProvider()
        .getByName(AndroidSdkLocation.DEFAULT_NAME, UnconfiguredTargetConfiguration.INSTANCE);

    Object buckGlobalState3 =
        buckGlobalStateLifecycleManager
            .getBuckGlobalState(
                cell,
                knownRuleTypesProviderFactory,
                watchman,
                Console.createNullConsole(),
                clock,
                unconfiguredBuildTargetFactory,
                targetConfigurationSerializer)
            .getFirst();

    assertNotEquals("Android SDK should be the other location", buckGlobalState2, buckGlobalState3);
    Object buckGlobalState4 =
        buckGlobalStateLifecycleManager
            .getBuckGlobalState(
                cell,
                knownRuleTypesProviderFactory,
                watchman,
                Console.createNullConsole(),
                clock,
                unconfiguredBuildTargetFactory,
                targetConfigurationSerializer)
            .getFirst();

    assertEquals(
        "Android SDK should be the same other location", buckGlobalState3, buckGlobalState4);
  }

  private Cells createCellWithAndroidSdk(Path androidSdkPath) {
    return new TestCellBuilder()
        .setBuckConfig(buckConfig)
        .setFilesystem(filesystem)
        .addEnvironmentVariable("ANDROID_HOME", androidSdkPath.toString())
        .addEnvironmentVariable("ANDROID_SDK", androidSdkPath.toString())
        .build();
  }

  @Test
  public void testParserInvalidatedWhenToolchainFailsToCreateFirstTime() throws IOException {
    assumeThat(Platform.detect(), not(Platform.WINDOWS));

    Path androidSdkPath = tmp.newFolder("android-sdk").getPath();
    Files.deleteIfExists(androidSdkPath);

    Cells cell = createCellWithAndroidSdk(androidSdkPath);
    cell.getRootCell()
        .getToolchainProvider()
        .getByNameIfPresent(
            AndroidSdkLocation.DEFAULT_NAME,
            UnconfiguredTargetConfiguration.INSTANCE,
            AndroidSdkLocation.class);
    BuckGlobalState buckGlobalStateWithBrokenAndroidSdk =
        buckGlobalStateLifecycleManager
            .getBuckGlobalState(
                cell,
                knownRuleTypesProviderFactory,
                watchman,
                Console.createNullConsole(),
                clock,
                unconfiguredBuildTargetFactory,
                targetConfigurationSerializer)
            .getFirst();

    tmp.newFolder("android-sdk");

    cell = createCellWithAndroidSdk(androidSdkPath);
    BuckGlobalState buckGlobalStateWithWorkingAndroidSdk =
        buckGlobalStateLifecycleManager
            .getBuckGlobalState(
                cell,
                knownRuleTypesProviderFactory,
                watchman,
                Console.createNullConsole(),
                clock,
                unconfiguredBuildTargetFactory,
                targetConfigurationSerializer)
            .getFirst();

    assertNotEquals(buckGlobalStateWithBrokenAndroidSdk, buckGlobalStateWithWorkingAndroidSdk);
  }

  @Test
  public void testParserInvalidatedWhenToolchainFailsToCreateAfterFirstCreation()
      throws IOException {
    assumeThat(Platform.detect(), not(Platform.WINDOWS));

    Path androidSdkPath = tmp.newFolder("android-sdk").getPath();

    Cells cell = createCellWithAndroidSdk(androidSdkPath);
    cell.getRootCell()
        .getToolchainProvider()
        .getByNameIfPresent(
            AndroidSdkLocation.DEFAULT_NAME,
            UnconfiguredTargetConfiguration.INSTANCE,
            AndroidSdkLocation.class);
    BuckGlobalState buckGlobalStateWithWorkingAndroidSdk =
        buckGlobalStateLifecycleManager
            .getBuckGlobalState(
                cell,
                knownRuleTypesProviderFactory,
                watchman,
                Console.createNullConsole(),
                clock,
                unconfiguredBuildTargetFactory,
                targetConfigurationSerializer)
            .getFirst();

    Files.deleteIfExists(androidSdkPath);

    cell = createCellWithAndroidSdk(androidSdkPath);
    BuckGlobalState buckGlobalStateWithBrokenAndroidSdk =
        buckGlobalStateLifecycleManager
            .getBuckGlobalState(
                cell,
                knownRuleTypesProviderFactory,
                watchman,
                Console.createNullConsole(),
                clock,
                unconfiguredBuildTargetFactory,
                targetConfigurationSerializer)
            .getFirst();

    assertNotEquals(buckGlobalStateWithWorkingAndroidSdk, buckGlobalStateWithBrokenAndroidSdk);
  }

  @Test
  public void testParserNotInvalidatedWhenToolchainFailsWithTheSameProblem() throws IOException {
    assumeThat(Platform.detect(), not(Platform.WINDOWS));

    Path androidSdkPath = tmp.newFolder("android-sdk").getPath();
    Files.deleteIfExists(androidSdkPath);

    Cells cell = createCellWithAndroidSdk(androidSdkPath);
    cell.getRootCell()
        .getToolchainProvider()
        .getByNameIfPresent(
            AndroidSdkLocation.DEFAULT_NAME,
            UnconfiguredTargetConfiguration.INSTANCE,
            AndroidSdkLocation.class);
    BuckGlobalState buckGlobalStateWithBrokenAndroidSdk1 =
        buckGlobalStateLifecycleManager
            .getBuckGlobalState(
                cell,
                knownRuleTypesProviderFactory,
                watchman,
                Console.createNullConsole(),
                clock,
                unconfiguredBuildTargetFactory,
                targetConfigurationSerializer)
            .getFirst();

    cell = createCellWithAndroidSdk(androidSdkPath);
    cell.getRootCell()
        .getToolchainProvider()
        .getByNameIfPresent(
            AndroidSdkLocation.DEFAULT_NAME,
            UnconfiguredTargetConfiguration.INSTANCE,
            AndroidSdkLocation.class);
    BuckGlobalState buckGlobalStateWithBrokenAndroidSdk2 =
        buckGlobalStateLifecycleManager
            .getBuckGlobalState(
                cell,
                knownRuleTypesProviderFactory,
                watchman,
                Console.createNullConsole(),
                clock,
                unconfiguredBuildTargetFactory,
                targetConfigurationSerializer)
            .getFirst();

    assertEquals(buckGlobalStateWithBrokenAndroidSdk1, buckGlobalStateWithBrokenAndroidSdk2);
  }

  @Test
  public void testParserNotInvalidatedWhenToolchainFailsWithTheSameProblemButNotInstantiated()
      throws IOException {
    assumeThat(Platform.detect(), not(Platform.WINDOWS));

    Path androidSdkPath = tmp.newFolder("android-sdk").getPath();
    Files.deleteIfExists(androidSdkPath);

    Cells cell = createCellWithAndroidSdk(androidSdkPath);
    cell.getRootCell()
        .getToolchainProvider()
        .getByNameIfPresent(
            AndroidSdkLocation.DEFAULT_NAME,
            UnconfiguredTargetConfiguration.INSTANCE,
            AndroidSdkLocation.class);
    BuckGlobalState buckGlobalStateWithBrokenAndroidSdk1 =
        buckGlobalStateLifecycleManager
            .getBuckGlobalState(
                cell,
                knownRuleTypesProviderFactory,
                watchman,
                Console.createNullConsole(),
                clock,
                unconfiguredBuildTargetFactory,
                targetConfigurationSerializer)
            .getFirst();

    cell = createCellWithAndroidSdk(androidSdkPath);
    BuckGlobalState buckGlobalStateWithBrokenAndroidSdk2 =
        buckGlobalStateLifecycleManager
            .getBuckGlobalState(
                cell,
                knownRuleTypesProviderFactory,
                watchman,
                Console.createNullConsole(),
                clock,
                unconfiguredBuildTargetFactory,
                targetConfigurationSerializer)
            .getFirst();

    assertEquals(buckGlobalStateWithBrokenAndroidSdk1, buckGlobalStateWithBrokenAndroidSdk2);
  }

  @Test
  public void testParserInvalidatedWhenToolchainFailsWithDifferentProblem() throws IOException {
    assumeThat(Platform.detect(), not(Platform.WINDOWS));

    Path androidSdkPath = tmp.newFolder("android-sdk").getPath();
    Files.deleteIfExists(androidSdkPath);

    Cells cell = createCellWithAndroidSdk(androidSdkPath);
    cell.getRootCell()
        .getToolchainProvider()
        .getByNameIfPresent(
            AndroidSdkLocation.DEFAULT_NAME,
            UnconfiguredTargetConfiguration.INSTANCE,
            AndroidSdkLocation.class);
    Object buckGlobalStateWithBrokenAndroidSdk1 =
        buckGlobalStateLifecycleManager.getBuckGlobalState(
            cell,
            knownRuleTypesProviderFactory,
            watchman,
            Console.createNullConsole(),
            clock,
            unconfiguredBuildTargetFactory,
            targetConfigurationSerializer);

    cell = createCellWithAndroidSdk(androidSdkPath.resolve("some-other-dir"));
    Object buckGlobalStateWithBrokenAndroidSdk2 =
        buckGlobalStateLifecycleManager.getBuckGlobalState(
            cell,
            knownRuleTypesProviderFactory,
            watchman,
            Console.createNullConsole(),
            clock,
            unconfiguredBuildTargetFactory,
            targetConfigurationSerializer);

    assertNotEquals(buckGlobalStateWithBrokenAndroidSdk1, buckGlobalStateWithBrokenAndroidSdk2);
  }

  @Test
  public void testBuckGlobalStateUptime() {
    Cells cell = new TestCellBuilder().setBuckConfig(buckConfig).setFilesystem(filesystem).build();
    SettableFakeClock clock = new SettableFakeClock(1000, 0);
    BuckGlobalState buckGlobalState =
        buckGlobalStateLifecycleManager
            .getBuckGlobalState(
                cell,
                knownRuleTypesProviderFactory,
                watchman,
                Console.createNullConsole(),
                clock,
                unconfiguredBuildTargetFactory,
                targetConfigurationSerializer)
            .getFirst();

    assertEquals(buckGlobalState.getUptime(), 0);
    clock.setCurrentTimeMillis(2000);
    assertEquals(buckGlobalState.getUptime(), 1000);
  }

  @Test
  public void whenBuckWhitelistedConfigChangesDaemonNotRestarted() {
    buckGlobalStateLifecycleManager.resetBuckGlobalState();
    Pair<BuckGlobalState, LifecycleStatus> buckStateResultFirstRun =
        buckGlobalStateLifecycleManager.getBuckGlobalState(
            new TestCellBuilder()
                .setBuckConfig(
                    FakeBuckConfig.builder()
                        .setSections(
                            ImmutableMap.of(
                                "ui",
                                ImmutableMap.of(CliConfig.TRUNCATE_FAILING_COMMAND_CONFIG, "true")))
                        .build())
                .setFilesystem(filesystem)
                .build(),
            knownRuleTypesProviderFactory,
            watchman,
            Console.createNullConsole(),
            clock,
            unconfiguredBuildTargetFactory,
            targetConfigurationSerializer);

    Pair<BuckGlobalState, LifecycleStatus> buckStateResultSecondRun =
        buckGlobalStateLifecycleManager.getBuckGlobalState(
            new TestCellBuilder()
                .setBuckConfig(
                    FakeBuckConfig.builder()
                        .setSections(
                            ImmutableMap.of(
                                "ui",
                                ImmutableMap.of(
                                    CliConfig.TRUNCATE_FAILING_COMMAND_CONFIG, "false")))
                        .build())
                .setFilesystem(filesystem)
                .build(),
            knownRuleTypesProviderFactory,
            watchman,
            Console.createNullConsole(),
            clock,
            unconfiguredBuildTargetFactory,
            targetConfigurationSerializer);

    assertEquals(
        "Daemon should not be replaced when whitelisted config changes.",
        buckStateResultFirstRun.getFirst(),
        buckStateResultSecondRun.getFirst());

    assertEquals(LifecycleStatus.NEW, buckStateResultFirstRun.getSecond());
    assertEquals(LifecycleStatus.REUSED, buckStateResultSecondRun.getSecond());
  }

  @Test
  public void whenConfigChangesKnownRuleTypesRecreated()
      throws LabelSyntaxException, EvalException {

    buckGlobalStateLifecycleManager.resetBuckGlobalState();

    Cells cells1 =
        new TestCellBuilder()
            .setBuckConfig(FakeBuckConfig.empty())
            .setFilesystem(filesystem)
            .build();
    Cells cells2 =
        new TestCellBuilder()
            .setBuckConfig(
                FakeBuckConfig.builder()
                    .setSections(ImmutableMap.of("foo", ImmutableMap.of("bar", "baz")))
                    .build())
            .setFilesystem(filesystem)
            .build();
    SkylarkUserDefinedRule rule = FakeSkylarkUserDefinedRuleFactory.createSimpleRule();

    Pair<BuckGlobalState, LifecycleStatus> buckStateResultFirstRun =
        buckGlobalStateLifecycleManager.getBuckGlobalState(
            cells1,
            () -> TestKnownRuleTypesProvider.create(pluginManager),
            watchman,
            Console.createNullConsole(),
            clock,
            unconfiguredBuildTargetFactory,
            targetConfigurationSerializer);
    KnownRuleTypesProvider firstRuleTypesProvider =
        buckStateResultFirstRun.getFirst().getKnownRuleTypesProvider();
    firstRuleTypesProvider.getUserDefinedRuleTypes(cells1.getRootCell()).addRule(rule);

    Pair<BuckGlobalState, LifecycleStatus> buckStateResultSecondRun =
        buckGlobalStateLifecycleManager.getBuckGlobalState(
            cells2,
            () -> TestKnownRuleTypesProvider.create(pluginManager),
            watchman,
            Console.createNullConsole(),
            clock,
            unconfiguredBuildTargetFactory,
            targetConfigurationSerializer);

    KnownRuleTypesProvider secondRuleTypesProvider =
        buckStateResultSecondRun.getFirst().getKnownRuleTypesProvider();

    assertNotSame(firstRuleTypesProvider, secondRuleTypesProvider);
    assertNull(
        secondRuleTypesProvider
            .getUserDefinedRuleTypes(cells1.getRootCell())
            .getRule("//foo:bar.bzl:some_rule"));

    assertNotEquals(buckStateResultFirstRun.getFirst(), buckStateResultSecondRun.getFirst());
    assertEquals(LifecycleStatus.NEW, buckStateResultFirstRun.getSecond());
    assertEquals(
        LifecycleStatus.INVALIDATED_BUCK_CONFIG_CHANGED, buckStateResultSecondRun.getSecond());
  }

  @Test
  public void whenNothingChangedOriginalKnownRuleTypesReturned() throws Exception {
    buckGlobalStateLifecycleManager.resetBuckGlobalState();

    Cells cells =
        new TestCellBuilder()
            .setBuckConfig(FakeBuckConfig.empty())
            .setFilesystem(filesystem)
            .build();
    SkylarkUserDefinedRule rule = FakeSkylarkUserDefinedRuleFactory.createSimpleRule();

    Pair<BuckGlobalState, LifecycleStatus> buckStateResultFirstRun =
        buckGlobalStateLifecycleManager.getBuckGlobalState(
            cells,
            knownRuleTypesProviderFactory,
            watchman,
            Console.createNullConsole(),
            clock,
            unconfiguredBuildTargetFactory,
            targetConfigurationSerializer);
    KnownRuleTypesProvider firstRuleTypesProvider =
        buckStateResultFirstRun.getFirst().getKnownRuleTypesProvider();
    firstRuleTypesProvider.getUserDefinedRuleTypes(cells.getRootCell()).addRule(rule);

    Pair<BuckGlobalState, LifecycleStatus> buckStateResultSecondRun =
        buckGlobalStateLifecycleManager.getBuckGlobalState(
            cells,
            knownRuleTypesProviderFactory,
            watchman,
            Console.createNullConsole(),
            clock,
            unconfiguredBuildTargetFactory,
            targetConfigurationSerializer);

    KnownRuleTypesProvider secondRuleTypesProvider =
        buckStateResultSecondRun.getFirst().getKnownRuleTypesProvider();

    assertSame(firstRuleTypesProvider, secondRuleTypesProvider);
    assertSame(
        rule,
        secondRuleTypesProvider
            .getUserDefinedRuleTypes(cells.getRootCell())
            .getRule("//foo:bar.bzl:some_rule"));

    assertEquals(buckStateResultFirstRun.getFirst(), buckStateResultSecondRun.getFirst());
    assertEquals(LifecycleStatus.NEW, buckStateResultFirstRun.getSecond());
    assertEquals(LifecycleStatus.REUSED, buckStateResultSecondRun.getSecond());
  }

  private LifecycleStatus getBuckGlobalState() throws Exception {
    Config config =
        Configs.createDefaultConfig(
            tmp.getRoot().getPath(),
            Configs.getRepoConfigurationFiles(tmp.getRoot().getPath()),
            RawConfig.of());

    DefaultCellPathResolver cellPathResolver =
        DefaultCellPathResolver.create(tmp.getRoot(), config);

    Cells cells =
        new Cells(
            LocalCellProviderFactory.create(
                filesystem,
                new BuckConfig(
                    config,
                    filesystem,
                    Architecture.AARCH64,
                    Platform.FREEBSD,
                    ImmutableMap.of(),
                    unconfiguredBuildTargetFactory,
                    cellPathResolver.getCellNameResolver()),
                CellConfig.EMPTY_INSTANCE,
                cellPathResolver,
                new EmptyToolchainProviderFactory(),
                new DefaultProjectFilesystemFactory(),
                unconfiguredBuildTargetFactory,
                new WatchmanFactory.NullWatchman(
                    "BuckGlobalStateLifecycleManagerTest", WatchmanError.TEST),
                Optional.empty()));

    return buckGlobalStateLifecycleManager
        .getBuckGlobalState(
            cells,
            knownRuleTypesProviderFactory,
            watchman,
            Console.createNullConsole(),
            clock,
            unconfiguredBuildTargetFactory,
            targetConfigurationSerializer)
        .getSecond();
  }

  @Test
  public void globalStateInvalidatedIfSecondCellBuckconfigChanges() throws Exception {
    AbsPath secondCellRoot = tmp.getRoot().resolve("second");

    Files.createDirectory(secondCellRoot.getPath());

    MostFiles.write(
        tmp.getRoot().resolve(".buckconfig"), "[repositories]\nroot = .\nsecond = second\n");

    LifecycleStatus rrr = getBuckGlobalState();
    assertEquals(LifecycleStatus.NEW, rrr);

    LifecycleStatus rrr2 = getBuckGlobalState();
    assertEquals(LifecycleStatus.REUSED, rrr2);

    MostFiles.write(secondCellRoot.resolve(".buckconfig"), "[some]\nvalue = banana\n");

    LifecycleStatus rrr3 = getBuckGlobalState();
    assertEquals(LifecycleStatus.INVALIDATED_BUCK_CONFIG_CHANGED, rrr3);
  }
}
