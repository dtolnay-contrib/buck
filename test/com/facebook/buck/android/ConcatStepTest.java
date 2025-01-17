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

import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.step.TestExecutionContext;
import com.google.common.collect.ImmutableList;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ConcatStepTest {
  @Rule public TemporaryFolder temp = new TemporaryFolder();

  @Test
  public void testConcatFiles() throws IOException {
    // Create three files containing "foo", "bar", and "baz"
    // and see if they are correctly concatenated.
    File dest = temp.newFile();
    ImmutableList.Builder<Path> inputsBuilder = ImmutableList.builder();
    String[] fileContents = {"foo", "bar", "baz"};
    for (String fileContent : fileContents) {
      File src = temp.newFile();
      PrintStream out = new PrintStream(src);
      out.print(fileContent);
      inputsBuilder.add(src.toPath());
      out.close();
    }

    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(temp.getRoot().toPath());
    StepExecutionContext context = TestExecutionContext.newInstance();

    ConcatStep step = new ConcatStep(filesystem, inputsBuilder.build(), dest.toPath());
    step.execute(context);
    BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(dest)));
    assertEquals(reader.readLine(), "foobarbaz");

    reader.close();
  }
}
