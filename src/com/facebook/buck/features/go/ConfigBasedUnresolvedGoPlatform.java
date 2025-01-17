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

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.tool.impl.CommandTool;
import com.facebook.buck.core.toolchain.tool.impl.HashedFileTool;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.UnresolvedCxxPlatform;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.base.CharMatcher;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumSet;
import java.util.NoSuchElementException;
import java.util.Optional;

/** An {@link UnresolvedGoPlatform} based on .buckconfig values. */
@BuckStyleValue
public abstract class ConfigBasedUnresolvedGoPlatform implements UnresolvedGoPlatform {

  private static final Path DEFAULT_GO_TOOL = Paths.get("go");

  abstract String getSection();

  @Override
  public abstract Flavor getFlavor();

  abstract BuckConfig getBuckConfig();

  abstract UnresolvedCxxPlatform getUnresolvedCxxPlatform();

  abstract ProcessExecutor getProcessExecutor();

  abstract ExecutableFinder getExecutableFinder();

  @Override
  public GoPlatform resolve(BuildRuleResolver resolver, TargetConfiguration targetConfiguration) {

    String section = getSection();
    Path goRoot = getGoRoot(getSection());
    CxxPlatform cxxPlatform = getUnresolvedCxxPlatform().resolve(resolver, targetConfiguration);
    return ImmutableGoPlatform.builder()
        .setFlavor(getFlavor())
        .setGoOs(getOs(section))
        .setGoArch(getArch(section))
        .setGoRoot(goRoot)
        .setCompiler(getGoTool(section, goRoot, "compiler", "compile", "compiler_flags"))
        .setAssembler(getGoTool(section, goRoot, "assembler", "asm", "asm_flags"))
        .setAssemblerIncludeDirs(ImmutableList.of(goRoot.resolve("pkg").resolve("include")))
        .setCGo(getGoTool(section, goRoot, "cgo", "cgo", "cgo_compiler_flags"))
        .setPacker(getGoTool(section, goRoot, "packer", "pack", ""))
        .setLinker(getGoTool(section, goRoot, "linker", "link", "linker_flags"))
        .setCover(getGoTool(section, goRoot, "cover", "cover", ""))
        .setTestMainGen(getGoToolFromSection(section, goRoot, "testmaingen", "testmaingen", ""))
        .setCxxPlatform(cxxPlatform)
        .setExternalLinkerFlags(getFlags(section, "external_linker_flags"))
        .build();
  }

  @Override
  public Iterable<BuildTarget> getParseTimeDeps(TargetConfiguration targetConfiguration) {
    return getUnresolvedCxxPlatform().getParseTimeDeps(targetConfiguration);
  }

  private GoOs getOs(String section) {
    return getBuckConfig()
        .getValue(section, "os")
        .map(
            os -> {
              try {
                return GoOs.fromString(os);
              } catch (IllegalArgumentException e) {
                throw new HumanReadableException("%s.os: unknown GOOS '%s'", section, os);
              }
            })
        .orElse(GoPlatformFactory.getDefaultOs());
  }

  private GoArch getArch(String section) {
    return getBuckConfig()
        .getValue(section, "arch")
        .map(
            arch -> {
              try {
                return GoArch.fromString(arch);
              } catch (NoSuchElementException e) {
                throw new HumanReadableException("%s.arch unknown GOARCH '%s'", section, arch);
              }
            })
        .orElse(GoPlatformFactory.getDefaultArch());
  }

  private Path getToolDir(String section) {
    return getBuckConfig()
        .getPath(section, "tool_dir")
        .orElseGet(() -> Paths.get(getGoEnvFromTool(section, "GOTOOLDIR")));
  }

  private Tool getGoTool(
      String section, Path goRoot, String configName, String toolName, String extraFlagsConfigKey) {

    CommandTool.Builder builder =
        new CommandTool.Builder(
            new HashedFileTool(
                () ->
                    getBuckConfig()
                        .getPathSourcePath(
                            getBuckConfig()
                                .getPath(section, configName)
                                .orElseGet(() -> getToolDir(section).resolve(toolName)))));
    if (!extraFlagsConfigKey.isEmpty()) {
      for (String arg : getFlags(section, extraFlagsConfigKey)) {
        builder.addArg(arg);
      }
    }
    builder.addEnv("GOROOT", goRoot.toString());
    return builder.build();
  }

  private Optional<Tool> getGoToolFromSection(
      String section, Path goRoot, String configName, String toolName, String extraFlagsConfigKey) {

    Optional<Path> goTool = getBuckConfig().getPath(section, configName);
    if (!goTool.isPresent()) {
      return Optional.empty();
    }

    return Optional.of(getGoTool(section, goRoot, configName, toolName, extraFlagsConfigKey));
  }

  private ImmutableList<String> getFlags(String section, String key) {
    return getBuckConfig().getListWithoutComments(section, key, ' ');
  }

  private Optional<Path> getConfiguredGoRoot(String section) {
    return getBuckConfig().getPath(section, "root");
  }

  private Path getGoRoot(String section) {
    return getConfiguredGoRoot(section)
        .orElseGet(() -> Paths.get(getGoEnvFromTool(section, "GOROOT")));
  }

  private String getGoEnvFromTool(String section, String env) {
    Path goTool = getGoToolPath(section);
    Optional<ImmutableMap<String, String>> goRootEnv =
        getConfiguredGoRoot(section).map(input -> ImmutableMap.of("GOROOT", input.toString()));
    try {
      ProcessExecutor.Result goToolResult =
          getProcessExecutor()
              .launchAndExecute(
                  ProcessExecutorParams.builder()
                      .addCommand(goTool.toString(), "env", env)
                      .setEnvironment(goRootEnv)
                      .build(),
                  EnumSet.of(ProcessExecutor.Option.EXPECTING_STD_OUT),
                  /* stdin */ Optional.empty(),
                  /* timeOutMs */ Optional.empty(),
                  /* timeoutHandler */ Optional.empty());
      if (goToolResult.getExitCode() == 0) {
        return CharMatcher.whitespace().trimFrom(goToolResult.getStdout().get());
      } else {
        throw new HumanReadableException(goToolResult.getStderr().get());
      }
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    } catch (IOException e) {
      throw new HumanReadableException(
          e, "Could not run \"%s env %s\": %s", goTool, env, e.getMessage());
    }
  }

  private Path getGoToolPath(String section) {
    Optional<Path> goTool = getBuckConfig().getPath(section, "tool");
    if (goTool.isPresent()) {
      return goTool.get();
    }

    // Try resolving it via the go root config var. We can't use goRootSupplier here since that
    // would create a recursion.
    Optional<Path> goRoot = getConfiguredGoRoot(section);
    if (goRoot.isPresent()) {
      return goRoot.get().resolve("bin").resolve("go");
    }

    return getExecutableFinder().getExecutable(DEFAULT_GO_TOOL, getBuckConfig().getEnvironment());
  }
}
