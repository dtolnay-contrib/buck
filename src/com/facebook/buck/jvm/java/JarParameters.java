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

package com.facebook.buck.jvm.java;

import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.util.immutables.BuckStyleValueWithBuilder;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.logging.Level;
import org.immutables.value.Value;

@BuckStyleValueWithBuilder
public abstract class JarParameters {
  @Value.Default
  public boolean getHashEntries() {
    return false;
  }

  @Value.Default
  public boolean getMergeManifests() {
    return false;
  }

  public abstract RelPath getJarPath();

  @Value.Default
  public Predicate<Object> getRemoveEntryPredicate() {
    return RemoveClassesPatternsMatcher.EMPTY;
  }

  public abstract ImmutableSortedSet<RelPath> getEntriesToJar();

  @Value.Default
  public ImmutableSortedSet<RelPath> getOverrideEntriesToJar() {
    return ImmutableSortedSet.of();
  }

  public abstract Optional<String> getMainClass();

  public abstract Optional<RelPath> getManifestFile();

  @Value.Default
  public Level getDuplicatesLogLevel() {
    return Level.INFO;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder extends ImmutableJarParameters.Builder {}
}
