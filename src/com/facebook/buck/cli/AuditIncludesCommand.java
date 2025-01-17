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

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.ForwardRelPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.parser.DefaultProjectBuildFileParserFactory;
import com.facebook.buck.parser.ParserPythonInterpreterProvider;
import com.facebook.buck.parser.api.ProjectBuildFileParser;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.core.JsonGenerator;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;

/**
 * Evaluates a build file and prints out a list of build file extensions included at parse time.
 * This commands is kind of like a buck query deps command, but for build files.
 */
public class AuditIncludesCommand extends AbstractCommand {

  @Option(name = "--json", usage = "Print JSON representation of each rule")
  private boolean json;

  @Argument private List<String> arguments = new ArrayList<>();

  public List<String> getArguments() {
    return arguments;
  }

  @Override
  public String getShortDescription() {
    return "List build file extensions imported at parse time.";
  }

  @Override
  public ExitCode runWithoutHelp(CommandRunnerParams params) throws Exception {
    ProjectFilesystem projectFilesystem = params.getCells().getRootCell().getFilesystem();
    try (ProjectBuildFileParser parser =
        new DefaultProjectBuildFileParserFactory(
                new DefaultTypeCoercerFactory(),
                new ParserPythonInterpreterProvider(
                    params.getCells().getRootCell().getBuckConfig(), params.getExecutableFinder()),
                params.getKnownRuleTypesProvider())
            .createFileParser(
                params.getBuckEventBus(),
                params.getCells().getRootCell(),
                params.getWatchman(),
                params.getEdenClient())) {
      PrintStream out = params.getConsole().getStdOut();
      for (String pathToBuildFile : getArguments()) {
        if (!json) {
          // Print a comment with the path to the build file.
          out.printf("# %s\n\n", pathToBuildFile);
        }

        // Resolve the path specified by the user.
        Path path = Paths.get(pathToBuildFile);

        AbsPath absPath = projectFilesystem.getRootPath().resolve(path);

        RelPath relPath = MorePaths.relativize(projectFilesystem.getRootPath(), absPath);
        if (relPath.startsWith("..")) {
          throw BuildFileParseException.createForUnknownParseError(
              "Path %s is not under cell root %s", path, projectFilesystem.getRootPath());
        }
        ForwardRelPath forwardRelPath = ForwardRelPath.ofRelPath(relPath);

        Iterable<String> includes = parser.getIncludedFiles(forwardRelPath);
        printIncludesToStdout(
            params, Objects.requireNonNull(includes, "__includes metadata entry is missing"));
      }
    }

    return ExitCode.SUCCESS;
  }

  @Override
  public boolean isReadOnly() {
    return true;
  }

  private void printIncludesToStdout(CommandRunnerParams params, Iterable<String> includes)
      throws IOException {

    PrintStream stdOut = params.getConsole().getStdOut();

    if (json) {
      // We create a new JsonGenerator that does not close the stream.
      try (JsonGenerator generator =
          ObjectMappers.createGenerator(stdOut).useDefaultPrettyPrinter()) {
        ObjectMappers.WRITER.writeValue(generator, includes);
      }
    } else {
      for (String include : includes) {
        stdOut.println(include);
      }
    }
  }
}
