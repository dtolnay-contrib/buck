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

package com.facebook.buck.parser.implicit;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.Arrays;
import java.util.Map;
import org.immutables.value.Value;

/** Represents a load path and symbols that should be implicitly included in a build file */
@BuckStyleValue
public abstract class ImplicitInclude {
  @JsonIgnore
  public abstract ImplicitIncludePath getRawImportLabel();

  @JsonProperty("load_symbols")
  public abstract ImmutableMap<String, String> getSymbols();

  @JsonProperty("load_path")
  @Value.Derived
  public String getImportString() {
    return getLoadPath();
  }

  /** Returns the load path for the given path. */
  @JsonIgnore
  @Value.Derived
  public String getLoadPath() {
    return getRawImportLabel().reconstructWithColon();
  }

  /**
   * Constructs a {@link ImplicitInclude} from a configuration string in the form of
   *
   * <p>//path/to:bzl_file.bzl::symbol_to_import::second_symbol_to_import
   *
   * @param configurationString The string used in configuration
   * @return A parsed {@link ImplicitInclude} object
   * @throws {@link HumanReadableException} if the configuration string is invalid
   */
  public static ImplicitInclude fromConfigurationString(String configurationString) {
    // Double colons are used so that if someone uses an absolute windows path, their error
    // messages will not be confusing. e.g. C:\foo.bzl:bar would lead to a file named
    // 'C', and symbols '\foo.bzl' and 'bar'. This just makes things explicit.
    ImmutableList<String> parts =
        Arrays.stream(configurationString.split("::"))
            .map(String::trim)
            .collect(ImmutableList.toImmutableList());
    if (parts.size() < 2) {
      throw new HumanReadableException(
          "Configuration setting '%s' did not list any symbols to load. Setting should be of "
              + "the format //<load label>::<symbol1>::<symbol2>...",
          configurationString);
    }

    ImplicitIncludePath rawLabel = ImplicitIncludePath.parse(parts.get(0), configurationString);
    ImmutableMap<String, String> symbols =
        parseAllSymbolsFromConfiguration(parts.subList(1, parts.size()), configurationString);

    return ImplicitInclude.of(rawLabel, symbols);
  }

  private static ImmutableMap<String, String> parseAllSymbolsFromConfiguration(
      ImmutableList<String> allSymbols, String configurationString) {
    ImmutableMap.Builder<String, String> symbolBuilder = ImmutableMap.builder();
    for (String symbolString : allSymbols) {
      if (symbolString.isEmpty()) {
        throw new HumanReadableException(
            "Provided configuration %s specifies an empty path/symbols", configurationString);
      }
      parseSymbolsFromConfiguration(symbolString, symbolBuilder, configurationString);
    }
    return symbolBuilder.build();
  }

  static void parseSymbolsFromConfiguration(
      String part, ImmutableMap.Builder<String, String> symbolBuilder, String configurationString) {
    String[] symbolParts = part.split("=", 2);
    String alias;
    String symbol;
    if (symbolParts.length == 1) {
      alias = symbolParts[0];
      symbol = symbolParts[0];
    } else {
      alias = symbolParts[0];
      symbol = symbolParts[1];
    }
    if (symbol.isEmpty()) {
      throw new HumanReadableException(
          "Provided configuration %s specifies an empty symbol", configurationString);
    }
    if (alias.isEmpty()) {
      throw new HumanReadableException(
          "Provided configuration %s specifies an empty symbol alias", configurationString);
    }
    symbolBuilder.put(alias, symbol);
  }

  public static ImplicitInclude of(
      ImplicitIncludePath rawImportLabel, Map<String, ? extends String> symbols) {
    return ImmutableImplicitInclude.ofImpl(rawImportLabel, symbols);
  }
}
