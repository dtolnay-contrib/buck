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

// Copyright 2015 The Bazel Authors. All rights reserved.
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

package net.starlark.java.eval;

import java.util.Iterator;

/**
 * A Starlark value that support indexed access ({@code object[key]}) and membership tests ({@code
 * key in object}).
 */
public abstract class StarlarkIndexable<K> extends StarlarkIterable<K> {

  /** Returns the value associated with the given key. */
  public abstract Object getIndex(StarlarkSemantics semantics, Object key) throws EvalException;

  /**
   * Returns whether the key is in the object. New types should try to follow the semantics of dict:
   * 'x in y' should return True when 'y[x]' is valid; otherwise, it should either be False or a
   * failure. Note however that the builtin types string, list, and tuple do not follow this
   * convention.
   */
  public abstract boolean containsKey(StarlarkSemantics semantics, Object key) throws EvalException;

  @Override
  public Iterator<K> iterator() {
    // TODO(nga): throw EvalException
    throw new RuntimeException(String.format("type '%s' is not iterable", Starlark.type(this)));
  }
}
