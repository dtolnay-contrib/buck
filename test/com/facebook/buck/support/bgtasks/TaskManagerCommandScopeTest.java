/*
 * Copyright 2019-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.facebook.buck.support.bgtasks;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.core.model.BuildId;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import java.util.concurrent.Future;
import org.junit.Test;

public class TaskManagerCommandScopeTest {

  @Test
  public void taskManagerCommandScopeSchedulesTasksToManager() {
    TestBackgroundTaskManager testBackgroundTaskManager = TestBackgroundTaskManager.of();
    TaskManagerCommandScope scope =
        new TaskManagerCommandScope(testBackgroundTaskManager, new BuildId());
    BackgroundTask<?> task = ImmutableBackgroundTask.of("test", ignored -> fail(), new Object());
    scope.schedule(task);

    assertSame(
        task, Iterables.getOnlyElement(testBackgroundTaskManager.getScheduledTasks()).getTask());
  }

  @Test
  public void taskManagerCommandScopeStoresAllTasksForScope() {
    TestBackgroundTaskManager testBackgroundTaskManager = TestBackgroundTaskManager.of();
    TaskManagerCommandScope scope =
        new TaskManagerCommandScope(testBackgroundTaskManager, new BuildId());
    BackgroundTask<?> task1 = ImmutableBackgroundTask.of("test1", ignored -> fail(), new Object());
    BackgroundTask<?> task2 = ImmutableBackgroundTask.of("test2", ignored -> {}, new Object());
    scope.schedule(task1);
    scope.schedule(task2);

    ImmutableMap<BackgroundTask<?>, Future<Void>> scheduledTasks = scope.getScheduledTasksResults();

    assertTrue(scheduledTasks.containsKey(task1));
    assertTrue(scheduledTasks.containsKey(task2));

    assertFalse(scheduledTasks.get(task1).isDone());
    assertFalse(scheduledTasks.get(task2).isDone());
  }
}
