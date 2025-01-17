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

// Copyright 2019 The Bazel Authors. All rights reserved.
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
//

package net.starlark.java.eval;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * A StarlarkSemantics is an immutable set of optional name/value pairs that affect the dynamic
 * behavior of Starlark operators and built-in functions, both core and application-defined.
 *
 * <p>For extensibility, a StarlarkSemantics only records a name/value pair when the value differs
 * from the default value appropriate to that name. Values of most types are accessed using a {@link
 * Key}, which defines the name, type, and default value for an entry. Boolean values are accessed
 * using a string key; the string must have the prefix "+" or "-", indicating the default value: +
 * for true, - for false. The reason for the special treatment of boolean entries is that they may
 * enable or disable methods and parameters in the StarlarkMethod annotation system, and it is not
 * possible to refer to a Key from a Java annotation, only a string.
 *
 * <p>It is the client's responsibility to ensure that a StarlarkSemantics does not encounter
 * multiple Keys of the same name but different value types.
 *
 * <p>For Bazel's semantics options, see {@link packages.semantics.BuildLanguageOptions}.
 *
 * <p>For options that affect the static behavior of the Starlark frontend (lexer, parser,
 * validator, compiler), see {@link FileOptions}.
 */
public final class StarlarkSemantics {

  /** Returns the empty semantics, in which every option has its default value. */
  public static final StarlarkSemantics DEFAULT = new StarlarkSemantics(ImmutableMap.of());

  // A map entry must be accessed by Key iff its name has no [+-] prefix.
  // Key<Boolean> is permitted too.
  // The map keys are sorted but we avoid ImmutableSortedMap due to observed inefficiency.
  private final ImmutableMap<String, Object> map;
  private final int hashCode;

  private StarlarkSemantics(ImmutableMap<String, Object> map) {
    this.map = map;
    this.hashCode = map.hashCode();
  }

  /** Returns the value of a boolean option, which must have a [+-] prefix. */
  public boolean getBool(String name) {
    char prefix = name.charAt(0);
    Preconditions.checkArgument(prefix == '+' || prefix == '-');
    boolean defaultValue = prefix == '+';
    Boolean v = (Boolean) map.get(name); // prefix => cast cannot fail
    return v != null ? v : defaultValue;
  }

  /** Returns the value of the option denoted by {@code key}. */
  public <T> T get(Key<T> key) {
    @SuppressWarnings("unchecked") // safe, if Key.names are unique
    T v = (T) map.get(key.name);
    return v != null ? v : key.defaultValue;
  }

  /** A Key identifies an option, providing its name, type, and default value. */
  public static class Key<T> {
    public final String name;
    public final T defaultValue;

    /**
     * Constructs a key. The name must not start with [+-]. The value must not be subsequently
     * modified.
     */
    public Key(String name, T defaultValue) {
      char prefix = name.charAt(0);
      Preconditions.checkArgument(prefix != '-' && prefix != '+');
      this.name = name;
      this.defaultValue = Preconditions.checkNotNull(defaultValue);
    }

    @Override
    public String toString() {
      return this.name;
    }
  }

  /**
   * Returns a new builder that initially holds the same key/value pairs as this StarlarkSemantics.
   */
  public Builder toBuilder() {
    return new Builder(new TreeMap<>(map));
  }

  /** Returns a new empty builder. */
  public static Builder builder() {
    return new Builder(new TreeMap<>());
  }

  /** A Builder is a mutable container used to construct an immutable StarlarkSemantics. */
  public static final class Builder {
    private final TreeMap<String, Object> map;

    private Builder(TreeMap<String, Object> map) {
      this.map = map;
    }

    /** Sets the value for the specified key. */
    public <T> Builder set(Key<T> key, T value) {
      if (!value.equals(key.defaultValue)) {
        map.put(key.name, value);
      } else {
        map.remove(key.name);
      }
      return this;
    }

    /** Sets the value for the boolean key, which must have a [+-] prefix. */
    public Builder setBool(String name, boolean value) {
      char prefix = name.charAt(0);
      Preconditions.checkArgument(prefix == '+' || prefix == '-');
      boolean defaultValue = prefix == '+';
      if (value != defaultValue) {
        map.put(name, value);
      } else {
        map.remove(name);
      }
      return this;
    }

    /** Returns an immutable StarlarkSemantics. */
    public StarlarkSemantics build() {
      return new StarlarkSemantics(ImmutableMap.copyOf(map));
    }
  }

  @Override
  public int hashCode() {
    return hashCode;
  }

  @Override
  public boolean equals(Object that) {
    return this == that
        || (that instanceof StarlarkSemantics && this.map.equals(((StarlarkSemantics) that).map));
  }

  /**
   * Returns a representation of this StarlarkSemantics' non-default key/value pairs in key order.
   */
  @Override
  public String toString() {
    // Print "StarlarkSemantics{k=v, ...}", without +/- prefixes.
    StringBuilder buf = new StringBuilder();
    buf.append("StarlarkSemantics{");
    String sep = "";
    for (Map.Entry<String, Object> e : map.entrySet()) {
      String key = e.getKey();
      buf.append(sep);
      sep = ", ";
      if (key.charAt(0) == '+' || key.charAt(0) == '-') {
        buf.append(key, 1, key.length());
      } else {
        buf.append(key);
      }
      buf.append('=').append(e.getValue());
    }
    return buf.append('}').toString();
  }

  // -- semantics options affecting the Starlark interpreter itself --

  /** Change the behavior of 'print' statements. Used in tests to verify flag propagation. */
  public static final String PRINT_TEST_MARKER = "-print_test_marker";

  /**
   * Whether recursive function calls are allowed. This option is not exposed to Bazel, which
   * unconditionally prohibits recursion.
   */
  public static final String ALLOW_RECURSION = "-allow_recursion";

  /** When disabled, any float operation results in evaluation error. */
  public static final String ALLOW_FLOATS = "+allow_floats";
}
