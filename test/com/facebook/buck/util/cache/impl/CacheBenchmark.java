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

package com.facebook.buck.util.cache.impl;

import com.facebook.buck.core.filesystems.ForwardRelPath;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.io.watchman.WatchmanEvent.Kind;
import com.facebook.buck.io.watchman.WatchmanPathEvent;
import com.facebook.buck.util.cache.FileHashCacheMode;
import com.google.caliper.BeforeExperiment;
import com.google.caliper.Benchmark;
import com.google.caliper.Param;
import com.google.common.collect.Lists;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import java.nio.file.Paths;
import java.util.List;
import java.util.Random;
import org.junit.Before;
import org.junit.Test;

public class CacheBenchmark {
  @Param({"10000", "100000", "250000"})
  private final int leavesCount = 100;

  private static final Random random = new Random(123);
  private final List<String> folders = Lists.newArrayList("");
  private final List<String> leaves = Lists.newArrayList();

  private FakeProjectFilesystem projectFilesystem;
  private WatchedFileHashCache cache;

  @Before
  public void setUpTest() {
    setUpBenchmark();
    projectFilesystem = new FakeProjectFilesystem();
    cache =
        new WatchedFileHashCache(
            projectFilesystem, FileHashCacheMode.DEFAULT, false
            /** loggingEnabled * */
            );
  }

  private static String generateRandomString() {
    StringBuilder sb = new StringBuilder();
    int length = random.nextInt(10) + 3; // min 3, max 12
    for (int i = 0; i < length; i++) {
      sb.append((char) (random.nextInt(26) + 97)); // min 'a', max 'z'
    }
    return sb.toString();
  }

  @BeforeExperiment
  public void setUpBenchmark() {
    while (leaves.size() < leavesCount) {
      String path = folders.get(random.nextInt(folders.size()));
      // create a folder? 25% chance of doing so.
      if (random.nextInt(4) == 0) {
        path += generateRandomString();
        // is it a leaf?
        if (random.nextBoolean()) {
          leaves.add(path);
        }
        path += "/";
        folders.add(path);
      } else {
        // it's a file.
        path += generateRandomString() + ".txt";
        leaves.add(path);
      }
    }
  }

  @Test
  public void addMultipleEntriesPerformance() {
    addMultipleEntries();
  }

  @Benchmark
  public void addMultipleEntries() {
    addEntries();
  }

  private void addEntries() {
    leaves.forEach(
        leaf -> {
          HashCode hashCode = Hashing.sha1().newHasher().putBytes(leaf.getBytes()).hash();
          cache.set(Paths.get(leaf), hashCode);
        });
  }

  @Test
  public void invalidateMultipleEntries() {
    addEntries();
    invalidateEntries();
  }

  @Benchmark
  public void invalidateEntries() {
    leaves.forEach(
        leaf ->
            cache.onFileSystemChange(
                WatchmanPathEvent.of(
                    projectFilesystem.resolve(leaf), Kind.CREATE, ForwardRelPath.of(leaf))));
  }
}
