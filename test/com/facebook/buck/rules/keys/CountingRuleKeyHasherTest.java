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

package com.facebook.buck.rules.keys;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.io.ArchiveMemberPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.RuleType;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.rules.keys.hasher.CountingRuleKeyHasher;
import com.facebook.buck.rules.keys.hasher.GuavaRuleKeyHasher;
import com.facebook.buck.rules.keys.hasher.RuleKeyHasher;
import com.facebook.buck.util.sha1.Sha1HashCode;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import java.nio.file.Paths;
import java.util.regex.Pattern;
import org.junit.Test;

public class CountingRuleKeyHasherTest {

  private static final RuleKey RULE_KEY_1 = new RuleKey("a002b39af204cdfaa5fdb67816b13867c32ac52c");
  private static final RuleKey RULE_KEY_2 = new RuleKey("b67816b13867c32ac52ca002b39af204cdfaa5fd");
  private static final BuildTarget TARGET_1 = BuildTargetFactory.newInstance("//example/base:one");
  private static final BuildTarget TARGET_2 =
      BuildTargetFactory.newInstance("//example/base:one#flavor");

  @Test
  public void testForwarding() {
    assertEquals(newGuavaHasher().hash(), newCountHasher().hash());
    assertEquals(newGuavaHasher().putKey("").hash(), newCountHasher().putKey("").hash());
    assertEquals(newGuavaHasher().putKey("42").hash(), newCountHasher().putKey("42").hash());
    assertEquals(
        newGuavaHasher().putKey("4").putKey("2").hash(),
        newCountHasher().putKey("4").putKey("2").hash());
    assertEquals(newGuavaHasher().putNull().hash(), newCountHasher().putNull().hash());
    assertEquals(
        newGuavaHasher().putBoolean(true).hash(), newCountHasher().putBoolean(true).hash());
    assertEquals(
        newGuavaHasher().putBoolean(false).hash(), newCountHasher().putBoolean(false).hash());
    assertEquals(newGuavaHasher().putNumber(0).hash(), newCountHasher().putNumber(0).hash());
    assertEquals(newGuavaHasher().putNumber(42).hash(), newCountHasher().putNumber(42).hash());
    assertEquals(
        newGuavaHasher().putNumber((long) 0).hash(), newCountHasher().putNumber((long) 0).hash());
    assertEquals(
        newGuavaHasher().putNumber((long) 42).hash(), newCountHasher().putNumber((long) 42).hash());
    assertEquals(
        newGuavaHasher().putNumber((short) 0).hash(), newCountHasher().putNumber((short) 0).hash());
    assertEquals(
        newGuavaHasher().putNumber((short) 42).hash(),
        newCountHasher().putNumber((short) 42).hash());
    assertEquals(
        newGuavaHasher().putNumber((byte) 0).hash(), newCountHasher().putNumber((byte) 0).hash());
    assertEquals(
        newGuavaHasher().putNumber((byte) 42).hash(), newCountHasher().putNumber((byte) 42).hash());
    assertEquals(
        newGuavaHasher().putNumber((float) 0).hash(), newCountHasher().putNumber((float) 0).hash());
    assertEquals(
        newGuavaHasher().putNumber((float) 42).hash(),
        newCountHasher().putNumber((float) 42).hash());
    assertEquals(
        newGuavaHasher().putNumber((double) 0).hash(),
        newCountHasher().putNumber((double) 0).hash());
    assertEquals(
        newGuavaHasher().putNumber((double) 42).hash(),
        newCountHasher().putNumber((double) 42).hash());
    assertEquals(
        newGuavaHasher().putCharacter((char) 0).hash(),
        newCountHasher().putCharacter((char) 0).hash());
    assertEquals(
        newGuavaHasher().putCharacter((char) 42).hash(),
        newCountHasher().putCharacter((char) 42).hash());

    assertEquals(newGuavaHasher().putString("").hash(), newCountHasher().putString("").hash());
    assertEquals(newGuavaHasher().putString("42").hash(), newCountHasher().putString("42").hash());
    assertEquals(
        newGuavaHasher().putString("4").putString("2").hash(),
        newCountHasher().putString("4").putString("2").hash());
    assertEquals(
        newGuavaHasher().putBytes(new byte[0]).hash(),
        newCountHasher().putBytes(new byte[0]).hash());
    assertEquals(
        newGuavaHasher().putBytes(new byte[] {42}).hash(),
        newCountHasher().putBytes(new byte[] {42}).hash());
    assertEquals(
        newGuavaHasher().putBytes(new byte[] {42, 42}).hash(),
        newCountHasher().putBytes(new byte[] {42, 42}).hash());
    assertEquals(
        newGuavaHasher().putPattern(Pattern.compile("")).hash(),
        newCountHasher().putPattern(Pattern.compile("")).hash());
    assertEquals(
        newGuavaHasher().putPattern(Pattern.compile("42")).hash(),
        newCountHasher().putPattern(Pattern.compile("42")).hash());
    assertEquals(
        newGuavaHasher().putPattern(Pattern.compile("4")).putPattern(Pattern.compile("2")).hash(),
        newCountHasher().putPattern(Pattern.compile("4")).putPattern(Pattern.compile("2")).hash());
    assertEquals(
        newGuavaHasher()
            .putSha1(Sha1HashCode.of("a002b39af204cdfaa5fdb67816b13867c32ac52c"))
            .hash(),
        newCountHasher()
            .putSha1(Sha1HashCode.of("a002b39af204cdfaa5fdb67816b13867c32ac52c"))
            .hash());
    assertEquals(
        newGuavaHasher()
            .putSha1(Sha1HashCode.of("b67816b13867c32ac52ca002b39af204cdfaa5fd"))
            .hash(),
        newCountHasher()
            .putSha1(Sha1HashCode.of("b67816b13867c32ac52ca002b39af204cdfaa5fd"))
            .hash());
    assertEquals(
        newGuavaHasher().putPath(Paths.get(""), HashCode.fromInt(0)).hash(),
        newCountHasher().putPath(Paths.get(""), HashCode.fromInt(0)).hash());
    assertEquals(
        newGuavaHasher().putPath(Paths.get(""), HashCode.fromInt(42)).hash(),
        newCountHasher().putPath(Paths.get(""), HashCode.fromInt(42)).hash());
    assertEquals(
        newGuavaHasher().putPath(Paths.get("42"), HashCode.fromInt(0)).hash(),
        newCountHasher().putPath(Paths.get("42"), HashCode.fromInt(0)).hash());
    assertEquals(
        newGuavaHasher().putPath(Paths.get("42"), HashCode.fromInt(42)).hash(),
        newCountHasher().putPath(Paths.get("42"), HashCode.fromInt(42)).hash());
    assertEquals(
        newGuavaHasher().putPath(Paths.get("42/42"), HashCode.fromInt(42)).hash(),
        newCountHasher().putPath(Paths.get("42/42"), HashCode.fromInt(42)).hash());
    assertEquals(
        newGuavaHasher()
            .putArchiveMemberPath(Paths.get(""), Paths.get(""), HashCode.fromInt(0))
            .hash(),
        newCountHasher()
            .putArchiveMemberPath(Paths.get(""), Paths.get(""), HashCode.fromInt(0))
            .hash());
    assertEquals(
        newGuavaHasher()
            .putArchiveMemberPath(Paths.get(""), Paths.get(""), HashCode.fromInt(42))
            .hash(),
        newCountHasher()
            .putArchiveMemberPath(Paths.get(""), Paths.get(""), HashCode.fromInt(42))
            .hash());
    assertEquals(
        newGuavaHasher()
            .putArchiveMemberPath(Paths.get("42"), Paths.get("2"), HashCode.fromInt(0))
            .hash(),
        newCountHasher()
            .putArchiveMemberPath(Paths.get("42"), Paths.get("2"), HashCode.fromInt(0))
            .hash());
    assertEquals(
        newGuavaHasher()
            .putArchiveMemberPath(Paths.get("42"), Paths.get("2"), HashCode.fromInt(42))
            .hash(),
        newCountHasher()
            .putArchiveMemberPath(Paths.get("42"), Paths.get("2"), HashCode.fromInt(42))
            .hash());
    assertEquals(
        newGuavaHasher()
            .putArchiveMemberPath(Paths.get("42/42"), Paths.get("2"), HashCode.fromInt(42))
            .hash(),
        newCountHasher()
            .putArchiveMemberPath(Paths.get("42/42"), Paths.get("2"), HashCode.fromInt(42))
            .hash());
    assertEquals(
        newGuavaHasher().putNonHashingPath(Paths.get("")).hash(),
        newCountHasher().putNonHashingPath(Paths.get("")).hash());
    assertEquals(
        newGuavaHasher().putNonHashingPath(Paths.get("42")).hash(),
        newCountHasher().putNonHashingPath(Paths.get("42")).hash());
    assertEquals(
        newGuavaHasher().putNonHashingPath(Paths.get("4")).putNonHashingPath(Paths.get("2")).hash(),
        newCountHasher()
            .putNonHashingPath(Paths.get("4"))
            .putNonHashingPath(Paths.get("2"))
            .hash());
    assertEquals(
        newGuavaHasher().putRuleKey(RULE_KEY_1).hash(),
        newCountHasher().putRuleKey(RULE_KEY_1).hash());
    assertEquals(
        newGuavaHasher().putRuleKey(RULE_KEY_2).hash(),
        newCountHasher().putRuleKey(RULE_KEY_2).hash());
    assertEquals(
        newGuavaHasher().putRuleType(RuleType.of("", RuleType.Kind.BUILD)).hash(),
        newCountHasher().putRuleType(RuleType.of("", RuleType.Kind.BUILD)).hash());
    assertEquals(
        newGuavaHasher().putRuleType(RuleType.of("42", RuleType.Kind.BUILD)).hash(),
        newCountHasher().putRuleType(RuleType.of("42", RuleType.Kind.BUILD)).hash());
    assertEquals(
        newGuavaHasher()
            .putRuleType(RuleType.of("4", RuleType.Kind.BUILD))
            .putRuleType(RuleType.of("2", RuleType.Kind.BUILD))
            .hash(),
        newCountHasher()
            .putRuleType(RuleType.of("4", RuleType.Kind.BUILD))
            .putRuleType(RuleType.of("2", RuleType.Kind.BUILD))
            .hash());
    assertEquals(
        newGuavaHasher().putBuildTarget(TARGET_1).hash(),
        newCountHasher().putBuildTarget(TARGET_1).hash());
    assertEquals(
        newGuavaHasher().putBuildTarget(TARGET_2).hash(),
        newCountHasher().putBuildTarget(TARGET_2).hash());
    assertEquals(
        newGuavaHasher().putBuildTargetSourcePath(DefaultBuildTargetSourcePath.of(TARGET_1)).hash(),
        newCountHasher()
            .putBuildTargetSourcePath(DefaultBuildTargetSourcePath.of(TARGET_1))
            .hash());
    assertEquals(
        newGuavaHasher().putBuildTargetSourcePath(DefaultBuildTargetSourcePath.of(TARGET_2)).hash(),
        newCountHasher()
            .putBuildTargetSourcePath(DefaultBuildTargetSourcePath.of(TARGET_2))
            .hash());
    assertEquals(
        newGuavaHasher().putContainer(RuleKeyHasher.Container.LIST, 0).hash(),
        newCountHasher().putContainer(RuleKeyHasher.Container.LIST, 0).hash());
    assertEquals(
        newGuavaHasher().putContainer(RuleKeyHasher.Container.LIST, 42).hash(),
        newCountHasher().putContainer(RuleKeyHasher.Container.LIST, 42).hash());
    assertEquals(
        newGuavaHasher().putContainer(RuleKeyHasher.Container.MAP, 0).hash(),
        newCountHasher().putContainer(RuleKeyHasher.Container.MAP, 0).hash());
    assertEquals(
        newGuavaHasher().putContainer(RuleKeyHasher.Container.MAP, 42).hash(),
        newCountHasher().putContainer(RuleKeyHasher.Container.MAP, 42).hash());
    assertEquals(
        newGuavaHasher().putWrapper(RuleKeyHasher.Wrapper.SUPPLIER).hash(),
        newCountHasher().putWrapper(RuleKeyHasher.Wrapper.SUPPLIER).hash());
    assertEquals(
        newGuavaHasher().putWrapper(RuleKeyHasher.Wrapper.OPTIONAL).hash(),
        newCountHasher().putWrapper(RuleKeyHasher.Wrapper.OPTIONAL).hash());
    assertEquals(
        newGuavaHasher().putWrapper(RuleKeyHasher.Wrapper.EITHER_LEFT).hash(),
        newCountHasher().putWrapper(RuleKeyHasher.Wrapper.EITHER_LEFT).hash());
    assertEquals(
        newGuavaHasher().putWrapper(RuleKeyHasher.Wrapper.EITHER_RIGHT).hash(),
        newCountHasher().putWrapper(RuleKeyHasher.Wrapper.EITHER_RIGHT).hash());
    assertEquals(
        newGuavaHasher().putWrapper(RuleKeyHasher.Wrapper.BUILD_RULE).hash(),
        newCountHasher().putWrapper(RuleKeyHasher.Wrapper.BUILD_RULE).hash());
    assertEquals(
        newGuavaHasher().putWrapper(RuleKeyHasher.Wrapper.APPENDABLE).hash(),
        newCountHasher().putWrapper(RuleKeyHasher.Wrapper.APPENDABLE).hash());
  }

  @Test
  public void testCounting() {
    CountingRuleKeyHasher<HashCode> hasher = newCountHasher();
    int count = 0;
    assertEquals(count, hasher.getCount());
    hasher.putKey("");
    assertEquals(++count, hasher.getCount());
    hasher.putKey("42").putKey("43").putKey("44");
    assertEquals(count += 3, hasher.getCount());
    hasher.putNull();
    assertEquals(++count, hasher.getCount());
    hasher.putBoolean(true);
    assertEquals(++count, hasher.getCount());
    hasher.putBoolean(false).putBoolean(true);
    assertEquals(count += 2, hasher.getCount());
    hasher.putNumber(0);
    assertEquals(++count, hasher.getCount());
    hasher.putNumber(42).putNumber(43);
    assertEquals(count += 2, hasher.getCount());
    hasher.putNumber((long) 0);
    assertEquals(++count, hasher.getCount());
    hasher.putNumber((long) 42).putNumber((long) 43);
    assertEquals(count += 2, hasher.getCount());
    hasher.putNumber((short) 0);
    assertEquals(++count, hasher.getCount());
    hasher.putNumber((short) 42).putNumber((short) 43);
    assertEquals(count += 2, hasher.getCount());
    hasher.putNumber((byte) 0);
    assertEquals(++count, hasher.getCount());
    hasher.putNumber((byte) 42).putNumber((byte) 43);
    assertEquals(count += 2, hasher.getCount());
    hasher.putNumber((float) 0);
    assertEquals(++count, hasher.getCount());
    hasher.putNumber((float) 42).putNumber((float) 43);
    assertEquals(count += 2, hasher.getCount());
    hasher.putNumber((double) 0);
    assertEquals(++count, hasher.getCount());
    hasher.putNumber((double) 42).putNumber((double) 43);
    assertEquals(count += 2, hasher.getCount());
    hasher.putCharacter((char) 0);
    assertEquals(++count, hasher.getCount());
    hasher.putCharacter((char) 42).putCharacter((char) 43);
    assertEquals(count += 2, hasher.getCount());
    hasher.putString("");
    assertEquals(++count, hasher.getCount());
    hasher.putString("42").putString("43");
    assertEquals(count += 2, hasher.getCount());
    hasher.putBytes(new byte[0]);
    assertEquals(++count, hasher.getCount());
    hasher.putBytes(new byte[] {42});
    assertEquals(++count, hasher.getCount());
    hasher.putBytes(new byte[] {42, 42}).putBytes(new byte[] {43});
    assertEquals(count += 2, hasher.getCount());
    hasher.putPattern(Pattern.compile(""));
    assertEquals(++count, hasher.getCount());
    hasher.putPattern(Pattern.compile("42")).putPattern(Pattern.compile("43"));
    assertEquals(count += 2, hasher.getCount());
    hasher.putSha1(Sha1HashCode.of("a002b39af204cdfaa5fdb67816b13867c32ac52c"));
    assertEquals(++count, hasher.getCount());
    hasher
        .putSha1(Sha1HashCode.of("b67816b13867c32ac52ca002b39af204cdfaa5fd"))
        .putSha1(Sha1HashCode.of("a002b39af204cdfaa5fdb67816b13867c32ac52c"));
    assertEquals(count += 2, hasher.getCount());
    hasher.putPath(Paths.get(""), HashCode.fromInt(0));
    assertEquals(++count, hasher.getCount());
    hasher.putPath(Paths.get(""), HashCode.fromInt(42));
    assertEquals(++count, hasher.getCount());
    hasher.putPath(Paths.get("42"), HashCode.fromInt(0));
    assertEquals(++count, hasher.getCount());
    hasher.putPath(Paths.get("42"), HashCode.fromInt(42));
    assertEquals(++count, hasher.getCount());
    hasher
        .putPath(Paths.get("42/42"), HashCode.fromInt(42))
        .putPath(Paths.get("43"), HashCode.fromInt(43));
    assertEquals(count += 2, hasher.getCount());
    hasher.putArchiveMemberPath(Paths.get(""), Paths.get(""), HashCode.fromInt(0));
    assertEquals(++count, hasher.getCount());
    hasher.putArchiveMemberPath(Paths.get(""), Paths.get(""), HashCode.fromInt(42));
    assertEquals(++count, hasher.getCount());
    hasher.putArchiveMemberPath(Paths.get("42"), Paths.get("2"), HashCode.fromInt(0));
    assertEquals(++count, hasher.getCount());
    hasher.putArchiveMemberPath(Paths.get("42"), Paths.get("2"), HashCode.fromInt(42));
    assertEquals(++count, hasher.getCount());
    hasher
        .putArchiveMemberPath(Paths.get("42/42"), Paths.get("2"), HashCode.fromInt(42))
        .putArchiveMemberPath(Paths.get("43/43"), Paths.get("3"), HashCode.fromInt(43));
    assertEquals(count += 2, hasher.getCount());
    hasher.putNonHashingPath(Paths.get(""));
    assertEquals(++count, hasher.getCount());
    hasher.putNonHashingPath(Paths.get("42")).putNonHashingPath(Paths.get("43"));
    assertEquals(count += 2, hasher.getCount());
    hasher.putRuleKey(RULE_KEY_1);
    assertEquals(++count, hasher.getCount());
    hasher.putRuleKey(RULE_KEY_2).putRuleKey(RULE_KEY_1);
    assertEquals(count += 2, hasher.getCount());
    hasher.putRuleType(RuleType.of("", RuleType.Kind.BUILD));
    assertEquals(++count, hasher.getCount());
    hasher
        .putRuleType(RuleType.of("42", RuleType.Kind.BUILD))
        .putRuleType(RuleType.of("43", RuleType.Kind.BUILD));
    assertEquals(count += 2, hasher.getCount());
    hasher.putBuildTarget(TARGET_1);
    assertEquals(++count, hasher.getCount());
    hasher.putBuildTarget(TARGET_2).putBuildTarget(TARGET_1);
    assertEquals(count += 2, hasher.getCount());
    hasher.putBuildTargetSourcePath(DefaultBuildTargetSourcePath.of(TARGET_1));
    assertEquals(++count, hasher.getCount());
    hasher
        .putBuildTargetSourcePath(DefaultBuildTargetSourcePath.of(TARGET_2))
        .putBuildTargetSourcePath(DefaultBuildTargetSourcePath.of(TARGET_1));
    assertEquals(count += 2, hasher.getCount());
    hasher.putContainer(RuleKeyHasher.Container.LIST, 0);
    assertEquals(++count, hasher.getCount());
    hasher
        .putContainer(RuleKeyHasher.Container.LIST, 42)
        .putContainer(RuleKeyHasher.Container.LIST, 43);
    assertEquals(count += 2, hasher.getCount());
    hasher.putContainer(RuleKeyHasher.Container.MAP, 0);
    assertEquals(++count, hasher.getCount());
    hasher
        .putContainer(RuleKeyHasher.Container.MAP, 42)
        .putContainer(RuleKeyHasher.Container.MAP, 43);
    assertEquals(count += 2, hasher.getCount());
    hasher.putWrapper(RuleKeyHasher.Wrapper.SUPPLIER);
    assertEquals(++count, hasher.getCount());
    hasher.putWrapper(RuleKeyHasher.Wrapper.OPTIONAL);
    assertEquals(++count, hasher.getCount());
    hasher.putWrapper(RuleKeyHasher.Wrapper.EITHER_LEFT);
    assertEquals(++count, hasher.getCount());
    hasher.putWrapper(RuleKeyHasher.Wrapper.EITHER_RIGHT);
    assertEquals(++count, hasher.getCount());
    hasher.putWrapper(RuleKeyHasher.Wrapper.EITHER_RIGHT);
    assertEquals(++count, hasher.getCount());
    hasher.putWrapper(RuleKeyHasher.Wrapper.BUILD_RULE);
    assertEquals(++count, hasher.getCount());
    hasher.putWrapper(RuleKeyHasher.Wrapper.APPENDABLE);
    assertEquals(++count, hasher.getCount());
    hasher
        .putWrapper(RuleKeyHasher.Wrapper.SUPPLIER)
        .putWrapper(RuleKeyHasher.Wrapper.OPTIONAL)
        .putWrapper(RuleKeyHasher.Wrapper.EITHER_LEFT)
        .putWrapper(RuleKeyHasher.Wrapper.EITHER_RIGHT)
        .putWrapper(RuleKeyHasher.Wrapper.BUILD_RULE)
        .putWrapper(RuleKeyHasher.Wrapper.APPENDABLE)
        .putWrapper(RuleKeyHasher.Wrapper.OPTIONAL);
    assertEquals(count += 7, hasher.getCount());
    hasher
        .putKey("key")
        .putContainer(RuleKeyHasher.Container.LIST, 3)
        .putString("a")
        .putNumber(1)
        .putNull();
    assertEquals(count += 5, hasher.getCount());
  }

  private ArchiveMemberPath newArchiveMember(String archivePath, String memberPath) {
    return ArchiveMemberPath.of(Paths.get(archivePath), Paths.get(memberPath));
  }

  private CountingRuleKeyHasher<HashCode> newCountHasher() {
    return new CountingRuleKeyHasher<>(newGuavaHasher());
  }

  private GuavaRuleKeyHasher newGuavaHasher() {
    return new GuavaRuleKeyHasher(Hashing.sha1().newHasher());
  }
}
