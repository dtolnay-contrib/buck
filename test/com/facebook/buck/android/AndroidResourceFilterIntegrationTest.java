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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.artifact_cache.ArtifactCache;
import com.facebook.buck.artifact_cache.DirArtifactCacheTestUtil;
import com.facebook.buck.artifact_cache.TestArtifactCaches;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.BuckBuildLog;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.testutil.integration.ZipInspector;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.sha1.Sha1HashCode;
import com.google.common.base.Splitter;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class AndroidResourceFilterIntegrationTest {

  @Rule public TemporaryPaths tmpFolder = new TemporaryPaths();

  private ProjectWorkspace workspace;
  private AndroidSdkResolver sdkResolver;

  private ProjectFilesystem filesystem;

  private boolean isBuildToolsNew() {
    return sdkResolver.isBuildToolsVersionAtLeast("21");
  }

  private Supplier<Tool> getAapt() {
    return sdkResolver.getAndroidPlatformTarget().getAaptExecutable();
  }

  @Before
  public void setUp() throws IOException {
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "android_project", tmpFolder);
    workspace.setUp();
    AssumeAndroidPlatform.get(workspace).assumeSdkIsAvailable();
    sdkResolver = AndroidSdkResolver.get(workspace).get();

    filesystem = workspace.getProjectFileSystem();
  }

  @Test
  public void testApkWithoutResourceFilter() throws IOException {
    String target = "//apps/sample:app";
    ProcessResult result = workspace.runBuckCommand("build", target);
    result.assertSuccess();

    Path apkFile =
        workspace.getPath(
            BuildTargetPaths.getGenPath(
                filesystem.getBuckPaths(), BuildTargetFactory.newInstance(target), "%s.apk"));
    ZipInspector zipInspector = new ZipInspector(apkFile);

    if (isBuildToolsNew()) {
      zipInspector.assertFileExists("res/drawable-mdpi-v4/app_icon.png");
      zipInspector.assertFileExists("res/drawable-hdpi-v4/app_icon.png");
      zipInspector.assertFileExists("res/drawable-xhdpi-v4/app_icon.png");
    } else {
      zipInspector.assertFileExists("res/drawable-mdpi/app_icon.png");
      zipInspector.assertFileExists("res/drawable-hdpi/app_icon.png");
      zipInspector.assertFileExists("res/drawable-xhdpi/app_icon.png");
    }
  }

  @Test
  public void testApkWithMdpiFilter() throws IOException {
    String target = "//apps/sample:app_mdpi";
    ProcessResult result = workspace.runBuckCommand("build", target);
    result.assertSuccess();

    Path apkFile =
        workspace.getPath(
            BuildTargetPaths.getGenPath(
                filesystem.getBuckPaths(), BuildTargetFactory.newInstance(target), "%s.apk"));
    ZipInspector zipInspector = new ZipInspector(apkFile);

    if (isBuildToolsNew()) {
      zipInspector.assertFileExists("res/drawable-mdpi-v4/app_icon.png");
      zipInspector.assertFileDoesNotExist("res/drawable-hdpi-v4/app_icon.png");
      zipInspector.assertFileDoesNotExist("res/drawable-xhdpi-v4/app_icon.png");
    } else {
      zipInspector.assertFileExists("res/drawable-mdpi/app_icon.png");
      zipInspector.assertFileDoesNotExist("res/drawable-hdpi/app_icon.png");
      zipInspector.assertFileDoesNotExist("res/drawable-xhdpi/app_icon.png");
    }
  }

  @Test
  public void testModifyingImageRebuildsResourcesFilter() throws IOException {
    String target = "//apps/sample:app_mdpi";
    ProcessResult result = workspace.runBuckBuild(target);
    result.assertSuccess();

    Path apkFile =
        workspace.getPath(
            BuildTargetPaths.getGenPath(
                filesystem.getBuckPaths(), BuildTargetFactory.newInstance(target), "%s.apk"));
    String iconPath =
        isBuildToolsNew() ? "res/drawable-mdpi-v4/app_icon.png" : "res/drawable-mdpi/app_icon.png";
    long firstImageCrc = new ZipInspector(apkFile).getCrc(iconPath);

    workspace.copyFile(
        "res/com/sample/base/res/drawable-hdpi/app_icon.png",
        "res/com/sample/base/res/drawable-mdpi/app_icon.png");

    workspace.resetBuildLogFile();
    result = workspace.runBuckBuild(target);
    result.assertSuccess();

    BuckBuildLog buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally(target);

    apkFile =
        workspace.getPath(
            BuildTargetPaths.getGenPath(
                filesystem.getBuckPaths(), BuildTargetFactory.newInstance(target), "%s.apk"));
    long secondImageCrc = new ZipInspector(apkFile).getCrc(iconPath);

    assertNotEquals(firstImageCrc, secondImageCrc);
  }

  @Test
  public void testApkWithXhdpiAndHdpiFilter() throws IOException {
    String target = "//apps/sample:app_hdpi_xhdpi";
    ProcessResult result = workspace.runBuckCommand("build", target);
    result.assertSuccess();

    Path apkFile =
        workspace.getPath(
            BuildTargetPaths.getGenPath(
                filesystem.getBuckPaths(), BuildTargetFactory.newInstance(target), "%s.apk"));
    ZipInspector zipInspector = new ZipInspector(apkFile);

    if (isBuildToolsNew()) {
      zipInspector.assertFileDoesNotExist("res/drawable-mdpi-v4/app_icon.png");
      zipInspector.assertFileExists("res/drawable-hdpi-v4/app_icon.png");
      zipInspector.assertFileExists("res/drawable-xhdpi-v4/app_icon.png");
    } else {
      zipInspector.assertFileDoesNotExist("res/drawable-mdpi/app_icon.png");
      zipInspector.assertFileExists("res/drawable-hdpi/app_icon.png");
      zipInspector.assertFileExists("res/drawable-xhdpi/app_icon.png");
    }
  }

  @Test
  public void testPostFilterResourcesCmd() throws IOException {
    String target = "//apps/sample:app_post_filter_cmd";
    ProcessResult result = workspace.runBuckBuild(target);
    result.assertSuccess();

    Path apkFile =
        workspace.getPath(
            BuildTargetPaths.getGenPath(
                filesystem.getBuckPaths(), BuildTargetFactory.newInstance(target), "%s.apk"));
    ZipInspector zipInspector = new ZipInspector(apkFile);
    zipInspector.assertFileExists("res/drawable/tiny_black.xml");
    zipInspector.assertFileExists("res/drawable/tiny_new.xml");
    zipInspector.assertFileDoesNotExist("res/drawable/tiny_black.png");

    Path rDotJavaPath =
        workspace.getPath(
            Paths.get(
                BuildTargetPaths.getScratchPath(
                        filesystem,
                        BuildTargetFactory.newInstance(
                            "//apps/sample:app_post_filter_cmd#generate_rdot_java"),
                        "__%s_rdotjava_src__")
                    .toString(),
                "com",
                "sample",
                "R.java"));

    // Make sure the generated R.java contains both resources and that they're in the
    // R.custom_drawables array.
    List<String> lines = filesystem.readLines(rDotJavaPath);
    String tinyBlackId = null;
    String tinyNewId = null;
    boolean foundCustomDrawables = false;
    Pattern idPattern = Pattern.compile("\\s+public static final int (.+)=(.+);");
    for (String line : lines) {
      Matcher matcher = idPattern.matcher(line);
      if (matcher.matches()) {
        String name = matcher.group(1);
        String id = matcher.group(2);
        if ("tiny_black".equals(name)) {
          tinyBlackId = id;
        } else if ("tiny_new".equals(name)) {
          tinyNewId = id;
        }
      } else if (line.contains("custom_drawables")) {
        assertNotNull(tinyBlackId);
        assertNotNull(tinyNewId);
        assertTrue(line.contains(tinyBlackId));
        assertTrue(line.contains(tinyNewId));
        foundCustomDrawables = true;
      }
    }
    assertTrue("Didn't find custom_drawables line", foundCustomDrawables);
  }

  @Test
  public void testPostFilterResourcesAndBanDuplicates() {
    workspace.runBuckBuild("//apps/sample:app_post_filter_no_dups").assertSuccess();
  }

  @Test
  public void testApkWithStringsAsAssets() throws IOException {
    String target = "//apps/sample:app_comp_str";
    ProcessResult result = workspace.runBuckCommand("build", target);
    result.assertSuccess();

    Path apkFile =
        workspace.getPath(
            BuildTargetPaths.getGenPath(
                filesystem.getBuckPaths(), BuildTargetFactory.newInstance(target), "%s.apk"));
    ZipInspector zipInspector = new ZipInspector(apkFile);

    zipInspector.assertFileExists("assets/strings/fr.fbstr");
  }

  @Test
  public void testStringArtifactsAreCached() throws IOException {
    workspace.enableDirCache();
    workspace.runBuckBuild("//apps/sample:app_comp_str").assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    Sha1HashCode androidBinaryRuleKey = buildLog.getRuleKey("//apps/sample:app_comp_str");

    ArtifactCache cache =
        TestArtifactCaches.createDirCacheForTest(
            workspace.getPath("."), filesystem.getBuckPaths().getCacheDir());
    Path cachedFile =
        DirArtifactCacheTestUtil.getPathForRuleKey(
            cache, new RuleKey(androidBinaryRuleKey.getHash()), Optional.empty());
    Files.delete(workspace.resolve(cachedFile));

    workspace.runBuckCommand("clean", "--keep-cache").assertSuccess();
    workspace.runBuckBuild("//apps/sample:app_comp_str").assertSuccess();
  }

  @Test
  public void testApkWithStringsAsAssetsAndResourceFilter() throws IOException {
    String target = "//apps/sample:app_comp_str_xhdpi";
    ProcessResult result = workspace.runBuckBuild(target);
    result.assertSuccess();

    Path apkFile =
        workspace.getPath(
            BuildTargetPaths.getGenPath(
                filesystem.getBuckPaths(), BuildTargetFactory.newInstance(target), "%s.apk"));
    ZipInspector zipInspector = new ZipInspector(apkFile);

    zipInspector.assertFileExists("assets/strings/fr.fbstr");

    if (isBuildToolsNew()) {
      zipInspector.assertFileExists("res/drawable-xhdpi-v4/app_icon.png");
      zipInspector.assertFileDoesNotExist("res/drawable-hdpi-v4/app_icon.png");
      zipInspector.assertFileDoesNotExist("res/drawable-mdpi-v4/app_icon.png");
    } else {
      zipInspector.assertFileExists("res/drawable-xhdpi/app_icon.png");
      zipInspector.assertFileDoesNotExist("res/drawable-hdpi/app_icon.png");
      zipInspector.assertFileDoesNotExist("res/drawable-mdpi/app_icon.png");
    }
  }

  @Test
  public void testAsset() throws IOException {
    workspace.enableDirCache();
    String target = "//apps/sample:app";
    workspace.runBuckBuild(target).assertSuccess();

    Path apkFile =
        workspace.getPath(
            BuildTargetPaths.getGenPath(
                filesystem.getBuckPaths(), BuildTargetFactory.newInstance(target), "%s.apk"));
    ZipInspector zipInspector = new ZipInspector(apkFile);

    long firstCrc = zipInspector.getCrc("assets/asset_file.txt");

    workspace.replaceFileContents(
        "res/com/sample/asset_only/assets/asset_file.txt", "Hello", "Bye");
    workspace.runBuckBuild(target).assertSuccess();

    apkFile =
        workspace.getPath(
            BuildTargetPaths.getGenPath(
                filesystem.getBuckPaths(), BuildTargetFactory.newInstance(target), "%s.apk"));
    zipInspector = new ZipInspector(apkFile);

    long secondCrc = zipInspector.getCrc("assets/asset_file.txt");

    assertNotEquals("Rebuilt APK file must include the new asset file.", firstCrc, secondCrc);
  }

  @Test
  public void testEnglishBuildDoesntContainFrenchStrings()
      throws IOException, InterruptedException {
    String target = "//apps/sample:app";
    workspace.runBuckBuild(target).assertSuccess();
    Path apkFile =
        workspace.getPath(
            BuildTargetPaths.getGenPath(
                filesystem.getBuckPaths(), BuildTargetFactory.newInstance(target), "%s.apk"));

    int matchingLines = runAaptDumpResources(apkFile);
    assertEquals(2, matchingLines);

    target = "//apps/sample:app_en";
    workspace.runBuckBuild(target).assertSuccess();
    apkFile =
        workspace.getPath(
            BuildTargetPaths.getGenPath(
                filesystem.getBuckPaths(), BuildTargetFactory.newInstance(target), "%s.apk"));

    matchingLines = runAaptDumpResources(apkFile);
    assertEquals(1, matchingLines);
  }

  @Test
  public void testEnglishBuildDoesntContainFrenchStringsAapt2()
      throws IOException, InterruptedException {
    // TODO(dreiss): Remove this when aapt2 is everywhere.
    ProcessResult foundAapt2 = workspace.runBuckBuild("//apps/sample:check_for_aapt2");
    Assume.assumeTrue(foundAapt2.getExitCode().getCode() == 0);

    String target = "//apps/sample:app_en";
    workspace.replaceFileContents("apps/sample/BUCK", "'aapt1', # app_en", "'aapt2',");
    workspace.runBuckBuild(target).assertSuccess();
    Path apkFile =
        workspace.getPath(
            BuildTargetPaths.getGenPath(
                filesystem.getBuckPaths(), BuildTargetFactory.newInstance(target), "%s.apk"));
    int matchingLines = runAaptDumpResources(apkFile);
    assertEquals(1, matchingLines);
  }

  private int runAaptDumpResources(Path apkFile) throws IOException, InterruptedException {
    Pattern pattern = Pattern.compile(".*com.example:string/base_button: t=.*");
    ProcessExecutor.Result result =
        workspace.runCommand(
            ImmutableList.<String>builder()
                .addAll(
                    getAapt()
                        .get()
                        .getCommandPrefix(new TestActionGraphBuilder().getSourcePathResolver()))
                .add("dump")
                .add("resources")
                .add(apkFile.toAbsolutePath().toString())
                .build());
    assertEquals(0, result.getExitCode());
    return FluentIterable.from(Splitter.on('\n').split(result.getStdout().orElse("")))
        .filter(input -> pattern.matcher(input).matches())
        .size();
  }
}
