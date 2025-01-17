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

package com.facebook.buck.android.build_config;

import com.facebook.buck.android.build_config.BuildConfigFields.Field;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.google.common.base.Joiner;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.Iterator;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.immutables.value.Value;

/**
 * List of fields to add to a generated {@code BuildConfig.java} file. Each field knows its Java
 * type, variable name, and value.
 */
@Value.Enclosing
@BuckStyleValue
public abstract class BuildConfigFields implements Iterable<Field> {

  /** An individual field in a {@link BuildConfigFields}. */
  @BuckStyleValue
  public abstract static class Field {

    public abstract String getType();

    public abstract String getName();

    public abstract String getValue();

    public static Field of(String type, String name, String value) {
      return ImmutableBuildConfigFields.Field.ofImpl(type, name, value);
    }

    /**
     * @return a string that could be passed to {@link
     *     BuildConfigFields#fromFieldDeclarations(Iterable)} such that it could be parsed to return
     *     a {@link Field} equal to this object.
     */
    @Override
    public String toString() {
      return String.format("%s %s = %s", getType(), getName(), getValue());
    }
  }

  private static final BuildConfigFields INSTANCE =
      ImmutableBuildConfigFields.ofImpl(ImmutableMap.of());

  private static final Pattern VARIABLE_DEFINITION_PATTERN =
      Pattern.compile(
          "(?<type>[a-zA-Z_$][a-zA-Z0-9_.<>]+("
              + Pattern.quote("[]")
              + ")?)"
              + "\\s+"
              + "(?<name>[a-zA-Z_$][a-zA-Z0-9_$]+)"
              + "\\s*=\\s*"
              + "(?<value>.+)");

  private static final ImmutableSet<String> PRIMITIVE_NUMERIC_TYPE_NAMES =
      ImmutableSet.of("byte", "char", "double", "float", "int", "long", "short");

  private static final Function<String, Field> TRANSFORM =
      input -> {
        Matcher matcher = VARIABLE_DEFINITION_PATTERN.matcher(input);
        if (matcher.matches()) {
          return Field.of(matcher.group("type"), matcher.group("name"), matcher.group("value"));
        } else {
          throw new HumanReadableException(
              "Not a valid BuildConfig variable declaration: %s", input);
        }
      };

  public abstract Map<String, Field> getNameToField();

  public static BuildConfigFields of(Map<String, ? extends BuildConfigFields.Field> nameToField) {
    if (nameToField.isEmpty()) {
      return INSTANCE;
    }
    return ImmutableBuildConfigFields.ofImpl(nameToField);
  }

  public static BuildConfigFields of() {
    return INSTANCE;
  }

  public static BuildConfigFields fromFieldDeclarations(Iterable<String> declarations) {
    return fromFields(FluentIterable.from(declarations).transform(TRANSFORM::apply));
  }

  /** @return a {@link BuildConfigFields} that contains the specified fields in iteration order. */
  public static BuildConfigFields fromFields(Iterable<Field> fields) {
    ImmutableMap<String, Field> entries = FluentIterable.from(fields).uniqueIndex(Field::getName);
    return of(entries);
  }

  /**
   * @return A new {@link BuildConfigFields} with all of the fields from this object, combined with
   *     all of the fields from the specified {@code fields}. If both objects have fields with the
   *     same name, the entry from the {@code fields} parameter wins.
   */
  public BuildConfigFields putAll(BuildConfigFields fields) {

    ImmutableMap.Builder<String, Field> nameToFieldBuilder = ImmutableMap.builder();
    nameToFieldBuilder.putAll(fields.getNameToField());
    for (Field field : this.getNameToField().values()) {
      if (!fields.getNameToField().containsKey(field.getName())) {
        nameToFieldBuilder.put(field.getName(), field);
      }
    }
    return of(nameToFieldBuilder.build());
  }

  /**
   * Creates the Java code for a {@code BuildConfig.java} file in the specified {@code javaPackage}.
   *
   * @param source The build target of the rule that is responsible for generating this
   *     BuildConfig.java file.
   * @param javaPackage The Java package for the generated file.
   * @param useConstantExpressions Whether the value of each field in the generated Java code should
   *     be the literal value from the {@link Field} (i.e., a constant expression) or a
   *     non-constant-expression that is guaranteed to evaluate to the literal value.
   */
  public String generateBuildConfigDotJava(
      String source, String javaPackage, boolean useConstantExpressions) {

    StringBuilder builder = new StringBuilder();
    // By design, we drop the flavor from the BuildTarget (if present), so this debug text makes
    // more sense to users.
    builder.append(String.format("// Generated by %s. DO NOT MODIFY.\n", source));
    builder.append("package ").append(javaPackage).append(";\n");
    builder.append("public class BuildConfig {\n");
    builder.append("  private BuildConfig() {}\n");

    String prefix = "  public static final ";
    for (Field field : getNameToField().values()) {
      String type = field.getType();
      if ("boolean".equals(type)) {
        // type is a non-numeric primitive.
        boolean isTrue = "true".equals(field.getValue());
        if (!(isTrue || "false".equals(field.getValue()))) {
          throw new HumanReadableException(
              "expected boolean literal but was: %s", field.getValue());
        }
        String value;
        if (useConstantExpressions) {
          value = String.valueOf(isTrue);
        } else {
          value = "Boolean.parseBoolean(null)";
          if (isTrue) {
            value = "!" + value;
          }
        }
        builder
            .append(prefix)
            .append("boolean ")
            .append(field.getName())
            .append(" = ")
            .append(value)
            .append(";\n");
      } else {
        String typeSafeZero = PRIMITIVE_NUMERIC_TYPE_NAMES.contains(type) ? "0" : "null";
        String defaultValue = field.getValue();
        if (!useConstantExpressions) {
          defaultValue = "!Boolean.parseBoolean(null) ? " + defaultValue + " : " + typeSafeZero;
        }
        builder
            .append(prefix)
            .append(type)
            .append(" ")
            .append(field.getName())
            .append(" = ")
            .append(defaultValue)
            .append(";\n");
      }
    }

    builder.append("}\n");
    return builder.toString();
  }

  /**
   * @return iterator that enumerates the fields used to construct this {@link BuildConfigFields}.
   *     The {@link Iterator#remove()} method of the return value is not supported.
   */
  @Override
  public Iterator<Field> iterator() {
    return getNameToField().values().iterator();
  }

  /**
   * @return value that represents the data stored in this object such that it can be used to
   *     represent this object in a {@link RuleKey}.
   */
  @Override
  public String toString() {
    return Joiner.on(';').join(getNameToField().values());
  }
}
