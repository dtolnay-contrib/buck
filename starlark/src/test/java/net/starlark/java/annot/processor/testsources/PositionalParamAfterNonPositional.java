/*
 * Portions Copyright (c) Meta Platforms, Inc. and affiliates.
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

// Copyright 2018 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package net.starlark.java.annot.processor.testsources;

import net.starlark.java.annot.Param;
import net.starlark.java.annot.StarlarkMethod;
import net.starlark.java.eval.StarlarkInt;
import net.starlark.java.eval.StarlarkValue;

/**
 * Test case for a StarlarkMethod which has a positional parameter specified after a non positional
 * parameter.
 */
public class PositionalParamAfterNonPositional extends StarlarkValue {

  @StarlarkMethod(
      name = "positional_after_non_positional",
      documented = false,
      parameters = {
        @Param(name = "one", named = true, positional = false),
        @Param(name = "two", named = true, positional = true)
      })
  public Integer positionalAfterNonPositional(StarlarkInt one, StarlarkInt two) {
    return 42;
  }
}
