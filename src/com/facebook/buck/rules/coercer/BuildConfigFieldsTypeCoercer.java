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

import com.facebook.buck.android.build_config.BuildConfigFields;
import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.ForwardRelPath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.collect.ImmutableList;
import com.google.common.reflect.TypeToken;
import java.util.List;

/**
 * {@link TypeCoercer} that takes a list of strings and transforms it into a {@link
 * BuildConfigFields}. This class takes care of parsing each string, making sure it conforms to the
 * specification in {@link BuildConfigFields}.
 */
public class BuildConfigFieldsTypeCoercer extends LeafUnconfiguredOnlyCoercer<BuildConfigFields> {

  @Override
  public TypeCoercer.SkylarkSpec getSkylarkSpec() {
    return new SkylarkSpec() {
      @Override
      public String spec() {
        return "attr.list(attr.string())";
      }

      @Override
      public String topLevelSpec() {
        return "attr.list(attr.string(), default=[])";
      }
    };
  }

  @Override
  public TypeToken<BuildConfigFields> getUnconfiguredType() {
    return TypeToken.of(BuildConfigFields.class);
  }

  @Override
  public BuildConfigFields coerceToUnconfigured(
      CellNameResolver cellRoots,
      ProjectFilesystem filesystem,
      ForwardRelPath pathRelativeToProjectRoot,
      Object object)
      throws CoerceFailedException {
    if (!(object instanceof List)) {
      throw CoerceFailedException.simple(object, getOutputType());
    }

    List<?> list = (List<?>) object;
    List<String> values =
        list.stream()
            .map(
                input -> {
                  if (input instanceof String) {
                    return (String) input;
                  } else {
                    throw new HumanReadableException(
                        "Expected string for build config values but was: %s", input);
                  }
                })
            .collect(ImmutableList.toImmutableList());
    return BuildConfigFields.fromFieldDeclarations(values);
  }
}
