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

import com.facebook.buck.android.toolchain.ndk.AndroidNdk;
import com.facebook.buck.core.build.execution.context.IsolatedExecutionContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.isolatedsteps.shell.IsolatedShellStep;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.concurrent.ConcurrencyLimit;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import java.io.File;
import java.nio.file.Path;

public class NdkBuildStep extends IsolatedShellStep {

  private final ProjectFilesystem filesystem;
  private final AndroidNdk androidNdk;
  private final Path root;
  private final Path makefile;
  private final Path buildArtifactsDirectory;
  private final Path binDirectory;
  private final ImmutableList<String> flags;
  private final ConcurrencyLimit concurrencyLimit;

  public NdkBuildStep(
      ProjectFilesystem filesystem,
      RelPath cellPath,
      AndroidNdk androidNdk,
      Path root,
      Path makefile,
      Path buildArtifactsDirectory,
      Path binDirectory,
      Iterable<String> flags,
      ConcurrencyLimit concurrencyLimit,
      boolean withDownwardApi) {
    super(filesystem.getRootPath(), cellPath, withDownwardApi);

    this.filesystem = filesystem;
    this.androidNdk = androidNdk;
    this.root = root;
    this.makefile = makefile;
    this.buildArtifactsDirectory = buildArtifactsDirectory;
    this.binDirectory = binDirectory;
    this.flags = ImmutableList.copyOf(flags);
    this.concurrencyLimit = concurrencyLimit;
  }

  @Override
  public String getShortName() {
    return "ndk_build";
  }

  @Override
  public boolean shouldPrintStderr(Verbosity verbosity) {
    return verbosity.shouldPrintStandardInformation();
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(IsolatedExecutionContext context) {
    ImmutableList.Builder<String> builder = ImmutableList.builder();
    builder.add(
        androidNdk.getNdkBuildExecutable().toAbsolutePath().toString(),
        "-j",
        // TODO(msemko): Use -j 1 here. Rules shouldn't have their own concurrency and ndk_library
        // isn't really used and is (long) deprecated anyway.
        Integer.toString(concurrencyLimit.threadLimit),
        "-C",
        this.root.toString());

    builder.addAll(flags);

    // We want relative, not absolute, paths in the debug-info for binaries we build using
    // ndk_library.  Absolute paths are machine-specific, but relative ones should be the
    // same everywhere.

    RelPath relativePathToProject =
        AbsPath.of(filesystem.resolve(root)).relativize(filesystem.getRootPath());
    builder.add(
        "APP_PROJECT_PATH=" + filesystem.resolve(buildArtifactsDirectory) + File.separatorChar,
        "APP_BUILD_SCRIPT=" + filesystem.resolve(makefile),
        "NDK_OUT=" + filesystem.resolve(buildArtifactsDirectory) + File.separatorChar,
        "NDK_LIBS_OUT=" + filesystem.resolve(binDirectory),
        "BUCK_PROJECT_DIR=" + relativePathToProject);

    // Suppress the custom build step messages (e.g. "Compile++ ...").
    if (Platform.detect() == Platform.WINDOWS) {
      builder.add("host-echo-build-step=@REM");
    } else {
      builder.add("host-echo-build-step=@#");
    }

    // If we're running verbosely, force all the subcommands from the ndk build to be printed out.
    if (context.getVerbosity().shouldPrintCommand()) {
      builder.add("V=1");
      // Otherwise, suppress everything, including the "make: entering directory..." messages.
    } else {
      builder.add("--silent");
    }

    return builder.build();
  }
}
