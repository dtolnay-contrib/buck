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

package com.facebook.buck.versions;

import com.facebook.buck.core.cell.Cells;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.Optional;

public class FixedTargetNodeTranslator extends TargetNodeTranslator {

  private final ImmutableMap<BuildTarget, BuildTarget> translations;

  public FixedTargetNodeTranslator(
      TypeCoercerFactory typeCoercerFactory,
      ImmutableList<TargetTranslator<?>> translators,
      ImmutableMap<BuildTarget, BuildTarget> translations,
      Cells cells) {
    super(typeCoercerFactory, translators, cells);
    this.translations = translations;
  }

  public FixedTargetNodeTranslator(
      TypeCoercerFactory typeCoercerFactory,
      ImmutableMap<BuildTarget, BuildTarget> translations,
      Cells cells) {
    this(typeCoercerFactory, ImmutableList.of(), translations, cells);
  }

  @Override
  public Optional<BuildTarget> translateBuildTarget(BuildTarget target) {
    return Optional.ofNullable(translations.get(target));
  }

  @Override
  public Optional<ImmutableMap<BuildTarget, Version>> getSelectedVersions(BuildTarget target) {
    return Optional.empty();
  }
}
