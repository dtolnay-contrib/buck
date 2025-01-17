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

package com.facebook.buck.util.concurrent;

import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Convenience wrapper around {@link java.util.concurrent.locks.ReentrantReadWriteLock} that, when
 * combined with try-with-resources pattern, automatically unlocks the lock once the {@code try}
 * section is completed.
 *
 * <p>Usage example:
 *
 * <pre>
 *   AutoCloseableReadWriteLock lock = new AutoCloseableReadWriteLock();
 *   try (AutoCloseableLock readLock = lock.readLock()) {
 *     // no other thread can acquire write lock while this section is running
 *   }
 *   // the readLock is automatically unlocked
 * </pre>
 *
 * </pre>
 */
public class AutoCloseableReadWriteLock {

  private final ReentrantReadWriteLock reentrantReadWriteLock;

  public AutoCloseableReadWriteLock() {
    reentrantReadWriteLock = new ReentrantReadWriteLock();
  }

  public AutoCloseableReadLocked lockRead() {
    return AutoCloseableReadLocked.createFor(reentrantReadWriteLock.readLock());
  }

  public AutoCloseableWriteLocked lockWrite() {
    return AutoCloseableWriteLocked.createFor(reentrantReadWriteLock.writeLock());
  }
}
