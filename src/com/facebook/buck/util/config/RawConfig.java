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

package com.facebook.buck.util.config;

import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.google.common.collect.ImmutableMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Hierarchical configuration of section/key/value triples.
 *
 * <p>This class only implements the simple construction/storage/retrieval of these values. Other
 * classes like {@link Config} implements accessors that interpret the values as other types.
 */
@BuckStyleValue
public abstract class RawConfig {
  public abstract ImmutableMap<String, ImmutableMap<String, String>> getValues();

  /**
   * Retrieve a section by name.
   *
   * @return The contents of the named section. If the section does not exist, the empty map.
   */
  public ImmutableMap<String, String> getSection(String sectionName) {
    return Optional.ofNullable(getValues().get(sectionName)).orElse(ImmutableMap.of());
  }

  /** Retrieve a value from a named section. */
  public Optional<String> getValue(String sectionName, String key) {
    return Optional.ofNullable(getSection(sectionName).get(key));
  }

  /** Returns an empty config. */
  public static RawConfig of() {
    return of(ImmutableMap.of());
  }

  public static RawConfig of(Map<String, ? extends ImmutableMap<String, String>> values) {
    return ImmutableRawConfig.ofImpl(values);
  }

  public static Builder builder() {
    return new Builder();
  }

  /**
   * A builder for {@link RawConfig}s.
   *
   * <p>Unless otherwise stated, duplicate keys overwrites earlier ones.
   */
  public static class Builder {
    private Map<String, Map<String, String>> values = new LinkedHashMap<>();

    /** Merge raw config values into this config. */
    public <M extends Map<String, String>> Builder putAll(Map<String, M> config) {
      for (Map.Entry<String, M> entry : config.entrySet()) {
        requireSection(entry.getKey()).putAll(entry.getValue());
      }
      return this;
    }

    /** Merge the values from another {@code RawConfig}. */
    public Builder putAll(RawConfig config) {
      return putAll(config.getValues());
    }

    /** Put a single value. */
    public Builder put(String section, String key, String value) {
      requireSection(section).put(key, value);
      return this;
    }

    public RawConfig build() {
      ImmutableMap.Builder<String, ImmutableMap<String, String>> builder =
          ImmutableMap.builderWithExpectedSize(values.size());
      for (Map.Entry<String, Map<String, String>> entry : values.entrySet()) {
        builder.put(entry.getKey(), ImmutableMap.copyOf(entry.getValue()));
      }
      return of(builder.build());
    }

    /** Get a section or create it if it doesn't exist. */
    private Map<String, String> requireSection(String sectionName) {
      Map<String, String> section = values.get(sectionName);
      if (section == null) {
        section = new LinkedHashMap<>();
        values.put(sectionName, section);
      }
      return section;
    }
  }
}
