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

package com.facebook.buck.tools.consistency;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

public class TestPrintStream extends PrintStream {
  private final ByteArrayOutputStream outBytesStream;

  private TestPrintStream(ByteArrayOutputStream outBytesStream) {
    super(outBytesStream);
    this.outBytesStream = outBytesStream;
  }

  public static TestPrintStream create() {
    return new TestPrintStream(new ByteArrayOutputStream());
  }

  public String[] getOutputLines() {
    this.flush();
    return new String(
            outBytesStream.toByteArray(),
            0,
            outBytesStream.toByteArray().length,
            StandardCharsets.UTF_8)
        .split(System.lineSeparator());
  }
}
