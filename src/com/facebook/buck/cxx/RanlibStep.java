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

package com.facebook.buck.cxx;

import com.facebook.buck.core.build.execution.context.IsolatedExecutionContext;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.isolatedsteps.shell.IsolatedShellStep;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;

class RanlibStep extends IsolatedShellStep {

  private final ImmutableMap<String, String> ranlibEnv;
  private final ImmutableList<String> ranlibPrefix;
  private final ImmutableList<String> ranlibFlags;
  private final Path output;

  public RanlibStep(
      ProjectFilesystem filesystem,
      ImmutableMap<String, String> ranlibEnv,
      ImmutableList<String> ranlibPrefix,
      ImmutableList<String> ranlibFlags,
      Path output,
      RelPath cellPath,
      boolean withDownwardApi) {
    super(filesystem.getRootPath(), cellPath, withDownwardApi);
    Preconditions.checkArgument(!output.isAbsolute());
    this.ranlibEnv = ranlibEnv;
    this.ranlibPrefix = ranlibPrefix;
    this.ranlibFlags = ranlibFlags;
    this.output = output;
  }

  @Override
  public ImmutableMap<String, String> getEnvironmentVariables(Platform platform) {
    return ranlibEnv;
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(IsolatedExecutionContext context) {
    return ImmutableList.<String>builder()
        .addAll(ranlibPrefix)
        .addAll(ranlibFlags)
        .add(output.toString())
        .build();
  }

  @Override
  public String getShortName() {
    return "ranlib";
  }
}
