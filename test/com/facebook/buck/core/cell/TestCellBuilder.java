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

package com.facebook.buck.core.cell;

import com.facebook.buck.core.cell.impl.DefaultCellPathResolver;
import com.facebook.buck.core.cell.impl.LocalCellProviderFactory;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.parser.buildtargetparser.ParsingUnconfiguredBuildTargetViewFactory;
import com.facebook.buck.core.plugin.impl.BuckPluginManagerFactory;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.toolchain.ToolchainProviderFactory;
import com.facebook.buck.core.toolchain.impl.DefaultToolchainProviderFactory;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.DefaultProjectFilesystemFactory;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.io.watchman.WatchmanError;
import com.facebook.buck.io.watchman.WatchmanFactory;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.ProcessExecutor;
import com.google.common.collect.ImmutableMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;
import org.pf4j.PluginManager;

public class TestCellBuilder {

  @Nullable private ProjectFilesystem filesystem;
  private BuckConfig buckConfig;
  private CellConfig cellConfig;
  private final Map<String, String> environment = new HashMap<>();
  @Nullable private ToolchainProvider toolchainProvider = null;

  public TestCellBuilder() {
    cellConfig = CellConfig.EMPTY_INSTANCE;
  }

  public TestCellBuilder setFilesystem(ProjectFilesystem filesystem) {
    this.filesystem = filesystem;
    return this;
  }

  public TestCellBuilder setBuckConfig(BuckConfig buckConfig) {
    this.buckConfig = buckConfig;
    return this;
  }

  public TestCellBuilder setCellConfigOverride(CellConfig cellConfig) {
    this.cellConfig = cellConfig;
    return this;
  }

  public TestCellBuilder addEnvironmentVariable(String variableName, String value) {
    environment.put(variableName, value);
    return this;
  }

  public TestCellBuilder setToolchainProvider(ToolchainProvider toolchainProvider) {
    this.toolchainProvider = toolchainProvider;
    return this;
  }

  public Cells build() {
    ProjectFilesystem filesystem =
        this.filesystem != null ? this.filesystem : new FakeProjectFilesystem();

    BuckConfig config =
        buckConfig == null
            ? FakeBuckConfig.builder().setFilesystem(filesystem).build()
            : buckConfig;

    PluginManager pluginManager = BuckPluginManagerFactory.createPluginManager();
    ProcessExecutor processExecutor = new DefaultProcessExecutor(new TestConsole());
    ExecutableFinder executableFinder = new ExecutableFinder();

    ImmutableMap<String, String> environmentCopy = ImmutableMap.copyOf(environment);

    ToolchainProviderFactory toolchainProviderFactory =
        toolchainProvider == null
            ? new DefaultToolchainProviderFactory(
                pluginManager, environmentCopy, processExecutor, executableFinder)
            : (buckConfig, filesystemIgnore, ruleKeyConfiguration) -> toolchainProvider;

    DefaultCellPathResolver rootCellCellPathResolver =
        DefaultCellPathResolver.create(filesystem.getRootPath(), config.getConfig());

    return new Cells(
        LocalCellProviderFactory.create(
            filesystem,
            config,
            cellConfig,
            rootCellCellPathResolver,
            toolchainProviderFactory,
            new DefaultProjectFilesystemFactory(),
            new ParsingUnconfiguredBuildTargetViewFactory(),
            new WatchmanFactory.NullWatchman("TestCellBuilder", WatchmanError.TEST),
            Optional.empty()));
  }

  public static CellPathResolver createCellRoots(@Nullable ProjectFilesystem filesystem) {
    ProjectFilesystem toUse = filesystem == null ? new FakeProjectFilesystem() : filesystem;
    return TestCellPathResolver.get(toUse);
  }
}
