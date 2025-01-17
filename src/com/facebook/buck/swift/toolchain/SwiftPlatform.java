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

package com.facebook.buck.swift.toolchain;

import com.facebook.buck.apple.common.AppleCompilerTargetTriple;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.util.immutables.BuckStyleValueWithBuilder;
import com.facebook.buck.rules.args.Arg;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.util.Optional;
import org.immutables.value.Value;

/** Interface describing a Swift toolchain and platform to build for. */
@BuckStyleValueWithBuilder
public interface SwiftPlatform {

  Tool getSwiftc();

  Optional<Tool> getSwiftStdlibTool();

  /**
   * @return the path to the toolchains platform dir. This is used for adding compile and link
   *     search paths.
   */
  SourcePath getPlatformPath();

  /** @return the path to the target SDK. */
  SourcePath getSdkPath();

  /** @return the path to the compiler resource dir. TODO: make this non-optional once adopted. */
  Optional<SourcePath> getResourceDir();

  /**
   * @return an optional SDK dependency provider derived from the toolchains sdk_dependencies_path.
   */
  Optional<SwiftSdkDependenciesProvider> getSdkDependencies();

  /**
   * @return A set of directories which contain the Swift runtime as dynamic libraries. On macOS,
   *     the directory will contain libs like libswiftCore.dylib and others. The libs will be passed
   *     to swift-stdlib-tool for inclusion in the app bundle.
   */
  ImmutableSet<Path> getSwiftRuntimePathsForBundling();

  /**
   * @return A set of directories which contain the Swift runtime as dynamic libraries. On macOS,
   *     the directory will contain the .tbd libs like libSwiftCore.tbd and others. The libs will be
   *     passed during the link step.
   */
  ImmutableSet<Path> getSwiftRuntimePathsForLinking();

  /**
   * @return A set of directories which contain the Swift runtime as static libraries. On macOS, the
   *     directory will contain libs like libswiftCore.a and others.
   */
  ImmutableSet<Path> getSwiftStaticRuntimePaths();

  /**
   * @return A set of search paths used by the dynamic linker loader to find of linked shared
   *     libraries. Each of the paths is usually referred as an "rpath". For example, on iOS,
   *     "@executable_path/Frameworks" is a common rpath.
   */
  ImmutableList<Path> getSwiftSharedLibraryRunPaths();

  /**
   * @return Structure that represents the architecture target the swift module is built for e.g.
   *     x86_64-apple-ios9.0
   */
  AppleCompilerTargetTriple getSwiftTarget();

  /** @return A map of debug paths to prefix during compilation. */
  ImmutableBiMap<Path, String> getDebugPrefixMap();

  /** @return true if the platform supports the -prefix-serialized-debugging-options flag. */
  @Value.Default
  default boolean getPrefixSerializedDebuggingOptions() {
    return false;
  };

  @Value.Default
  default ImmutableList<Path> getAdditionalSystemFrameworkSearchPaths() {
    return ImmutableList.of();
  }

  /** @return A list of platform specific Swift flags. */
  @Value.Default
  default ImmutableList<Arg> getSwiftFlags() {
    return ImmutableList.of();
  }

  static Builder builder() {
    return new Builder();
  }

  class Builder extends ImmutableSwiftPlatform.Builder {}
}
