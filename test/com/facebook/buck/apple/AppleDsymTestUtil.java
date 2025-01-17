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

package com.facebook.buck.apple;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import org.hamcrest.Matchers;

public class AppleDsymTestUtil {
  private static final String MAIN = "main";

  private AppleDsymTestUtil() {}

  public static void checkDsymFileHasDebugSymbolForMain(
      ProjectWorkspace workspace, AbsPath dwarfPath) throws IOException, InterruptedException {
    checkDsymFileHasDebugSymbol(MAIN, workspace, dwarfPath);
  }

  public static void checkDsymFileHasDebugSymbol(
      String symbolName, ProjectWorkspace workspace, AbsPath dwarfPath)
      throws IOException, InterruptedException {
    checkDsymFileHasDebugSymbolForConcreteArchitectures(
        symbolName, workspace, dwarfPath, Optional.empty());
  }

  public static void checkDsymFileHasDebugSymbolsForMainForConcreteArchitectures(
      ProjectWorkspace workspace, AbsPath dwarfPath, Optional<ImmutableList<String>> architectures)
      throws IOException, InterruptedException {
    checkDsymFileHasDebugSymbolForConcreteArchitectures(MAIN, workspace, dwarfPath, architectures);
  }

  public static void checkDsymFileHasDebugSymbolForConcreteArchitectures(
      String symbolName,
      ProjectWorkspace workspace,
      AbsPath dwarfPath,
      Optional<ImmutableList<String>> architectures)
      throws IOException, InterruptedException {
    String dwarfdumpMainStdout =
        workspace
            .runCommand("dwarfdump", "-n", symbolName, dwarfPath.toString())
            .getStdout()
            .orElse("");

    int expectedMatchCount = 1;
    if (architectures.isPresent()) {
      expectedMatchCount = architectures.get().size();
      for (String arch : architectures.get()) {
        assertThat(dwarfdumpMainStdout, Matchers.containsString(arch));
      }
    }

    assertThat(countMatches(dwarfdumpMainStdout, "AT_name"), Matchers.equalTo(expectedMatchCount));
    assertThat(
        countMatches(dwarfdumpMainStdout, "AT_decl_file"), Matchers.equalTo(expectedMatchCount));
    assertThat(
        countMatches(dwarfdumpMainStdout, "AT_decl_line"), Matchers.equalTo(expectedMatchCount));
  }

  private static int countMatches(String str, String sub) {
    if (sub.equals("")) {
      return 0;
    }
    if (str == null) {
      return 0;
    }
    int count = 0;
    int idx = 0;
    while ((idx = str.indexOf(sub, idx)) != -1) {
      count++;
      idx += sub.length();
    }
    return count;
  }

  public static void checkDsymFileHasSection(
      String segname, String sectname, ProjectWorkspace workspace, Path dwarfPath)
      throws IOException, InterruptedException {
    String otoolStdout =
        workspace
            .runCommand("otool", "-s", segname, sectname, dwarfPath.toString())
            .getStdout()
            .orElse("");

    String contentsStr = "Contents of (" + segname + "," + sectname + ") section";
    assertThat(otoolStdout.contains(contentsStr), is(true));
  }
}
