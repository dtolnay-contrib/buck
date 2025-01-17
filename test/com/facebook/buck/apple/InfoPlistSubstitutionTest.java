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

package com.facebook.buck.apple;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.google.common.collect.ImmutableMap;
import java.util.Optional;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

/** Unit tests for {@link InfoPlistSubstitution}. */
public class InfoPlistSubstitutionTest {

  @Rule public ExpectedException thrown = ExpectedException.none();

  @Test
  public void emptyStringReplacementIsEmpty() {
    assertThat(
        InfoPlistSubstitution.replaceVariablesInString("", ImmutableMap.of()), is(emptyString()));
  }

  @Test
  public void emptyMapLeavesStringAsIs() {
    assertThat(
        InfoPlistSubstitution.replaceVariablesInString("Hello world", ImmutableMap.of()),
        equalTo("Hello world"));
  }

  @Test
  public void curlyBracesAreSubstituted() {
    assertThat(
        InfoPlistSubstitution.replaceVariablesInString(
            "Hello ${FOO} world", ImmutableMap.of("FOO", "cruel")),
        equalTo("Hello cruel world"));
  }

  @Test
  public void parensAreSubstituted() {
    assertThat(
        InfoPlistSubstitution.replaceVariablesInString(
            "Hello $(FOO) world", ImmutableMap.of("FOO", "cruel")),
        equalTo("Hello cruel world"));
  }

  @Test
  public void unknownModifiersAreIgnored() {
    assertThat(
        InfoPlistSubstitution.replaceVariablesInString(
            "Hello $(FOO:bar) world", ImmutableMap.of("FOO", "cruel")),
        equalTo("Hello cruel world"));
  }

  @Test
  public void multipleMatchesAreReplaced() {
    assertThat(
        InfoPlistSubstitution.replaceVariablesInString(
            "Hello $(FOO) $(BAR) world",
            ImmutableMap.of(
                "FOO", "cruel",
                "BAR", "mean")),
        equalTo("Hello cruel mean world"));
  }

  @Test
  public void unrecognizedVariableThrows() {
    thrown.expect(HumanReadableException.class);
    thrown.expectMessage("Unrecognized plist variable: ${XYZZY:blurgh}");
    InfoPlistSubstitution.replaceVariablesInString(
        "Hello ${XYZZY:blurgh} world", ImmutableMap.of());
  }

  @Test
  public void recursiveVariableThrows() {
    thrown.expect(HumanReadableException.class);
    thrown.expectMessage("Recursive plist variable: FOO -> BAR -> BAZ -> FOO");
    InfoPlistSubstitution.replaceVariablesInString(
        "Hello ${FOO}",
        ImmutableMap.of(
            "FOO", "${BAR}",
            "BAR", "${BAZ}",
            "BAZ", "${FOO}"));
  }

  @Test
  public void mismatchedParenIgnored() {
    assertThat(
        InfoPlistSubstitution.replaceVariablesInString("Hello $(FOO} world", ImmutableMap.of()),
        equalTo("Hello $(FOO} world"));
  }

  @Test
  public void mismatchedBraceIgnored() {
    assertThat(
        InfoPlistSubstitution.replaceVariablesInString("Hello ${FOO) world", ImmutableMap.of()),
        equalTo("Hello ${FOO) world"));
  }

  @Test
  public void replacementWithMatcherAppendReplacementSpecialChars() {
    assertThat(
        InfoPlistSubstitution.replaceVariablesInString(
            "Hello ${FOO} world",
            ImmutableMap.of(
                "FOO", "${BAZ}",
                "BAZ", "$BAR")),
        equalTo("Hello $BAR world"));
  }

  @Test
  public void testVariableExpansionForPlatform() {
    assertThat(
        InfoPlistSubstitution.getVariableExpansionForPlatform(
            "FOO",
            "iphoneos",
            ImmutableMap.of(
                "FOO", "BAR",
                "FOO[sdk=iphoneos]", "BARiphoneos",
                "FOO[sdk=iphonesimulator]", "BARiphonesimulator")),
        equalTo(Optional.of("BARiphoneos")));
  }

  @Test
  public void testVariableExpansionForPlatformWithUnknownKey() {
    assertThat(
        InfoPlistSubstitution.getVariableExpansionForPlatform(
            "BAZ",
            "iphoneos",
            ImmutableMap.of(
                "FOO", "BAR",
                "FOO[sdk=iphoneos]", "BARiphoneos",
                "FOO[sdk=iphonesimulator]", "BARiphonesimulator")),
        equalTo(Optional.empty()));
  }

  @Test
  public void testVariableExpansionForPlatformWithUnknownPlatform() {
    assertThat(
        InfoPlistSubstitution.getVariableExpansionForPlatform(
            "FOO",
            "baz",
            ImmutableMap.of(
                "FOO", "BAR",
                "FOO[sdk=iphoneos]", "BARiphoneos",
                "FOO[sdk=iphonesimulator]", "BARiphonesimulator")),
        equalTo(Optional.of("BAR")));
  }

  @Test
  public void testVariableExpansionWithDefaults() {
    ImmutableMap<String, String> expansion = ImmutableMap.of("FOO", "foo", "BAR", "bar");
    {
      ImmutableMap<String, String> defaults = ImmutableMap.of("BAR", "foo");
      assertThat(
          InfoPlistSubstitution.variableExpansionWithDefaults(expansion, defaults),
          equalTo(expansion));
    }
    {
      ImmutableMap<String, String> defaults = ImmutableMap.of("FOOBAR", "foobar");
      assertThat(
          InfoPlistSubstitution.variableExpansionWithDefaults(expansion, defaults),
          equalTo(ImmutableMap.of("FOO", "foo", "BAR", "bar", "FOOBAR", "foobar")));
    }
  }
}
