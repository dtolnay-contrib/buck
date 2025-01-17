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

package com.facebook.buck.cxx.toolchain;

import com.facebook.buck.core.toolchain.tool.Tool;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;

/**
 * Compiler implementation for the Clang for Windows toolchain.
 *
 * <p>This uses clang.exe directly, not simply clang-cl which is a CL-compatible front end for
 * clang. This implementation exists basically only because of argfile differences.
 * --rsp-quoting=windows should be added to use argfile on Windows.
 */
public class ClangWindowsCompiler extends ClangCompiler {

  public ClangWindowsCompiler(Tool tool, ToolType toolType, boolean useDependencyTree) {
    super(tool, toolType, useDependencyTree, false);
  }

  @Override
  public ImmutableList<String> getFlagsForReproducibleBuild(
      String altCompilationDir, Path currentCellPath) {
    ImmutableList<String> flags =
        super.getFlagsForReproducibleBuild(altCompilationDir, currentCellPath);
    ImmutableList.Builder<String> builder = ImmutableList.builder();
    flags = builder.addAll(flags).add("-mno-incremental-linker-compatible").build();
    return flags;
  }

  @Override
  public ImmutableList<String> getPreArgfileArgs() {
    ImmutableList.Builder<String> builder = ImmutableList.builder();
    return builder.add("--rsp-quoting=windows").build();
  }

  @Override
  public ImmutableList<String> getPicFlags() {
    return ImmutableList.of();
  }
}
