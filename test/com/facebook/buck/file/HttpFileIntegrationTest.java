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

import static com.facebook.buck.util.environment.Platform.WINDOWS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeThat;

import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.impl.BuildPaths;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.HttpdForTests;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class HttpFileIntegrationTest {

  public @Rule TemporaryPaths temporaryDir = new TemporaryPaths();

  private HttpdForTests.CapturingHttpHandler httpdHandler;
  private HttpdForTests httpd;
  private static final String echoDotSh = "#!/bin/sh\necho \"Hello, world\"";
  private static final String echoDotBat = "@echo off\necho Hello, world";
  private ProjectWorkspace workspace;

  @Before
  public void setUp() throws Exception {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "fetch_files", temporaryDir);
    httpdHandler =
        new HttpdForTests.CapturingHttpHandler(
            ImmutableMap.<String, byte[]>builder()
                .put("/foo/bar/echo.sh", echoDotSh.getBytes(StandardCharsets.UTF_8))
                .put("/foo/bar/echo.bat", echoDotBat.getBytes(StandardCharsets.UTF_8))
                .put(
                    "/package/artifact_name/version/artifact_name-version-classifier.zip",
                    echoDotSh.getBytes(StandardCharsets.UTF_8))
                .build());
    httpd = new HttpdForTests();
    httpd.addHandler(httpdHandler);
    httpd.start();
  }

  @After
  public void tearDown() throws Exception {
    httpd.close();
  }

  private void rewriteBuckFileTemplate() throws IOException {
    // Replace a few tokens with the real host and port where our server is running
    URI uri = httpd.getRootUri();
    workspace.writeContentsToPath(
        workspace
            .getFileContents("BUCK")
            .replace("<HOST>", uri.getHost())
            .replace("<PORT>", Integer.toString(uri.getPort())),
        "BUCK");
  }

  @Test
  public void setsExecutableBitToTrue() throws IOException {
    assumeThat(Platform.detect(), is(not(WINDOWS)));

    workspace.setUp();
    rewriteBuckFileTemplate();

    Path outputPath =
        workspace
            .getGenPath(BuildTargetFactory.newInstance("//:echo_executable.sh"), "%s")
            .resolve("echo_executable.sh");
    Path scratchPath =
        workspace.getScratchPath(BuildTargetFactory.newInstance("//:echo_executable.sh"), "%s");

    workspace.runBuckCommand("fetch", "//:echo_executable.sh").assertSuccess();

    Assert.assertTrue(Files.exists(workspace.resolve(outputPath)));
    Assert.assertTrue(Files.isExecutable(workspace.resolve(outputPath)));
    assertEquals(echoDotSh, workspace.getFileContents(outputPath));
    assertEquals(ImmutableList.of("/foo/bar/echo.sh"), httpdHandler.getRequestedPaths());
    assertEquals(
        0, Files.walk(workspace.resolve(scratchPath)).filter(Files::isRegularFile).count());
  }

  @Test
  public void setsExecutableBitToFalse() throws IOException {
    assumeThat(Platform.detect(), is(not(WINDOWS)));

    workspace.setUp();
    rewriteBuckFileTemplate();

    Path outputPath =
        workspace
            .getGenPath(BuildTargetFactory.newInstance("//:echo_nonexecutable.sh"), "%s")
            .resolve("echo_nonexecutable.sh");
    Path scratchPath =
        workspace.getScratchPath(BuildTargetFactory.newInstance("//:echo_nonexecutable.sh"), "%s");

    workspace.runBuckCommand("fetch", "//:echo_nonexecutable.sh").assertSuccess();

    Assert.assertTrue(Files.exists(workspace.resolve(outputPath)));
    Assert.assertFalse(Files.isExecutable(workspace.resolve(outputPath)));
    assertEquals(echoDotSh, workspace.getFileContents(outputPath));
    assertEquals(ImmutableList.of("/foo/bar/echo.sh"), httpdHandler.getRequestedPaths());
    assertEquals(
        0, Files.walk(workspace.resolve(scratchPath)).filter(Files::isRegularFile).count());
  }

  @Test
  public void doesNotWriteFileIfDownloadFails() throws IOException {
    workspace.setUp();
    rewriteBuckFileTemplate();

    Path outputPath =
        workspace
            .getGenPath(BuildTargetFactory.newInstance("//:echo_bad_urls.sh"), "%s")
            .resolve("echo_bad_urls.sh");
    Path scratchPath =
        workspace.getScratchPath(BuildTargetFactory.newInstance("//:echo_bad_urls.sh"), "%s");

    ProcessResult result = workspace.runBuckCommand("fetch", "//:echo_bad_urls.sh");

    result.assertFailure();
    assertThat(
        result.getStderr(),
        matchesPattern(
            Pattern.compile(".*Unable to download http://.*/invalid_path.*", Pattern.DOTALL)));
    Assert.assertFalse(Files.exists(workspace.resolve(outputPath)));
    assertEquals(ImmutableList.of("/invalid_path", "/missing"), httpdHandler.getRequestedPaths());
    assertEquals(
        0, Files.walk(workspace.resolve(scratchPath)).filter(Files::isRegularFile).count());
  }

  @Test
  public void doesNotWriteFileIfShaVerificationFails() throws IOException {
    assumeThat(Platform.detect(), is(not(WINDOWS)));

    workspace.setUp();
    rewriteBuckFileTemplate();

    Path outputPath =
        workspace
            .getGenPath(BuildTargetFactory.newInstance("//:echo_bad_hash.sh"), "%s")
            .resolve("echo_bad_hash.sh");
    Path scratchPath =
        workspace.getScratchPath(BuildTargetFactory.newInstance("//:echo_bad_hash.sh"), "%s");

    ProcessResult result = workspace.runBuckCommand("fetch", "//:echo_bad_hash.sh");

    result.assertFailure();
    assertThat(
        result.getStderr(),
        Matchers.containsString(
            "/foo/bar/echo.sh (hashes do not match. Expected 534be6d331e8f1ab7892f19e8fe23db4907bdc54f517a8b22adc82e69b6b1093, saw 2c7ae82268c1bab8d048a76405a6f7f39c2d95791df37ad2c36cb9252ee3a6ca)"));
    Assert.assertFalse(Files.exists(workspace.resolve(outputPath)));
    assertEquals(ImmutableList.of("/foo/bar/echo.sh"), httpdHandler.getRequestedPaths());
    assertEquals(
        1, Files.walk(workspace.resolve(scratchPath)).filter(Files::isRegularFile).count());
    Assert.assertTrue(Files.exists(workspace.resolve(scratchPath).resolve("echo_bad_hash.sh")));
    assertEquals(echoDotSh, workspace.getFileContents(scratchPath.resolve("echo_bad_hash.sh")));
  }

  @Test
  public void downloadsFileAndValidatesIt() throws IOException {
    workspace.setUp();
    rewriteBuckFileTemplate();

    Path outputPath =
        workspace.getGenPath(BuildTargetFactory.newInstance("//:echo.sh"), "%s").resolve("echo.sh");
    Path scratchPath = workspace.getScratchPath(BuildTargetFactory.newInstance("//:echo.sh"), "%s");

    workspace.runBuckCommand("fetch", "//:echo.sh").assertSuccess();

    Assert.assertTrue(Files.exists(workspace.resolve(outputPath)));
    assertEquals(echoDotSh, workspace.getFileContents(outputPath));
    assertEquals(ImmutableList.of("/foo/bar/echo.sh"), httpdHandler.getRequestedPaths());
    assertEquals(
        0, Files.walk(workspace.resolve(scratchPath)).filter(Files::isRegularFile).count());
  }

  @Test
  public void writesFileToAlternateLocationIfOutProvided() throws IOException {
    workspace.setUp();
    rewriteBuckFileTemplate();

    Path outputPath =
        workspace
            .getGenPath(BuildTargetFactory.newInstance("//:echo_with_out.sh"), "%s")
            .resolve("some_file.sh");
    Path scratchPath =
        workspace.getScratchPath(BuildTargetFactory.newInstance("//:echo_with_out.sh"), "%s");

    workspace.runBuckCommand("fetch", "//:echo_with_out.sh").assertSuccess();

    Assert.assertTrue(Files.exists(workspace.resolve(outputPath)));
    assertEquals(echoDotSh, workspace.getFileContents(outputPath));
    assertEquals(ImmutableList.of("/foo/bar/echo.sh"), httpdHandler.getRequestedPaths());
    assertEquals(
        0, Files.walk(workspace.resolve(scratchPath)).filter(Files::isRegularFile).count());
  }

  @Test
  public void downloadsFromMavenCoordinates() throws IOException {
    workspace.setUp();
    TestDataHelper.overrideBuckconfig(
        workspace,
        ImmutableMap.of("download", ImmutableMap.of("maven_repo", httpd.getRootUri().toString())));

    Path outputPath =
        workspace
            .getGenPath(BuildTargetFactory.newInstance("//:echo_from_maven.sh"), "%s")
            .resolve("echo_from_maven.sh");
    Path scratchPath =
        workspace.getScratchPath(BuildTargetFactory.newInstance("//:echo_from_maven.sh"), "%s");

    workspace.runBuckCommand("fetch", "//:echo_from_maven.sh").assertSuccess();

    Assert.assertTrue(Files.exists(workspace.resolve(outputPath)));
    assertEquals(echoDotSh, workspace.getFileContents(outputPath));
    assertEquals(
        ImmutableList.of("/package/artifact_name/version/artifact_name-version-classifier.zip"),
        httpdHandler.getRequestedPaths());
    assertEquals(
        0, Files.walk(workspace.resolve(scratchPath)).filter(Files::isRegularFile).count());
  }

  @Test
  public void canBeUsedAsDependencyInRuleAnalysis() throws IOException {
    workspace.setUp();
    rewriteBuckFileTemplate();

    workspace.addBuckConfigLocalOption("parser", "user_defined_rules", "enabled");
    workspace.addBuckConfigLocalOption("rule_analysis", "mode", "PROVIDER_COMPATIBLE");
    workspace.addBuckConfigLocalOption("download", "in_build", "true");

    String exeTarget = "//rag:copy_executable.sh";
    String nonExeTarget = "//rag:copy_with_out.sh";
    Path expectedExePath =
        workspace
            .getProjectFileSystem()
            .resolve(
                BuildPaths.getGenDir(
                        workspace.getProjectFileSystem().getBuckPaths(),
                        BuildTargetFactory.newInstance(exeTarget))
                    .resolve("echo_executable.sh"));
    Path expectedNonExePath =
        workspace
            .getProjectFileSystem()
            .resolve(
                BuildPaths.getGenDir(
                        workspace.getProjectFileSystem().getBuckPaths(),
                        BuildTargetFactory.newInstance(nonExeTarget))
                    .resolve("some_file.sh"));

    Path outputExec = workspace.buildAndReturnOutput(exeTarget);
    Path outputNonExec = workspace.buildAndReturnOutput(nonExeTarget);

    assertEquals(echoDotSh, workspace.getProjectFileSystem().readFileIfItExists(outputExec).get());
    assertEquals(
        echoDotSh, workspace.getProjectFileSystem().readFileIfItExists(outputNonExec).get());
    assertEquals(expectedExePath, outputExec);
    assertEquals(expectedNonExePath, outputNonExec);
  }

  @Test
  public void canBeExecutedWithBuckRun() throws IOException {
    workspace.setUp();
    rewriteBuckFileTemplate();

    workspace.addBuckConfigLocalOption("parser", "user_defined_rules", "enabled");
    workspace.addBuckConfigLocalOption("rule_analysis", "mode", "PROVIDER_COMPATIBLE");
    workspace.addBuckConfigLocalOption("download", "in_build", "true");

    String target =
        Platform.detect().getType().isWindows()
            ? "//:echo_executable.bat"
            : "//:echo_executable.sh";
    ProcessResult output = workspace.runBuckCommand("run", target).assertSuccess();
    assertEquals("Hello, world", output.getStdout().trim());
  }
}
