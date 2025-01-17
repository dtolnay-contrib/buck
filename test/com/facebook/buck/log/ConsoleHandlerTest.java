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

package com.facebook.buck.log;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertNotNull;

import com.facebook.buck.testutil.FakeOutputStream;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.Before;
import org.junit.Test;

/** Unit tests for {@link ConsoleHandler}. */
public class ConsoleHandlerTest {
  private FakeOutputStream outputStream;
  private ConcurrentHashMap<Long, String> threadIdToCommandId;
  private ConcurrentHashMap<String, ConsoleHandlerState.Writer> commandIdToConsoleWriter;
  private ConcurrentHashMap<String, Level> commandIdToLevel;
  private ConsoleHandlerState state;

  @Before
  public void setUp() {
    outputStream = new FakeOutputStream();
    threadIdToCommandId = new ConcurrentHashMap<>();
    commandIdToConsoleWriter = new ConcurrentHashMap<>();
    commandIdToLevel = new ConcurrentHashMap<>();

    state =
        new ConsoleHandlerState() {
          @Override
          public Level getLogLevel(String commandId) {
            return commandIdToLevel.get(commandId);
          }

          @Override
          public ConsoleHandlerState.Writer getWriter(String commandId) {
            return commandIdToConsoleWriter.get(commandId);
          }

          @Override
          public Iterable<ConsoleHandlerState.Writer> getAllAvailableWriters() {
            return commandIdToConsoleWriter.values();
          }

          @Override
          public String threadIdToCommandId(long threadId) {
            return threadIdToCommandId.get(threadId);
          }
        };
  }

  // We use a custom formatter so the test doesn't depend on locale, clock, or timezone.
  private static class MessageOnlyFormatter extends Formatter {
    @Override
    public String format(LogRecord record) {
      return record.getMessage();
    }
  }

  @Test
  public void consoleHandlerDoesNotWriteBelowLevelToStream() {
    ConsoleHandler handler =
        new ConsoleHandler(
            ConsoleHandler.utf8OutputStreamWriter(outputStream),
            new MessageOnlyFormatter(),
            Level.INFO,
            state);
    publishAndFlush(handler, new LogRecord(Level.FINE, "Shh.."));
    assertThat(outputStream.size(), equalTo(0));
  }

  @Test
  public void consoleHandlerWritesAtLevelToStream() throws IOException {
    ConsoleHandler handler =
        new ConsoleHandler(
            ConsoleHandler.utf8OutputStreamWriter(outputStream),
            new MessageOnlyFormatter(),
            Level.INFO,
            state);
    publishAndFlush(handler, new LogRecord(Level.INFO, "Hello"));
    assertThat(outputStream.toString("UTF-8"), equalTo("Hello"));
  }

  @Test
  public void consoleHandlerDoesNotFlushBelowSevere() {
    ConsoleHandler handler =
        new ConsoleHandler(
            ConsoleHandler.utf8OutputStreamWriter(outputStream),
            new MessageOnlyFormatter(),
            Level.INFO,
            state);
    handler.publish(new LogRecord(Level.INFO, "Info"));
    assertThat(outputStream.getLastFlushSize(), equalTo(0));
  }

  @Test
  public void consoleHandlerFlushesSevere() {
    ConsoleHandler handler =
        new ConsoleHandler(
            ConsoleHandler.utf8OutputStreamWriter(outputStream),
            new MessageOnlyFormatter(),
            Level.INFO,
            state);
    handler.publish(new LogRecord(Level.SEVERE, "Severe"));
    assertThat(outputStream.getLastFlushSize(), equalTo(6));
  }

  @Test
  public void consoleHandlerCanChangeOutputStreamWithoutClosing() throws IOException {
    FakeOutputStream outputStream1 = new FakeOutputStream();
    FakeOutputStream outputStream2 = new FakeOutputStream();
    ConsoleHandler handler =
        new ConsoleHandler(
            ConsoleHandler.utf8OutputStreamWriter(outputStream1),
            new MessageOnlyFormatter(),
            Level.INFO,
            state);
    publishAndFlush(handler, new LogRecord(Level.INFO, "Stream 1"));
    assertThat(outputStream1.toString("UTF-8"), equalTo("Stream 1"));

    threadIdToCommandId.put(49152L, "commandIdForOutputStream2");
    registerOutputStream("commandIdForOutputStream2", outputStream2);
    assertThat(outputStream1.isClosed(), equalTo(false));

    publishAndFlush(handler, newLogRecordWithThreadId(Level.INFO, "Stream 2", 49152));
    assertThat(outputStream1.toString("UTF-8"), equalTo("Stream 1"));
    assertThat(outputStream2.toString("UTF-8"), equalTo("Stream 2"));

    unregisterOutputStream("commandIdForOutputStream2");
    assertThat(outputStream2.isClosed(), equalTo(false));

    publishAndFlush(handler, new LogRecord(Level.INFO, " - DONE"));
    assertThat(outputStream1.toString("UTF-8"), equalTo("Stream 1 - DONE"));
    assertThat(outputStream2.toString("UTF-8"), equalTo("Stream 2"));
  }

  private void unregisterOutputStream(String commandId) {
    assertNotNull(
        String.format("Command id [%s] never had a registered writer.", commandId),
        commandIdToConsoleWriter.remove(commandId));
  }

  private void registerOutputStream(String commandId, FakeOutputStream outputStream) {
    commandIdToConsoleWriter.put(commandId, ConsoleHandler.utf8OutputStreamWriter(outputStream));
  }

  @Test
  public void logRecordsOnlyGoToRegisteredOutputStream() throws IOException {
    FakeOutputStream outputStream1 = new FakeOutputStream();
    FakeOutputStream outputStream2 = new FakeOutputStream();
    FakeOutputStream outputStream3 = new FakeOutputStream();
    ConsoleHandler handler =
        new ConsoleHandler(
            ConsoleHandler.utf8OutputStreamWriter(outputStream1),
            new MessageOnlyFormatter(),
            Level.INFO,
            state);

    threadIdToCommandId.put(49152L, "commandIdForOutputStream2");
    threadIdToCommandId.put(64738L, "commandIdForOutputStream3");
    registerOutputStream("commandIdForOutputStream2", outputStream2);
    registerOutputStream("commandIdForOutputStream3", outputStream3);

    publishAndFlush(handler, newLogRecordWithThreadId(Level.INFO, "Stream 2", 49152));
    assertThat(outputStream1.toString("UTF-8"), equalTo(""));
    assertThat(outputStream2.toString("UTF-8"), equalTo("Stream 2"));
    assertThat(outputStream3.toString("UTF-8"), equalTo(""));

    publishAndFlush(handler, newLogRecordWithThreadId(Level.INFO, "Stream 3", 64738));
    assertThat(outputStream1.toString("UTF-8"), equalTo(""));
    assertThat(outputStream2.toString("UTF-8"), equalTo("Stream 2"));
    assertThat(outputStream3.toString("UTF-8"), equalTo("Stream 3"));
  }

  @Test
  public void logRecordPublishedWithMultipleThreadIdsForSingleCommandId() throws IOException {
    FakeOutputStream outputStream1 = new FakeOutputStream();
    FakeOutputStream outputStream2 = new FakeOutputStream();
    FakeOutputStream outputStream3 = new FakeOutputStream();
    ConsoleHandler handler =
        new ConsoleHandler(
            ConsoleHandler.utf8OutputStreamWriter(outputStream1),
            new MessageOnlyFormatter(),
            Level.INFO,
            state);

    threadIdToCommandId.put(49152L, "commandIdForOutputStream2");
    threadIdToCommandId.put(49153L, "commandIdForOutputStream2");
    threadIdToCommandId.put(64738L, "commandIdForOutputStream3");
    registerOutputStream("commandIdForOutputStream2", outputStream2);
    registerOutputStream("commandIdForOutputStream3", outputStream3);

    publishAndFlush(handler, newLogRecordWithThreadId(Level.INFO, "Stream 2", 49152));
    assertThat(outputStream1.toString("UTF-8"), equalTo(""));
    assertThat(outputStream2.toString("UTF-8"), equalTo("Stream 2"));
    assertThat(outputStream3.toString("UTF-8"), equalTo(""));

    publishAndFlush(handler, newLogRecordWithThreadId(Level.INFO, " - Another Stream 2", 49153));
    assertThat(outputStream1.toString("UTF-8"), equalTo(""));
    assertThat(outputStream2.toString("UTF-8"), equalTo("Stream 2 - Another Stream 2"));
    assertThat(outputStream3.toString("UTF-8"), equalTo(""));
  }

  @Test
  public void previouslyRegisteredOutputStreamCanBeOverridden() throws IOException {
    FakeOutputStream outputStream1 = new FakeOutputStream();
    FakeOutputStream outputStream2 = new FakeOutputStream();
    FakeOutputStream outputStream3 = new FakeOutputStream();
    ConsoleHandler handler =
        new ConsoleHandler(
            ConsoleHandler.utf8OutputStreamWriter(outputStream1),
            new MessageOnlyFormatter(),
            Level.INFO,
            state);

    threadIdToCommandId.put(49152L, "commandIdForOutputStream2");
    registerOutputStream("commandIdForOutputStream2", outputStream2);

    publishAndFlush(handler, newLogRecordWithThreadId(Level.INFO, "Stream 2", 49152));
    assertThat(outputStream1.toString("UTF-8"), equalTo(""));
    assertThat(outputStream2.toString("UTF-8"), equalTo("Stream 2"));

    registerOutputStream("commandIdForOutputStream2", outputStream3);

    publishAndFlush(handler, newLogRecordWithThreadId(Level.INFO, "Stream 3", 49152));
    assertThat(outputStream1.toString("UTF-8"), equalTo(""));
    assertThat(outputStream2.toString("UTF-8"), equalTo("Stream 2"));
    assertThat(outputStream3.toString("UTF-8"), equalTo("Stream 3"));
  }

  @Test
  public void levelOverrideAppliesOnlyToRegisteredStream() throws IOException {
    FakeOutputStream outputStream1 = new FakeOutputStream();
    FakeOutputStream outputStream2 = new FakeOutputStream();
    FakeOutputStream outputStream3 = new FakeOutputStream();
    ConsoleHandler handler =
        new ConsoleHandler(
            ConsoleHandler.utf8OutputStreamWriter(outputStream1),
            new MessageOnlyFormatter(),
            Level.INFO,
            state);

    threadIdToCommandId.put(49152L, "commandIdForOutputStream2");
    threadIdToCommandId.put(64738L, "commandIdForOutputStream3");
    registerOutputStream("commandIdForOutputStream2", outputStream2);
    registerOutputStream("commandIdForOutputStream3", outputStream3);

    publishAndFlush(handler, newLogRecordWithThreadId(Level.FINE, "Shh..", 49152));
    assertThat(outputStream1.toString("UTF-8"), equalTo(""));
    assertThat(outputStream2.toString("UTF-8"), equalTo(""));
    assertThat(outputStream3.toString("UTF-8"), equalTo(""));

    publishAndFlush(handler, newLogRecordWithThreadId(Level.FINE, "Shh..", 64738));
    assertThat(outputStream1.toString("UTF-8"), equalTo(""));
    assertThat(outputStream2.toString("UTF-8"), equalTo(""));
    assertThat(outputStream3.toString("UTF-8"), equalTo(""));

    commandIdToLevel.put("commandIdForOutputStream3", Level.ALL);

    publishAndFlush(handler, newLogRecordWithThreadId(Level.FINE, "Stream 3", 64738));
    assertThat(outputStream1.toString("UTF-8"), equalTo(""));
    assertThat(outputStream2.toString("UTF-8"), equalTo(""));
    assertThat(outputStream3.toString("UTF-8"), equalTo("Stream 3"));
  }

  @Test
  public void levelOverrideCanBeRemoved() throws IOException {
    FakeOutputStream outputStream1 = new FakeOutputStream();
    FakeOutputStream outputStream2 = new FakeOutputStream();
    FakeOutputStream outputStream3 = new FakeOutputStream();
    ConsoleHandler handler =
        new ConsoleHandler(
            ConsoleHandler.utf8OutputStreamWriter(outputStream1),
            new MessageOnlyFormatter(),
            Level.INFO,
            state);

    threadIdToCommandId.put(49152L, "commandIdForOutputStream2");
    threadIdToCommandId.put(64738L, "commandIdForOutputStream3");
    registerOutputStream("commandIdForOutputStream2", outputStream2);
    registerOutputStream("commandIdForOutputStream3", outputStream3);

    commandIdToLevel.put("commandIdForOutputStream3", Level.FINE);

    publishAndFlush(handler, newLogRecordWithThreadId(Level.FINE, "Stream 3", 64738));
    assertThat(outputStream1.toString("UTF-8"), equalTo(""));
    assertThat(outputStream2.toString("UTF-8"), equalTo(""));
    assertThat(outputStream3.toString("UTF-8"), equalTo("Stream 3"));

    commandIdToLevel.remove("commandIdForOutputStream3");
    publishAndFlush(handler, newLogRecordWithThreadId(Level.FINE, "Shh...", 64738));
    assertThat(outputStream1.toString("UTF-8"), equalTo(""));
    assertThat(outputStream2.toString("UTF-8"), equalTo(""));
    assertThat(outputStream3.toString("UTF-8"), equalTo("Stream 3"));
  }

  @Test
  public void logMessageWithUnregisteredThreadIdGoesToAllConsoles() throws IOException {
    FakeOutputStream outputStream1 = new FakeOutputStream();
    FakeOutputStream outputStream2 = new FakeOutputStream();
    FakeOutputStream outputStream3 = new FakeOutputStream();
    ConsoleHandler handler =
        new ConsoleHandler(
            ConsoleHandler.utf8OutputStreamWriter(outputStream1),
            new MessageOnlyFormatter(),
            Level.INFO,
            state);

    threadIdToCommandId.put(49152L, "commandIdForOutputStream2");
    threadIdToCommandId.put(64738L, "commandIdForOutputStream3");
    registerOutputStream("commandIdForOutputStream2", outputStream2);
    registerOutputStream("commandIdForOutputStream3", outputStream3);

    publishAndFlush(handler, newLogRecordWithThreadId(Level.INFO, "What thread is this?", 999999));
    assertThat(outputStream1.toString("UTF-8"), equalTo(""));
    assertThat(outputStream2.toString("UTF-8"), equalTo("What thread is this?"));
    assertThat(outputStream3.toString("UTF-8"), equalTo("What thread is this?"));
  }

  private static void publishAndFlush(Handler handler, LogRecord logRecord) {
    handler.publish(logRecord);
    handler.flush();
  }

  private static LogRecord newLogRecordWithThreadId(Level level, String contents, int threadId) {
    LogRecord result = new LogRecord(level, contents);
    result.setThreadID(threadId);
    return result;
  }
}
