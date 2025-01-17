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
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;

/** Uses the strip tool to remove the contents (i.e., compiled code) from a dylib. */
public class MachoScrubContentSectionsStep extends IsolatedShellStep {

  private final ImmutableList<String> stripToolPrefix;

  private final ProjectFilesystem inputFilesystem;
  private final RelPath inputDylib;

  private final ProjectFilesystem outputFilesystem;
  private final Path outputDylib;

  public MachoScrubContentSectionsStep(
      ImmutableList<String> stripToolPrefix,
      ProjectFilesystem inputFilesystem,
      RelPath inputDylib,
      ProjectFilesystem outputFilesystem,
      Path outputDylib,
      RelPath cellPath,
      boolean withDownwardApi) {
    super(outputFilesystem.getRootPath(), cellPath, withDownwardApi);
    this.stripToolPrefix = stripToolPrefix;
    this.inputFilesystem = inputFilesystem;
    this.inputDylib = inputDylib;
    this.outputFilesystem = outputFilesystem;
    this.outputDylib = outputDylib;
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(IsolatedExecutionContext context) {
    ImmutableList.Builder<String> args = ImmutableList.builder();
    args.addAll(stripToolPrefix);
    args.add("-x");
    args.add("-c");
    args.add(inputFilesystem.resolve(inputDylib).toString());

    args.add("-o");
    args.add(outputFilesystem.resolve(outputDylib).toString());

    return args.build();
  }

  @Override
  public final String getShortName() {
    return "apple_dylib_stub_create";
  }
}
