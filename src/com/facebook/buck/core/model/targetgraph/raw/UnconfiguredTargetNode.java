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

package com.facebook.buck.core.model.targetgraph.raw;

import com.facebook.buck.core.graph.transformation.model.ComputeResult;
import com.facebook.buck.core.model.RuleType;
import com.facebook.buck.core.model.UnflavoredBuildTarget;
import com.facebook.buck.rules.param.ParamName;
import com.facebook.buck.rules.visibility.VisibilityPattern;
import com.facebook.buck.util.collect.TwoArraysImmutableHashMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.util.Optional;

/**
 * A target node with attributes kept in a map as oppose to in a structured object like in {@link
 * com.facebook.buck.core.model.targetgraph.TargetNode}.
 *
 * <p>The attributes are coerced from raw data produced by build file parser, but they are not
 * stored in a structured object as in {@link com.facebook.buck.core.model.targetgraph.TargetNode}.
 *
 * <p>The main purpose of having such nodes is to perform additional processing before storing them
 * in a structured constructor arguments.
 */
public interface UnconfiguredTargetNode extends Comparable<UnconfiguredTargetNode>, ComputeResult {

  /** Build target of this node. */
  UnflavoredBuildTarget getBuildTarget();

  /** The type of a rule. */
  RuleType getRuleType();

  /**
   * Attributes of this node coerced to the types declared in constructor arguments.
   *
   * <p>Note that some of these attributes may require additional processing before they can be
   * stored in a constructor argument. For example, selectable arguments need to be resolved first.
   */
  TwoArraysImmutableHashMap<ParamName, Object> getAttributes();

  /** List of patterns from <code>visibility</code> attribute. */
  ImmutableSet<VisibilityPattern> getVisibilityPatterns();

  /** List of patterns from <code>within_view</code> attribute. */
  ImmutableSet<VisibilityPattern> getWithinViewPatterns();

  /**
   * Value of {@code default_target_platform} attribute. Note this attribute only exists for build
   * targets.
   */
  Optional<UnflavoredBuildTarget> getDefaultTargetPlatform();

  /**
   * Value of {@code default_host_platform} attribute. Note this attribute only exists for build
   * targets.
   */
  Optional<UnflavoredBuildTarget> getDefaultHostPlatform();

  /**
   * List of targets from <code>compatible_with</code> attribute. Note method exists for all rules,
   * while {@code compatible_with} can be defined only for build rules.
   */
  ImmutableList<UnflavoredBuildTarget> getCompatibleWith();

  @Override
  default int compareTo(UnconfiguredTargetNode other) {
    return getBuildTarget().compareTo(other.getBuildTarget());
  }
}
