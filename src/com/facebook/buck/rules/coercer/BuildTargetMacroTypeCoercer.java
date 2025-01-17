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
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetWithOutputs;
import com.facebook.buck.core.model.UnconfiguredBuildTargetWithOutputs;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.macros.BuildTargetMacro;
import com.facebook.buck.rules.macros.UnconfiguredBuildTargetMacro;
import com.google.common.collect.ImmutableList;
import java.util.function.Function;

/** Coercer for macros which take a single {@link BuildTarget} arg. */
public final class BuildTargetMacroTypeCoercer<
        U extends UnconfiguredBuildTargetMacro, M extends BuildTargetMacro>
    implements MacroTypeCoercer<U, M> {

  private final TypeCoercer<UnconfiguredBuildTargetWithOutputs, BuildTargetWithOutputs>
      buildTargetWithOutputsTypeCoercer;
  private final Class<U> uClass;
  private final Class<M> mClass;
  private final Function<UnconfiguredBuildTargetWithOutputs, U> factory;

  public BuildTargetMacroTypeCoercer(
      TypeCoercer<UnconfiguredBuildTargetWithOutputs, BuildTargetWithOutputs>
          buildTargetWithOutputsTypeCoercer,
      Class<U> uClass,
      Class<M> mClass,
      Function<UnconfiguredBuildTargetWithOutputs, U> factory) {
    this.buildTargetWithOutputsTypeCoercer = buildTargetWithOutputsTypeCoercer;
    this.uClass = uClass;
    this.mClass = mClass;
    this.factory = factory;
  }

  @Override
  public boolean hasElementClass(Class<?>[] types) {
    return buildTargetWithOutputsTypeCoercer.hasElementClass(types);
  }

  @Override
  public void traverseUnconfigured(
      CellNameResolver cellRoots, U macro, TypeCoercer.Traversal traversal) {
    buildTargetWithOutputsTypeCoercer.traverseUnconfigured(
        cellRoots, macro.getTargetWithOutputs(), traversal);
  }

  @Override
  public void traverse(CellNameResolver cellRoots, M macro, TypeCoercer.Traversal traversal) {
    // TODO(irenewchen): Add output label to BuildTargetMacro and pass it on here
    buildTargetWithOutputsTypeCoercer.traverse(
        cellRoots,
        BuildTargetWithOutputs.of(macro.getTarget(), macro.getTargetWithOutputs().getOutputLabel()),
        traversal);
  }

  @Override
  public Class<U> getUnconfiguredOutputClass() {
    return uClass;
  }

  @Override
  public Class<M> getOutputClass() {
    return mClass;
  }

  @Override
  public U coerceToUnconfigured(
      CellNameResolver cellNameResolver,
      ProjectFilesystem filesystem,
      ForwardRelPath pathRelativeToProjectRoot,
      ImmutableList<String> args)
      throws CoerceFailedException {
    if (args.size() != 1) {
      throw new CoerceFailedException(
          String.format("expected exactly one argument (found %d)", args.size()));
    }
    UnconfiguredBuildTargetWithOutputs target =
        buildTargetWithOutputsTypeCoercer.coerceToUnconfigured(
            cellNameResolver, filesystem, pathRelativeToProjectRoot, args.get(0));
    return factory.apply(target);
  }
}
