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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

public class MoreAtomicsTest {

  @Test
  public void testSetMaxLong() {
    AtomicLong atomic = new AtomicLong(0L);
    long result = MoreAtomics.setMaxAndGet(1L, atomic);

    assertEquals(1L, result);
    assertEquals(1L, atomic.get());

    result = MoreAtomics.setMaxAndGet(0L, atomic);
    assertEquals(1L, result);
    assertEquals(1L, atomic.get());
  }

  @Test
  public void testSetMinLong() {
    AtomicLong atomic = new AtomicLong(1L);
    long result = MoreAtomics.setMinAndGet(0L, atomic);
    assertEquals(0L, result);
    assertEquals(0L, atomic.get());

    result = MoreAtomics.setMinAndGet(1L, atomic);
    assertEquals(0L, result);
    assertEquals(0L, atomic.get());
  }

  @Test
  public void testSetMaxLongConcurrent() throws InterruptedException {
    AtomicLong atomic = new AtomicLong(0L);

    ExecutorService executor = Executors.newFixedThreadPool(10);

    int oneCount = 1000;
    int twoCount = 1000;
    int threeCount = 1000;

    AtomicInteger successOneCounter = new AtomicInteger(0);
    AtomicInteger successTwoCounter = new AtomicInteger(0);
    AtomicInteger successThreeCounter = new AtomicInteger(0);
    CountDownLatch latch = new CountDownLatch(oneCount + twoCount + threeCount);

    runSetMax(executor, 1L, atomic, successOneCounter, oneCount, latch);
    runSetMax(executor, 3L, atomic, successThreeCounter, threeCount, latch);
    runSetMax(executor, 2L, atomic, successTwoCounter, twoCount, latch);

    // wait for all tasks to complete
    latch.await();

    executor.shutdown();
    if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
      executor.shutdownNow();
    }

    assertEquals(3L, atomic.get());

    // maximum value should be returned back the same anount of times it was requested
    assertEquals(threeCount, successThreeCounter.get());

    // non-maximum values could be set no more than once; it is possible they are not set at all
    // if greater number gets checked first by concurrent thread
    assertTrue(successOneCounter.get() <= oneCount);
    assertTrue(successTwoCounter.get() <= twoCount);
  }

  private static void runSetMax(
      ExecutorService executor,
      long value,
      AtomicLong atomic,
      AtomicInteger successCounter,
      int count,
      CountDownLatch latch) {
    for (int i = 0; i < count; i++) {
      executor.submit(
          () -> {
            if (MoreAtomics.setMaxAndGet(value, atomic) == value) {
              // count number of successful maximum updates
              successCounter.incrementAndGet();
            }
            latch.countDown();
          });
    }
  }
}
