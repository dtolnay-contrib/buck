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

package com.facebook.buck.features.halide;

import com.facebook.buck.core.build.execution.context.IsolatedExecutionContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.step.isolatedsteps.shell.IsolatedShellStep;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.Optional;

public class HalideCompilerStep extends IsolatedShellStep {
  // The list of environment variables needed to run our generated compiler.
  private final ImmutableMap<String, String> environment;

  // The list of arguments needed to run our generated compiler.
  private final ImmutableList<String> compilerPrefix;

  // The output directory in which to write the generated shader code.
  private final RelPath outputDir;

  // The name of the function (or pipeline) to compile.
  private final String funcName;

  // The Halide target string for the target architecture, e.g. "x86-64-osx".
  private final String halideTarget;

  // Flags that can be passed directly to the Halide compiler.
  private final Optional<ImmutableList<String>> compilerInvocationFlags;

  public HalideCompilerStep(
      AbsPath workingDirectory,
      ImmutableMap<String, String> environment,
      ImmutableList<String> compilerPrefix,
      RelPath outputDir,
      String funcName,
      String halideTarget,
      Optional<ImmutableList<String>> compilerInvocationFlags,
      RelPath cellPath,
      boolean withDownwardApi) {
    super(workingDirectory, cellPath, withDownwardApi);
    this.environment = environment;
    this.compilerPrefix = compilerPrefix;
    this.outputDir = outputDir;
    this.funcName = funcName;
    this.halideTarget = halideTarget;
    this.compilerInvocationFlags = compilerInvocationFlags;
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(IsolatedExecutionContext context) {
    ImmutableList.Builder<String> builder = ImmutableList.builder();
    builder.addAll(compilerPrefix);
    builder.add("-h");
    builder.add("-o", outputDir.toString());
    builder.add("-t", halideTarget);

    if (compilerInvocationFlags.isPresent()) {
      builder.addAll(compilerInvocationFlags.get());
    }

    builder.add(funcName);
    return builder.build();
  }

  @Override
  public ImmutableMap<String, String> getEnvironmentVariables(Platform platform) {
    return environment;
  }

  @Override
  public String getShortName() {
    return "halide";
  }
}
