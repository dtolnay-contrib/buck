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

package com.facebook.buck.cli;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.util.environment.Platform;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

class TestUtils {

  static void assertBuildReport(
      ProjectWorkspace workspace,
      TemporaryPaths tmp,
      AbsPath buildReportPath,
      String expectedFileName)
      throws IOException {
    assertTrue(Files.exists(buildReportPath.getPath()));
    String randomNumberPlaceholder = "<RANDOM_NUMBER>";
    String outputPrefixPlaceholder = "<OUTPUT_PREFIX>";
    String extension = "sh";
    String fileSeparator = "/";
    if (Platform.detect() == Platform.WINDOWS) {
      extension = "cmd";
      fileSeparator = "\\\\";
    }
    String buildReportContents =
        new String(Files.readAllBytes(buildReportPath.getPath()), StandardCharsets.UTF_8);
    String buildReportContentsToReplaceWithOutputPrefix = buildReportContents;
    String buildReportContentsToReplaceWithRandomNumber = buildReportContents;

    int randomNumberReplacerStartIndex =
        buildReportContents.indexOf("/buck-out/tmp/genrule-".replace("/", fileSeparator));
    if (randomNumberReplacerStartIndex != -1) {
      buildReportContentsToReplaceWithOutputPrefix =
          buildReportContents.substring(0, randomNumberReplacerStartIndex);
      buildReportContentsToReplaceWithRandomNumber =
          buildReportContents.substring(randomNumberReplacerStartIndex);
    }

    buildReportContentsToReplaceWithOutputPrefix =
        buildReportContentsToReplaceWithOutputPrefix.replaceAll(
            "buck-out(.*[\\\\/])", outputPrefixPlaceholder);
    buildReportContentsToReplaceWithRandomNumber =
        buildReportContentsToReplaceWithRandomNumber.replaceFirst(
            "genrule-\\d+\\." + extension, "genrule-" + randomNumberPlaceholder + "." + extension);

    String expectedResult =
        String.format(
                workspace.getFileContents(expectedFileName),
                (tmp.getRoot().toString()
                        + "/buck-out/tmp/genrule-"
                        + randomNumberPlaceholder
                        + "."
                        + extension)
                    .replace("/", File.separator)
                    .replace(File.separator, fileSeparator))
            .replace("\r\n", "\n")
            .trim();

    buildReportContents =
        randomNumberReplacerStartIndex == -1
            ? buildReportContentsToReplaceWithOutputPrefix
            : buildReportContentsToReplaceWithOutputPrefix
                + buildReportContentsToReplaceWithRandomNumber;

    buildReportContents = buildReportContents.replace("\r\n", "\n").trim();

    assertEquals(expectedResult, buildReportContents);
  }
}
