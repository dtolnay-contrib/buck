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

package com.facebook.buck.util.types;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Optional;
import java.util.function.Function;
import org.junit.Test;

public class EitherTest {

  @Test
  public void is() {
    assertTrue("Left isLeft", Either.ofLeft(new Object()).isLeft());
    assertFalse("Left !isRight", Either.ofLeft(new Object()).isRight());

    assertTrue("Right isRight", Either.ofRight(new Object()).isRight());
    assertFalse("Right !isLeft", Either.ofRight(new Object()).isLeft());
  }

  @Test
  public void get() {
    Object value = new Object();

    assertSame("Left getLeft returns value", value, Either.ofLeft(value).getLeft());
    assertSame("Right getRight returns value", value, Either.ofRight(value).getRight());
  }

  @Test
  public void getOption() {
    assertEquals(Optional.empty(), Either.ofRight("a").getLeftOption());
    assertEquals(Optional.empty(), Either.ofLeft("a").getRightOption());
    assertEquals(Optional.of("a"), Either.ofRight("a").getRightOption());
    assertEquals(Optional.of("a"), Either.ofLeft("a").getLeftOption());
  }

  @Test
  public void equality() {
    Object value = new Object();
    assertEquals("Left equals left", Either.ofLeft(value), Either.ofLeft(value));
    assertEquals("Right equals right", Either.ofRight(value), Either.ofRight(value));
    assertNotEquals("Left !equals right", Either.ofLeft(value), Either.ofRight(value));
  }

  @Test
  public void transform() {
    Function<String, String> throwingTransformer =
        x -> {
          throw new RuntimeException(x);
        };
    assertEquals(
        "Left is transformed via left function",
        "ab",
        Either.<String, String>ofLeft("a").transform(x -> x + "b", throwingTransformer));
    assertEquals(
        "Right is transformed via right function",
        "ab",
        Either.<String, String>ofRight("a").transform(throwingTransformer, x -> x + "b"));
  }

  @Test
  public void hash() {
    Object value = new Object();
    assertEquals(
        "left instances of same value hash the same",
        Either.ofLeft(value).hashCode(),
        Either.ofLeft(value).hashCode());
    assertEquals(
        "right instances of same value hash the same",
        Either.ofRight(value).hashCode(),
        Either.ofRight(value).hashCode());
    assertNotEquals(
        "left and right instances hash differently even when holding identical object",
        Either.ofLeft(value).hashCode(),
        Either.ofRight(value).hashCode());
  }

  @Test(expected = IllegalStateException.class)
  public void getLeftOfRightThrows() {
    Either.ofRight(new Object()).getLeft();
  }

  @Test(expected = IllegalStateException.class)
  public void getRightOfLeftThrows() {
    Either.ofLeft(new Object()).getRight();
  }

  @Test
  public void mapLeft() {
    Either<Integer, Object> left = Either.ofLeft(1);
    assertSame(
        left,
        left.mapRight(
            i -> {
              throw new AssertionError();
            }));
    assertEquals(Either.ofLeft("1"), Either.ofLeft(1).mapLeft(Object::toString));
  }

  @Test
  public void mapRight() {
    Either<Object, Integer> right = Either.ofRight(1);
    assertSame(
        right,
        right.mapLeft(
            i -> {
              throw new AssertionError();
            }));
    assertEquals(Either.ofRight("1"), Either.ofRight(1).mapRight(Object::toString));
  }
}
