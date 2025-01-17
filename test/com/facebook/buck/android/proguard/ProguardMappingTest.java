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

package com.facebook.buck.android.proguard;

import static org.junit.Assert.assertEquals;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.Map;
import org.junit.Test;

public class ProguardMappingTest {
  @Test
  public void testBasicParse() {
    Map<String, String> mapping =
        ProguardMapping.readClassMapping(
            ImmutableList.of(
                "foo.bar.Baz -> foo.bar.a:",
                "  member -> x",
                "foo.bar.Baz$Qux -> foo.bar.Baz$Qux:"));
    assertEquals(
        mapping,
        ImmutableMap.of(
            "foo.bar.Baz", "foo.bar.a",
            "foo.bar.Baz$Qux", "foo.bar.Baz$Qux"));
  }

  @Test(expected = IllegalArgumentException.class)
  public void testInternalNameError() {
    ProguardMapping.readClassMapping(ImmutableList.of("foo/bar/Baz -> foo/bar/a:"));
  }
}
