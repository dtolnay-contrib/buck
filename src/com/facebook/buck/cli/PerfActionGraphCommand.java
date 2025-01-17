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

import com.facebook.buck.cli.PerfActionGraphCommand.PreparedState;
import com.facebook.buck.core.exceptions.BuckUncheckedExecutionException;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetGraphCreationResult;
import com.facebook.buck.util.CommandLineException;
import com.google.common.collect.ImmutableSet;
import java.util.ArrayList;
import java.util.List;
import org.kohsuke.args4j.Argument;

/** Tests performance of creating the action graph. */
public class PerfActionGraphCommand extends AbstractPerfCommand<PreparedState> {

  @Argument private List<String> arguments = new ArrayList<>();

  @Override
  PreparedState prepareTest(CommandRunnerParams params) {
    try {
      ImmutableSet<UnconfiguredBuildTarget> targets =
          convertArgumentsToUnconfiguredBuildTargets(params, arguments);

      if (targets.isEmpty()) {
        throw new CommandLineException("must specify at least one build target");
      }

      TargetGraphCreationResult targetGraph =
          getTargetGraphFromUnconfiguredTargets(params, targets);

      return new PreparedState(targetGraph);
    } catch (Exception e) {
      throw new BuckUncheckedExecutionException(e, "When creating the target graph.");
    }
  }

  /** The state prepared for us to compute keys. */
  static class PreparedState {
    private final TargetGraphCreationResult targetGraph;

    public PreparedState(TargetGraphCreationResult targetGraph) {
      this.targetGraph = targetGraph;
    }
  }

  @Override
  protected String getComputationName() {
    return "action-graph creation";
  }

  @Override
  void runPerfTest(CommandRunnerParams params, PreparedState state) {
    params.getActionGraphProvider().getFreshActionGraph(state.targetGraph);
  }

  @Override
  public String getShortDescription() {
    return "tests performance of creating buck's action graph";
  }
}
