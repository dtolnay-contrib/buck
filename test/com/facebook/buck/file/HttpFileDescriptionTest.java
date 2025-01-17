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

package com.facebook.buck.file;

import static org.hamcrest.MatcherAssert.assertThat;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TestBuildRuleCreationContextFactory;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.net.URI;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class HttpFileDescriptionTest {

  public @Rule TemporaryPaths temporaryDir = new TemporaryPaths();
  public @Rule ExpectedException thrown = ExpectedException.none();

  private HttpFileDescription description;
  private ActionGraphBuilder graphBuilder;
  private ProjectFilesystem filesystem;
  private TargetGraph targetGraph;

  @Before
  public void setUp() {
    description = new HttpFileDescription();
    graphBuilder = new TestActionGraphBuilder();
    filesystem = TestProjectFilesystems.createProjectFilesystem(temporaryDir.getRoot());
    targetGraph = TargetGraph.EMPTY;
  }

  private HttpFile createDescrptionFromArgs(String targetName, HttpFileDescriptionArg args) {
    BuildTarget target = BuildTargetFactory.newInstance(targetName);
    return (HttpFile)
        description.createBuildRule(
            TestBuildRuleCreationContextFactory.create(targetGraph, graphBuilder, filesystem),
            target,
            new BuildRuleParams(
                ImmutableSortedSet::of, ImmutableSortedSet::of, ImmutableSortedSet.of()),
            args);
  }

  private AbsPath getOutputPath(HttpFile buildRule) {
    graphBuilder.computeIfAbsent(buildRule.getBuildTarget(), t -> buildRule);
    return graphBuilder.getSourcePathResolver().getAbsolutePath(buildRule.getSourcePathToOutput());
  }

  @Test
  public void usesRuleNameIfOutNotProvided() {
    HttpFile buildRule =
        createDescrptionFromArgs(
            "//foo/bar:baz",
            HttpFileDescriptionArg.builder()
                .setName("baz")
                .setExecutable(false)
                .setSha256("2c7ae82268c1bab8d048a76405a6f7f39c2d95791df37ad2c36cb9252ee3a6ca")
                .setUrls(ImmutableList.of(URI.create("https://example.com/first.exe")))
                .build());

    Assert.assertEquals(
        filesystem.resolve(
            BuildTargetPaths.getGenPath(
                    filesystem.getBuckPaths(),
                    BuildTargetFactory.newInstance("//foo/bar:baz"),
                    "%s")
                .resolve("baz")),
        getOutputPath(buildRule).getPath());
  }

  @Test
  public void usesOutIfProvided() {
    HttpFile buildRule =
        createDescrptionFromArgs(
            "//foo/bar:baz",
            HttpFileDescriptionArg.builder()
                .setName("baz")
                .setExecutable(false)
                .setSha256("2c7ae82268c1bab8d048a76405a6f7f39c2d95791df37ad2c36cb9252ee3a6ca")
                .setUrls(ImmutableList.of(URI.create("https://example.com/first.exe")))
                .setOut("my_cool_exe")
                .build());

    Assert.assertEquals(
        filesystem.resolve(
            BuildTargetPaths.getGenPath(
                    filesystem.getBuckPaths(),
                    BuildTargetFactory.newInstance("//foo/bar:baz"),
                    "%s")
                .resolve("my_cool_exe")),
        getOutputPath(buildRule).getPath());
  }

  @Test
  public void givesAUsableErrorIfShaCouldNotBeParsed() {
    thrown.expect(HumanReadableException.class);
    thrown.expectMessage("when parsing sha256 of //foo/bar:baz");
    createDescrptionFromArgs(
        "//foo/bar:baz",
        HttpFileDescriptionArg.builder()
            .setName("baz")
            .setExecutable(false)
            .setSha256("z")
            .setUrls(ImmutableList.of(URI.create("https://example.com/first.exe")))
            .build());
  }

  @Test
  public void givesAUsableErrorIfLooksLikeASha1() {
    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(
        "does not appear to be a sha256 hash. Expected 256 bits, got 160 bits when parsing //foo/bar:baz");
    createDescrptionFromArgs(
        "//foo/bar:baz",
        HttpFileDescriptionArg.builder()
            .setName("baz")
            .setExecutable(false)
            .setSha256("37a575feb201ecd7591cbe1558747a2b4d9b9562")
            .setUrls(ImmutableList.of(URI.create("https://example.com/first.exe")))
            .build());
  }

  @Test
  public void givesAUsableErrorIfZeroUrlsProvided() {
    thrown.expect(HumanReadableException.class);
    thrown.expectMessage("At least one url must be provided for //foo/bar:baz");
    createDescrptionFromArgs(
        "//foo/bar:baz",
        HttpFileDescriptionArg.builder()
            .setName("baz")
            .setExecutable(false)
            .setSha256("2c7ae82268c1bab8d048a76405a6f7f39c2d95791df37ad2c36cb9252ee3a6ca")
            .setUrls(ImmutableList.of())
            .build());
  }

  @Test
  public void givesAUsableErrorIfNonHttpOrHttpsUrlIsProvided() {
    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(
        "Unsupported protocol 'ftp' for url ftp://ftp.example.com/second.exe in //foo/bar:baz. Must be http or https");
    createDescrptionFromArgs(
        "//foo/bar:baz",
        HttpFileDescriptionArg.builder()
            .setName("baz")
            .setExecutable(false)
            .setSha256("2c7ae82268c1bab8d048a76405a6f7f39c2d95791df37ad2c36cb9252ee3a6ca")
            .setUrls(
                ImmutableList.of(
                    URI.create("https://example.com/first.exe"),
                    URI.create("ftp://ftp.example.com/second.exe")))
            .build());
  }

  @Test
  public void returnsBinaryIfExecutableSet() {
    HttpFile buildRule =
        createDescrptionFromArgs(
            "//foo/bar:baz",
            HttpFileDescriptionArg.builder()
                .setName("baz")
                .setExecutable(true)
                .setSha256("2c7ae82268c1bab8d048a76405a6f7f39c2d95791df37ad2c36cb9252ee3a6ca")
                .setUrls(ImmutableList.of(URI.create("https://example.com/first.exe")))
                .build());

    assertThat(buildRule, Matchers.instanceOf(HttpFileBinary.class));
  }

  @Test
  public void returnsHttpFileIfExecutableNotSet() {
    HttpFile buildRule =
        createDescrptionFromArgs(
            "//foo/bar:baz",
            HttpFileDescriptionArg.builder()
                .setName("baz")
                .setExecutable(false)
                .setSha256("2c7ae82268c1bab8d048a76405a6f7f39c2d95791df37ad2c36cb9252ee3a6ca")
                .setUrls(ImmutableList.of(URI.create("https://example.com/first.exe")))
                .build());

    assertThat(buildRule, Matchers.not(Matchers.instanceOf(HttpFileBinary.class)));
  }
}
