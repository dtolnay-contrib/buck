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

package com.facebook.buck.core.macros;

/**
 * A simple MacroCombiner for {@code MacroReplacer<String>} that just concatenates the strings and
 * expanded macros.
 */
public class StringMacroCombiner implements MacroCombiner<String> {
  private final StringBuilder builder = new StringBuilder();

  @Override
  public String build() {
    return builder.toString();
  }

  @Override
  public void addString(String part) {
    builder.append(part);
  }

  @Override
  public void add(String expandedMacro) {
    builder.append(expandedMacro);
  }
}
