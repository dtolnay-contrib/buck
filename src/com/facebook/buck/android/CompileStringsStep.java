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

import com.facebook.buck.android.resources.strings.CompileStrings;
import com.facebook.buck.android.resources.strings.StringResources;
import com.facebook.buck.android.resources.strings.StringResources.Gender;
import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * This {@link Step} takes a list of string resource files (strings.xml), groups them by locales,
 * and for each locale generates a file with all the string resources for that locale. Strings.xml
 * files without a resource qualifier are mapped to the "en" locale.
 *
 * <p>A typical strings.xml file looks like:
 *
 * <pre>{@code
 * <?xml version="1.0" encoding="utf-8"?>
 * <resources>
 *   <string name="resource_name1">I am a string.</string>
 *   <string name="resource_name2">I am another string.</string>
 *   <plurals name="time_hours_ago">
 *     <item quantity="one">1 minute ago</item>
 *     <item quantity="other">%d minutes ago</item>
 *   </plurals>
 *   <string-array name="logging_levels">
 *     <item>Default</item>
 *     <item>Verbose</item>
 *     <item>Debug</item>
 *   </string-array>
 * </resources>
 *
 * }</pre>
 *
 * <p>For more information on the xml file format, refer to: <a
 * href="http://developer.android.com/guide/topics/resources/string-resource.html">String Resources
 * - Android Developers </a>
 *
 * <p>So for each supported locale in a project, this step goes through all such xml files for that
 * locale, and builds a map of resource name to resource value, where resource value is either:
 *
 * <ol>
 *   <li>a string
 *   <li>a map of plurals
 *   <li>a list of strings
 * </ol>
 *
 * and dumps this map into the output file. See {@link StringResources} for the file format.
 */
public class CompileStringsStep implements Step {

  @VisibleForTesting
  static final Pattern NON_ENGLISH_STRING_FILE_PATTERN =
      CompileStrings.NON_ENGLISH_STRING_FILE_PATTERN;

  @VisibleForTesting
  static final Pattern R_DOT_TXT_STRING_RESOURCE_PATTERN =
      CompileStrings.R_DOT_TXT_STRING_RESOURCE_PATTERN;

  private final CompileStrings compileStrings;
  private final ProjectFilesystem filesystem;
  private final ImmutableList<Path> stringFiles;
  private final Path rDotTxtFile;
  private final Function<String, Path> pathBuilder;

  /**
   * Note: The ordering of files in the input list determines which resource value ends up in the
   * output .fbstr file, in the event of multiple xml files of a locale sharing the same string
   * resource name - file that appears first in the list wins.
   *
   * @param stringFiles Set containing paths to strings.xml files matching {@link
   *     GetStringsFilesStep#STRINGS_FILE_PATH}
   * @param rDotTxtFile Path to the R.txt file generated by aapt.
   * @param pathBuilder Builds a path to store a .fbstr file at.
   */
  public CompileStringsStep(
      ProjectFilesystem filesystem,
      ImmutableList<Path> stringFiles,
      Path rDotTxtFile,
      Function<String, Path> pathBuilder) {
    this.compileStrings = new CompileStrings();
    this.filesystem = filesystem;
    this.stringFiles = stringFiles;
    this.rDotTxtFile = rDotTxtFile;
    this.pathBuilder = pathBuilder;
  }

  @Override
  public StepExecutionResult execute(StepExecutionContext context) throws IOException {
    CompileStrings compileStrings = new CompileStrings();
    try {
      compileStrings.compileStrings(
          filesystem.getRootPath(), stringFiles, rDotTxtFile, pathBuilder);
    } catch (SAXException e) {
      context.logError(e, "Error parsing string file");
      return StepExecutionResults.ERROR;
    }

    return StepExecutionResults.SUCCESS;
  }

  /**
   * Groups a list of strings.xml files by locale. String files with no resource qualifier (eg.
   * values/strings.xml) are mapped to the "en" locale
   *
   * <p>eg. given the following list:
   *
   * <p>ImmutableList.of( Paths.get("one/res/values-es/strings.xml"),
   * Paths.get("two/res/values-es/strings.xml"), Paths.get("three/res/values-pt-rBR/strings.xml"),
   * Paths.get("four/res/values-pt-rPT/strings.xml"), Paths.get("five/res/values/strings.xml"));
   *
   * <p>returns:
   *
   * <p>ImmutableMap.of( "es", ImmutableList.of(Paths.get("one/res/values-es/strings.xml"),
   * Paths.get("two/res/values-es/strings.xml")), "pt_BR",
   * ImmutableList.of(Paths.get("three/res/values-pt-rBR/strings.xml'), "pt_PT",
   * ImmutableList.of(Paths.get("four/res/values-pt-rPT/strings.xml"), "en",
   * ImmutableList.of(Paths.get("five/res/values/strings.xml")));
   */
  @VisibleForTesting
  ImmutableMultimap<String, Path> groupFilesByLocale(ImmutableList<Path> files) {
    return compileStrings.groupFilesByLocale(files);
  }

  /**
   * Scrapes string resource names and values from the list of xml nodes passed and populates {@code
   * stringsMap}, ignoring resource names that are already present in the map.
   *
   * @param stringNodes A list of {@code <string></string>} nodes.
   * @param stringsMap Map from string resource id to its values.
   */
  @VisibleForTesting
  void scrapeStringNodes(NodeList stringNodes, Map<Integer, EnumMap<Gender, String>> stringsMap) {
    compileStrings.scrapeStringNodes(stringNodes, stringsMap);
  }

  /** Similar to {@code scrapeStringNodes}, but for plurals nodes. */
  @VisibleForTesting
  void scrapePluralsNodes(
      NodeList pluralNodes,
      Map<Integer, EnumMap<Gender, ImmutableMap<String, String>>> pluralsMap) {
    compileStrings.scrapePluralsNodes(pluralNodes, pluralsMap);
  }

  /** Similar to {@code scrapeStringNodes}, but for string array nodes. */
  @VisibleForTesting
  void scrapeStringArrayNodes(
      NodeList arrayNodes, Map<Integer, EnumMap<Gender, ImmutableList<String>>> arraysMap) {
    compileStrings.scrapeStringArrayNodes(arrayNodes, arraysMap);
  }

  /** Used in unit tests to inject the resource name to id map. */
  @VisibleForTesting
  void addStringResourceNameToIdMap(Map<String, Integer> nameToIdMap) {
    compileStrings.addStringResourceNameToIdMap(nameToIdMap);
  }

  @VisibleForTesting
  void addPluralsResourceNameToIdMap(Map<String, Integer> nameToIdMap) {
    compileStrings.addPluralsResourceNameToIdMap(nameToIdMap);
  }

  @VisibleForTesting
  void addArrayResourceNameToIdMap(Map<String, Integer> nameToIdMap) {
    compileStrings.addArrayResourceNameToIdMap(nameToIdMap);
  }

  @Override
  public String getShortName() {
    return "compile_strings";
  }

  @Override
  public String getDescription(StepExecutionContext context) {
    return "Combine, parse string resource xml files into one binary file per locale.";
  }
}
