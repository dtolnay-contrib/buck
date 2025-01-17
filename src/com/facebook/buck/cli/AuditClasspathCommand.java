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

import com.facebook.buck.command.config.BuildBuckConfig;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.model.actiongraph.computation.ActionGraphCache;
import com.facebook.buck.core.model.actiongraph.computation.ActionGraphFactory;
import com.facebook.buck.core.model.actiongraph.computation.ActionGraphProvider;
import com.facebook.buck.core.model.targetgraph.TargetGraphCreationResult;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.jvm.core.HasClasspathEntries;
import com.facebook.buck.parser.SpeculativeParsing;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.util.CommandLineException;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.MoreExceptions;
import com.facebook.buck.util.json.ObjectMappers;
import com.facebook.buck.versions.VersionException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;
import javax.annotation.Nullable;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;

public class AuditClasspathCommand extends AbstractCommand {

  /**
   * Expected usage:
   *
   * <pre>
   * buck audit classpath --dot //java/com/facebook/pkg:pkg > /tmp/graph.dot
   * dot -Tpng /tmp/graph.dot -o /tmp/graph.png
   * </pre>
   */
  @Option(name = "--dot", usage = "Print dependencies as Dot graph")
  private boolean generateDotOutput;

  public boolean shouldGenerateDotOutput() {
    return generateDotOutput;
  }

  @Option(name = "--json", usage = "Output in JSON format")
  @VisibleForTesting
  boolean generateJsonOutput;

  public boolean shouldGenerateJsonOutput() {
    return generateJsonOutput;
  }

  @Option(name = "--show-targets", usage = "Output targets")
  @VisibleForTesting
  boolean showTargets;

  public boolean shouldShowTargets() {
    return showTargets;
  }

  @Argument private List<String> arguments = new ArrayList<>();

  public List<String> getArguments() {
    return arguments;
  }

  @Override
  public ExitCode runWithoutHelp(CommandRunnerParams params) throws Exception {
    // Create a TargetGraph that is composed of the transitive closure of all of the dependent
    // BuildRules for the specified BuildTargetPaths.
    ImmutableSet<UnconfiguredBuildTarget> targets =
        convertArgumentsToUnconfiguredBuildTargets(params, getArguments());

    if (targets.isEmpty()) {
      throw new CommandLineException("must specify at least one build target");
    }

    TargetGraphCreationResult targetGraph;
    try (CommandThreadManager pool =
        new CommandThreadManager("Audit", getConcurrencyLimit(params.getBuckConfig()))) {
      targetGraph =
          params
              .getParser()
              .buildTargetGraphFromUnconfiguredTargets(
                  createParsingContext(params.getCells(), pool.getListeningExecutorService())
                      .withSpeculativeParsing(SpeculativeParsing.ENABLED),
                  targets,
                  params.getTargetConfiguration());
    } catch (BuildFileParseException e) {
      params
          .getBuckEventBus()
          .post(ConsoleEvent.severe(MoreExceptions.getHumanReadableOrLocalizedMessage(e)));
      return ExitCode.PARSE_ERROR;
    }

    try {
      if (shouldGenerateDotOutput()) {
        return printDotOutput(params, targetGraph);
      } else {
        return printClasspath(params, targetGraph);
      }
    } catch (VersionException e) {
      throw new HumanReadableException(e, MoreExceptions.getHumanReadableOrLocalizedMessage(e));
    }
  }

  @Override
  public boolean isReadOnly() {
    return true;
  }

  @VisibleForTesting
  ExitCode printDotOutput(CommandRunnerParams params, TargetGraphCreationResult targetGraph) {
    try {
      Dot.builder(targetGraph.getTargetGraph(), "target_graph")
          .setNodeToName(
              targetNode -> "\"" + targetNode.getBuildTarget().getFullyQualifiedName() + "\"")
          .setNodeToTypeName(targetNode -> targetNode.getRuleType().getName())
          .build()
          .writeOutput(params.getConsole().getStdOut());
    } catch (IOException e) {
      return ExitCode.FATAL_IO;
    }
    return ExitCode.SUCCESS;
  }

  @VisibleForTesting
  ExitCode printClasspath(
      CommandRunnerParams params, TargetGraphCreationResult targetGraphCreationResult)
      throws InterruptedException, IOException, VersionException {

    if (params.getBuckConfig().getView(BuildBuckConfig.class).getBuildVersions()) {
      targetGraphCreationResult = toVersionedTargetGraph(params, targetGraphCreationResult);
    }

    ActionGraphBuilder graphBuilder =
        Objects.requireNonNull(
                new ActionGraphProvider(
                        params.getBuckEventBus(),
                        ActionGraphFactory.create(
                            params.getBuckEventBus(),
                            params.getCells().getCellProvider(),
                            params.getExecutors(),
                            params.getDepsAwareExecutorSupplier(),
                            params.getBuckConfig()),
                        new ActionGraphCache(
                            params
                                .getBuckConfig()
                                .getView(BuildBuckConfig.class)
                                .getMaxActionGraphCacheEntries()),
                        params.getRuleKeyConfiguration(),
                        params.getBuckConfig())
                    .getFreshActionGraph(targetGraphCreationResult))
            .getActionGraphBuilder();

    Multimap<String, String> targetClasspaths = LinkedHashMultimap.create();

    for (BuildTarget target : targetGraphCreationResult.getBuildTargets()) {
      BuildRule rule = Objects.requireNonNull(graphBuilder.requireRule(target));
      HasClasspathEntries hasClasspathEntries = getHasClasspathEntriesFrom(rule);
      if (hasClasspathEntries != null) {
        if (shouldShowTargets()) {
          targetClasspaths.putAll(
              target.getFullyQualifiedName(),
              hasClasspathEntries.getTransitiveClasspathDeps().stream()
                  .map(lib -> lib.getBuildTarget().toString())
                  .collect(ImmutableList.toImmutableList()));
        } else {
          targetClasspaths.putAll(
              target.getFullyQualifiedName(),
              hasClasspathEntries.getTransitiveClasspaths().stream()
                  .map(graphBuilder.getSourcePathResolver()::getAbsolutePath)
                  .map(Objects::toString)
                  .collect(ImmutableList.toImmutableList()));
        }

      } else {
        throw new HumanReadableException(
            rule.getFullyQualifiedName() + " is not a java-based" + " build target");
      }
    }

    if (shouldGenerateJsonOutput()) {
      // Note: using `asMap` here ensures that the keys are sorted
      ObjectMappers.WRITER.writeValue(params.getConsole().getStdOut(), targetClasspaths.asMap());
    } else {
      SortedSet<String> paths = new TreeSet<>(targetClasspaths.values());
      for (String path : paths) {
        params.getConsole().getStdOut().println(path);
      }
    }

    return ExitCode.SUCCESS;
  }

  @Nullable
  private HasClasspathEntries getHasClasspathEntriesFrom(BuildRule rule) {
    if (rule instanceof HasClasspathEntries) {
      return (HasClasspathEntries) rule;
    }
    return null;
  }

  @Override
  public String getShortDescription() {
    return "provides facilities to audit build targets' classpaths";
  }
}
