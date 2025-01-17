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

package com.facebook.buck.android;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.util.types.Pair;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;
import java.util.regex.Pattern;

public interface HasNativeMergeMapArgs {
  Optional<ImmutableMap<String, ImmutableList<Pattern>>> getNativeLibraryMergeMap();

  Optional<ImmutableList<Pair<String, ImmutableList<Pattern>>>> getNativeLibraryMergeSequence();

  Optional<ImmutableList<Pattern>> getNativeLibraryMergeSequenceBlocklist();

  Optional<BuildTarget> getNativeLibraryMergeGlue();

  Optional<BuildTarget> getNativeLibraryMergeCodeGenerator();

  Optional<ImmutableSortedSet<String>> getNativeLibraryMergeLocalizedSymbols();
}
