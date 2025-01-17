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

import static com.facebook.buck.testutil.MoreAsserts.assertIterablesEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.android.resources.filter.GetStringsFiles;
import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Rule;
import org.junit.Test;

public class GetStringsFilesStepTest {
  @Rule public TemporaryPaths temporaryPaths = new TemporaryPaths();

  @Test
  public void testStringFileOrderIsMaintained() throws Exception {
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(temporaryPaths.getRoot());
    StepExecutionContext context = TestExecutionContext.newInstance();
    ImmutableSet<Path> paths =
        ImmutableSet.of(
            Paths.get("test/res/values/strings.xml"),
            Paths.get("test/res/values-es/strings.xml"),
            Paths.get("test/res/values-es-rES/strings.xml"),
            Paths.get("test2/res/values/strings.xml"),
            Paths.get("test2/res/values-es/strings.xml"),
            Paths.get("test2/res/values-es-rES/strings.xml"),
            Paths.get("test3/res/values/strings.xml"),
            Paths.get("test3/res/values-es/strings.xml"),
            Paths.get("test3/res/values-es-rES/strings.xml"),
            Paths.get("test3/res/values/dimens.xml"));

    for (Path path : paths) {
      filesystem.createParentDirs(path);
      filesystem.createNewFile(path);
    }

    ImmutableList.Builder<Path> stringFilesBuilder = ImmutableList.builder();
    GetStringsFilesStep step =
        new GetStringsFilesStep(
            filesystem,
            ImmutableList.of(Paths.get("test3"), Paths.get("test"), Paths.get("test2")),
            stringFilesBuilder);

    assertEquals(0, step.execute(context).getExitCode());

    ImmutableList<Path> expectedStringFiles =
        ImmutableList.of(
            Paths.get("test3/res/values/strings.xml"),
            Paths.get("test3/res/values-es/strings.xml"),
            Paths.get("test3/res/values-es-rES/strings.xml"),
            Paths.get("test/res/values/strings.xml"),
            Paths.get("test/res/values-es/strings.xml"),
            Paths.get("test/res/values-es-rES/strings.xml"),
            Paths.get("test2/res/values/strings.xml"),
            Paths.get("test2/res/values-es/strings.xml"),
            Paths.get("test2/res/values-es-rES/strings.xml"));

    assertIterablesEquals(expectedStringFiles, stringFilesBuilder.build());
  }

  @Test
  public void testStringsPathRegex() {
    assertTrue(matchesRegex("res/values-es/strings.xml"));
    assertTrue(matchesRegex("res/values/strings.xml"));
    assertFalse(matchesRegex("res/values-/strings.xml"));
    assertTrue(matchesRegex("/res/values-es/strings.xml"));
    assertFalse(matchesRegex("rootres/values-es/strings.xml"));
    assertTrue(matchesRegex("root/res/values-es-rUS/strings.xml"));
  }

  private static boolean matchesRegex(String input) {
    return GetStringsFiles.STRINGS_FILE_PATH.matcher(input).matches();
  }
}
