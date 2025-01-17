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

package com.facebook.buck.step.impl;

import com.facebook.buck.core.artifact.Artifact;
import com.facebook.buck.core.artifact.ArtifactFilesystem;
import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.build.execution.context.actionid.ActionId;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.actions.AbstractAction;
import com.facebook.buck.core.rules.actions.Action;
import com.facebook.buck.core.rules.actions.ActionCreationException;
import com.facebook.buck.core.rules.actions.ActionRegistry;
import com.facebook.buck.core.rules.actions.ActionRegistryForTests;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.ProjectFilesystemFactory;
import com.facebook.buck.io.filesystem.impl.DefaultProjectFilesystemFactory;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.timing.FakeClock;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple helper class that makes running an {@link Action} easier and allows tests to focus on
 * exercising that logic, rather than boilerplate
 */
public class TestActionExecutionRunner {

  private static final boolean WITH_DOWNWARD_API = false;

  private final ProjectFilesystem projectFilesystem;
  private final ActionRegistryForTests actionFactory;
  private final ProjectFilesystemFactory projectFilesystemFactory;
  private final ProcessExecutor processExecutor;

  public TestActionExecutionRunner(
      ProjectFilesystemFactory projectFilesystemFactory,
      ProjectFilesystem projectFilesystem,
      BuildTarget buildTarget) {
    this.projectFilesystemFactory = projectFilesystemFactory;
    this.projectFilesystem = projectFilesystem;
    this.actionFactory = new ActionRegistryForTests(buildTarget);
    this.processExecutor = new DefaultProcessExecutor(TestConsole.createNullConsole());
  }

  public TestActionExecutionRunner(ProjectFilesystem projectFilesystem, BuildTarget buildTarget) {
    this(new DefaultProjectFilesystemFactory(), projectFilesystem, buildTarget);
  }

  public ActionRegistry getRegistry() {
    return actionFactory;
  }

  public Artifact declareArtifact(Path path) {
    return actionFactory.declareArtifact(path);
  }

  @BuckStyleValue
  public interface ExecutionDetails<T> {
    T getAction();

    StepExecutionResult getResult();
  }

  public <T extends AbstractAction> ExecutionDetails<T> runAction(T action)
      throws ActionCreationException, IOException {

    ActionExecutionStep step =
        new ActionExecutionStep(
            action, new ArtifactFilesystem(projectFilesystem), WITH_DOWNWARD_API);
    BuckEventBus testEventBus = BuckEventBusForTests.newInstance();
    BuckEventBusForTests.CapturingEventListener consoleEventListener =
        new BuckEventBusForTests.CapturingEventListener();
    testEventBus.register(consoleEventListener);

    AbsPath rootPath = projectFilesystem.getRootPath();
    StepExecutionResult executionResult =
        step.execute(
            StepExecutionContext.builder()
                .setConsole(Console.createNullConsole())
                .setBuckEventBus(testEventBus)
                .setPlatform(Platform.UNKNOWN)
                .setEnvironment(ImmutableMap.of())
                .setBuildCellRootPath(rootPath.getPath())
                .setProcessExecutor(processExecutor)
                .setProjectFilesystemFactory(projectFilesystemFactory)
                .setRuleCellRoot(rootPath)
                .setActionId(ActionId.of(action.getBuildTarget()))
                .setClock(FakeClock.doNotCare())
                .setWorkerToolPools(new ConcurrentHashMap<>())
                .build());

    return ImmutableExecutionDetails.ofImpl(action, executionResult);
  }
}
