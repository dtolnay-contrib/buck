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

package com.facebook.buck.event;

import com.facebook.buck.event.external.events.ConsoleEventExternalInterface;
import java.util.Objects;
import java.util.logging.Level;

/**
 * Event for messages. Post ConsoleEvents to the event bus where you would normally use {@code
 * java.util.logging}.
 */
public class ConsoleEvent extends AbstractBuckEvent implements ConsoleEventExternalInterface {

  private final Level level;
  private final boolean containsAnsiEscapeCodes;
  private final String message;

  protected ConsoleEvent(Level level, boolean containsAnsiEscapeCodes, String message) {
    super(EventKey.unique());
    this.level = level;
    this.containsAnsiEscapeCodes = containsAnsiEscapeCodes;
    this.message = Objects.requireNonNull(message);
  }

  public Level getLevel() {
    return level;
  }

  public boolean containsAnsiEscapeCodes() {
    return containsAnsiEscapeCodes;
  }

  @Override
  public String getMessage() {
    return message;
  }

  public static ConsoleEvent create(Level level, String message) {
    return new ConsoleEvent(level, /* containsAnsiEscapeCodes */ false, message);
  }

  public static ConsoleEvent create(Level level, String message, Object... args) {
    return ConsoleEvent.create(level, String.format(message, args));
  }

  public static ConsoleEvent createForMessageWithAnsiEscapeCodes(Level level, String message) {
    return new ConsoleEvent(level, /* containsAnsiEscapeCodes */ true, message);
  }

  public static ConsoleEvent finer(String message) {
    return ConsoleEvent.create(Level.FINER, message);
  }

  public static ConsoleEvent finer(String message, Object... args) {
    return ConsoleEvent.create(Level.FINER, message, args);
  }

  public static ConsoleEvent fine(String message) {
    return ConsoleEvent.create(Level.FINE, message);
  }

  public static ConsoleEvent fine(String message, Object... args) {
    return ConsoleEvent.create(Level.FINE, message, args);
  }

  public static ConsoleEvent info(String message) {
    return ConsoleEvent.create(Level.INFO, message);
  }

  public static ConsoleEvent info(String message, Object... args) {
    return ConsoleEvent.create(Level.INFO, message, args);
  }

  public static ConsoleEvent warning(String message) {
    return ConsoleEvent.create(Level.WARNING, message);
  }

  public static ConsoleEvent warning(String message, Object... args) {
    return ConsoleEvent.create(Level.WARNING, message, args);
  }

  public static ConsoleEvent severe(String message) {
    return ConsoleEvent.create(Level.SEVERE, message);
  }

  public static ConsoleEvent severe(String message, Object... args) {
    return ConsoleEvent.create(Level.SEVERE, message, args);
  }

  @Override
  public String getEventName() {
    return CONSOLE_EVENT;
  }

  @Override
  protected String getValueString() {
    return String.format("%s: %s", getLevel(), getMessage());
  }

  @Override
  public String toString() {
    return getMessage();
  }
}
