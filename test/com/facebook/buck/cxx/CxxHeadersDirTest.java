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

package com.facebook.buck.cxx;

import static org.hamcrest.MatcherAssert.assertThat;

import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.rules.keys.AlterRuleKeys;
import com.facebook.buck.rules.keys.DefaultRuleKeyFactory;
import com.facebook.buck.rules.keys.TestDefaultRuleKeyFactory;
import com.facebook.buck.rules.keys.UncachedRuleKeyBuilder;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.util.cache.FileHashCacheMode;
import com.facebook.buck.util.cache.impl.DefaultFileHashCache;
import com.facebook.buck.util.cache.impl.StackedFileHashCache;
import com.facebook.buck.util.hashing.FileHashLoader;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.file.Path;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;

public class CxxHeadersDirTest {
  @Rule public TemporaryPaths temporaryPaths = new TemporaryPaths();

  private RuleKey getRuleKey(ProjectFilesystem filesystem, CxxHeaders cxxHeaders) {
    SourcePathRuleFinder ruleFinder = new TestActionGraphBuilder();
    FileHashLoader fileHashLoader =
        new StackedFileHashCache(
            ImmutableList.of(
                DefaultFileHashCache.createDefaultFileHashCache(
                    filesystem, FileHashCacheMode.DEFAULT, false)));
    DefaultRuleKeyFactory factory = new TestDefaultRuleKeyFactory(fileHashLoader, ruleFinder);
    UncachedRuleKeyBuilder builder =
        new UncachedRuleKeyBuilder(ruleFinder, fileHashLoader, factory);
    AlterRuleKeys.amendKey(builder, cxxHeaders);
    return builder.build(RuleKey::new);
  }

  @Test
  public void dirContentsAffectsRuleKey() throws IOException {
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(temporaryPaths.getRoot());
    Path headerDir = filesystem.getPath("foo");
    filesystem.mkdirs(headerDir);
    CxxHeadersDir cxxHeaders =
        CxxHeadersDir.of(
            CxxPreprocessables.IncludeType.SYSTEM, FakeSourcePath.of(filesystem, headerDir));
    filesystem.writeContentsToPath("something", headerDir.resolve("bar.h"));
    RuleKey ruleKey1 = getRuleKey(filesystem, cxxHeaders);
    filesystem.writeContentsToPath("something else", headerDir.resolve("bar.h"));
    RuleKey ruleKey2 = getRuleKey(filesystem, cxxHeaders);
    assertThat(ruleKey1, Matchers.not(Matchers.equalTo(ruleKey2)));
  }

  @Test
  public void typeAffectsRuleKey() throws IOException {
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(temporaryPaths.getRoot());
    Path headerDir = filesystem.getPath("foo");
    filesystem.mkdirs(headerDir);
    RuleKey ruleKey1 =
        getRuleKey(
            filesystem,
            CxxHeadersDir.of(
                CxxPreprocessables.IncludeType.LOCAL, FakeSourcePath.of(filesystem, headerDir)));
    RuleKey ruleKey2 =
        getRuleKey(
            filesystem,
            CxxHeadersDir.of(
                CxxPreprocessables.IncludeType.SYSTEM, FakeSourcePath.of(filesystem, headerDir)));
    assertThat(ruleKey1, Matchers.not(Matchers.equalTo(ruleKey2)));
  }
}
