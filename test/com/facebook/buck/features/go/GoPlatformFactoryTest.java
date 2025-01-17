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

package com.facebook.buck.features.go;

import static org.hamcrest.MatcherAssert.assertThat;

import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.UnconfiguredTargetConfiguration;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.io.AlwaysFoundExecutableFinder;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.util.FakeProcessExecutor;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import org.hamcrest.Matchers;
import org.junit.Test;

public class GoPlatformFactoryTest {

  @Test
  public void getPlatform() throws IOException {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    AbsPath root = filesystem.resolve("root");
    filesystem.mkdirs(root);
    GoPlatformFactory factory =
        ImmutableGoPlatformFactory.ofImpl(
            FakeBuckConfig.builder()
                .setFilesystem(filesystem)
                .setSections(
                    ImmutableMap.of(
                        "section",
                        ImmutableMap.of(
                            "os",
                            "linux",
                            "arch",
                            "amd64",
                            "root",
                            root.toString(),
                            "tool_dir",
                            root.toString())))
                .build(),
            new FakeProcessExecutor(),
            new AlwaysFoundExecutableFinder(),
            CxxPlatformUtils.DEFAULT_PLATFORMS,
            CxxPlatformUtils.DEFAULT_UNRESOLVED_PLATFORM);
    GoPlatform platform =
        factory
            .getPlatform("section", CxxPlatformUtils.DEFAULT_PLATFORM_FLAVOR)
            .resolve(new TestActionGraphBuilder(), UnconfiguredTargetConfiguration.INSTANCE);
    assertThat(platform.getGoOs(), Matchers.equalTo(GoOs.LINUX));
    assertThat(platform.getGoArch(), Matchers.equalTo(GoArch.AMD64));
  }
}
