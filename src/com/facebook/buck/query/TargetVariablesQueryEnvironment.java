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
import com.google.common.collect.ImmutableMap;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Provides a view of an existing {@link QueryEnvironment} augmented with additional target
 * variables.
 */
public class TargetVariablesQueryEnvironment<NODE_TYPE> implements QueryEnvironment<NODE_TYPE> {

  private final ImmutableMap<String, Set<NODE_TYPE>> targetVariables;
  private final QueryEnvironment<NODE_TYPE> delegate;

  public TargetVariablesQueryEnvironment(
      ImmutableMap<String, Set<NODE_TYPE>> targetVariables, QueryEnvironment<NODE_TYPE> delegate) {
    this.targetVariables = targetVariables;
    this.delegate = delegate;
  }

  @Override
  public TargetEvaluator<NODE_TYPE> getTargetEvaluator() {
    return delegate.getTargetEvaluator();
  }

  @Override
  public Set<NODE_TYPE> getFwdDeps(Iterable<NODE_TYPE> targets) throws QueryException {
    return delegate.getFwdDeps(targets);
  }

  @Override
  public Set<NODE_TYPE> getReverseDeps(Iterable<NODE_TYPE> targets) throws QueryException {
    return delegate.getReverseDeps(targets);
  }

  @Override
  public Set<NODE_TYPE> getInputs(NODE_TYPE target) throws QueryException {
    return delegate.getInputs(target);
  }

  @Override
  public Set<NODE_TYPE> getTransitiveClosure(Set<NODE_TYPE> targets) throws QueryException {
    return delegate.getTransitiveClosure(targets);
  }

  @Override
  public void buildTransitiveClosure(Set<NODE_TYPE> targetNodes) throws QueryException {
    delegate.buildTransitiveClosure(targetNodes);
  }

  @Override
  public String getTargetKind(NODE_TYPE target) throws QueryException {
    return delegate.getTargetKind(target);
  }

  @Override
  public Set<NODE_TYPE> getTestsForTarget(NODE_TYPE target) throws QueryException {
    return delegate.getTestsForTarget(target);
  }

  @Override
  public Set<NODE_TYPE> getBuildFiles(Set<NODE_TYPE> targets) throws QueryException {
    return delegate.getBuildFiles(targets);
  }

  @Override
  public Set<NODE_TYPE> getFileOwners(ImmutableList<String> files) throws QueryException {
    return delegate.getFileOwners(files);
  }

  @Override
  public Set<NODE_TYPE> getConfiguredTargets(Set<NODE_TYPE> targets, Optional<String> configuration)
      throws QueryException {
    return delegate.getConfiguredTargets(targets, configuration);
  }

  @Override
  public Set<NODE_TYPE> getTargetsInAttribute(NODE_TYPE target, ParamName attribute)
      throws QueryException {
    return delegate.getTargetsInAttribute(target, attribute);
  }

  @Override
  public Set<Object> filterAttributeContents(
      NODE_TYPE target, ParamName attribute, Predicate<Object> predicate) throws QueryException {
    return delegate.filterAttributeContents(target, attribute, predicate);
  }

  @Override
  public Iterable<QueryFunction<NODE_TYPE>> getFunctions() {
    return delegate.getFunctions();
  }

  @Override
  public Set<NODE_TYPE> resolveTargetVariable(String name) {
    Set<NODE_TYPE> targets = targetVariables.get(name);
    if (targets != null) {
      return targets;
    }
    return delegate.resolveTargetVariable(name);
  }

  @Override
  public Optional<BuckEventBus> getEventBus() {
    return delegate.getEventBus();
  }
}
