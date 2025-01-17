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

import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.google.common.base.Preconditions;
import java.util.regex.Pattern;
import org.immutables.value.Value;

/**
 * A class that holds a pattern and a message related to this pattern. Example usage: if the pattern
 * matches, throw the message.
 */
@BuckStyleValue
public abstract class PatternAndMessage {

  public abstract Pattern getPattern();

  public abstract String getMessage();

  @Value.Check
  protected void check() {
    Preconditions.checkArgument(getPattern().toString().compareTo("*") != 0);
    Preconditions.checkArgument(getMessage() != null && !getMessage().isEmpty());
  }

  public static PatternAndMessage of(Pattern pattern, String message) {
    return ImmutablePatternAndMessage.ofImpl(pattern, message);
  }
}
