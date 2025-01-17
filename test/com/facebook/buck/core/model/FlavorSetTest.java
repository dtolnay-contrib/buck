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

package com.facebook.buck.core.model;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class FlavorSetTest {
  @Test
  public void toPostfixString() {
    assertEquals("", FlavorSet.of().toPostfixString());
    assertEquals("#aa", FlavorSet.of(UserFlavor.of("aa", "bb")).toPostfixString());
    assertEquals(
        "#aa,cc",
        FlavorSet.of(UserFlavor.of("aa", "bb"), UserFlavor.of("cc", "dd")).toPostfixString());
  }

  @Test
  public void toCommaSeparatedString() {
    assertEquals("", FlavorSet.of().toCommaSeparatedString());
    assertEquals("aa", FlavorSet.of(UserFlavor.of("aa", "bb")).toCommaSeparatedString());
    assertEquals(
        "aa,cc",
        FlavorSet.of(UserFlavor.of("aa", "bb"), UserFlavor.of("cc", "dd"))
            .toCommaSeparatedString());
  }
}
