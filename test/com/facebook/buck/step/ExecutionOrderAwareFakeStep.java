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

package com.facebook.buck.step;

import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.google.common.base.Preconditions;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fake implementation of {@link Step} which remembers the order in which it was executed (usually
 * relative to other {@link Step}s).
 */
public class ExecutionOrderAwareFakeStep implements Step {
  private final String shortName;
  private final String description;
  private final int exitCode;
  private final AtomicInteger atomicExecutionOrder;
  private OptionalInt executionBeginOrder;
  private OptionalInt executionEndOrder;

  public ExecutionOrderAwareFakeStep(
      String shortName, String description, int exitCode, AtomicInteger atomicExecutionOrder) {
    this.shortName = shortName;
    this.description = description;
    this.exitCode = exitCode;
    this.atomicExecutionOrder = atomicExecutionOrder;
    this.executionBeginOrder = OptionalInt.empty();
    this.executionEndOrder = OptionalInt.empty();
  }

  @Override
  public StepExecutionResult execute(StepExecutionContext context) {
    Preconditions.checkState(!executionBeginOrder.isPresent());
    Preconditions.checkState(!executionEndOrder.isPresent());
    executionBeginOrder = OptionalInt.of(atomicExecutionOrder.getAndIncrement());

    // Let any other threads execute so we can test running multiple
    // of these steps in a test which should never run in parallel and
    // confirm their execution order.
    Thread.yield();

    executionEndOrder = OptionalInt.of(atomicExecutionOrder.getAndIncrement());

    return StepExecutionResult.of(exitCode);
  }

  @Override
  public String getShortName() {
    return shortName;
  }

  @Override
  public String getDescription(StepExecutionContext context) {
    return description;
  }

  public OptionalInt getExecutionBeginOrder() {
    return executionBeginOrder;
  }

  public OptionalInt getExecutionEndOrder() {
    return executionEndOrder;
  }
}
