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

package com.facebook.buck.io.watchman;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.cli.TestWithBuckd;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.event.DefaultBuckEventBus;
import com.facebook.buck.event.console.TestEventConsole;
import com.facebook.buck.io.file.FileExtensionMatcher;
import com.facebook.buck.io.file.GlobPatternMatcher;
import com.facebook.buck.io.file.PathMatcher;
import com.facebook.buck.io.watchman.WatchmanEvent.Kind;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.util.environment.EnvVariablesProvider;
import com.facebook.buck.util.timing.DefaultClock;
import com.facebook.buck.util.timing.FakeClock;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class WatchmanWatcherIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();
  @Rule public TestWithBuckd initWatchman = new TestWithBuckd(tmp);

  private Watchman watchman;
  private EventBus eventBus;
  private WatchmanEventCollector watchmanEventCollector;

  @Before
  public void setUp() throws InterruptedException, IOException {
    WatchmanFactory watchmanFactory = new WatchmanFactory();
    watchman =
        watchmanFactory.build(
            ImmutableSet.of(tmp.getRoot()),
            EnvVariablesProvider.getSystemEnv(),
            new TestEventConsole(),
            new DefaultClock(),
            Optional.empty(),
            1_000,
            TimeUnit.SECONDS.toNanos(10),
            TimeUnit.SECONDS.toNanos(1));
    assertTrue(watchman.getTransportPath().isPresent());

    eventBus = new EventBus();
    watchmanEventCollector = new WatchmanEventCollector();
    eventBus.register(watchmanEventCollector);
  }

  @Test
  public void ignoreDotFileInGlob() throws IOException, InterruptedException {
    WatchmanWatcher watcher = createWatchmanWatcher(FileExtensionMatcher.of("swp"));

    // Create a dot-file which should be ignored by the above glob.
    Path path = tmp.getRoot().getFileSystem().getPath("foo/bar/.hello.swp");
    Files.createDirectories(tmp.getRoot().resolve(path).getParent().getPath());
    Files.write(tmp.getRoot().resolve(path).getPath(), new byte[0]);

    // Verify we don't get an event for the path.
    watcher.postEvents(
        new DefaultBuckEventBus(FakeClock.doNotCare(), new BuildId()),
        WatchmanWatcher.FreshInstanceAction.NONE);
    assertThat(watchmanEventCollector.getEvents(), Matchers.empty());
  }

  @Test
  public void globMatchesWholeName() throws IOException, InterruptedException {
    WatchmanWatcher watcher = createWatchmanWatcher(GlobPatternMatcher.of("*.txt"));

    // Create a dot-file which should be ignored by the above glob.
    RelPath path = RelPath.of(tmp.getRoot().getFileSystem().getPath("foo/bar/hello.txt"));
    Files.createDirectories(tmp.getRoot().resolve(path.getPath()).getParent().getPath());
    Files.write(tmp.getRoot().resolve(path.getPath()).getPath(), new byte[0]);

    // Verify we still get an event for the created path.
    watcher.postEvents(
        new DefaultBuckEventBus(FakeClock.doNotCare(), new BuildId()),
        WatchmanWatcher.FreshInstanceAction.NONE);
    WatchmanPathEvent event = watchmanEventCollector.getOnlyEvent(WatchmanPathEvent.class);
    RelPath eventPath = event.getRelPath();
    assertThat(eventPath, Matchers.equalTo(path));
    assertSame(event.getKind(), Kind.CREATE);
  }

  // Create a watcher for the given ignore paths, clearing the initial overflow event before
  // returning it.
  private WatchmanWatcher createWatchmanWatcher(PathMatcher... ignorePaths)
      throws IOException, InterruptedException {

    WatchmanWatcher watcher =
        new WatchmanWatcher(
            watchman,
            eventBus,
            ImmutableSet.copyOf(ignorePaths),
            ImmutableMap.of(tmp.getRoot(), new WatchmanCursor("n:buckd" + UUID.randomUUID())),
            /* numThreads */ 1);

    // Clear out the initial overflow event.
    watcher.postEvents(
        new DefaultBuckEventBus(FakeClock.doNotCare(), new BuildId()),
        WatchmanWatcher.FreshInstanceAction.NONE);
    watchmanEventCollector.clear();

    return watcher;
  }

  // TODO(buck_team): unite with WatchmanWatcherTest#EventBuffer
  private static final class WatchmanEventCollector {

    private final List<WatchmanEvent> events = new ArrayList<>();

    @Subscribe
    protected void handle(WatchmanEvent event) {
      events.add(event);
    }

    public void clear() {
      events.clear();
    }

    public ImmutableList<WatchmanEvent> getEvents() {
      return ImmutableList.copyOf(events);
    }

    /** Helper to retrieve the only event of the specific class that should be in the list. */
    public <E extends WatchmanEvent> List<E> filterEventsByClass(Class<E> clazz) {
      return events.stream()
          .filter(e -> clazz.isAssignableFrom(e.getClass()))
          .map(e -> (E) e)
          .collect(Collectors.toList());
    }

    /** Helper to retrieve the only event of the specific class that should be in the list. */
    public <E extends WatchmanEvent> E getOnlyEvent(Class<E> clazz) {
      List<E> filteredEvents = filterEventsByClass(clazz);
      assertEquals(
          String.format("Expected only one event of type %s", clazz.getName()),
          1,
          filteredEvents.size());
      return filteredEvents.get(0);
    }
  }
}
