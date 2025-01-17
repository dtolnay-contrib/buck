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

package com.facebook.buck.util;

import com.facebook.buck.core.util.log.Logger;
import com.google.common.base.Throwables;

public class Threads {

  private static final Logger LOG = Logger.get(Threads.class);

  /** Utility class: do not instantiate. */
  private Threads() {}

  public static Thread namedThread(String name, Runnable runnable) {
    Thread newThread = new Thread(runnable);
    newThread.setName(name);
    return newThread;
  }

  public static void interruptCurrentThread() {
    LOG.warn(
        "Current thread interrupted at this location: "
            + Throwables.getStackTraceAsString(new Throwable()));
    Thread.currentThread().interrupt();
  }

  public static void interruptThread(Thread thread) {
    LOG.warn(
        "Thread interrupted at this location: "
            + Throwables.getStackTraceAsString(new Throwable()));
    thread.interrupt();
  }
}
