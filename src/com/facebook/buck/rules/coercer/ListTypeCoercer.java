/*
 * Copyright (c) Facebook, Inc. and its affiliates.
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

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.coercer.concat.Concatable;
import com.facebook.buck.rules.coercer.concat.ImmutableListConcatable;
import com.google.common.collect.ImmutableList;
import com.google.common.reflect.TypeParameter;
import com.google.common.reflect.TypeToken;

public class ListTypeCoercer<T> extends CollectionTypeCoercer<ImmutableList<T>, T>
    implements Concatable<ImmutableList<T>> {

  private final ImmutableListConcatable<T> concatable = new ImmutableListConcatable<>();
  private final TypeToken<ImmutableList<T>> typeToken;

  public ListTypeCoercer(TypeCoercer<T> elementTypeCoercer) {
    super(elementTypeCoercer);
    this.typeToken =
        new TypeToken<ImmutableList<T>>() {}.where(
            new TypeParameter<T>() {}, elementTypeCoercer.getOutputType());
  }

  @Override
  public TypeToken<ImmutableList<T>> getOutputType() {
    return typeToken;
  }

  @Override
  public ImmutableList<T> coerce(
      CellPathResolver cellRoots,
      ProjectFilesystem filesystem,
      ForwardRelativePath pathRelativeToProjectRoot,
      TargetConfiguration targetConfiguration,
      TargetConfiguration hostConfiguration,
      Object object)
      throws CoerceFailedException {
    ImmutableList.Builder<T> builder = ImmutableList.builder();
    fill(
        cellRoots,
        filesystem,
        pathRelativeToProjectRoot,
        targetConfiguration,
        hostConfiguration,
        builder,
        object);
    return builder.build();
  }

  @Override
  public ImmutableList<T> concat(Iterable<ImmutableList<T>> elements) {
    return concatable.concat(elements);
  }
}
