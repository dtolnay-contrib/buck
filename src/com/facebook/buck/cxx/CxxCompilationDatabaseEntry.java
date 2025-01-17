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

package com.facebook.buck.cxx;

import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.google.common.collect.ImmutableList;

/*
 * The compilation database specification (https://clang.llvm.org/docs/JSONCompilationDatabase.html)
 * mandates that either 'arguments' or 'command' is required. They both contain
 * exactly the same information but in a different format. Hence, for the sake
 * of producing smaller compilation databases, only arguments is used.
 */
@BuckStyleValue
@JsonSerialize(as = ImmutableCxxCompilationDatabaseEntry.class)
@JsonDeserialize(as = ImmutableCxxCompilationDatabaseEntry.class)
public abstract class CxxCompilationDatabaseEntry {

  public abstract String getDirectory();

  public abstract String getFile();

  public abstract ImmutableList<String> getArguments();

  public static CxxCompilationDatabaseEntry of(
      String directory, String file, ImmutableList<String> arguments) {
    return ImmutableCxxCompilationDatabaseEntry.ofImpl(directory, file, arguments);
  }
}
