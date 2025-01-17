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

package com.facebook.buck.util.bser;

import static com.facebook.buck.util.bser.BserConstants.BSER_ARRAY;
import static com.facebook.buck.util.bser.BserConstants.BSER_FALSE;
import static com.facebook.buck.util.bser.BserConstants.BSER_INT16;
import static com.facebook.buck.util.bser.BserConstants.BSER_INT32;
import static com.facebook.buck.util.bser.BserConstants.BSER_INT64;
import static com.facebook.buck.util.bser.BserConstants.BSER_INT8;
import static com.facebook.buck.util.bser.BserConstants.BSER_NULL;
import static com.facebook.buck.util.bser.BserConstants.BSER_OBJECT;
import static com.facebook.buck.util.bser.BserConstants.BSER_REAL;
import static com.facebook.buck.util.bser.BserConstants.BSER_SKIP;
import static com.facebook.buck.util.bser.BserConstants.BSER_STRING;
import static com.facebook.buck.util.bser.BserConstants.BSER_TEMPLATE;
import static com.facebook.buck.util.bser.BserConstants.BSER_TRUE;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.ByteStreams;
import java.io.IOException;
import java.io.InputStream;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Decoder for the BSER binary JSON format used by the Watchman service:
 *
 * <p>https://facebook.github.io/watchman/docs/bser.html
 */
public class BserDeserializer {

  /** Exception thrown when BSER parser unexpectedly reaches the end of the input stream. */
  public static class BserEofException extends IOException {
    public BserEofException(String message) {
      super(message);
    }

    public BserEofException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  private final CharsetDecoder utf8Decoder;

  /**
   * If {@code keyOrdering} is {@code SORTED}, any {@code Map} objects in the resulting value will
   * have their keys sorted in natural order. Otherwise, any {@code Map}s will have their keys in
   * the same order with which they were encoded.
   */
  public BserDeserializer() {
    this.utf8Decoder =
        StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT);
  }

  // 2 bytes marker, 1 byte int size
  private static final int INITIAL_SNIFF_LEN = 3;

  // 2 bytes marker, 1 byte int size, up to 8 bytes int64 value
  private static final int SNIFF_BUFFER_SIZE = 13;

  /**
   * Deserializes the next BSER-encoded value from the stream.
   *
   * @return either a {@link String}, {@link Number}, {@link List}, {@link Map}, or {@code null},
   *     depending on the type of the top-level encoded object.
   */
  @Nullable
  public Object deserializeBserValue(InputStream inputStream) throws IOException {
    try {
      return deserializeRecursive(readBserBuffer(inputStream));
    } catch (BufferUnderflowException e) {
      throw new BserEofException("Prematurely reached end of BSER buffer", e);
    }
  }

  private ByteBuffer readBserBuffer(InputStream inputStream) throws IOException {
    ByteBuffer sniffBuffer = ByteBuffer.allocate(SNIFF_BUFFER_SIZE).order(ByteOrder.nativeOrder());
    Preconditions.checkState(sniffBuffer.hasArray());

    int sniffBytesRead = ByteStreams.read(inputStream, sniffBuffer.array(), 0, INITIAL_SNIFF_LEN);
    if (sniffBytesRead < INITIAL_SNIFF_LEN) {
      throw new BserEofException(
          String.format(
              "Invalid BSER header (expected %d bytes, got %d bytes)",
              INITIAL_SNIFF_LEN, sniffBytesRead));
    }

    if (sniffBuffer.get() != 0x00 || sniffBuffer.get() != 0x01) {
      throw new IOException("Invalid BSER header");
    }

    byte lengthType = sniffBuffer.get();
    int lengthBytesRemaining;
    switch (lengthType) {
      case BSER_INT8:
        lengthBytesRemaining = 1;
        break;
      case BSER_INT16:
        lengthBytesRemaining = 2;
        break;
      case BSER_INT32:
        lengthBytesRemaining = 4;
        break;
      case BSER_INT64:
        lengthBytesRemaining = 8;
        break;
      default:
        throw new IOException(String.format("Unrecognized BSER header length type %d", lengthType));
    }
    int lengthBytesRead =
        ByteStreams.read(
            inputStream, sniffBuffer.array(), sniffBuffer.position(), lengthBytesRemaining);
    if (lengthBytesRead < lengthBytesRemaining) {
      throw new BserEofException(
          String.format(
              "Invalid BSER header length (expected %d bytes, got %d bytes)",
              lengthBytesRemaining, lengthBytesRead));
    }
    int bytesRemaining = deserializeIntLen(sniffBuffer, lengthType);

    ByteBuffer bserBuffer = ByteBuffer.allocate(bytesRemaining).order(ByteOrder.nativeOrder());
    Preconditions.checkState(bserBuffer.hasArray());

    int remainingBytesRead = ByteStreams.read(inputStream, bserBuffer.array(), 0, bytesRemaining);

    if (remainingBytesRead < bytesRemaining) {
      throw new IOException(
          String.format(
              "Invalid BSER header (expected %d bytes, got %d bytes)",
              bytesRemaining, remainingBytesRead));
    }

    return bserBuffer;
  }

  private int deserializeIntLen(ByteBuffer buffer, byte type) throws IOException {
    long value = deserializeNumber(buffer, type).longValue();
    if (value > Integer.MAX_VALUE) {
      throw new IOException(
          String.format("BSER length out of range (%d > %d)", value, Integer.MAX_VALUE));
    } else if (value < 0) {
      throw new IOException(String.format("BSER length out of range (%d < 0)", value));
    }
    return (int) value;
  }

  private Number deserializeNumber(ByteBuffer buffer, byte type) throws IOException {
    switch (type) {
      case BSER_INT8:
        return buffer.get();
      case BSER_INT16:
        return buffer.getShort();
      case BSER_INT32:
        return buffer.getInt();
      case BSER_INT64:
        return buffer.getLong();
      default:
        throw new IOException(String.format("Invalid BSER number encoding %d", type));
    }
  }

  private String deserializeString(ByteBuffer buffer) throws IOException {
    byte intType = buffer.get();
    int len = deserializeIntLen(buffer, intType);

    // We use a CharsetDecoder here instead of String(byte[], Charset)
    // because we want it to throw an exception for any non-UTF-8 input.
    buffer.limit(buffer.position() + len);

    try {
      // We'll likely have many duplicates of this string. Java 7 and
      // up have not-insane behavior of String.intern(), so we'll use
      // it to deduplicate the String instances.
      //
      // See: http://java-performance.info/string-intern-in-java-6-7-8/
      return utf8Decoder.decode(buffer).toString().intern();
    } finally {
      buffer.limit(buffer.capacity());
    }
  }

  private ImmutableList<Object> deserializeArray(ByteBuffer buffer) throws IOException {
    byte intType = buffer.get();
    int numItems = deserializeIntLen(buffer, intType);
    if (numItems == 0) {
      return ImmutableList.of();
    }
    ImmutableList.Builder<Object> list = ImmutableList.builderWithExpectedSize(numItems);
    for (int i = 0; i < numItems; i++) {
      list.add(deserializeRecursive(buffer));
    }
    return list.build();
  }

  private ImmutableMap<String, Object> deserializeObject(ByteBuffer buffer) throws IOException {
    byte intType = buffer.get();
    int numItems = deserializeIntLen(buffer, intType);
    if (numItems == 0) {
      return ImmutableMap.of();
    }
    ImmutableMap.Builder<String, Object> builder = ImmutableMap.builder();
    for (int i = 0; i < numItems; i++) {
      byte stringType = buffer.get();
      if (stringType != BSER_STRING) {
        throw new IOException(
            String.format("Unrecognized BSER object key type %d, expected string", stringType));
      }
      String key = deserializeString(buffer);
      Object value = deserializeRecursive(buffer);
      builder.put(key, value);
    }
    return builder.build();
  }

  private ImmutableList<ImmutableMap<String, Object>> deserializeTemplate(ByteBuffer buffer)
      throws IOException {
    byte arrayType = buffer.get();
    if (arrayType != BSER_ARRAY) {
      throw new IOException(String.format("Expected ARRAY to follow TEMPLATE, got %d", arrayType));
    }
    ImmutableList<Object> keys = deserializeArray(buffer);
    byte numItemsType = buffer.get();
    int numItems = deserializeIntLen(buffer, numItemsType);
    ImmutableList.Builder<ImmutableMap<String, Object>> result = ImmutableList.builder();
    for (int itemIdx = 0; itemIdx < numItems; itemIdx++) {
      ImmutableMap.Builder<String, Object> obj = ImmutableMap.builder();
      for (Object o : keys) {
        byte keyValueType = buffer.get();
        if (keyValueType != BSER_SKIP) {
          String key = (String) o;
          obj.put(key, deserializeRecursiveWithType(buffer, keyValueType));
        }
      }
      result.add(obj.build());
    }
    return result.build();
  }

  private Object deserializeRecursive(ByteBuffer buffer) throws IOException {
    byte type = buffer.get();
    return deserializeRecursiveWithType(buffer, type);
  }

  private Object deserializeRecursiveWithType(ByteBuffer buffer, byte type) throws IOException {
    switch (type) {
      case BSER_INT8:
      case BSER_INT16:
      case BSER_INT32:
      case BSER_INT64:
        return deserializeNumber(buffer, type);
      case BSER_REAL:
        return buffer.getDouble();
      case BSER_TRUE:
        return true;
      case BSER_FALSE:
        return false;
      case BSER_NULL:
        return BserNull.NULL;
      case BSER_STRING:
        return deserializeString(buffer);
      case BSER_ARRAY:
        return deserializeArray(buffer);
      case BSER_OBJECT:
        return deserializeObject(buffer);
      case BSER_TEMPLATE:
        return deserializeTemplate(buffer);
      default:
        throw new IOException(String.format("Unrecognized BSER value type %d", type));
    }
  }
}
