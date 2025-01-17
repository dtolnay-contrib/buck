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

package com.facebook.buck.shell;

import static com.facebook.buck.util.string.MoreStrings.linesToText;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.step.isolatedsteps.shell.ShellStepDelegate;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.Escaper;
import com.facebook.buck.util.FakeProcess;
import com.facebook.buck.util.FakeProcessExecutor;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.eventbus.Subscribe;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import org.junit.Test;

public class ShellStepTest {

  private static final ImmutableList<String> ARGS = ImmutableList.of("bash", "-c", "echo $V1 $V2");

  private static final ImmutableMap<String, String> ENV =
      ImmutableMap.of(
          "V1", "two words",
          "V2", "$foo'bar'");

  private static final Path PATH = Paths.get("/tmp/a b");
  private static final String ERROR_MSG =
      linesToText("some syntax error", "compilation failed", "");
  private static final String OUTPUT_MSG = "processing data..." + System.lineSeparator();
  private static final int EXIT_FAILURE = 1;
  private static final int EXIT_SUCCESS = 0;

  private static StepExecutionContext createContext(
      ImmutableMap<ProcessExecutorParams, FakeProcess> processes, Console console) {
    StepExecutionContext context =
        TestExecutionContext.newBuilder()
            .setConsole(console)
            .setProcessExecutor(new FakeProcessExecutor(processes, console))
            .build();

    context
        .getBuckEventBus()
        .register(
            new Object() {
              @Subscribe
              public void logEvent(ConsoleEvent event) throws IOException {
                if (event.getLevel().equals(Level.WARNING)) {
                  console.getStdErr().write(event.getMessage().getBytes(StandardCharsets.UTF_8));
                }
              }
            });

    return context;
  }

  private static ProcessExecutorParams createParams() {
    return ProcessExecutorParams.builder().setCommand(ImmutableList.of("test")).build();
  }

  private static ShellStep createCommand(
      ImmutableMap<String, String> env, ImmutableList<String> cmd, Path workingDirectory) {
    return createCommand(
        env,
        cmd,
        workingDirectory,
        /* shouldPrintStdErr */ false,
        /* shouldRecordStdOut */ false,
        /* stdin */ Optional.empty());
  }

  private static ShellStep createCommand(boolean shouldPrintStdErr, boolean shouldPrintStdOut) {
    return createCommand(
        ENV, ARGS, null, shouldPrintStdErr, shouldPrintStdOut, /* stdin */ Optional.empty());
  }

  private static ShellStep createCommand(
      ImmutableMap<String, String> env,
      ImmutableList<String> cmd,
      Path workingDirectory,
      boolean shouldPrintStdErr,
      boolean shouldPrintStdOut,
      Optional<String> stdin) {
    workingDirectory =
        workingDirectory == null ? Paths.get(".").toAbsolutePath().normalize() : workingDirectory;

    return new ShellStep(
        new ShellStepDelegate(workingDirectory, false, Logger.get("ShellStepTest")) {
          @Override
          public ImmutableMap<String, String> getEnvironmentVariables(Platform platform) {
            return env;
          }

          @Override
          public void writeStdin(OutputStream stream) throws IOException {
            if (stdin.isPresent()) {
              stream.write(stdin.get().getBytes(StandardCharsets.UTF_8));
            }
          }

          @Override
          public boolean shouldPrintStderr(Verbosity verbosity) {
            return shouldPrintStdErr;
          }

          @Override
          public boolean shouldPrintStdout(Verbosity verbosity) {
            return shouldPrintStdOut;
          }
        }) {

      @Override
      public String getShortName() {
        return cmd.get(0);
      }

      @Override
      protected ImmutableList<String> getShellCommandInternal(StepExecutionContext context) {
        return cmd;
      }
    };
  }

  @Test
  public void testDescriptionWithEnvironment() {
    Path workingDirectory = Paths.get(".").toAbsolutePath().normalize();
    ShellStep command = createCommand(ENV, ARGS, null);
    StepExecutionContext context =
        TestExecutionContext.newBuilder().setProcessExecutor(new FakeProcessExecutor()).build();
    String template =
        Platform.detect() == Platform.WINDOWS
            ? "(cd %s && V1=\"two words\" V2=$foo'bar' bash -c \"echo $V1 $V2\")"
            : "(cd %s && V1='two words' V2='$foo'\\''bar'\\''' bash -c 'echo $V1 $V2')";
    assertEquals(
        String.format(template, Escaper.escapeAsBashString(workingDirectory)),
        command.getDescription(context));
  }

  @Test
  public void testDescriptionWithEnvironmentAndPath() {
    ShellStep command = createCommand(ENV, ARGS, PATH);
    StepExecutionContext context =
        TestExecutionContext.newBuilder().setProcessExecutor(new FakeProcessExecutor()).build();
    String template =
        Platform.detect() == Platform.WINDOWS
            ? "(cd %s && V1=\"two words\" V2=$foo'bar' bash -c \"echo $V1 $V2\")"
            : "(cd %s && V1='two words' V2='$foo'\\''bar'\\''' bash -c 'echo $V1 $V2')";
    assertEquals(
        String.format(template, Escaper.escapeAsBashString(PATH)), command.getDescription(context));
  }

  @Test
  public void testDescriptionWithPath() {
    ShellStep command = createCommand(ImmutableMap.of(), ARGS, PATH);
    StepExecutionContext context =
        TestExecutionContext.newBuilder().setProcessExecutor(new FakeProcessExecutor()).build();
    String template =
        Platform.detect() == Platform.WINDOWS
            ? "(cd %s && bash -c \"echo $V1 $V2\")"
            : "(cd %s && bash -c 'echo $V1 $V2')";
    assertEquals(
        String.format(template, Escaper.escapeAsBashString(PATH)), command.getDescription(context));
  }

  @Test
  public void testDescription() {

    Path workingDirectory = Paths.get(".").toAbsolutePath().normalize();
    ShellStep command = createCommand(ImmutableMap.of(), ARGS, null);
    StepExecutionContext context =
        TestExecutionContext.newBuilder().setProcessExecutor(new FakeProcessExecutor()).build();
    String expectedDescription =
        Platform.detect() == Platform.WINDOWS
            ? "(cd %s && bash -c \"echo $V1 $V2\")"
            : "(cd %s && bash -c 'echo $V1 $V2')";
    assertEquals(
        String.format(expectedDescription, Escaper.escapeAsBashString(workingDirectory)),
        command.getDescription(context));
  }

  @Test
  public void testStdErrPrintedOnErrorIfNotSilentEvenIfNotShouldPrintStdErr() throws Exception {
    ShellStep command = createCommand(/*shouldPrintStdErr*/ false, /*shouldPrintStdOut*/ false);
    ProcessExecutorParams params = createParams();
    FakeProcess process = new FakeProcess(EXIT_FAILURE, OUTPUT_MSG, ERROR_MSG);
    TestConsole console = new TestConsole(Verbosity.STANDARD_INFORMATION);
    StepExecutionContext context = createContext(ImmutableMap.of(params, process), console);
    command.launchAndInteractWithProcess(context, params, Optional.empty());
    assertEquals(ERROR_MSG, console.getTextWrittenToStdErr());
  }

  @Test
  public void testStdErrPrintedOnErrorIfShouldPrintStdErrEvenIfSilent() throws Exception {
    ShellStep command = createCommand(/*shouldPrintStdErr*/ true, /*shouldPrintStdOut*/ false);
    ProcessExecutorParams params = createParams();
    FakeProcess process = new FakeProcess(EXIT_FAILURE, OUTPUT_MSG, ERROR_MSG);
    TestConsole console = new TestConsole(Verbosity.SILENT);
    StepExecutionContext context = createContext(ImmutableMap.of(params, process), console);
    command.launchAndInteractWithProcess(context, params, Optional.empty());
    assertEquals(ERROR_MSG, console.getTextWrittenToStdErr());
  }

  @Test
  public void testStdErrNotPrintedOnSuccessIfNotShouldPrintStdErr() throws Exception {
    ShellStep command = createCommand(/*shouldPrintStdErr*/ false, /*shouldPrintStdOut*/ false);
    ProcessExecutorParams params = createParams();
    FakeProcess process = new FakeProcess(EXIT_SUCCESS, OUTPUT_MSG, ERROR_MSG);
    TestConsole console = new TestConsole(Verbosity.STANDARD_INFORMATION);
    StepExecutionContext context = createContext(ImmutableMap.of(params, process), console);
    command.launchAndInteractWithProcess(context, params, Optional.empty());
    assertEquals("", console.getTextWrittenToStdErr());
  }

  @Test
  public void testStdErrPrintedOnSuccessIfShouldPrintStdErrEvenIfSilent() throws Exception {
    ShellStep command = createCommand(/*shouldPrintStdErr*/ true, /*shouldPrintStdOut*/ false);
    ProcessExecutorParams params = createParams();
    FakeProcess process = new FakeProcess(EXIT_SUCCESS, OUTPUT_MSG, ERROR_MSG);
    TestConsole console = new TestConsole(Verbosity.SILENT);
    StepExecutionContext context = createContext(ImmutableMap.of(params, process), console);
    command.launchAndInteractWithProcess(context, params, Optional.empty());
    assertEquals(ERROR_MSG, console.getTextWrittenToStdErr());
  }

  @Test
  public void testStdOutNotPrintedIfNotShouldRecordStdoutEvenIfVerbose() throws Exception {
    ShellStep command = createCommand(/*shouldPrintStdErr*/ false, /*shouldPrintStdOut*/ false);
    ProcessExecutorParams params = createParams();
    FakeProcess process = new FakeProcess(EXIT_SUCCESS, OUTPUT_MSG, ERROR_MSG);
    TestConsole console = new TestConsole(Verbosity.ALL);
    StepExecutionContext context = createContext(ImmutableMap.of(params, process), console);
    command.launchAndInteractWithProcess(context, params, Optional.empty());
    assertEquals("", console.getTextWrittenToStdErr());
  }

  @Test
  public void processEnvironmentIsUnionOfContextAndStepEnvironments() {
    ShellStep command = createCommand(/*shouldPrintStdErr*/ false, /*shouldPrintStdOut*/ false);
    StepExecutionContext context =
        TestExecutionContext.newBuilder()
            .setEnvironment(
                ImmutableMap.of(
                    "CONTEXT_ENVIRONMENT_VARIABLE", "CONTEXT_VALUE", "PWD", "/wrong-pwd"))
            .build();
    Path pwd = Paths.get("/right-pwd");
    Map<String, String> subProcessEnvironment = new HashMap<>();
    subProcessEnvironment.put("PROCESS_ENVIRONMENT_VARIABLE", "PROCESS_VALUE");
    command.setProcessEnvironment(
        context,
        subProcessEnvironment,
        pwd.toString(),
        command.getEnvironmentVariables(context.getPlatform()));
    Map<String, String> actualProcessEnvironment = new HashMap<>(context.getEnvironment());
    actualProcessEnvironment.remove("PWD");
    assertEquals(
        "Sub-process environment should be union of client and step environments.",
        subProcessEnvironment,
        ImmutableMap.<String, String>builder()
            .putAll(actualProcessEnvironment)
            .put("PWD", pwd.toString())
            .putAll(ENV)
            .build());
  }

  @Test
  public void testStdinGetsToProcessWhenPresent() throws Exception {
    Optional<String> stdin = Optional.of("hello world!");
    ShellStep command =
        createCommand(
            ImmutableMap.of(),
            ImmutableList.of("cat", "-"),
            null,
            /*shouldPrintStdErr*/ true,
            /*shouldPrintStdOut*/ true,
            stdin);
    ProcessExecutorParams params = createParams();
    FakeProcess process = new FakeProcess(EXIT_SUCCESS, OUTPUT_MSG, ERROR_MSG);
    TestConsole console = new TestConsole(Verbosity.ALL);
    StepExecutionContext context = createContext(ImmutableMap.of(params, process), console);
    command.launchAndInteractWithProcess(context, params, stdin.map(ProcessExecutor.Stdin::of));
    assertEquals(stdin.get(), process.getOutput());
  }

  @Test
  public void testStdinDoesNotGetToProcessWhenAbsent() throws Exception {
    Optional<String> stdin = Optional.empty();
    ShellStep command =
        createCommand(
            ImmutableMap.of(),
            ImmutableList.of("cat", "-"),
            null,
            /*shouldPrintStdErr*/ true,
            /*shouldPrintStdOut*/ true,
            stdin);
    ProcessExecutorParams params = createParams();
    FakeProcess process = new FakeProcess(EXIT_SUCCESS, OUTPUT_MSG, ERROR_MSG);
    TestConsole console = new TestConsole(Verbosity.ALL);
    StepExecutionContext context = createContext(ImmutableMap.of(params, process), console);
    command.launchAndInteractWithProcess(context, params, stdin.map(ProcessExecutor.Stdin::of));
    assertEquals("", process.getOutput());
  }
}
