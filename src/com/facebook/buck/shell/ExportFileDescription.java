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

package com.facebook.buck.shell;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.description.attr.ImplicitInputsInferringDescription;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.ForwardRelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.UnflavoredBuildTarget;
import com.facebook.buck.core.rules.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.param.ParamName;
import com.facebook.buck.util.collect.TwoArraysImmutableHashMap;
import com.google.common.collect.ImmutableSet;
import java.util.Optional;

public class ExportFileDescription
    implements DescriptionWithTargetGraph<ExportFileDescriptionArg>,
        ImplicitInputsInferringDescription {

  @Override
  public Class<ExportFileDescriptionArg> getConstructorArgType() {
    return ExportFileDescriptionArg.class;
  }

  private final ExportFileDirectoryAction directoryAction;

  public ExportFileDescription(BuckConfig buckConfig) {
    this.directoryAction = getDirectoryActionFromConfig(buckConfig);
  }

  @Override
  public ExportFile createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      ExportFileDescriptionArg args) {
    Mode mode = args.getMode().orElse(Mode.COPY);

    String name;
    if (args.getOut().isPresent()) {
      if (mode == ExportFileDescription.Mode.REFERENCE) {
        throw new HumanReadableException(
            "%s: must not set `out` for `export_file` when using `REFERENCE` mode", buildTarget);
      }
      name = args.getOut().get();
    } else {
      name = buildTarget.getShortNameAndFlavorPostfix();
    }

    SourcePath src;
    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();
    if (args.getSrc().isPresent()) {
      if (mode == ExportFileDescription.Mode.REFERENCE
          && !context
              .getActionGraphBuilder()
              .getSourcePathResolver()
              .getFilesystem(args.getSrc().get())
              .equals(projectFilesystem)) {
        throw new HumanReadableException(
            "%s: must use `COPY` mode for `export_file` when source (%s) uses a different cell",
            buildTarget, args.getSrc().get());
      }
      src = args.getSrc().get();
    } else {
      src =
          PathSourcePath.of(
              projectFilesystem,
              buildTarget
                  .getCellRelativeBasePath()
                  .getPath()
                  .toPath(projectFilesystem.getFileSystem())
                  .resolve(buildTarget.getShortNameAndFlavorPostfix()));
    }

    return new ExportFile(
        buildTarget,
        projectFilesystem,
        context.getActionGraphBuilder(),
        name,
        mode,
        src,
        directoryAction);
  }

  private static ExportFileDirectoryAction getDirectoryActionFromConfig(BuckConfig buckConfig) {
    return buckConfig
        .getEnum("export_file", "input_directory_action", ExportFileDirectoryAction.class)
        .orElse(ExportFileDirectoryAction.ALLOW);
  }

  /** If the src field is absent, add the name field to the list of inputs. */
  @Override
  public ImmutableSet<ForwardRelPath> inferInputsFromAttributes(
      UnflavoredBuildTarget buildTarget, TwoArraysImmutableHashMap<ParamName, Object> attributes) {
    ImmutableSet.Builder<ForwardRelPath> inputs = ImmutableSet.builder();
    if (!attributes.containsKey(ParamName.bySnakeCase("src"))) {
      inputs.add(
          buildTarget.getCellRelativeBasePath().getPath().resolve(buildTarget.getLocalName()));
    }
    return inputs.build();
  }

  @Override
  public boolean producesCacheableSubgraph() {
    return true;
  }

  /** Controls how `export_file` exports it's wrapped source. */
  public enum Mode {

    /**
     * Forward the wrapped {@link SourcePath} reference without any build time overhead (e.g.
     * copying, caching, etc).
     */
    REFERENCE,

    /**
     * Create and export a copy of the wrapped {@link SourcePath} (incurring the cost of copying and
     * caching this copy at build time).
     */
    COPY,
  }

  @RuleArg
  interface AbstractExportFileDescriptionArg extends BuildRuleArg {
    Optional<SourcePath> getSrc();

    Optional<String> getOut();

    Optional<Mode> getMode();
  }
}
