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

package com.facebook.buck.rules.coercer;

import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.filesystems.ForwardRelPath;
import com.facebook.buck.core.model.BuildTargetWithOutputs;
import com.facebook.buck.core.model.CellRelativePath;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.TargetConfigurationResolver;
import com.facebook.buck.core.model.UnconfiguredBuildTargetWithOutputs;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.UnconfiguredSourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.reflect.TypeToken;
import java.nio.file.Path;

/** Coerce to {@link com.facebook.buck.core.sourcepath.SourcePath}. */
public class SourcePathTypeCoercer extends LeafTypeNewCoercer<UnconfiguredSourcePath, SourcePath> {
  private final TypeCoercer<UnconfiguredBuildTargetWithOutputs, BuildTargetWithOutputs>
      buildTargetWithOutputsTypeCoercer;
  private final TypeCoercer<Path, Path> pathTypeCoercer;

  public SourcePathTypeCoercer(
      TypeCoercer<UnconfiguredBuildTargetWithOutputs, BuildTargetWithOutputs>
          buildTargetWithOutputsTypeCoercer,
      TypeCoercer<Path, Path> pathTypeCoercer) {
    this.buildTargetWithOutputsTypeCoercer = buildTargetWithOutputsTypeCoercer;
    this.pathTypeCoercer = pathTypeCoercer;
  }

  @Override
  public SkylarkSpec getSkylarkSpec() {
    return () -> "attr.source()";
  }

  @Override
  public TypeToken<SourcePath> getOutputType() {
    return TypeToken.of(SourcePath.class);
  }

  @Override
  public TypeToken<UnconfiguredSourcePath> getUnconfiguredType() {
    return TypeToken.of(UnconfiguredSourcePath.class);
  }

  @Override
  public UnconfiguredSourcePath coerceToUnconfigured(
      CellNameResolver cellRoots,
      ProjectFilesystem filesystem,
      ForwardRelPath pathRelativeToProjectRoot,
      Object object)
      throws CoerceFailedException {
    if (!(object instanceof String)) {
      throw CoerceFailedException.simple(object, getOutputType());
    }

    String string = (String) object;
    if ((string.contains("//") || string.startsWith(":"))) {
      UnconfiguredBuildTargetWithOutputs buildTargetWithOutputs =
          buildTargetWithOutputsTypeCoercer.coerceToUnconfigured(
              cellRoots, filesystem, pathRelativeToProjectRoot, object);
      return new UnconfiguredSourcePath.BuildTarget(buildTargetWithOutputs);
    } else {
      Path path =
          pathTypeCoercer.coerceToUnconfigured(
              cellRoots, filesystem, pathRelativeToProjectRoot, object);
      if (path.isAbsolute()) {
        throw CoerceFailedException.simple(
            object, getOutputType(), "SourcePath cannot contain an absolute path");
      }
      return new UnconfiguredSourcePath.Path(
          CellRelativePath.of(cellRoots.getCurrentCellName(), ForwardRelPath.ofPath(path)));
    }
  }

  @Override
  public SourcePath coerce(
      CellNameResolver cellRoots,
      ProjectFilesystem filesystem,
      ForwardRelPath pathRelativeToProjectRoot,
      TargetConfiguration targetConfiguration,
      TargetConfigurationResolver hostConfigurationResolver,
      UnconfiguredSourcePath object)
      throws CoerceFailedException {
    return object.configure(cellRoots, filesystem, targetConfiguration);
  }
}
