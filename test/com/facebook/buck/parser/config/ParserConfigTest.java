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

package com.facebook.buck.parser.config;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.BuckConfigTestUtils;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.parser.implicit.ImplicitInclude;
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class ParserConfigTest {

  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();

  @Rule public ExpectedException thrown = ExpectedException.none();

  @Test
  public void testGetAllowEmptyGlobs() throws IOException {
    assertTrue(getDefaultConfig().getAllowEmptyGlobs());
    ParserConfig config = parseConfig("[build]\nallow_empty_globs = false");
    assertFalse(config.getAllowEmptyGlobs());
  }

  @Test
  public void testGetGlobHandler() throws IOException {
    assertThat(getDefaultConfig().getGlobHandler(), equalTo(ParserConfig.GlobHandler.PYTHON));

    for (ParserConfig.GlobHandler handler : ParserConfig.GlobHandler.values()) {
      ParserConfig config = parseConfig("[project]\nglob_handler = " + handler);
      assertThat(config.getGlobHandler(), equalTo(handler));
    }
  }

  @Test
  public void testGetBuildFileSearchMethod() throws IOException {
    ParserConfig config;

    config = getDefaultConfig();
    assertEquals(
        config.getBuildFileSearchMethod(), ParserConfig.BuildFileSearchMethod.FILESYSTEM_CRAWL);

    config = parseConfig("[project]\nbuild_file_search_method = filesystem_crawl");
    assertEquals(
        config.getBuildFileSearchMethod(), ParserConfig.BuildFileSearchMethod.FILESYSTEM_CRAWL);

    config = parseConfig("[project]\nbuild_file_search_method = watchman");
    assertEquals(config.getBuildFileSearchMethod(), ParserConfig.BuildFileSearchMethod.WATCHMAN);
  }

  @Test
  public void testGetPathsCheckMethod() throws IOException {
    ParserConfig config;

    config = getDefaultConfig();
    assertEquals(config.getPathsCheckMethod(), ParserConfig.PathsCheckMethod.FILESYSTEM);

    config = parseConfig("[project]\npaths_check_method = filesystem");
    assertEquals(config.getPathsCheckMethod(), ParserConfig.PathsCheckMethod.FILESYSTEM);

    config = parseConfig("[project]\npaths_check_method = watchman");
    assertEquals(config.getPathsCheckMethod(), ParserConfig.PathsCheckMethod.WATCHMAN);

    config = parseConfig("[project]\npaths_check_method = none");
    assertEquals(config.getPathsCheckMethod(), ParserConfig.PathsCheckMethod.NONE);
  }

  @Test
  public void testGetPackageBoundaryCheckMethod() throws IOException {
    ParserConfig config;
    config = getDefaultConfig();
    assertEquals(
        config.getPackageBoundaryCheckMethod(), ParserConfig.PackageBoundaryCheckMethod.FILESYSTEM);

    config = parseConfig("[project]\npackage_boundary_check_method = filesystem");
    assertEquals(
        config.getPackageBoundaryCheckMethod(), ParserConfig.PackageBoundaryCheckMethod.FILESYSTEM);

    config = parseConfig("[project]\npackage_boundary_check_method = watchman");
    assertEquals(
        config.getPackageBoundaryCheckMethod(), ParserConfig.PackageBoundaryCheckMethod.WATCHMAN);
  }

  @Test
  public void testGetWatchCells() throws IOException {
    assertTrue("watch_cells defaults to true", getDefaultConfig().getWatchCells());

    ParserConfig config = parseConfig("[project]\nwatch_cells = false");
    assertFalse(config.getWatchCells());

    config = parseConfig("[project]\nwatch_cells = true");
    assertTrue(config.getWatchCells());
  }

  @Test
  public void shouldReturnThreadCountIfParallelParsingIsEnabled() {
    BuckConfig config =
        FakeBuckConfig.builder()
            .setSections("[project]", "parsing_threads = 2", "parallel_parsing = true")
            .build();

    ParserConfig parserConfig = config.getView(ParserConfig.class);

    assertTrue(parserConfig.getEnableParallelParsing());
    assertEquals(2, parserConfig.getNumParsingThreads());
  }

  @Test
  public void shouldReturnOneThreadCountIfParallelParsingIsNotEnabled() {
    BuckConfig config =
        FakeBuckConfig.builder()
            .setSections("[project]", "parsing_threads = 3", "parallel_parsing = false")
            .build();

    ParserConfig parserConfig = config.getView(ParserConfig.class);

    assertFalse(parserConfig.getEnableParallelParsing());
    assertEquals(1, parserConfig.getNumParsingThreads());
  }

  @Test
  public void shouldGetReadOnlyDirs() {
    String existingPath1 = "tmp/tmp-file";
    String existingPath2 = "tmp2/tmp2-file";
    ImmutableSet<Path> readOnlyPaths =
        ImmutableSet.of(Paths.get(existingPath1), Paths.get(existingPath2));
    ProjectFilesystem filesystem = new FakeProjectFilesystem(readOnlyPaths);

    ParserConfig parserConfig =
        FakeBuckConfig.builder()
            .setSections("[project]", "read_only_paths = " + existingPath1 + "," + existingPath2)
            .setFilesystem(filesystem)
            .build()
            .getView(ParserConfig.class);

    assertTrue(parserConfig.getReadOnlyPaths().isPresent());
    assertThat(
        parserConfig.getReadOnlyPaths().get(),
        is(equalTo(ImmutableList.of(Paths.get(existingPath1), Paths.get(existingPath2)))));

    String notExistingDir = "not/existing/path";
    parserConfig =
        FakeBuckConfig.builder()
            .setSections("[project]", "read_only_paths = " + notExistingDir)
            .setFilesystem(filesystem)
            .build()
            .getView(ParserConfig.class);

    assertTrue(parserConfig.getReadOnlyPaths().get().isEmpty());
  }

  @Test
  public void whenParserPythonPathIsNotSetDefaultIsUsed() {
    ParserConfig parserConfig = getDefaultConfig();
    assertEquals(
        "Should return an empty optional",
        "<not set>",
        parserConfig.getPythonModuleSearchPath().orElse("<not set>"));
  }

  @Test
  public void whenParserPythonPathIsSet() {
    ParserConfig parserConfig =
        FakeBuckConfig.builder()
            .setSections(
                ImmutableMap.of("parser", ImmutableMap.of("python_path", "foobar:spamham")))
            .build()
            .getView(ParserConfig.class);
    assertEquals(
        "Should return the configured string",
        "foobar:spamham",
        parserConfig.getPythonModuleSearchPath().orElse("<not set>"));
  }

  @Test
  public void getImplicitIncludes() {
    ImmutableMap<String, ImplicitInclude> actual =
        FakeBuckConfig.builder()
            .setSections(
                ImmutableMap.of(
                    "buildfile",
                    ImmutableMap.of(
                        "package_includes",
                        "=>//:includes.bzl::get_name::get_value,foo/bar=>//foo/bar:includes.bzl::get_name::get_value")))
            .build()
            .getView(ParserConfig.class)
            .getPackageImplicitIncludes();

    ImmutableMap<String, ImplicitInclude> expected =
        ImmutableMap.of(
            "",
            ImplicitInclude.fromConfigurationString("//:includes.bzl::get_name::get_value"),
            "foo/bar",
            ImplicitInclude.fromConfigurationString("//foo/bar:includes.bzl::get_name::get_value"));

    assertEquals(expected, actual);
  }

  private ParserConfig getDefaultConfig() {
    return FakeBuckConfig.empty().getView(ParserConfig.class);
  }

  private ParserConfig parseConfig(String... configStrings) throws IOException {
    String configString = Joiner.on("\n").join(configStrings);
    Reader reader = new StringReader(configString);
    return BuckConfigTestUtils.createWithDefaultFilesystem(temporaryFolder, reader)
        .getView(ParserConfig.class);
  }
}
