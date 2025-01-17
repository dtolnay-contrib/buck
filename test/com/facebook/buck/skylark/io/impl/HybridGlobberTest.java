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

package com.facebook.buck.skylark.io.impl;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.cli.TestWithBuckd;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.ForwardRelPath;
import com.facebook.buck.event.console.TestEventConsole;
import com.facebook.buck.io.watchman.StubWatchmanClient;
import com.facebook.buck.io.watchman.WatchRoot;
import com.facebook.buck.io.watchman.Watchman;
import com.facebook.buck.io.watchman.WatchmanClient;
import com.facebook.buck.io.watchman.WatchmanFactory;
import com.facebook.buck.io.watchman.WatchmanQueryResp;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.util.timing.FakeClock;
import com.facebook.buck.util.types.Either;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class HybridGlobberTest {
  private AbsPath root;
  private HybridGlobber globber;

  @Rule public ExpectedException thrown = ExpectedException.none();
  @Rule public TemporaryPaths tmp = new TemporaryPaths();
  @Rule public TestWithBuckd testWithBuckd = new TestWithBuckd(tmp); // set up Watchman
  private NativeGlobber nativeGlobber;

  @Before
  public void setUp() throws Exception {
    root = tmp.getRoot();
    WatchmanFactory watchmanFactory = new WatchmanFactory();
    Watchman watchman =
        watchmanFactory.build(
            ImmutableSet.of(),
            ImmutableMap.of(),
            new TestEventConsole(),
            FakeClock.doNotCare(),
            Optional.empty(),
            1_000,
            TimeUnit.SECONDS.toNanos(10),
            TimeUnit.SECONDS.toNanos(1));
    assumeTrue(watchman.getTransportPath().isPresent());
    nativeGlobber = NativeGlobber.create(root);
  }

  @Test
  public void watchmanResultsAreReturnedIfTheyExist() throws Exception {
    WatchmanGlobber watchmanGlobber =
        newGlobber(
            Either.ofLeft(
                WatchmanQueryResp.generic(
                    ImmutableMap.of("files", ImmutableList.of("bar.txt", "foo.txt")))));
    globber = new HybridGlobber(nativeGlobber, watchmanGlobber);
    assertThat(
        globber.run(Collections.singleton("*.txt"), Collections.emptySet(), false),
        equalTo(ImmutableSet.of("bar.txt", "foo.txt")));
  }

  private WatchmanGlobber newGlobber(Either<WatchmanQueryResp, WatchmanClient.Timeout> result) {
    return WatchmanGlobber.create(
        new StubWatchmanClient(result),
        TimeUnit.SECONDS.toNanos(10),
        TimeUnit.SECONDS.toNanos(1),
        ForwardRelPath.EMPTY,
        new WatchRoot(root));
  }

  @Test
  public void simpleGlobberResultsAreReturnedIfWatchmanDoesNotProduceAnything() throws Exception {
    tmp.newFile("some.txt");
    WatchmanGlobber watchmanGlobber = newGlobber(Either.ofRight(WatchmanClient.Timeout.INSTANCE));
    globber = new HybridGlobber(nativeGlobber, watchmanGlobber);
    assertEquals(
        ImmutableSet.of("some.txt"),
        globber.run(ImmutableList.of("*.txt"), ImmutableList.of(), false));
  }

  @Test
  public void testWatchmanGlobFailsOnBrokenPattern() throws Exception {
    tmp.newFile("some.txt");
    WatchmanGlobber watchmanGlobber = newGlobber(Either.ofRight(WatchmanClient.Timeout.INSTANCE));
    globber = new HybridGlobber(nativeGlobber, watchmanGlobber);
    Exception capturedException = null;
    try {
      globber.run(ImmutableList.of("**folder/*.txt"), ImmutableList.of(), false);
      fail("Globber should fail on attempt to parse incorrect path");
    } catch (IllegalArgumentException e) {
      capturedException = e;
    }
    assertThat(
        capturedException.getMessage(),
        Matchers.containsString("recursive wildcard must be its own segment"));
  }
}
