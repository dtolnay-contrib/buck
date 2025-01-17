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

package com.facebook.buck.intellij.ideabuck.config;

import com.google.common.base.Strings;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.configurations.PathEnvironmentVariableUtil;
import com.intellij.openapi.project.Project;
import com.intellij.util.EnvironmentUtil;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class BuckWSServerPortUtils {
  private static final String SEARCH_FOR = "http.port=";

  /** @return true if buckd is running */
  public static boolean hasBuckd(Project project) {
    if (project.getBasePath() == null) {
      return false;
    }
    Path pidFile = Paths.get(project.getBasePath(), ".buckd", "pid");
    if (Files.exists(pidFile)) {
      try {
        if (PathEnvironmentVariableUtil.findInPath("ps") != null) {
          String pid = new String(Files.readAllBytes(pidFile));
          Process process = new GeneralCommandLine("ps", "-p", pid).createProcess();
          process.waitFor();
          return process.exitValue() == 0;
        }
      } catch (IOException | ExecutionException | InterruptedException e) {
        return false;
      }
    }
    return false;
  }

  /** Returns the port number of Buck's HTTP server, if it can be determined. */
  public static int getPort(Project project, String path)
      throws NumberFormatException, IOException, ExecutionException {
    String exec = BuckExecutableSettingsProvider.getInstance(project).resolveBuckExecutable();

    if (Strings.isNullOrEmpty(exec)) {
      throw new RuntimeException("Buck executable is not defined in settings.");
    }

    GeneralCommandLine commandLine = new GeneralCommandLine();
    commandLine.setExePath(exec);
    commandLine.withWorkDirectory(path);
    commandLine.withEnvironment(EnvironmentUtil.getEnvironmentMap());
    commandLine.addParameter("server");
    commandLine.addParameter("status");
    commandLine.addParameter("--reuse-current-config");
    commandLine.addParameter("--http-port");
    commandLine.setRedirectErrorStream(true);

    Process p = commandLine.createProcess();
    BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));

    String line;
    while ((line = reader.readLine()) != null) {
      if (line.startsWith(SEARCH_FOR)) {
        return Integer.parseInt(line.substring(SEARCH_FOR.length()));
      }
    }
    throw new RuntimeException(
        "Configured buck executable did not report a valid port string,"
            + " ensure "
            + commandLine.getCommandLineString()
            + " can be run from "
            + path);
  }
}
