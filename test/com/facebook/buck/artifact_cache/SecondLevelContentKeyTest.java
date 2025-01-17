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

package com.facebook.buck.artifact_cache;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class SecondLevelContentKeyTest {
  @Parameters(name = "[{index}] {0}: {1} -> {2}")
  public static Iterable<Object[]> data() {
    return Arrays.asList(
        new Object[][] {
          // {Test case label, raw key, expected key type, actual key data}
          {
            "old style",
            "8f459ab30afc9c154cd94f5d5d4cf7b949ffb8fc2c00",
            SecondLevelContentKey.Type.OLD_STYLE,
            "8f459ab30afc9c154cd94f5d5d4cf7b949ffb8fc2c00",
            "8f459ab30afc9c154cd94f5d5d4cf7b949ffb8fc",
            0L
          },
          {"unrealistic", "asdf", SecondLevelContentKey.Type.UNKNOWN, "asdf", "asdf", 0L},
          {
            "cas",
            "cas/597b4cc3b19069e6361dfe878bbc992498dacc30:139562",
            SecondLevelContentKey.Type.CAS_ONLY,
            "597b4cc3b19069e6361dfe878bbc992498dacc30:139562",
            "597b4cc3b19069e6361dfe878bbc992498dacc30",
            139562L
          },
          {
            "cache",
            "cache/9674344c90c2f0646f0b78026e127c9b86e3ad77:20971520",
            SecondLevelContentKey.Type.CACHE_ONLY,
            "9674344c90c2f0646f0b78026e127c9b86e3ad77:20971520",
            "9674344c90c2f0646f0b78026e127c9b86e3ad77",
            20971520L
          },
          {
            "unknown prefix",
            "buck/7a8bf8efc28275f9957f283c4dea66cc98b0c29b:314572800",
            SecondLevelContentKey.Type.UNKNOWN,
            "buck/7a8bf8efc28275f9957f283c4dea66cc98b0c29b:314572800",
            "buck/7a8bf8efc28275f9957f283c4dea66cc98b0c29b",
            314572800L
          },
        });
  }

  private final String raw;
  private final SecondLevelContentKey contentKey;
  private final SecondLevelContentKey.Type expectedType;
  private final String expectedKey;
  private final String expectedDigestHash;
  private final long expectedDigestBytes;

  public SecondLevelContentKeyTest(
      @SuppressWarnings("unused") String label,
      String raw,
      SecondLevelContentKey.Type expectedType,
      String expectedKey,
      String expectedDigestHash,
      long expectedDigestBytes) {
    this.raw = raw;
    this.contentKey = SecondLevelContentKey.fromString(raw);
    this.expectedType = expectedType;
    this.expectedKey = expectedKey;
    this.expectedDigestHash = expectedDigestHash;
    this.expectedDigestBytes = expectedDigestBytes;
  }

  @Test
  public void testParse() {
    assertEquals(raw, contentKey.toString());
    assertEquals(expectedType, contentKey.getType());
    assertEquals(expectedKey, contentKey.getKey());
  }

  @Test
  public void testDigestParse() {
    assertEquals(expectedDigestHash, SecondLevelContentKey.getDigestHash(contentKey));
    assertEquals(expectedDigestBytes, SecondLevelContentKey.getDigestBytes(contentKey));
  }
}
