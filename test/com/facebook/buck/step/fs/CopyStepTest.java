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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

public class CopyStepTest {

  @Test
  public void testGetShellCommandInternalPath() {
    Path source = Paths.get("path/to/source.txt");
    Path destination = Paths.get("path/to/destination.txt");
    CopyStep copyCommand = CopyStep.forFile(source, destination);
    assertEquals(source, copyCommand.getSource());
    assertEquals(destination, copyCommand.getDestination());
    assertFalse(copyCommand.isRecursive());
  }

  @Test
  public void testGetShellCommandInternal() {
    Path source = Paths.get("path/to/source.txt");
    Path destination = Paths.get("path/to/destination.txt");
    CopyStep copyCommand = CopyStep.forFile(source, destination);
    assertEquals(source, copyCommand.getSource());
    assertEquals(destination, copyCommand.getDestination());
    assertFalse(copyCommand.isRecursive());
  }

  @Test
  public void testGetShellCommandInternalWithRecurse() {
    Path source = Paths.get("path/to/source");
    Path destination = Paths.get("path/to/destination");
    CopyStep copyCommand =
        CopyStep.forDirectory(source, destination, CopyStep.DirectoryMode.CONTENTS_ONLY);
    assertEquals(source, copyCommand.getSource());
    assertEquals(destination, copyCommand.getDestination());
    assertTrue(copyCommand.isRecursive());
  }

  @Test
  public void testGetShortName() {
    CopyStep copyCommand = CopyStep.forFile(Paths.get("here"), Paths.get("there"));
    assertEquals("delegated_cp", copyCommand.getShortName());
  }
}
