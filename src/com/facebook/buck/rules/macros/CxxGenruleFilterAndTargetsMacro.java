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

package com.facebook.buck.rules.macros;

import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.model.BaseName;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.versions.TargetNodeTranslator;
import com.google.common.collect.Comparators;
import com.google.common.collect.ComparisonChain;
import com.google.common.collect.ImmutableList;
import java.util.Comparator;
import java.util.Optional;
import java.util.regex.Pattern;

/** Base class for <code>cxx_genrule</code> flags-based macros. */
public abstract class CxxGenruleFilterAndTargetsMacro implements Macro {

  public abstract Optional<Pattern> getFilter();

  public abstract ImmutableList<BuildTarget> getTargets();

  @Override
  public int compareTo(Macro o) {
    int result = Macro.super.compareTo(o);
    if (result != 0) {
      return result;
    }
    CxxGenruleFilterAndTargetsMacro other = (CxxGenruleFilterAndTargetsMacro) o;
    return ComparisonChain.start()
        .compare(
            getFilter(),
            other.getFilter(),
            Comparators.emptiesFirst(Comparator.comparing(Pattern::pattern)))
        .compare(
            getTargets(),
            other.getTargets(),
            Comparators.lexicographical(Comparator.<BuildTarget>naturalOrder()))
        .result();
  }

  /**
   * @return a copy of this {@link CxxGenruleFilterAndTargetsMacro} with the given {@link
   *     BuildTarget}.
   */
  abstract CxxGenruleFilterAndTargetsMacro withTargets(ImmutableList<BuildTarget> targets);

  @Override
  public Optional<Macro> translateTargets(
      CellNameResolver cellPathResolver, BaseName targetBaseName, TargetNodeTranslator translator) {
    return translator
        .translate(cellPathResolver, targetBaseName, getTargets())
        .map(this::withTargets);
  }
}
