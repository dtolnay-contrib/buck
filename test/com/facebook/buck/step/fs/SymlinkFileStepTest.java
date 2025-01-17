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

package com.facebook.buck.step.fs;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import com.google.common.io.Files;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class SymlinkFileStepTest {

  @Rule public final TemporaryFolder tmpDir = new TemporaryFolder();

  @Test
  public void testAbsoluteSymlinkFiles() throws IOException, InterruptedException {
    StepExecutionContext context = TestExecutionContext.newInstance();

    File source = tmpDir.newFile();
    Files.write("foobar", source, UTF_8);

    File target = tmpDir.newFile();
    target.delete();

    Path existingFile = Paths.get(source.getName());
    Path desiredLink = Paths.get(target.getName());
    Path root = tmpDir.getRoot().toPath();
    SymlinkFileStep step =
        SymlinkFileStep.of(
            TestProjectFilesystems.createProjectFilesystem(root), existingFile, desiredLink);
    step.execute(context);
    // Run twice to ensure we can overwrite an existing symlink
    step.execute(context);

    assertTrue(target.exists());
    assertEquals("foobar", Files.readFirstLine(target, UTF_8));

    // Modify the original file and see if the linked file changes as well.
    Files.write("new", source, UTF_8);
    assertEquals("new", Files.readFirstLine(target, UTF_8));

    assertEquals(
        "ln -f -s " + root.resolve(existingFile) + " " + root.resolve(desiredLink),
        step.getDescription(context));
  }

  @Test
  public void testReplaceMalformedSymlink() throws IOException, InterruptedException {
    Assume.assumeThat(Platform.detect(), not(Platform.WINDOWS));

    // Run `ln -s /path/that/does/not/exist dummy` in /tmp.
    Path root = tmpDir.getRoot().toPath();
    ProcessExecutorParams params =
        ProcessExecutorParams.builder()
            .setCommand(ImmutableList.of("ln", "-s", "/path/that/does/not/exist", "my_symlink"))
            .setDirectory(root)
            .build();
    ProcessExecutor executor = new DefaultProcessExecutor(Console.createNullConsole());
    executor.launchAndExecute(params);

    // Verify that the symlink points to a non-existent file.
    Path symlink = Paths.get(tmpDir.getRoot().getAbsolutePath(), "my_symlink");
    assertFalse(
        "exists() should reflect the existence of what the symlink points to",
        symlink.toFile().exists());
    assertTrue(
        "even though exists() is false, isSymbolicLink should be true",
        java.nio.file.Files.isSymbolicLink(symlink));

    // Create an ExecutionContext to return the ProjectFilesystem.
    ProjectFilesystem projectFilesystem = TestProjectFilesystems.createProjectFilesystem(root);
    StepExecutionContext executionContext = TestExecutionContext.newInstance();

    tmpDir.newFile("dummy");
    SymlinkFileStep symlinkStep =
        SymlinkFileStep.of(projectFilesystem, Paths.get("dummy"), Paths.get("my_symlink"));
    int exitCode = symlinkStep.execute(executionContext).getExitCode();
    assertEquals(0, exitCode);
    assertTrue(java.nio.file.Files.isSymbolicLink(symlink));
    assertTrue(symlink.toFile().exists());

    assertEquals(
        "ln -f -s " + root.resolve("dummy") + " " + root.resolve("my_symlink"),
        symlinkStep.getDescription(executionContext));
  }
}
