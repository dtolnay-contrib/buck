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

package com.facebook.buck.query;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.rules.param.ParamName;
import com.google.common.collect.ImmutableList;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Stub Query Environment for use in tests.
 *
 * @param <NODE_TYPE>
 */
public class BaseTestQueryEnvironment<NODE_TYPE> implements QueryEnvironment<NODE_TYPE> {
  @Override
  public QueryEnvironment.TargetEvaluator getTargetEvaluator() {
    throw new UnsupportedOperationException();
  }

  @Override
  public Set<NODE_TYPE> getFwdDeps(Iterable<NODE_TYPE> targets) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Set<NODE_TYPE> getReverseDeps(Iterable<NODE_TYPE> targets) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Set<NODE_TYPE> getInputs(NODE_TYPE target) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Set<NODE_TYPE> getTransitiveClosure(Set<NODE_TYPE> targets) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void buildTransitiveClosure(Set<NODE_TYPE> targetNodes) {
    throw new UnsupportedOperationException();
  }

  @Override
  public String getTargetKind(NODE_TYPE target) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Set<NODE_TYPE> getTestsForTarget(NODE_TYPE target) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Set<NODE_TYPE> getBuildFiles(Set<NODE_TYPE> targets) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Set<NODE_TYPE> getFileOwners(ImmutableList<String> files) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Set<NODE_TYPE> getConfiguredTargets(
      Set<NODE_TYPE> targets, Optional<String> configuration) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Set<NODE_TYPE> getTargetsInAttribute(NODE_TYPE target, ParamName attribute) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Set<Object> filterAttributeContents(
      NODE_TYPE target, ParamName attribute, Predicate<Object> predicate) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Iterable<QueryEnvironment.QueryFunction<NODE_TYPE>> getFunctions() {
    throw new UnsupportedOperationException();
  }

  @Override
  public Optional<BuckEventBus> getEventBus() {
    return Optional.empty();
  }
}
