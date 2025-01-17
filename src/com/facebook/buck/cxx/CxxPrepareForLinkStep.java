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

import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.FileListableLinkerInputArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.Escaper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Prepares argfile for the CxxLinkStep, so all arguments to the linker will be stored in a single
 * file. CxxLinkStep then would pass it to the linker via @path/to/the.argsfile command. This allows
 * us to break the constraints that command line sets for the maximum length of the commands.
 */
public class CxxPrepareForLinkStep {

  private static final Logger LOG = Logger.get(CxxPrepareForLinkStep.class);

  public static ImmutableList<Step> create(
      Path argFilePath,
      Path fileListPath,
      Iterable<Arg> linkerArgsToSupportFileList,
      Path output,
      ImmutableList<Arg> args,
      Linker linker,
      CanonicalCellName currentCellName,
      Path currentCellPath,
      SourcePathResolverAdapter resolver,
      ImmutableMap<Path, Path> cellRootMap,
      ImmutableList<Arg> focusedDebuggingArgs) {

    ImmutableList<Arg> allArgs =
        new ImmutableList.Builder<Arg>()
            .addAll(StringArg.from(linker.outputArgs(output.toString())))
            .addAll(args)
            .addAll(linkerArgsToSupportFileList)
            .addAll(linker.pathNormalizationArgs(cellRootMap))
            .addAll(focusedDebuggingArgs)
            .build();

    boolean hasLinkArgsToSupportFileList = linkerArgsToSupportFileList.iterator().hasNext();

    if (LOG.isVerboseEnabled()) {
      LOG.verbose(
          "Link command (pwd=%s): %s %s",
          currentCellPath.toString(),
          String.join("", linker.getCommandPrefix(resolver)),
          String.join(
              " ",
              CxxWriteArgsToFileStep.stringify(
                  allArgs, currentCellName, resolver, linker.getUseUnixPathSeparator())));
    }

    CxxWriteArgsToFileStep createArgFileStep =
        CxxWriteArgsToFileStep.create(
            argFilePath,
            hasLinkArgsToSupportFileList
                ? allArgs.stream()
                    .filter(input -> !(input instanceof FileListableLinkerInputArg))
                    .collect(ImmutableList.toImmutableList())
                : allArgs,
            Optional.of(Escaper.SHELL_ESCAPER),
            currentCellName,
            resolver,
            linker.getUseUnixPathSeparator());

    if (!hasLinkArgsToSupportFileList) {
      LOG.verbose("linkerArgsToSupportFileList is empty, filelist feature is not supported");
      return ImmutableList.of(createArgFileStep);
    }

    CxxWriteArgsToFileStep createFileListStep =
        CxxWriteArgsToFileStep.create(
            fileListPath,
            allArgs.stream()
                .filter(input -> input instanceof FileListableLinkerInputArg)
                .collect(ImmutableList.toImmutableList()),
            Optional.empty(),
            currentCellName,
            resolver,
            linker.getUseUnixPathSeparator());

    return ImmutableList.of(createArgFileStep, createFileListStep);
  }

  private CxxPrepareForLinkStep() {}
}
