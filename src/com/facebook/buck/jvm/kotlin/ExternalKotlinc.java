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

package com.facebook.buck.jvm.kotlin;

import static com.google.common.collect.Iterables.transform;

import com.facebook.buck.core.build.execution.context.IsolatedExecutionContext;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.jvm.core.BuildTargetValue;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.MoreSuppliers;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutor.Result;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Supplier;

/** kotlinc implemented as a separate binary. */
public class ExternalKotlinc implements Kotlinc, AddsToRuleKey {

  private static final KotlincVersion DEFAULT_VERSION =
      ImmutableKotlincVersion.ofImpl("unknown version");

  private final Path pathToKotlinc;
  private final Supplier<KotlincVersion> version;
  @AddToRuleKey private final String kotlinCompilerVersion;

  public ExternalKotlinc(Path pathToKotlinc) {
    this.pathToKotlinc = pathToKotlinc;

    this.version =
        MoreSuppliers.memoize(
            () -> {
              ProcessExecutorParams params =
                  ProcessExecutorParams.builder()
                      .setCommand(ImmutableList.of(pathToKotlinc.toString(), "-version"))
                      .build();
              Result result;
              try {
                result = createProcessExecutor().launchAndExecute(params);
              } catch (InterruptedException | IOException e) {
                throw new RuntimeException(e);
              }
              Optional<String> stderr = result.getStderr();
              String output = stderr.orElse("").trim();
              if (Strings.isNullOrEmpty(output)) {
                return DEFAULT_VERSION;
              } else {
                return ImmutableKotlincVersion.ofImpl(output);
              }
            });
    this.kotlinCompilerVersion = getKotlinCompilerVersion();
  }

  /** Returns the Kotlin version, or the path if version is unknown */
  private String getKotlinCompilerVersion() {
    if (DEFAULT_VERSION.equals(getVersion())) {
      // What we really want to do here is use a VersionedTool, however, this will suffice for now.
      return getShortName();
    } else {
      return getVersion().toString();
    }
  }

  @Override
  public ImmutableList<String> getCommandPrefix(SourcePathResolverAdapter resolver) {
    return ImmutableList.of(pathToKotlinc.toString());
  }

  @Override
  public KotlincVersion getVersion() {
    return version.get();
  }

  @Override
  public int buildWithClasspath(
      IsolatedExecutionContext context,
      BuildTargetValue invokingRule,
      ImmutableList<String> options,
      ImmutableSortedSet<AbsPath> kotlinHomeLibraries,
      ImmutableSortedSet<RelPath> kotlinSourceFilePaths,
      Path pathToSrcsList,
      Optional<Path> workingDirectory,
      AbsPath ruleCellRoot,
      boolean withDownwardApi)
      throws InterruptedException {

    ImmutableList<Path> expandedSources;
    try {
      expandedSources =
          getExpandedSourcePaths(ruleCellRoot, kotlinSourceFilePaths, workingDirectory);
    } catch (Throwable throwable) {
      throwable.printStackTrace();
      throw new HumanReadableException(
          "Unable to expand sources for %s into %s",
          invokingRule.getFullyQualifiedName(), workingDirectory);
    }

    ImmutableList<String> command =
        ImmutableList.<String>builder()
            .add(pathToKotlinc.toString())
            .addAll(options)
            .addAll(transform(expandedSources, path -> ruleCellRoot.resolve(path).toString()))
            .build();

    // Run the command
    int exitCode = -1;
    try {
      ProcessExecutorParams params =
          ProcessExecutorParams.builder()
              .setCommand(command)
              .setEnvironment(context.getEnvironment())
              .setDirectory(ruleCellRoot.getPath())
              .build();
      ProcessExecutor processExecutor = context.getProcessExecutor();
      if (withDownwardApi) {
        processExecutor = context.getDownwardApiProcessExecutor(processExecutor);
      }

      ProcessExecutor.Result result = processExecutor.launchAndExecute(params);
      exitCode = result.getExitCode();
    } catch (IOException e) {
      e.printStackTrace(context.getStdErr());
      return exitCode;
    }

    return exitCode;
  }

  @VisibleForTesting
  ProcessExecutor createProcessExecutor() {
    return new DefaultProcessExecutor(Console.createNullConsole());
  }

  @Override
  public String getDescription(
      ImmutableList<String> options,
      ImmutableSortedSet<RelPath> kotlinSourceFilePaths,
      Path pathToSrcsList) {
    StringBuilder builder = new StringBuilder(getShortName());
    builder.append(" ");
    Joiner.on(" ").appendTo(builder, options);
    builder.append(" ");
    builder.append("@").append(pathToSrcsList);

    return builder.toString();
  }

  @Override
  public String getShortName() {
    return pathToKotlinc.toString();
  }

  @Override
  public ImmutableMap<String, String> getEnvironment(SourcePathResolverAdapter resolver) {
    return ImmutableMap.of();
  }
}
