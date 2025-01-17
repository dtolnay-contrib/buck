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

import com.facebook.buck.android.toolchain.AndroidPlatformTarget;
import com.facebook.buck.command.config.BuildBuckConfig;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.AbstractNodeBuilder;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.toolchain.impl.ToolchainProviderBuilder;
import com.facebook.buck.downwardapi.config.DownwardApiConfig;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.util.types.Either;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;

public class AndroidResourceBuilder
    extends AbstractNodeBuilder<
        AndroidResourceDescriptionArg.Builder,
        AndroidResourceDescriptionArg,
        AndroidResourceDescription,
        AndroidResource> {

  private AndroidResourceBuilder(BuildTarget target, ProjectFilesystem filesystem) {
    super(getDescription(), target, filesystem);
  }

  private static AndroidResourceDescription getDescription() {
    BuckConfig buckConfig = FakeBuckConfig.empty();
    return new AndroidResourceDescription(
        createToolchainProviderForAndroidResource(),
        DownwardApiConfig.of(buckConfig),
        BuildBuckConfig.of(buckConfig));
  }

  private static ToolchainProvider createToolchainProviderForAndroidResource() {
    return new ToolchainProviderBuilder()
        .withToolchain(
            AndroidPlatformTarget.DEFAULT_NAME, AndroidTestUtils.createAndroidPlatformTarget())
        .build();
  }

  public static AndroidResourceBuilder createBuilder(BuildTarget target) {
    return new AndroidResourceBuilder(target, new FakeProjectFilesystem());
  }

  public static AndroidResourceBuilder createBuilder(
      BuildTarget target, ProjectFilesystem filesystem) {
    return new AndroidResourceBuilder(target, filesystem);
  }

  public AndroidResourceBuilder setDeps(ImmutableSortedSet<BuildTarget> deps) {
    getArgForPopulating().setDeps(deps);
    return this;
  }

  public AndroidResourceBuilder setRes(SourcePath res) {
    getArgForPopulating().setRes(Either.ofLeft(res));
    return this;
  }

  public AndroidResourceBuilder setRes(Path res) {
    return setRes(PathSourcePath.of(new FakeProjectFilesystem(), res));
  }

  public AndroidResourceBuilder setAssets(SourcePath assets) {
    getArgForPopulating().setAssets(Either.ofLeft(assets));
    return this;
  }

  public AndroidResourceBuilder setAssets(ImmutableSortedMap<String, SourcePath> assets) {
    getArgForPopulating().setAssets(Either.ofRight(assets));
    return this;
  }

  public AndroidResourceBuilder setRDotJavaPackage(String rDotJavaPackage) {
    getArgForPopulating().setPackage(rDotJavaPackage);
    return this;
  }

  public AndroidResourceBuilder setManifest(SourcePath manifest) {
    getArgForPopulating().setManifest(manifest);
    return this;
  }
}
