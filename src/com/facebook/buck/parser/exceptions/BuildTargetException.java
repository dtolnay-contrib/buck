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

package com.facebook.buck.parser.exceptions;

import com.facebook.buck.core.exceptions.DependencyStack;

/** Base class for exceptions raised when parser is unable to resolve a dependency */
public abstract class BuildTargetException extends BuildFileParseException {
  public BuildTargetException(String message) {
    super(message);
  }

  public BuildTargetException(DependencyStack dependencyStack, String message) {
    super(dependencyStack, message);
  }
}
