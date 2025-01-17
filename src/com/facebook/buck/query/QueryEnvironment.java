/*
 * Portions Copyright (c) Meta Platforms, Inc. and affiliates.
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

// Copyright 2014 Google Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.facebook.buck.query;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.rules.param.ParamName;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import javax.annotation.Nullable;

/**
 * The environment of a Buck query that can evaluate queries to produce a result.
 *
 * <p>The query language is documented at docs/command/query.soy
 *
 * @param <NODE_TYPE> The primary type of "node" in the graph over which a {@code buck query} is run
 *     in this environment. Although all objects returned by {@link QueryEnvironment} implement
 *     {@link ConfiguredQueryTarget}, which is a marker interface for all possible "nodes" in the
 *     graph being queried, <em>most</em> methods return objects that correspond to build rules. As
 *     such, {@code NODE_TYPE} specifies the type used to represent build rules in this environment.
 *     Methods that return objects of type {@code NODE_TYPE} therefore provide stronger guarantees
 *     than those that only guarantee {@link ConfiguredQueryTarget} as the return type.
 */
public interface QueryEnvironment<NODE_TYPE> {

  /** Type of an argument of a user-defined query function. */
  enum ArgumentType {
    EXPRESSION,
    WORD,
    INTEGER,
  }

  /**
   * Value of an argument of a user-defined query function.
   *
   * @param <ENV_NODE_TYPE> If this argument represents an {@link ArgumentType#EXPRESSION},
   *     determines the type of the {@link QueryEnvironment} in which the expression is expected to
   *     be evaluated.
   */
  class Argument<ENV_NODE_TYPE> {

    private final ArgumentType type;

    @Nullable private final QueryExpression<ENV_NODE_TYPE> expression;

    @Nullable private final String word;

    private final int integer;

    private Argument(
        ArgumentType type,
        @Nullable QueryExpression<ENV_NODE_TYPE> expression,
        @Nullable String word,
        int integer) {
      this.type = type;
      this.expression = expression;
      this.word = word;
      this.integer = integer;
    }

    public static <T> Argument<T> of(QueryExpression<T> expression) {
      return new Argument<T>(ArgumentType.EXPRESSION, expression, null, 0);
    }

    public static Argument<?> of(String word) {
      return new Argument<>(ArgumentType.WORD, null, word, 0);
    }

    public static Argument<?> of(int integer) {
      return new Argument<>(ArgumentType.INTEGER, null, null, integer);
    }

    public ArgumentType getType() {
      return type;
    }

    public QueryExpression<ENV_NODE_TYPE> getExpression() {
      return Objects.requireNonNull(expression);
    }

    public String getWord() {
      return Objects.requireNonNull(word);
    }

    public int getInteger() {
      return integer;
    }

    @Override
    public String toString() {
      switch (type) {
        case WORD:
          return "'" + word + "'";
        case EXPRESSION:
          return Objects.requireNonNull(expression).toString();
        case INTEGER:
          return Integer.toString(integer);
        default:
          throw new IllegalStateException();
      }
    }

    @Override
    public boolean equals(Object other) {
      return (other instanceof Argument) && equalTo((Argument<?>) other);
    }

    public boolean equalTo(Argument<?> other) {
      return type.equals(other.type)
          && integer == other.integer
          && Objects.equals(expression, other.expression)
          && Objects.equals(word, other.word);
    }

    @Override
    public int hashCode() {
      int h = 31;
      h = h * 17 + type.hashCode();
      h = h * 17 + integer;
      if (expression != null) {
        h = h * 17 + expression.hashCode();
      }
      if (word != null) {
        h = h * 17 + word.hashCode();
      }
      return h;
    }
  }

  /** A user-defined query function, which operates on the nodes of the QueryEnvironment */
  interface QueryFunction<ENV_NODE_TYPE> {
    /** Name of the function as it appears in the query language. */
    String getName();

    /**
     * The number of arguments that are required. The rest is optional.
     *
     * <p>This should be greater than or equal to zero and at smaller than or equal to the length of
     * the list returned by {@link #getArgumentTypes}.
     */
    int getMandatoryArguments();

    /** The types of the arguments of the function. */
    ImmutableList<ArgumentType> getArgumentTypes();

    /**
     * Called when a user-defined function is to be evaluated.
     *
     * @param evaluator the evaluator for evaluating argument expressions.
     * @param env the query environment this function is evaluated in.
     * @param args the input arguments. These are type-checked against the specification returned by
     *     {@link #getArgumentTypes} and {@link #getMandatoryArguments}
     * @return results of evaluating the query expression. The result type is mutable {@link Set} to
     *     enable actual implementation to avoid making unnecessary copies, but resulting set is not
     *     supposed to be mutated afterwards so implementation is ok to return {@link ImmutableSet}.
     */
    Set<ENV_NODE_TYPE> eval(
        QueryEvaluator<ENV_NODE_TYPE> evaluator,
        QueryEnvironment<ENV_NODE_TYPE> env,
        ImmutableList<Argument<ENV_NODE_TYPE>> args)
        throws QueryException;
  }

  /**
   * A procedure for evaluating a target literal to {@link ConfiguredQueryTarget}. This evaluation
   * can either happen immediately at parse time or be delayed until evalution of the entire query.
   */
  interface TargetEvaluator<ENV_NODE_TYPE> {
    /** Returns the set of target nodes for the specified target pattern, in 'buck build' syntax. */
    Set<ENV_NODE_TYPE> evaluateTarget(String target) throws QueryException;

    Type getType();

    enum Type {
      IMMEDIATE,
      LAZY
    }
  }

  /** Returns an evaluator for target patterns. */
  TargetEvaluator<NODE_TYPE> getTargetEvaluator();

  /** Query parser environment. */
  default QueryParserEnv<NODE_TYPE> getQueryParserEnv() {
    return QueryParserEnv.of(getFunctions(), getTargetEvaluator());
  }

  /** Returns the direct forward dependencies of the specified targets. */
  Set<NODE_TYPE> getFwdDeps(Iterable<NODE_TYPE> targets) throws QueryException;

  /**
   * Applies {@code action} to each forward dependencies of the specified targets.
   *
   * <p>Might apply more than once to the same target, so {@code action} should be idempotent.
   */
  default void forEachFwdDep(Iterable<NODE_TYPE> targets, Consumer<NODE_TYPE> action)
      throws QueryException {
    getFwdDeps(targets).forEach(action);
  }

  /** Returns the direct reverse dependencies of the specified targets. */
  Set<NODE_TYPE> getReverseDeps(Iterable<NODE_TYPE> targets) throws QueryException;

  Set<NODE_TYPE> getInputs(NODE_TYPE target) throws QueryException;

  /**
   * Returns the forward transitive closure of all of the targets in "targets". Callers must ensure
   * that {@link #buildTransitiveClosure} has been called for the relevant subgraph.
   */
  Set<NODE_TYPE> getTransitiveClosure(Set<NODE_TYPE> targets) throws QueryException;

  /**
   * Construct the dependency graph for a depth-bounded forward transitive closure of all nodes in
   * "targetNodes". The identity of the calling expression is required to produce error messages.
   *
   * <p>If a larger transitive closure was already built, returns it to improve incrementality,
   * since all depth-constrained methods filter it after it is built anyway.
   */
  void buildTransitiveClosure(Set<NODE_TYPE> targetNodes) throws QueryException;

  String getTargetKind(NODE_TYPE target) throws QueryException;

  /** Returns the tests associated with the given target. */
  Set<NODE_TYPE> getTestsForTarget(NODE_TYPE target) throws QueryException;

  /** Returns the build files that define the given targets. */
  Set<NODE_TYPE> getBuildFiles(Set<NODE_TYPE> targets) throws QueryException;

  /** Returns the targets that own one or more of the given files. */
  Set<NODE_TYPE> getFileOwners(ImmutableList<String> files) throws QueryException;

  /**
   * Returns a set of targets equal to the input targets except configured for `configuration`
   * instead (or the default target platform if no configuration is provided)
   */
  Set<NODE_TYPE> getConfiguredTargets(Set<NODE_TYPE> targets, Optional<String> configuration)
      throws QueryException;

  /**
   * Returns the existing targets in the value of `attribute` of the given `target`.
   *
   * <p>Note that unlike most methods in this interface, this method can return a heterogeneous
   * collection of objects (both paths and build targets).
   */
  Set<NODE_TYPE> getTargetsInAttribute(NODE_TYPE target, ParamName attribute) throws QueryException;

  /** Returns the objects in the `attribute` of the given `target` that satisfy `predicate` */
  Set<Object> filterAttributeContents(
      NODE_TYPE target, ParamName attribute, Predicate<Object> predicate) throws QueryException;

  /** Returns the set of query functions implemented by this query environment. */
  Iterable<QueryFunction<NODE_TYPE>> getFunctions();

  /** @return the {@link NODE_TYPE}s expanded from the given variable {@code name}. */
  default Set<NODE_TYPE> resolveTargetVariable(String name) {
    throw new IllegalArgumentException(String.format("unexpected target variable \"%s\"", name));
  }

  Optional<BuckEventBus> getEventBus();
}
