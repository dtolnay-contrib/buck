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

package com.facebook.buck.zip;

import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.util.zip.ZipScrubber;
import com.google.common.base.Preconditions;
import java.io.IOException;
import java.nio.file.Path;
import org.immutables.value.Value;

@BuckStyleValue
public abstract class ZipScrubberStep implements Step {

  public abstract Path getZipAbsolutePath();

  @Value.Check
  protected void check() {
    Preconditions.checkArgument(
        getZipAbsolutePath().isAbsolute(), "ZipScrubberStep must take an absolute path");
  }

  @Override
  public String getShortName() {
    return "zip-scrub";
  }

  @Override
  public String getDescription(StepExecutionContext context) {
    return "zip-scrub " + getZipAbsolutePath();
  }

  @Override
  public StepExecutionResult execute(StepExecutionContext context) throws IOException {
    ZipScrubber.scrubZip(getZipAbsolutePath());
    return StepExecutionResults.SUCCESS;
  }

  public static ZipScrubberStep of(Path zipAbsolutePath) {
    return ImmutableZipScrubberStep.ofImpl(zipAbsolutePath);
  }

  public static ZipScrubberStep of(AbsPath zipAbsolutePath) {
    return of(zipAbsolutePath.getPath());
  }
}
