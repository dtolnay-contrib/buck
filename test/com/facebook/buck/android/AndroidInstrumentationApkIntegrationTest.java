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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;

import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.java.testutil.AbiCompilationModeTest;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.DexInspector;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.testutil.integration.ZipInspector;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;

public class AndroidInstrumentationApkIntegrationTest extends AbiCompilationModeTest {

  @Rule public TemporaryPaths tmpFolder = new TemporaryPaths();

  @Test
  public void testCxxLibraryDep() throws IOException {

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "android_instrumentation_apk_integration_test", tmpFolder);
    workspace.setUp();
    AssumeAndroidPlatform.get(workspace).assumeSdkIsAvailable();
    AssumeAndroidPlatform.get(workspace).assumeNdkIsAvailable();
    setWorkspaceCompilationMode(workspace);
    ProjectFilesystem filesystem = workspace.getProjectFileSystem();

    String target = "//:app_cxx_lib_dep";
    workspace.runBuckCommand("build", target).assertSuccess();

    ZipInspector zipInspector =
        new ZipInspector(
            workspace.getPath(
                BuildTargetPaths.getGenPath(
                    filesystem.getBuckPaths(), BuildTargetFactory.newInstance(target), "%s.apk")));
    if (AssumeAndroidPlatform.get(workspace).isArmAvailable()) {
      zipInspector.assertFileExists("lib/armeabi/libcxx.so");
      zipInspector.assertFileExists("lib/armeabi/libgnustl_shared.so");
    }
    zipInspector.assertFileExists("lib/armeabi-v7a/libcxx.so");
    zipInspector.assertFileExists("lib/x86/libcxx.so");
    if (AssumeAndroidPlatform.get(workspace).isGnuStlAvailable()) {
      zipInspector.assertFileExists("lib/armeabi-v7a/libgnustl_shared.so");
      zipInspector.assertFileExists("lib/x86/libgnustl_shared.so");
    } else {
      zipInspector.assertFileExists("lib/armeabi-v7a/libc++_shared.so");
      zipInspector.assertFileExists("lib/x86/libc++_shared.so");
    }
  }

  @Test
  public void instrumentationApkCannotTestAnotherInstrumentationApk() throws IOException {

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "android_instrumentation_apk_integration_test", tmpFolder);
    workspace.setUp();
    AssumeAndroidPlatform.get(workspace).assumeSdkIsAvailable();
    AssumeAndroidPlatform.get(workspace).assumeNdkIsAvailable();
    setWorkspaceCompilationMode(workspace);

    ProcessResult result =
        workspace.runBuckCommand("build", "//:instrumentation_apk_with_instrumentation_apk");
    assertThat(
        result.getStderr(),
        containsString(
            "In //:instrumentation_apk_with_instrumentation_apk, apk='//:app_cxx_lib_dep'"
                + " must be an android_binary() or apk_genrule() but was"
                + " android_instrumentation_apk()."));
  }

  @Test
  public void instrumentationApkExcludesRDotJava() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "android_instrumentation_apk_integration_test", tmpFolder);
    workspace.setUp();
    AssumeAndroidPlatform.get(workspace).assumeSdkIsAvailable();
    AssumeAndroidPlatform.get(workspace).assumeNdkIsAvailable();
    setWorkspaceCompilationMode(workspace);

    Path apkUnderTestPath = workspace.buildAndReturnOutput("//:app");
    DexInspector apkUnderTestDexInspector = new DexInspector(apkUnderTestPath, "classes.dex");
    apkUnderTestDexInspector.assertTypeExists("Lcom/example/R;");
    apkUnderTestDexInspector.assertTypeExists("Lcom/example/R$color;");

    Path apkPath =
        workspace.buildAndReturnOutput("//:instrumentation_apk_with_r_dot_java_conflict");
    ZipInspector zipInspector = new ZipInspector(apkPath);
    zipInspector.assertFileExists("classes.dex");

    DexInspector testApkDexInspector = new DexInspector(apkPath, "classes.dex");
    testApkDexInspector.assertTypeDoesNotExist("Lcom/example/R;");
    testApkDexInspector.assertTypeDoesNotExist("Lcom/example/R$color;");
  }

  @Test
  public void testCxxLibraryExclusion() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "android_instrumentation_apk_integration_test", tmpFolder);
    workspace.setUp();
    AssumeAndroidPlatform.get(workspace).assumeSdkIsAvailable();
    AssumeAndroidPlatform.get(workspace).assumeNdkIsAvailable();
    setWorkspaceCompilationMode(workspace);
    ProjectFilesystem filesystem = workspace.getProjectFileSystem();

    String target = "//:app_cxx_lib_dep";
    workspace.runBuckCommand("build", target).assertSuccess();

    ZipInspector zipInspector =
        new ZipInspector(
            workspace.getPath(
                BuildTargetPaths.getGenPath(
                    filesystem.getBuckPaths(), BuildTargetFactory.newInstance(target), "%s.apk")));
    zipInspector.assertFileDoesNotExist("lib/armeabi-v7a/libcxx_in_app.so");
    zipInspector.assertFileDoesNotExist("lib/x86/libcxx_in_app.so");

    // Note that assets are packaged in the lib directory in an instrumentation APK
    zipInspector.assertFileDoesNotExist("lib/armeabi-v7a/libcxx_asset_in_app.so");
    zipInspector.assertFileDoesNotExist("lib/x86/libcxx_asset_in_app.so");

    zipInspector.assertFileDoesNotExist("lib/armeabi-v7a/libcxx_used_by_wrap_script_in_app.so");
    zipInspector.assertFileDoesNotExist("lib/x86/libcxx_used_by_wrap_script_in_app.so");

    zipInspector.assertFileDoesNotExist("lib/armeabi-v7a/libprebuilt.so");
    zipInspector.assertFileDoesNotExist("lib/x86/libprebuilt_asset.so");
    zipInspector.assertFileDoesNotExist("lib/armeabi-v7a/libprebuilt_has_wrap_script.so");
    zipInspector.assertFileDoesNotExist("lib/armeabi-v7a/wrap.sh");
  }
}
