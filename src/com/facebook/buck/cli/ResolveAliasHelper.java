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

package com.facebook.buck.cli;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.parser.PerBuildState;
import com.facebook.buck.parser.config.ParserConfig;
import com.facebook.buck.parser.exceptions.MissingBuildFileException;
import com.facebook.buck.support.cli.config.AliasConfig;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

/** Helper class with functionality to resolve alias in `targets` command. */
public class ResolveAliasHelper {
  private ResolveAliasHelper() {}

  /**
   * Assumes each argument passed to this command is an alias defined in .buckconfig, or a fully
   * qualified (non-alias) target to be verified by checking the build files. Prints the build
   * target that each alias maps to on its own line to standard out.
   */
  public static void resolveAlias(
      CommandRunnerParams params, PerBuildState parserState, List<String> aliases) {

    List<String> resolvedAliases = new ArrayList<>();
    for (String alias : aliases) {
      ImmutableSet<String> buildTargets;
      if (alias.contains("//")) {
        String buildTarget = validateBuildTargetForFullyQualifiedTarget(params, alias, parserState);
        if (buildTarget == null) {
          throw new HumanReadableException("%s is not a valid target.", alias);
        }
        buildTargets = ImmutableSet.of(buildTarget);
      } else {
        buildTargets = getBuildTargetForAlias(params.getBuckConfig(), alias);
        if (buildTargets.isEmpty()) {
          throw new HumanReadableException("%s is not an alias.", alias);
        }
      }
      resolvedAliases.addAll(buildTargets);
    }

    for (String resolvedAlias : resolvedAliases) {
      params.getConsole().getStdOut().println(resolvedAlias);
    }
  }

  /** Verify that the given target is a valid full-qualified (non-alias) target. */
  @Nullable
  static String validateBuildTargetForFullyQualifiedTarget(
      CommandRunnerParams params, String target, PerBuildState parserState) {

    UnconfiguredBuildTarget buildTarget =
        params.getBuckConfig().getUnconfiguredBuildTargetForFullyQualifiedTarget(target);

    Cell owningCell = params.getCells().getCell(buildTarget.getCell());
    AbsPath buildFile;
    try {
      buildFile =
          owningCell
              .getBuckConfigView(ParserConfig.class)
              .getAbsolutePathToBuildFile(
                  owningCell, buildTarget, DependencyStack.top(buildTarget));
    } catch (MissingBuildFileException e) {
      throw new HumanReadableException(e);
    }

    // Get all valid targets in our target directory by reading the build file.
    // TODO(nga): fetch unconfigured nodes
    ImmutableList<TargetNode<?>> targetNodes =
        params
            .getParser()
            .getAllTargetNodesWithTargetCompatibilityFiltering(
                parserState, owningCell, buildFile, params.getTargetConfiguration());

    // Check that the given target is a valid target.
    for (TargetNode<?> candidate : targetNodes) {
      if (candidate.getBuildTarget().getUnconfiguredBuildTarget().equals(buildTarget)) {
        return buildTarget.getFullyQualifiedName();
      }
    }
    return null;
  }

  /** @return the name of the build target identified by the specified alias or an empty set. */
  private static ImmutableSet<String> getBuildTargetForAlias(BuckConfig buckConfig, String alias) {
    return AliasConfig.from(buckConfig).getBuildTargetForAliasAsString(alias);
  }
}
