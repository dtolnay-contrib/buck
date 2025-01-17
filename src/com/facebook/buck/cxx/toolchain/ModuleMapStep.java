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

import com.facebook.buck.apple.clang.ModuleMap;
import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.google.common.base.Objects;
import java.io.IOException;
import java.nio.file.Path;

class ModuleMapStep implements Step {

  private static final Logger LOG = Logger.get(ModuleMapStep.class);

  private final ProjectFilesystem filesystem;
  private final Path output;
  private final ModuleMap moduleMap;

  public ModuleMapStep(ProjectFilesystem filesystem, Path output, ModuleMap moduleMap) {
    this.filesystem = filesystem;
    this.output = output;
    this.moduleMap = moduleMap;
  }

  @Override
  public String getDescription(StepExecutionContext context) {
    return "modulemap @ " + output;
  }

  @Override
  public String getShortName() {
    return "module_map";
  }

  @Override
  public StepExecutionResult execute(StepExecutionContext context) throws IOException {
    LOG.debug("Writing modulemap to %s", output);
    if (!filesystem.exists(output)) {
      filesystem.createParentDirs(output);
    }

    try {
      filesystem.writeContentsToPath(moduleMap.render(), output);
      return StepExecutionResults.SUCCESS;
    } catch (IOException e) {
      LOG.debug("Error writing modulemap to %s:\n%s", output, e.getMessage());
      return StepExecutionResults.ERROR;
    }
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof ModuleMapStep)) {
      return false;
    }
    ModuleMapStep that = (ModuleMapStep) obj;
    return Objects.equal(this.output, that.output) && Objects.equal(this.moduleMap, that.moduleMap);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(output, moduleMap);
  }

  @Override
  public String toString() {
    return String.format("modulemap %s %s", output.toString(), moduleMap.render());
  }
}
