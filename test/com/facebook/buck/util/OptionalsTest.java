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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.util.Optionals;
import com.google.common.collect.ImmutableSet;
import java.util.Optional;
import org.junit.Test;

/** Unit test for {@link Optionals}. */
public class OptionalsTest {

  @Test
  public void testAbsentItemNotAdded() {
    Optional<String> absent = Optional.empty();
    ImmutableSet.Builder<String> builder = ImmutableSet.builder();

    Optionals.addIfPresent(absent, builder);

    assertEquals(ImmutableSet.<String>of(), builder.build());
  }

  @Test
  public void testPresentItemAdded() {
    Optional<String> absent = Optional.of("Hello");
    ImmutableSet.Builder<String> builder = ImmutableSet.builder();

    Optionals.addIfPresent(absent, builder);

    assertEquals(ImmutableSet.of("Hello"), builder.build());
  }

  @Test
  public void testCompare() {
    assertThat(Optionals.<Integer>compare(Optional.empty(), Optional.empty()), equalTo(0));

    assertThat(Optionals.compare(Optional.empty(), Optional.of(1)), lessThan(0));
    assertThat(Optionals.compare(Optional.of(1), Optional.empty()), greaterThan(0));
    assertThat(Optionals.compare(Optional.of(1), Optional.of(2)), lessThan(0));
    assertThat(Optionals.compare(Optional.of(2), Optional.of(1)), greaterThan(0));
  }

  @Test
  public void firstOf() {
    assertThat(Optionals.firstOf(Optional.of(5), Optional.of(4)), equalTo(Optional.of(5)));
    assertThat(
        Optionals.firstOf(Optional.empty(), Optional.of(4), Optional.of(3)),
        equalTo(Optional.of(4)));
    assertThat(Optionals.firstOf(Optional.empty(), Optional.empty()), equalTo(Optional.empty()));
    assertThat(Optionals.firstOf(), equalTo(Optional.empty()));
  }
}
