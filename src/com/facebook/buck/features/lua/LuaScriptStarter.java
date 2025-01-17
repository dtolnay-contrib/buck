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

package com.facebook.buck.features.lua;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.impl.WriteStringTemplateRule;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.file.WriteFile;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.util.Escaper;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.Resources;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;

/** {@link Starter} implementation which builds a starter as a Lua script. */
@BuckStyleValue
abstract class LuaScriptStarter implements Starter {

  private static final String STARTER = "starter.lua.in";

  public static LuaScriptStarter of(
      ProjectFilesystem projectFilesystem,
      BuildTarget baseTarget,
      BuildRuleParams baseParams,
      ActionGraphBuilder actionGraphBuilder,
      SourcePathResolverAdapter pathResolver,
      LuaPlatform luaPlatform,
      BuildTarget target,
      Path output,
      String mainModule,
      Optional<? extends Path> relativeModulesDir,
      Optional<? extends Path> relativePythonModulesDir) {
    return ImmutableLuaScriptStarter.ofImpl(
        projectFilesystem,
        baseTarget,
        baseParams,
        actionGraphBuilder,
        pathResolver,
        luaPlatform,
        target,
        output,
        mainModule,
        relativeModulesDir,
        relativePythonModulesDir);
  }

  abstract ProjectFilesystem getProjectFilesystem();

  abstract BuildTarget getBaseTarget();

  abstract BuildRuleParams getBaseParams();

  abstract ActionGraphBuilder getActionGraphBuilder();

  abstract SourcePathResolverAdapter getPathResolver();

  abstract LuaPlatform getLuaPlatform();

  abstract BuildTarget getTarget();

  abstract Path getOutput();

  abstract String getMainModule();

  abstract Optional<Path> getRelativeModulesDir();

  abstract Optional<Path> getRelativePythonModulesDir();

  private String getPureStarterTemplate() {
    try {
      return Resources.toString(
          Resources.getResource(LuaScriptStarter.class, STARTER), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public SourcePath build() {
    BuildTarget templateTarget =
        getBaseTarget().withAppendedFlavors(InternalFlavor.of("starter-template"));
    WriteFile templateRule =
        getActionGraphBuilder()
            .addToIndex(
                new WriteFile(
                    templateTarget,
                    getProjectFilesystem(),
                    getPureStarterTemplate(),
                    BuildTargetPaths.getGenPath(
                        getProjectFilesystem().getBuckPaths(), templateTarget, "%s/starter.lua.in"),
                    /* executable */ false));

    Tool lua =
        getLuaPlatform()
            .getLua()
            .resolve(getActionGraphBuilder(), getBaseTarget().getTargetConfiguration());
    WriteStringTemplateRule writeStringTemplateRule =
        getActionGraphBuilder()
            .addToIndex(
                WriteStringTemplateRule.from(
                    getProjectFilesystem(),
                    getBaseParams(),
                    getActionGraphBuilder(),
                    getTarget(),
                    getOutput(),
                    templateRule.getSourcePathToOutput(),
                    ImmutableMap.of(
                        "SHEBANG",
                        lua.getCommandPrefix(getPathResolver()).get(0),
                        "MAIN_MODULE",
                        Escaper.escapeAsPythonString(getMainModule()),
                        "MODULES_DIR",
                        getRelativeModulesDir().isPresent()
                            ? Escaper.escapeAsPythonString(getRelativeModulesDir().get().toString())
                            : "nil",
                        "PY_MODULES_DIR",
                        getRelativePythonModulesDir().isPresent()
                            ? Escaper.escapeAsPythonString(
                                getRelativePythonModulesDir().get().toString())
                            : "nil",
                        "EXT_SUFFIX",
                        Escaper.escapeAsPythonString(
                            getLuaPlatform().getCxxPlatform().getSharedLibraryExtension())),
                    /* executable */ true));

    return writeStringTemplateRule.getSourcePathToOutput();
  }
}
