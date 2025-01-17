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

package com.facebook.buck.features.python.toolchain.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.features.python.PythonBuckConfig;
import com.facebook.buck.features.python.toolchain.PythonInterpreter;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableMap;
import java.io.File;
import java.io.IOException;
import org.junit.Rule;
import org.junit.Test;

public class PythonInterpreterFromConfigTest {

  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();

  @Rule public TemporaryPaths temporaryFolder2 = new TemporaryPaths();

  @Test
  public void whenToolsPythonIsExecutableFileThenItIsUsed() throws IOException {
    AbsPath configPythonFile = temporaryFolder.newExecutableFile("python");
    PythonBuckConfig config =
        new PythonBuckConfig(
            FakeBuckConfig.builder()
                .setSections(
                    ImmutableMap.of(
                        "python", ImmutableMap.of("interpreter", configPythonFile.toString())))
                .build());
    PythonInterpreter pythonInterpreter =
        new PythonInterpreterFromConfig(config, new ExecutableFinder());

    assertEquals(
        "Should return path to temp file.",
        configPythonFile.getPath(),
        pythonInterpreter.getPythonInterpreterPath(config.getDefaultSection()));
  }

  @Test
  public void whenPythonOnPathIsExecutableFileThenItIsUsed() throws IOException {
    temporaryFolder.newExecutableFile("python2");
    PythonBuckConfig config =
        new PythonBuckConfig(
            FakeBuckConfig.builder()
                .setEnvironment(
                    ImmutableMap.<String, String>builder()
                        .put("PATH", temporaryFolder.getRoot().toString())
                        .put("PATHEXT", "")
                        .build())
                .build());
    PythonInterpreter pythonInterpreter =
        new PythonInterpreterFromConfig(config, new ExecutableFinder());

    pythonInterpreter.getPythonInterpreterPath(config.getDefaultSection());
  }

  @Test
  public void whenPythonPlusExtensionOnPathIsExecutableFileThenItIsUsed() throws IOException {
    temporaryFolder.newExecutableFile("my-py.exe");
    PythonBuckConfig config =
        new PythonBuckConfig(
            FakeBuckConfig.builder()
                .setSections(ImmutableMap.of("python", ImmutableMap.of("interpreter", "my-py")))
                .setEnvironment(
                    ImmutableMap.<String, String>builder()
                        .put("PATH", temporaryFolder.getRoot().toString())
                        .put("PATHEXT", ".exe")
                        .build())
                .build());
    PythonInterpreter pythonInterpreter =
        new PythonInterpreterFromConfig(config, new ExecutableFinder(Platform.WINDOWS));

    pythonInterpreter.getPythonInterpreterPath(config.getDefaultSection());
  }

  @Test
  public void whenPython2OnPathThenItIsUsed() throws IOException {
    temporaryFolder.newExecutableFile("python");
    AbsPath python2 = temporaryFolder.newExecutableFile("python2");
    PythonBuckConfig config =
        new PythonBuckConfig(
            FakeBuckConfig.builder()
                .setEnvironment(
                    ImmutableMap.<String, String>builder()
                        .put("PATH", temporaryFolder.getRoot().toString())
                        .put("PATHEXT", "")
                        .build())
                .build());
    PythonInterpreter pythonInterpreter =
        new PythonInterpreterFromConfig(config, new ExecutableFinder());

    assertEquals(
        "Should return path to python2.",
        python2.getPath(),
        pythonInterpreter.getPythonInterpreterPath(config.getDefaultSection()));
  }

  @Test(expected = HumanReadableException.class)
  public void whenPythonOnPathNotFoundThenThrow() {
    PythonBuckConfig config =
        new PythonBuckConfig(
            FakeBuckConfig.builder()
                .setSections(
                    ImmutableMap.of("python", ImmutableMap.of("interpreter", "does-not-exist")))
                .setEnvironment(
                    ImmutableMap.<String, String>builder()
                        .put("PATH", temporaryFolder.getRoot().toString())
                        .put("PATHEXT", "")
                        .build())
                .build());
    PythonInterpreter pythonInterpreter =
        new PythonInterpreterFromConfig(config, new ExecutableFinder());

    pythonInterpreter.getPythonInterpreterPath(config.getDefaultSection());
    fail("Should throw an exception when Python isn't found.");
  }

  @Test
  public void whenMultiplePythonExecutablesOnPathFirstIsUsed() throws IOException {
    AbsPath pythonA = temporaryFolder.newExecutableFile("python2");
    temporaryFolder2.newExecutableFile("python2");
    String path = temporaryFolder.getRoot() + File.pathSeparator + temporaryFolder2.getRoot();
    PythonBuckConfig config =
        new PythonBuckConfig(
            FakeBuckConfig.builder()
                .setEnvironment(
                    ImmutableMap.<String, String>builder()
                        .put("PATH", path)
                        .put("PATHEXT", "")
                        .build())
                .build());
    PythonInterpreter pythonInterpreter =
        new PythonInterpreterFromConfig(config, new ExecutableFinder());

    assertEquals(
        "Should return the first path",
        pythonInterpreter.getPythonInterpreterPath(config.getDefaultSection()),
        pythonA.getPath());
  }
}
