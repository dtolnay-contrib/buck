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

import com.facebook.buck.core.build.execution.context.IsolatedExecutionContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.step.isolatedsteps.shell.IsolatedShellStep;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;

public class GoTestCoverStep extends IsolatedShellStep {

  enum Mode {
    SET("set"),
    COUNT("count"),
    ATOMIC("atomic"),
    NONE("");

    private final String coverMode;

    Mode(String coverMode) {
      this.coverMode = coverMode;
    }

    String getMode() {
      return coverMode;
    }
  }

  private final ImmutableMap<String, String> environment;
  private final ImmutableList<String> generatorCommandPrefix;
  private final Mode coverageMode;
  private final Path sourceFile;
  private final Path targetFile;

  public GoTestCoverStep(
      AbsPath workingDirectory,
      Path sourceFile,
      Path targetFile,
      ImmutableMap<String, String> environment,
      ImmutableList<String> generatorCommandPrefix,
      Mode coverageMode,
      RelPath cellPath,
      boolean withDownwardApi) {
    super(workingDirectory, cellPath, withDownwardApi);
    this.environment = environment;
    this.generatorCommandPrefix = generatorCommandPrefix;
    this.coverageMode = coverageMode;
    this.sourceFile = sourceFile;
    this.targetFile = targetFile;
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(IsolatedExecutionContext context) {
    ImmutableList.Builder<String> command =
        ImmutableList.<String>builder()
            .addAll(generatorCommandPrefix)
            .add("-mode", coverageMode.getMode())
            .add("-var", GoTestCoverSource.getVarName(targetFile))
            .add("-o", targetFile.toString())
            .add(sourceFile.toString());
    return command.build();
  }

  @Override
  public ImmutableMap<String, String> getEnvironmentVariables(Platform platform) {
    return environment;
  }

  @Override
  public String getShortName() {
    return "go test cover gen";
  }
}
