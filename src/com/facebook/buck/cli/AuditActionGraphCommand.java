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
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.OutputLabel;
import com.facebook.buck.core.model.actiongraph.ActionGraph;
import com.facebook.buck.core.model.actiongraph.ActionGraphAndBuilder;
import com.facebook.buck.core.model.targetgraph.TargetGraphCreationResult;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.attr.HasMultipleOutputs;
import com.facebook.buck.core.rules.attr.HasRuntimeDeps;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.util.graph.DirectedAcyclicGraph;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.parser.SpeculativeParsing;
import com.facebook.buck.parser.config.ParserConfig;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.util.DirtyPrintStreamDecorator;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.MoreExceptions;
import com.facebook.buck.util.stream.RichStream;
import com.facebook.buck.versions.VersionException;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.SortedSet;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;

/** Command that dumps basic information about the action graph. */
public class AuditActionGraphCommand extends AbstractCommand {

  /** Defines how node parameters are rendered */
  private enum NodeView {
    // Only node names are exported
    NameOnly,
    // Additional node attributes are exported too
    Extended
  }

  private static final Logger LOG = Logger.get(AuditActionGraphCommand.class);
  private static final String LEFT_BRACKET = "[";
  private static final String RIGHT_BRACKET = "]";

  @Option(
      name = "--dot",
      usage = "Print result in graphviz dot format.",
      forbids = {"--dot-compact"})
  private boolean generateDotOutput;

  @Option(
      name = "--dot-compact",
      usage = "Print result in a more compact graphviz dot format.",
      forbids = {"---dot"})
  private boolean generateDotOutputInCompactMode;

  @Option(
      name = "--node-view",
      usage = "Whether to include additional build rule parameters as node attributes")
  private NodeView nodeView = NodeView.NameOnly;

  @Option(name = "--include-runtime-deps", usage = "Include runtime deps in addition to build deps")
  private boolean includeRuntimeDeps;

  @Argument private List<String> targetSpecs = new ArrayList<>();

  @Override
  public ExitCode runWithoutHelp(CommandRunnerParams params) throws Exception {
    try (CommandThreadManager pool =
        new CommandThreadManager("Audit", getConcurrencyLimit(params.getBuckConfig()))) {
      // Create the target graph.
      TargetGraphCreationResult unversionedTargetGraphCreationResult =
          params
              .getParser()
              .buildTargetGraphWithoutTopLevelConfigurationTargets(
                  createParsingContext(params.getCells(), pool.getListeningExecutorService())
                      .withApplyDefaultFlavorsMode(
                          params
                              .getBuckConfig()
                              .getView(ParserConfig.class)
                              .getDefaultFlavorsMode())
                      .withSpeculativeParsing(SpeculativeParsing.ENABLED),
                  parseArgumentsAsTargetNodeSpecs(
                      params.getCells(),
                      params.getClientWorkingDir(),
                      targetSpecs,
                      params.getBuckConfig()),
                  params.getTargetConfiguration());
      TargetGraphCreationResult targetGraphCreationResult =
          params.getBuckConfig().getView(BuildBuckConfig.class).getBuildVersions()
              ? toVersionedTargetGraph(params, unversionedTargetGraphCreationResult)
              : unversionedTargetGraphCreationResult;

      // Create the action graph.
      ActionGraphAndBuilder actionGraphAndBuilder =
          params.getActionGraphProvider().getActionGraph(targetGraphCreationResult);

      // Dump the action graph.
      if (generateDotOutput || generateDotOutputInCompactMode) {
        dumpAsDot(
            actionGraphAndBuilder.getActionGraph(),
            actionGraphAndBuilder.getActionGraphBuilder(),
            includeRuntimeDeps,
            nodeView,
            params.getConsole().getStdOut(),
            generateDotOutputInCompactMode);
      } else {
        dumpAsJson(
            actionGraphAndBuilder.getActionGraph(),
            actionGraphAndBuilder.getActionGraphBuilder(),
            includeRuntimeDeps,
            nodeView,
            params.getConsole().getStdOut());
      }
    } catch (BuildFileParseException | VersionException e) {
      // The exception should be logged with stack trace instead of only emitting the error.
      LOG.info(e, "Caught an exception and treating it as a command error.");
      params
          .getBuckEventBus()
          .post(ConsoleEvent.severe(MoreExceptions.getHumanReadableOrLocalizedMessage(e)));
      return ExitCode.PARSE_ERROR;
    }
    return ExitCode.SUCCESS;
  }

  @Override
  public boolean isReadOnly() {
    return true;
  }

  @Override
  public String getShortDescription() {
    return "Dump basic action graph node and connectivity information.";
  }

  /**
   * Dump basic information about the action graph to the given stream in a simple JSON format.
   *
   * <p>The passed in stream is not closed after this operation.
   */
  private static void dumpAsJson(
      ActionGraph graph,
      ActionGraphBuilder actionGraphBuilder,
      boolean includeRuntimeDeps,
      NodeView nodeView,
      OutputStream out)
      throws IOException {
    try (JsonGenerator json =
        new JsonFactory()
            .createGenerator(out)
            .configure(JsonGenerator.Feature.AUTO_CLOSE_TARGET, false)) {
      json.writeStartArray();
      for (BuildRule node : Sets.newLinkedHashSet(graph.getNodes())) {
        writeJsonObjectForBuildRule(json, node, actionGraphBuilder, includeRuntimeDeps, nodeView);
      }
      json.writeEndArray();
    }
  }

  private static void writeJsonObjectForBuildRule(
      JsonGenerator json,
      BuildRule node,
      ActionGraphBuilder actionGraphBuilder,
      boolean includeRuntimeDeps,
      NodeView nodeView)
      throws IOException {
    json.writeStartObject();
    json.writeStringField("name", node.getFullyQualifiedName());
    json.writeStringField("type", node.getType());
    {
      json.writeArrayFieldStart("buildDeps");
      for (BuildRule dep : node.getBuildDeps()) {
        json.writeString(dep.getFullyQualifiedName());
      }
      json.writeEndArray();
      if (includeRuntimeDeps) {
        json.writeArrayFieldStart("runtimeDeps");
        for (BuildRule dep : getRuntimeDeps(node, actionGraphBuilder)) {
          json.writeString(dep.getFullyQualifiedName());
        }
        json.writeEndArray();
      }
      if (node instanceof HasMultipleOutputs) {
        ImmutableSet<OutputLabel> outputLabels = ((HasMultipleOutputs) node).getOutputLabels();
        json.writeObjectFieldStart("outputPaths");
        SourcePathResolverAdapter resolver = actionGraphBuilder.getSourcePathResolver();
        for (OutputLabel outputLabel : outputLabels) {
          ImmutableSet<SourcePath> sourcePaths =
              getSourcePathsForNamedOutput((HasMultipleOutputs) node, outputLabel);
          if (!sourcePaths.isEmpty()) {
            json.writeObjectField(
                outputLabel.toString(),
                sourcePaths.stream()
                    .map(sourcePath -> resolver.getAbsolutePath(sourcePath))
                    .collect(ImmutableSet.toImmutableSet())
                    .toString());
          }
        }
        json.writeEndObject();
        // For backwards compatibility for existing scripts in the repo, write the first default
        // output to outputPath
        ImmutableSet<SourcePath> sourcePaths =
            getSourcePathsForNamedOutput((HasMultipleOutputs) node, OutputLabel.defaultLabel());
        if (!sourcePaths.isEmpty()) {
          json.writeStringField(
              "outputPath",
              actionGraphBuilder
                  .getSourcePathResolver()
                  .getAbsolutePath(Iterables.get(sourcePaths, 0))
                  .toString());
        }
      } else {
        SourcePath sourcePathToOutput = node.getSourcePathToOutput();
        if (sourcePathToOutput != null) {
          AbsPath outputPath =
              actionGraphBuilder.getSourcePathResolver().getAbsolutePath(sourcePathToOutput);
          json.writeStringField("outputPath", outputPath.toString());
        }
      }
    }
    if (nodeView == NodeView.Extended) {
      ImmutableSortedMap<String, Object> attrs = getNodeAttributes(node);
      for (ImmutableSortedMap.Entry<String, Object> attr : attrs.entrySet()) {
        // add 'buck_' prefix to avoid name collisions and make it compatible with DOT output
        json.writeStringField("buck_" + attr.getKey(), String.valueOf(attr.getValue()));
      }
    }
    json.writeEndObject();
  }

  private static void dumpAsDot(
      ActionGraph graph,
      ActionGraphBuilder actionGraphBuilder,
      boolean includeRuntimeDeps,
      NodeView nodeView,
      DirtyPrintStreamDecorator out,
      boolean compactMode)
      throws IOException {
    DirectedAcyclicGraph.Builder<BuildRule> dag = DirectedAcyclicGraph.serialBuilder();
    graph.getNodes().forEach(dag::addNode);
    graph.getNodes().forEach(from -> from.getBuildDeps().forEach(to -> dag.addEdge(from, to)));
    if (includeRuntimeDeps) {
      graph
          .getNodes()
          .forEach(
              from ->
                  getRuntimeDeps(from, actionGraphBuilder).forEach(to -> dag.addEdge(from, to)));
    }
    Dot.Builder<BuildRule> builder =
        Dot.builder(dag.build(), "action_graph")
            .setNodeToName(BuildRule::getFullyQualifiedName)
            .setNodeToTypeName(BuildRule::getType)
            .setCompactMode(compactMode);
    if (nodeView == NodeView.Extended) {
      builder.setNodeToAttributes(AuditActionGraphCommand::getNodeAttributes);
    }
    builder.build().writeOutput(out);
  }

  private static ImmutableSortedMap<String, Object> getNodeAttributes(BuildRule rule) {
    ImmutableSortedMap.Builder<String, Object> attrs = ImmutableSortedMap.naturalOrder();
    attrs.put("short_name", rule.getBuildTarget().getShortName());
    attrs.put("type", rule.getType());
    if (rule instanceof HasMultipleOutputs) {
      HasMultipleOutputs namedOutputsRule = (HasMultipleOutputs) rule;
      ImmutableSet<OutputLabel> outputLabels = namedOutputsRule.getOutputLabels();
      ImmutableSortedMap.Builder<String, String> builder = ImmutableSortedMap.naturalOrder();
      for (OutputLabel outputLabel : outputLabels) {
        Optional<SourcePath> sourcePath =
            getSourcePathForNamedOutput(namedOutputsRule, outputLabel);
        builder.put(
            outputLabel.toString(),
            sourcePath.isPresent()
                ? LEFT_BRACKET + sourcePath.get().toString() + RIGHT_BRACKET
                : "[]");
      }
      attrs.put("outputs", builder.build());
      // For backwards compatibility for existing scripts in the repo, write the default output to
      // outputPath
      Optional<SourcePath> sourcePath =
          getSourcePathForNamedOutput(namedOutputsRule, OutputLabel.defaultLabel());
      attrs.put("output", sourcePath.isPresent() ? sourcePath.get().toString() : "");
    } else {
      attrs.put(
          "output",
          rule.getSourcePathToOutput() == null ? "" : rule.getSourcePathToOutput().toString());
    }
    attrs.put("cacheable", rule.isCacheable() ? "true" : "false");
    attrs.put("flavored", rule.getBuildTarget().isFlavored() ? "true" : "false");
    return attrs.build();
  }

  private static SortedSet<BuildRule> getRuntimeDeps(
      BuildRule buildRule, ActionGraphBuilder actionGraphBuilder) {
    if (!(buildRule instanceof HasRuntimeDeps)) {
      return ImmutableSortedSet.of();
    }
    return actionGraphBuilder.getAllRules(
        RichStream.from(((HasRuntimeDeps) buildRule).getRuntimeDeps(actionGraphBuilder))
            .toOnceIterable());
  }

  private static Optional<SourcePath> getSourcePathForNamedOutput(
      HasMultipleOutputs node, OutputLabel outputLabel) {
    ImmutableSet<SourcePath> sourcePaths = node.getSourcePathToOutput(outputLabel);
    if (sourcePaths.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(Iterables.getOnlyElement(sourcePaths));
  }

  private static ImmutableSet<SourcePath> getSourcePathsForNamedOutput(
      HasMultipleOutputs node, OutputLabel outputLabel) {
    ImmutableSet<SourcePath> sourcePaths = node.getSourcePathToOutput(outputLabel);
    return sourcePaths;
  }
}
