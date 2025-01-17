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

import static org.hamcrest.CoreMatchers.hasItem;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.android.relinker.Symbols;
import com.facebook.buck.android.toolchain.ndk.impl.AndroidNdkHelper;
import com.facebook.buck.android.toolchain.ndk.impl.AndroidNdkHelper.SymbolGetter;
import com.facebook.buck.android.toolchain.ndk.impl.AndroidNdkHelper.SymbolsAndDtNeeded;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.jvm.java.testutil.AbiCompilationModeTest;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.testutil.integration.ZipInspector;
import com.facebook.buck.util.ExitCode;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class AndroidApkNativeIntegrationTest extends AbiCompilationModeTest {

  @Rule public TemporaryPaths tmpFolder = new TemporaryPaths();

  @Rule public ExpectedException thrown = ExpectedException.none();

  private ProjectWorkspace workspace;

  private ProjectFilesystem filesystem;

  @Before
  public void setUp() throws IOException {
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            new AndroidApkNativeIntegrationTest(), "android_project", tmpFolder);
    workspace.setUp();
    AssumeAndroidPlatform.get(workspace).assumeSdkIsAvailable();
    AssumeAndroidPlatform.get(workspace).assumeNdkIsAvailable();
    setWorkspaceCompilationMode(workspace);
    filesystem = TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());
  }

  @Test
  public void testPrebuiltNativeLibraryIsIncluded() throws IOException {
    Path output = workspace.buildAndReturnOutput("//apps/sample:app_with_prebuilt_native_libs");
    ZipInspector zipInspector = new ZipInspector(output);
    zipInspector.assertFileExists("lib/armeabi/libprebuilt.so");
  }

  @Test
  public void testPrebuiltNativeLibraryAsAssetIsIncluded() throws IOException {
    Path output = workspace.buildAndReturnOutput("//apps/sample:app_with_prebuilt_native_libs");
    ZipInspector zipInspector = new ZipInspector(output);
    zipInspector.assertFileExists("assets/lib/armeabi/libprebuilt_asset.so");
  }

  @Test
  public void testWrapShIsIncluded() throws IOException {
    Path output = workspace.buildAndReturnOutput("//apps/sample:app_with_prebuilt_native_libs");
    ZipInspector zipInspector = new ZipInspector(output);
    zipInspector.assertFileExists("lib/armeabi/wrap.sh");
  }

  @Test
  public void testNativeLibraryMergeMap() throws IOException, InterruptedException {
    SymbolGetter syms = getSymbolGetter();

    workspace.replaceFileContents(".buckconfig", "#cpu_abis", "cpu_abis = x86");
    ImmutableMap<String, Path> paths =
        workspace.buildMultipleAndReturnOutputs(
            "//apps/sample:app_with_merge_map",
            "//apps/sample:app_with_merge_map_and_alternate_merge_glue",
            "//apps/sample:app_with_merge_map_and_alternate_merge_glue_and_localized_symbols",
            "//apps/sample:app_with_merge_map_modular");

    testNativeLibraryMergeMapGeneric(syms, paths);
    testNativeLibraryMergeMapAlternateMergeGlue(syms, paths);
    testNativeLibraryMergeMapAlternateMergeGlueAndLocalizedSymbols(syms, paths);
    testNativeLibraryMergeMapModular(paths);
  }

  private static void testNativeLibraryMergeMapGeneric(
      SymbolGetter syms, ImmutableMap<String, Path> paths)
      throws IOException, InterruptedException {
    Path apkPath = paths.get("//apps/sample:app_with_merge_map");
    ZipInspector zipInspector = new ZipInspector(apkPath);
    zipInspector.assertFileDoesNotExist("lib/x86/lib1a.so");
    zipInspector.assertFileDoesNotExist("lib/x86/lib1b.so");
    zipInspector.assertFileDoesNotExist("lib/x86/lib2e.so");
    zipInspector.assertFileDoesNotExist("lib/x86/lib2f.so");

    SymbolsAndDtNeeded info = syms.getSymbolsAndDtNeeded(apkPath, "lib/x86/lib1.so");
    assertThat(info.symbols.global, Matchers.hasItem("A"));
    assertThat(info.symbols.global, Matchers.hasItem("B"));
    assertThat(info.symbols.global, Matchers.hasItem("glue_1"));
    assertThat(info.symbols.global, not(Matchers.hasItem("glue_2")));
    assertThat(info.dtNeeded, Matchers.hasItem("libnative_merge_C.so"));
    assertThat(info.dtNeeded, Matchers.hasItem("libnative_merge_D.so"));
    assertThat(info.dtNeeded, not(Matchers.hasItem("libnative_merge_B.so")));

    info = syms.getSymbolsAndDtNeeded(apkPath, "lib/x86/libnative_merge_C.so");
    assertThat(info.symbols.global, Matchers.hasItem("C"));
    assertThat(info.symbols.global, Matchers.hasItem("static_func_C"));
    assertThat(info.symbols.global, not(Matchers.hasItem("glue_1")));
    assertThat(info.symbols.global, not(Matchers.hasItem("glue_2")));
    assertThat(info.dtNeeded, Matchers.hasItem("libnative_merge_D.so"));
    assertThat(info.dtNeeded, Matchers.hasItem("libprebuilt_for_C.so"));

    info = syms.getSymbolsAndDtNeeded(apkPath, "lib/x86/libnative_merge_D.so");
    assertThat(info.symbols.global, Matchers.hasItem("D"));
    assertThat(info.symbols.global, not(Matchers.hasItem("glue_1")));
    assertThat(info.symbols.global, not(Matchers.hasItem("glue_2")));
    assertThat(info.dtNeeded, Matchers.hasItem("lib2.so"));
    assertThat(info.dtNeeded, not(Matchers.hasItem("libnative_merge_E.so")));
    assertThat(info.dtNeeded, not(Matchers.hasItem("libnative_merge_F.so")));

    info = syms.getSymbolsAndDtNeeded(apkPath, "lib/x86/lib2.so");
    assertThat(info.symbols.global, Matchers.hasItem("E"));
    assertThat(info.symbols.global, Matchers.hasItem("F"));
    assertThat(info.symbols.global, Matchers.hasItem("static_func_F"));
    assertThat(info.symbols.global, Matchers.hasItem("glue_1"));
    assertThat(info.symbols.global, not(Matchers.hasItem("glue_2")));
    assertThat(info.dtNeeded, Matchers.hasItem("libprebuilt_for_F.so"));
  }

  private static void testNativeLibraryMergeMapAlternateMergeGlue(
      SymbolGetter syms, ImmutableMap<String, Path> paths)
      throws IOException, InterruptedException {
    Path otherPath = paths.get("//apps/sample:app_with_merge_map_and_alternate_merge_glue");
    SymbolsAndDtNeeded info = syms.getSymbolsAndDtNeeded(otherPath, "lib/x86/lib2.so");
    assertThat(info.symbols.global, not(Matchers.hasItem("glue_1")));
    assertThat(info.symbols.global, Matchers.hasItem("glue_2"));
    assertThat(info.dtNeeded, Matchers.hasItem("libprebuilt_for_F.so"));
  }

  private static void testNativeLibraryMergeMapAlternateMergeGlueAndLocalizedSymbols(
      SymbolGetter syms, ImmutableMap<String, Path> paths)
      throws IOException, InterruptedException {
    Path localizePath =
        paths.get(
            "//apps/sample:app_with_merge_map_and_alternate_merge_glue_and_localized_symbols");
    SymbolsAndDtNeeded info = syms.getSymbolsAndDtNeeded(localizePath, "lib/x86/lib2.so");
    assertThat(info.symbols.global, not(Matchers.hasItem("glue_1")));
    assertThat(info.symbols.global, not(Matchers.hasItem("glue_2")));
  }

  private static void testNativeLibraryMergeMapModular(ImmutableMap<String, Path> paths)
      throws IOException, InterruptedException {
    Path modularPath = paths.get("//apps/sample:app_with_merge_map_modular");
    ZipInspector modularZipInspector = new ZipInspector(modularPath);
    modularZipInspector.assertFileDoesNotExist("lib/x86/lib1a.so");
    modularZipInspector.assertFileDoesNotExist("lib/x86/lib1b.so");
    modularZipInspector.assertFileExists("lib/x86/libnative_merge_C.so");
    modularZipInspector.assertFileExists("lib/x86/libnative_merge_D.so");
    modularZipInspector.assertFileDoesNotExist("lib/x86/lib2e.so");
    modularZipInspector.assertFileDoesNotExist("lib/x86/lib2f.so");
    modularZipInspector.assertFileExists("assets/native.merge.A/libs.txt");
    modularZipInspector.assertFileExists("assets/native.merge.A/libs.xzs");
    modularZipInspector.assertFileDoesNotExist("lib/x86/lib1.so");
    modularZipInspector.assertFileExists("lib/x86/lib2.so");
  }

  @Test
  public void testMergeMapDisassembly() throws IOException, InterruptedException {
    workspace.replaceFileContents(".buckconfig", "#cpu_abis", "cpu_abis = x86");
    Path disassembly =
        workspace.buildAndReturnOutput("//apps/sample:disassemble_app_with_merge_map_gencode");
    List<String> disassembledLines = filesystem.readLines(disassembly);

    Pattern fieldPattern =
        Pattern.compile("^\\.field public static final ([^:]+):Ljava/lang/String; = \"([^\"]+)\"$");
    ImmutableMap.Builder<String, String> mapBuilder = ImmutableMap.builder();
    for (String line : disassembledLines) {
      Matcher m = fieldPattern.matcher(line);
      if (!m.matches()) {
        continue;
      }
      mapBuilder.put(m.group(1), m.group(2));
    }

    assertThat(
        mapBuilder.build(),
        Matchers.equalTo(
            ImmutableMap.of(
                "lib1a_so", "lib1_so",
                "lib1b_so", "lib1_so",
                "lib2e_so", "lib2_so",
                "lib2f_so", "lib2_so")));
  }

  @Test
  public void testMergeMapWithPlatformSpecificDeps() throws Exception {
    workspace.replaceFileContents(".buckconfig", "#cpu_abis", "cpu_abis = x86, armv7");
    Path apkPath =
        workspace.buildAndReturnOutput(
            "//apps/sample:app_with_merge_map_different_merged_libs_per_platform");

    ZipInspector zipInspector = new ZipInspector(apkPath);

    zipInspector.assertFileExists("lib/armeabi-v7a/liball.so");
    zipInspector.assertFileExists("lib/x86/liball.so");
  }

  @Test
  public void testMergeMapHeaderOnly() throws Exception {
    workspace.replaceFileContents(".buckconfig", "#cpu_abis", "cpu_abis = x86");
    Path apkPath = workspace.buildAndReturnOutput("//apps/sample:app_with_merge_map_header_only");

    ZipInspector zipInspector = new ZipInspector(apkPath);
    zipInspector.assertFileExists("lib/x86/liball.so");
  }

  @Test
  public void throwIfLibMergedIntoTwoTargets() {
    ProcessResult processResult =
        workspace.runBuckBuild(
            "//apps/sample:app_with_merge_map_merging_target_into_two_libraries");
    processResult.assertFailure();
    assertThat(
        processResult.getStderr(),
        allOf(containsString("attempted to merge"), containsString("into both")));
  }

  @Test
  public void throwIfMergedLibContainsAssetsAndNonAssets() {
    ProcessResult processResult =
        workspace.runBuckBuild("//apps/sample:app_with_cross_asset_merge_map");
    processResult.assertFailure();
    assertThat(
        processResult.getStderr(), containsString("contains both asset and non-asset libraries"));
  }

  @Test
  public void throwIfMergeMapHasCircularDependency() {
    ProcessResult processResult =
        workspace.runBuckBuild("//apps/sample:app_with_circular_merge_map");
    processResult.assertFailure();
    String stderr = processResult.getStderr();
    assertThat(stderr, containsString("Error: Dependency cycle detected"));
    assertThat(
        stderr,
        containsString(
            "Dependencies between merge:libbroken.so and no-merge://native/merge:D:\n"
                + "  //native/merge:C -> //native/merge:D\n"));
    // We need to split the assertion because the order in which
    // these occur in the output is not deterministic.
    assertThat(
        stderr,
        containsString(
            "Dependencies between no-merge://native/merge:D and merge:libbroken.so:\n"
                + "  //native/merge:D -> //native/merge:F"));
  }

  @Test
  public void throwIfMergeMapHasCircularDependencyIncludingPrecompiledHeader() {
    ProcessResult processResult =
        workspace.runBuckBuild(
            "//apps/sample:app_with_circular_merge_map_including_precompiled_header");
    processResult.assertFailure();
    String stderr = processResult.getStderr();
    assertThat(stderr, containsString("Error: Dependency cycle detected"));
    assertThat(
        stderr,
        containsString(
            "Dependencies between merge:libbroken.so and no-merge://native/merge:precompiled_for_D:\n"
                + "  //native/merge:D -> //native/merge:precompiled_for_D\n"));
    // We need to split the assertion because the order in which
    // these occur in the output is not deterministic.
    assertThat(
        stderr,
        containsString(
            "Dependencies between no-merge://native/merge:precompiled_for_D and merge:libbroken.so:\n"
                + "  //native/merge:precompiled_for_D -> //native/merge:F"));
  }

  @Test
  public void throwIfMergeMapHasCircularDependencyIncludeRoot() {
    ProcessResult processResult =
        workspace.runBuckBuild("//apps/sample:app_with_circular_merge_map_including_root");
    processResult.assertFailure();
    assertThat(processResult.getStderr(), containsString("Error: Dependency cycle detected"));
  }

  @Test
  public void throwIfMergedWithInvalidGlue() {
    ProcessResult processResult =
        workspace.runBuckBuild(
            "//apps/sample:app_with_merge_map_and_invalid_native_lib_merge_glue");
    processResult.assertExitCode(ExitCode.FATAL_GENERIC);
    assertThat(
        processResult.getStderr(),
        allOf(containsString("Native library merge glue"), containsString("is not linkable")));
  }

  @Test
  public void testNativeLibraryMergeSequence() throws IOException, InterruptedException {
    SymbolGetter syms = getSymbolGetter();

    workspace.replaceFileContents(".buckconfig", "#cpu_abis", "cpu_abis = x86");
    ImmutableMap<String, Path> paths =
        workspace.buildMultipleAndReturnOutputs(
            "//apps/sample:app_with_merge_sequence",
            "//apps/sample:app_with_merge_sequence_modular",
            "//apps/sample:app_with_redundant_merge_sequence",
            "//apps/sample:app_with_merge_sequence_and_exclusions",
            "//apps/sample:app_with_merge_sequence_and_root_non_root_root_module_dependencies");

    testNativeLibraryMergeSequenceGeneric(syms, paths);
    testNativeLibraryMergeSequenceModular(syms, paths);
    testNativeLibraryMergeSequenceRedundant(syms, paths);
    testNativeLibraryMergeSequenceWithExclusions(paths);
    testNativeLibraryMergeSequenceWithRootNonRootRootModuleDependencies(syms, paths);
  }

  private static void testNativeLibraryMergeSequenceGeneric(
      SymbolGetter syms, ImmutableMap<String, Path> paths)
      throws IOException, InterruptedException {
    Path apkPath = paths.get("//apps/sample:app_with_merge_sequence");
    ZipInspector zipInspector = new ZipInspector(apkPath);
    zipInspector.assertFileDoesNotExist("lib/x86/lib1a.so");
    zipInspector.assertFileDoesNotExist("lib/x86/lib1b.so");
    zipInspector.assertFileExists("lib/x86/libnative_merge_C.so");
    zipInspector.assertFileExists("lib/x86/libnative_merge_D.so");
    zipInspector.assertFileExists("lib/x86/lib2e.so");
    zipInspector.assertFileExists("lib/x86/lib2f.so");

    // Library 1: Root module linkables with no module dependencies
    SymbolsAndDtNeeded info = syms.getSymbolsAndDtNeeded(apkPath, "lib/x86/lib1.so");
    assertThat(info.symbols.global, Matchers.hasItem("A"));
    assertThat(info.symbols.global, Matchers.hasItem("B"));
    assertThat(info.symbols.global, not(Matchers.hasItem("C")));
    assertThat(info.symbols.global, not(Matchers.hasItem("D")));
    assertThat(info.symbols.global, Matchers.hasItem("glue_1"));
    assertThat(info.symbols.global, not(Matchers.hasItem("glue_2")));
    assertThat(info.dtNeeded, Matchers.hasItem("libnative_merge_C.so"));
    assertThat(info.dtNeeded, Matchers.hasItem("libnative_merge_D.so"));
  }

  private static void testNativeLibraryMergeSequenceModular(
      SymbolGetter syms, ImmutableMap<String, Path> paths)
      throws IOException, InterruptedException {
    Path apkPath = paths.get("//apps/sample:app_with_merge_sequence_modular");
    ZipInspector zipInspector = new ZipInspector(apkPath);
    zipInspector.assertFileDoesNotExist("lib/x86/lib1a.so");
    zipInspector.assertFileDoesNotExist("lib/x86/lib1b.so");
    zipInspector.assertFileDoesNotExist("lib/x86/libnative_merge_C.so");
    zipInspector.assertFileDoesNotExist("lib/x86/libnative_merge_D.so");
    zipInspector.assertFileDoesNotExist("lib/x86/lib2e.so");
    zipInspector.assertFileDoesNotExist("lib/x86/lib2f.so");
    zipInspector.assertFileExists("assets/native.merge.C/libs.txt");
    zipInspector.assertFileExists("assets/native.merge.C/libs.xzs");
    zipInspector.assertFileExists("assets/native.merge.D/libs.txt");
    zipInspector.assertFileExists("assets/native.merge.D/libs.xzs");
    zipInspector.assertFileExists("assets/native.merge.F/libs.txt");
    zipInspector.assertFileExists("assets/native.merge.F/libs.xzs");

    // Root of library 1: Root-module linkables depending on modules C, D, and F
    SymbolsAndDtNeeded info = syms.getSymbolsAndDtNeeded(apkPath, "lib/x86/lib1.so");
    assertThat(info.symbols.global, Matchers.hasItem("A"));
    assertThat(info.symbols.global, not(Matchers.hasItem("B")));
    assertThat(info.symbols.global, not(Matchers.hasItem("C")));
    assertThat(info.symbols.global, Matchers.hasItem("glue_1"));
    assertThat(info.symbols.global, not(Matchers.hasItem("glue_2")));
    assertThat(info.dtNeeded, Matchers.hasItem("lib1_1.so"));
    assertThat(info.dtNeeded, Matchers.hasItem("lib1_2.so"));
    assertThat(info.dtNeeded, not(Matchers.hasItem("lib1b.so")));
    assertThat(info.dtNeeded, not(Matchers.hasItem("libnative_merge_C.so")));

    // Library 1, sub-library 1: Module C linkables depending on modules D and F (C)
    zipInspector.assertFileContains("assets/native.merge.C/libs.txt", "lib1_1.so");

    // Library 1, sub-library 2: Root-module linkables depending on modules D and F
    info = syms.getSymbolsAndDtNeeded(apkPath, "lib/x86/lib1_2.so");
    assertThat(info.symbols.global, Matchers.hasItem("B"));
    assertThat(info.symbols.global, not(Matchers.hasItem("D")));
    assertThat(info.symbols.global, Matchers.hasItem("glue_1"));
    assertThat(info.symbols.global, not(Matchers.hasItem("glue_2")));
    assertThat(info.dtNeeded, Matchers.hasItem("lib1_3.so"));
    assertThat(info.dtNeeded, not(Matchers.hasItem("libnative_merge_D.so")));

    // Library 1, sub-library 3: Module D linkables depending on module F (D)
    zipInspector.assertFileContains("assets/native.merge.D/libs.txt", "lib1_3.so");

    // Library 1, sub-library 4: Module F linkables with no module dependencies (F)
    zipInspector.assertFileContains("assets/native.merge.F/libs.txt", "lib1_4.so");

    // Library 1, sub-library 5: Module D linkables with no module dependencies (E)
    zipInspector.assertFileContains("assets/native.merge.D/libs.txt", "lib1_5.so");
  }

  private static void testNativeLibraryMergeSequenceRedundant(
      SymbolGetter syms, ImmutableMap<String, Path> paths)
      throws IOException, InterruptedException {
    Path apkPath = paths.get("//apps/sample:app_with_redundant_merge_sequence");
    ZipInspector zipInspector = new ZipInspector(apkPath);
    zipInspector.assertFileDoesNotExist("lib/x86/lib1a.so");
    zipInspector.assertFileDoesNotExist("lib/x86/lib1b.so");
    zipInspector.assertFileExists("lib/x86/libnative_merge_C.so");
    zipInspector.assertFileExists("lib/x86/libnative_merge_D.so");
    zipInspector.assertFileExists("lib/x86/lib2e.so");
    zipInspector.assertFileExists("lib/x86/lib2f.so");

    // Library 1: Root module linkables with no module dependencies
    SymbolsAndDtNeeded info = syms.getSymbolsAndDtNeeded(apkPath, "lib/x86/lib1.so");
    assertThat(info.symbols.global, Matchers.hasItem("A"));
    assertThat(info.symbols.global, Matchers.hasItem("B"));
    assertThat(info.symbols.global, not(Matchers.hasItem("C")));
    assertThat(info.symbols.global, not(Matchers.hasItem("D")));
    assertThat(info.symbols.global, Matchers.hasItem("glue_1"));
    assertThat(info.symbols.global, not(Matchers.hasItem("glue_2")));
    assertThat(info.dtNeeded, Matchers.hasItem("libnative_merge_C.so"));
    assertThat(info.dtNeeded, Matchers.hasItem("libnative_merge_D.so"));

    // There is no library 2, as the higher-priority sequence entry completely covers its roots.
  }

  private static void testNativeLibraryMergeSequenceWithExclusions(ImmutableMap<String, Path> paths)
      throws IOException, InterruptedException {
    Path apkPath = paths.get("//apps/sample:app_with_merge_sequence_and_exclusions");
    ZipInspector zipInspector = new ZipInspector(apkPath);
    zipInspector.assertFileDoesNotExist("lib/x86/lib1a.so");
    zipInspector.assertFileDoesNotExist("lib/x86/lib1b.so");
    zipInspector.assertFileDoesNotExist("lib/x86/libnative_merge_C.so");
    zipInspector.assertFileDoesNotExist("lib/x86/libnative_merge_D.so");
    zipInspector.assertFileDoesNotExist("lib/x86/lib2e.so");
    zipInspector.assertFileDoesNotExist("lib/x86/lib2f.so");
    zipInspector.assertFileExists("assets/native.merge.A/libs.txt");
    zipInspector.assertFileExists("assets/native.merge.A/libs.xzs");

    // Root of library 1: Module A linkables with no unmerged dependents (A, C)
    zipInspector.assertFileContains("assets/native.merge.A/libs.txt", "lib1.so");

    // Library 1, sub-library 1: Module A linkables on which :B depends (D, E)
    zipInspector.assertFileContains("assets/native.merge.A/libs.txt", "lib1_1.so");

    // Library 1, sub-library 2: Module A linkables on which :B and :precompiled_for_D depend (F)
    zipInspector.assertFileContains("assets/native.merge.A/libs.txt", "lib1_2.so");

    // Unmerged library B
    zipInspector.assertFileContains("assets/native.merge.A/libs.txt", "lib1b.so");
  }

  private static void testNativeLibraryMergeSequenceWithRootNonRootRootModuleDependencies(
      SymbolGetter syms, ImmutableMap<String, Path> paths)
      throws IOException, InterruptedException {
    Path apkPath =
        paths.get(
            "//apps/sample:app_with_merge_sequence_and_root_non_root_root_module_dependencies");
    ZipInspector zipInspector = new ZipInspector(apkPath);
    zipInspector.assertFileDoesNotExist("lib/x86/libnative_merge_G.so");
    zipInspector.assertFileDoesNotExist("lib/x86/libnative_merge_H.so");
    zipInspector.assertFileDoesNotExist("lib/x86/libnative_merge_I.so");
    zipInspector.assertFileDoesNotExist("lib/x86/libnative_merge_J.so");
    zipInspector.assertFileDoesNotExist("lib/x86/libnative_merge_K.so");
    zipInspector.assertFileDoesNotExist("lib/x86/libnative_merge_L.so");
    zipInspector.assertFileDoesNotExist("lib/x86/libnative_merge_M.so");
    zipInspector.assertFileExists("assets/native.merge.G/libs.txt");
    zipInspector.assertFileExists("assets/native.merge.G/libs.xzs");

    // Root of library 1: Module G linkables with entry count 4
    zipInspector.assertFileContains("assets/native.merge.G/libs.txt", "lib1.so");

    // Library 1, sub-library 1: Root module linkables with module G entry count 3
    SymbolsAndDtNeeded info = syms.getSymbolsAndDtNeeded(apkPath, "lib/x86/lib1_1.so");
    assertThat(info.symbols.global, Matchers.hasItem("H"));
    assertThat(info.symbols.global, not(Matchers.hasItem("I")));
    assertThat(info.symbols.global, not(Matchers.hasItem("J")));
    assertThat(info.symbols.global, Matchers.hasItem("glue_1"));
    assertThat(info.symbols.global, not(Matchers.hasItem("glue_2")));
    assertThat(info.dtNeeded, Matchers.hasItem("lib1_2.so"));
    assertThat(info.dtNeeded, Matchers.hasItem("lib1_3.so"));

    // Library 1, sub-library 2: Module G linkables with entry count 3
    zipInspector.assertFileContains("assets/native.merge.G/libs.txt", "lib1_2.so");

    // Library 1, sub-library 3: Root module linkables with module G entry count 2
    info = syms.getSymbolsAndDtNeeded(apkPath, "lib/x86/lib1_3.so");
    assertThat(info.symbols.global, Matchers.hasItem("J"));
    assertThat(info.symbols.global, not(Matchers.hasItem("K")));
    assertThat(info.symbols.global, not(Matchers.hasItem("L")));
    assertThat(info.symbols.global, Matchers.hasItem("glue_1"));
    assertThat(info.symbols.global, not(Matchers.hasItem("glue_2")));
    assertThat(info.dtNeeded, Matchers.hasItem("lib1_4.so"));
    assertThat(info.dtNeeded, Matchers.hasItem("lib1_5.so"));

    // Library 1, sub-library 4: Module G linkables with entry count 2
    zipInspector.assertFileContains("assets/native.merge.G/libs.txt", "lib1_4.so");

    // Library 1, sub-library 5: Root module linkables with module G entry count 1
    info = syms.getSymbolsAndDtNeeded(apkPath, "lib/x86/lib1_5.so");
    assertThat(info.symbols.global, Matchers.hasItem("L"));
    assertThat(info.symbols.global, not(Matchers.hasItem("M")));
    assertThat(info.symbols.global, Matchers.hasItem("glue_1"));
    assertThat(info.symbols.global, not(Matchers.hasItem("glue_2")));
    assertThat(info.dtNeeded, Matchers.hasItem("lib1_6.so"));

    // Library 1, sub-library 6: Module G linkables with entry count 1
    zipInspector.assertFileContains("assets/native.merge.G/libs.txt", "lib1_6.so");
  }

  @Test
  public void testMergeMapWithLibraryUsedByWrapScript() {
    String targetString = "//apps/sample:app_with_merge_map_lib_used_by_wrap_script";
    ProcessResult processResult = workspace.runBuckBuild(targetString);
    processResult.assertExitCode(ExitCode.BUILD_ERROR);
    Pattern errorPattern =
        Pattern.compile(
            String.format(
                ".*^Error: When processing %s, attempted to merge //native/merge:P\\[android-.+?\\]"
                    + " used by wrap script into merge:libNOPE\\.so$.*",
                targetString),
            Pattern.DOTALL | Pattern.MULTILINE);
    assertThat(processResult.getStderr(), matchesPattern(errorPattern));
  }

  @Test
  public void testMergeSequenceWithLibraryUsedByWrapScript()
      throws IOException, InterruptedException {
    Path apk =
        workspace.buildAndReturnOutput(
            "//apps/sample:app_with_merge_sequence_lib_used_by_wrap_script");
    ZipInspector zipInspector = new ZipInspector(apk);
    zipInspector.assertFileExists("lib/x86/libNO.so");
    zipInspector.assertFileDoesNotExist("lib/x86/libN.so");
    zipInspector.assertFileDoesNotExist("lib/x86/libO.so");
    zipInspector.assertFileExists("lib/x86/libP.so");

    SymbolGetter syms = getSymbolGetter();
    SymbolsAndDtNeeded info = syms.getSymbolsAndDtNeeded(apk, "lib/x86/libNO.so");
    assertThat(info.symbols.global, Matchers.hasItem("N"));
    assertThat(info.symbols.global, Matchers.hasItem("O"));
    assertThat(info.symbols.global, not(Matchers.hasItem("P")));
    assertThat(info.dtNeeded, not(Matchers.hasItem("libO.so")));
    assertThat(info.dtNeeded, Matchers.hasItem("libP.so"));

    info = syms.getSymbolsAndDtNeeded(apk, "lib/x86/libP.so");
    assertThat(info.symbols.global, Matchers.hasItem("P"));
  }

  @Test
  public void throwIfBothMergeArgumentsSpecified() {
    String targetString = "//apps/sample:app_with_merge_map_and_merge_sequence";
    ProcessResult processResult = workspace.runBuckBuild(targetString);
    processResult.assertExitCode(ExitCode.BUILD_ERROR);
    assertThat(
        processResult.getStderr(),
        containsString(
            targetString
                + " specifies mutually exclusive native_library_merge_map "
                + "and native_library_merge_sequence arguments"));
  }

  @Test
  public void testNativeRelinker() throws IOException, InterruptedException {
    SymbolGetter syms = getSymbolGetter();
    Symbols sym;

    Path apkPath = workspace.buildAndReturnOutput("//apps/sample:app_xdso_dce");

    sym = syms.getDynamicSymbols(apkPath, "lib/x86/libnative_xdsodce_top.so");
    assertTrue(sym.global.contains("_Z10JNI_OnLoadii"));
    assertTrue(sym.undefined.contains("_Z10midFromTopi"));
    assertTrue(sym.undefined.contains("_Z10botFromTopi"));
    assertFalse(sym.all.contains("_Z6unusedi"));

    sym = syms.getDynamicSymbols(apkPath, "lib/x86/libnative_xdsodce_mid.so");
    assertTrue(sym.global.contains("_Z10midFromTopi"));
    assertTrue(sym.undefined.contains("_Z10botFromMidi"));
    assertFalse(sym.all.contains("_Z6unusedi"));

    sym = syms.getDynamicSymbols(apkPath, "lib/x86/libnative_xdsodce_bot.so");
    assertTrue(sym.global.contains("_Z10botFromTopi"));
    assertTrue(sym.global.contains("_Z10botFromMidi"));
    assertFalse(sym.all.contains("_Z6unusedi"));

    // Run some verification on the same apk with native_relinker disabled.
    apkPath = workspace.buildAndReturnOutput("//apps/sample:app_no_xdso_dce");

    sym = syms.getDynamicSymbols(apkPath, "lib/x86/libnative_xdsodce_top.so");
    assertTrue(sym.all.contains("_Z6unusedi"));

    sym = syms.getDynamicSymbols(apkPath, "lib/x86/libnative_xdsodce_mid.so");
    assertTrue(sym.all.contains("_Z6unusedi"));

    sym = syms.getDynamicSymbols(apkPath, "lib/x86/libnative_xdsodce_bot.so");
    assertTrue(sym.all.contains("_Z6unusedi"));
  }

  @Test
  public void testNativeRelinkerWhitelist() throws IOException, InterruptedException {
    SymbolGetter syms = getSymbolGetter();
    Symbols sym;

    Path apkPath = workspace.buildAndReturnOutput("//apps/sample:app_xdso_dce");

    // The test data has "^_Z12preserved(Bot|Mid)v$" as the only whitelist pattern, so
    // we don't expect preservedTop to survive.
    sym = syms.getDynamicSymbols(apkPath, "lib/x86/libnative_xdsodce_top.so");
    assertFalse(sym.all.contains("_Z12preservedTopv"));

    sym = syms.getDynamicSymbols(apkPath, "lib/x86/libnative_xdsodce_mid.so");
    assertTrue(sym.global.contains("_Z12preservedMidv"));

    sym = syms.getDynamicSymbols(apkPath, "lib/x86/libnative_xdsodce_bot.so");
    assertTrue(sym.global.contains("_Z12preservedBotv"));
  }

  @Test
  public void testNativeRelinkerModular() throws IOException, InterruptedException {
    SymbolGetter syms = getSymbolGetter();
    Symbols sym;

    Path apkPath = workspace.buildAndReturnOutput("//apps/sample:app_xdso_dce_modular");

    sym =
        syms.getXzsSymbols(
            apkPath,
            "libnative_xdsodce_top.so",
            "assets/native.xdsodce.top/libs.xzs",
            "assets/native.xdsodce.top/libs.txt");
    assertTrue(sym.global.contains("_Z10JNI_OnLoadii"));
    assertTrue(sym.undefined.contains("_Z10midFromTopi"));
    assertTrue(sym.undefined.contains("_Z10botFromTopi"));
    assertFalse(sym.all.contains("_Z6unusedi"));

    sym = syms.getDynamicSymbols(apkPath, "lib/x86/libnative_xdsodce_mid.so");
    assertTrue(sym.global.contains("_Z10midFromTopi"));
    assertTrue(sym.undefined.contains("_Z10botFromMidi"));
    assertFalse(sym.all.contains("_Z6unusedi"));

    sym = syms.getDynamicSymbols(apkPath, "lib/x86/libnative_xdsodce_bot.so");
    assertTrue(sym.global.contains("_Z10botFromTopi"));
    assertTrue(sym.global.contains("_Z10botFromMidi"));
    assertFalse(sym.all.contains("_Z6unusedi"));
  }

  @Test
  public void testUnstrippedNativeLibraries() throws IOException, InterruptedException {
    workspace.enableDirCache();
    String app = "//apps/sample:app_with_static_symbols";
    String usnl = app + "#unstripped_native_libraries";
    ImmutableMap<String, Path> outputs = workspace.buildMultipleAndReturnOutputs(app, usnl);

    SymbolGetter syms = getSymbolGetter();
    Symbols strippedSyms =
        syms.getNormalSymbols(outputs.get(app), "lib/x86/libnative_cxx_symbols.so");
    assertThat(strippedSyms.all, Matchers.empty());

    workspace.runBuckCommand("clean", "--keep-cache").assertSuccess();
    workspace.runBuckBuild(usnl);

    String unstrippedPath = null;
    for (String line : filesystem.readLines(workspace.buildAndReturnOutput(usnl))) {
      if (line.matches(".*x86.*cxx_symbols.*")) {
        unstrippedPath = line.trim();
      }
    }
    if (unstrippedPath == null) {
      Assert.fail("Couldn't find path to our x86 library.");
    }

    Symbols unstrippedSyms =
        syms.getNormalSymbolsFromFile(filesystem.resolve(unstrippedPath).getPath());
    assertThat(unstrippedSyms.global, hasItem("get_value"));
    if (AssumeAndroidPlatform.get(workspace).isGnuStlAvailable()) {
      assertThat(unstrippedSyms.all, hasItem("supply_value"));
    } else {
      assertThat(unstrippedSyms.all, hasItem("ndk_version"));
    }
  }

  @Test
  public void testMergeMapAndSupportedPlatforms() throws IOException, InterruptedException {
    Path output =
        workspace.buildAndReturnOutput("//apps/sample:app_with_merge_map_and_supported_platforms");

    SymbolGetter symGetter = getSymbolGetter();
    Symbols syms;

    syms = symGetter.getDynamicSymbols(output, "lib/x86/liball.so");
    assertThat(syms.global, hasItem("_Z3foov"));
    assertThat(syms.global, hasItem("x86_only_function"));
    syms = symGetter.getDynamicSymbols(output, "lib/armeabi-v7a/liball.so");
    assertThat(syms.global, hasItem("_Z3foov"));
    assertThat(syms.global, not(hasItem("x86_only_function")));
  }

  @Test
  public void testMergeMapAndPrecompiledHeader() throws Exception {
    AssumeAndroidPlatform.get(workspace).assumeSdkIsAvailable();
    ProcessResult result = workspace.runBuckBuild("//apps/sample:app_with_merge_map_and_pch");
    result.assertSuccess();
  }

  @Test
  public void testLibcxxUsesCorrectUnwinder() throws IOException, InterruptedException {
    String target = "//apps/sample:app_with_exceptions";
    Path output =
        workspace.buildAndReturnOutput(
            "-c",
            "ndk.compiler=clang",
            "-c",
            "ndk.cxx_runtime=libcxx",
            "-c",
            "ndk.cxx_runtime_type=static",
            // libcxx depends on posix_memalign, which doesn't exist in libc.so in
            // the default app_platform (android-16)
            "-c",
            "ndk.app_platform=android-21",
            target);

    SymbolGetter symGetter = getSymbolGetter();
    Symbols syms =
        symGetter.getDynamicSymbols(output, "lib/armeabi-v7a/libnative_cxx_lib-with-exceptions.so");

    // Test target throws an exception, which involves a call to __cxa_throw.
    assertTrue(syms.all.contains("__cxa_throw"));

    // For 32-bit ARM the NDK makes use of two unwinders: libgcc and LLVM's libunwind. Exception
    // handling in libcxx depends on libunwind, so we need to make sure that unwind methods from
    // libgcc are not inadvertently linked into the target binary. For more info see
    // https://android.googlesource.com/platform/ndk/+/master/docs/BuildSystemMaintainers.md#Unwinding
    assertFalse(syms.all.contains("__gnu_Unwind_RaiseException"));
  }

  private SymbolGetter getSymbolGetter() throws IOException {
    return AndroidNdkHelper.getSymbolGetter(filesystem, tmpFolder);
  }
}
