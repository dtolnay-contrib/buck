/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

package com.example;

import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

public class TimeoutChangesBehaviorTest {

  private Database database;

  @Before
  public void setUp() {
    database = new Database();
    database.beginTransaction();
  }

  @Test
  public void testTimeoutDictatesTheSuccessOfThisTest() throws InterruptedException {
    assertTrue(
        "Database should have an open transaction due to setUp().", database.isInTransaction());
  }

  private static class Database {
    /** This fake database is designed such that there is only one Transaction per thread. */
    private static ThreadLocal<Transaction> TX =
        new ThreadLocal<Transaction>() {
          @Override
          protected Transaction initialValue() {
            return new Transaction();
          }
        };

    public void beginTransaction() {
      TX.get().beginTransaction();
    }

    public boolean isInTransaction() {
      return TX.get().isInTransaction();
    }
  }

  private static class Transaction {

    private boolean isInTransaction = false;

    public void beginTransaction() {
      isInTransaction = true;
    }

    public boolean isInTransaction() {
      return isInTransaction;
    }
  }
}
