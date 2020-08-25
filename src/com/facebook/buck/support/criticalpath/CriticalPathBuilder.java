/*
 * Copyright (c) Facebook, Inc. and its affiliates.
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

package com.facebook.buck.support.criticalpath;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.BuildRule;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import javax.annotation.concurrent.GuardedBy;

/**
 * Builder class for constructing the critical path of a build using build rule completion
 * callbacks. This class is designed to be driven by a listener to build events.
 *
 * <p>In addition to providing the critical path, this class also provides sub-critical path deltas
 * along the path. For every node where this applies (i.e. more than one dependency), both the
 * critical dependency and the second-longest dependency are reported.
 */
public final class CriticalPathBuilder {
  /** Map of build targets to critical path nodes. */
  @GuardedBy("this")
  private final Map<BuildTarget, CriticalPathNode> criticalPathNodeMap;

  /**
   * Map of build targets to execution times. If a build target was retrieved from cache instead of
   * built, there will not be an entry in this map for that target.
   */
  @GuardedBy("this")
  private final Map<BuildTarget, Long> executionTimeMap;

  /** The longest path seen so far. Updated continuously as new build rules complete. */
  @GuardedBy("this")
  private CriticalPathNode longestPathSoFar;

  public CriticalPathBuilder() {
    this.criticalPathNodeMap = Maps.newHashMap();
    this.executionTimeMap = Maps.newHashMap();
    this.longestPathSoFar = null;
  }

  /**
   * Records that a build rule has completed execution with the given execution time, in
   * milliseconds. This callback should be invoked only for rules that executed; cache hits are not
   * executed and do not apply here.
   *
   * @param rule The rule that just finished executing
   * @param elapsedTimeMillis The duration of the executed rule, in milliseconds
   */
  public synchronized void onBuildRuleCompletedExecution(BuildRule rule, long elapsedTimeMillis) {
    executionTimeMap.put(rule.getBuildTarget(), elapsedTimeMillis);
  }

  /**
   * Records that a build rule has been finalized and its processing is now complete. This callback
   * is invoked for all rules, regardless of whether or not they hit the cache.
   *
   * @param rule The rule that was just finalized
   */
  public synchronized void onBuildRuleFinalized(BuildRule rule, long eventNanoTime) {
    long longestSoFar = 0;
    CriticalPathNode longestPath = null;

    // For every dependency of this rule, find the longest path ending at that dependency and
    // extend it with this rule.
    for (BuildRule dependency : rule.getBuildDeps()) {
      BuildTarget dependencyTarget = dependency.getBuildTarget();
      CriticalPathNode cpNode = criticalPathNodeMap.get(dependencyTarget);
      if (cpNode != null && cpNode.getPathCost() > longestSoFar) {
        longestSoFar = cpNode.getPathCost();
        longestPath = cpNode;
      }
    }

    // Default to 0 if not in the execution time map; this indicates a cache hit.
    long thisTargetExecutionTime = executionTimeMap.getOrDefault(rule.getBuildTarget(), 0L);
    CriticalPathNode thisRuleNode =
        ImmutableCriticalPathNode.ofImpl(
            longestPath, rule, longestSoFar + thisTargetExecutionTime, eventNanoTime);
    criticalPathNodeMap.put(rule.getBuildTarget(), thisRuleNode);

    // Is this new path the longest one we've seen so far? Stash it, it might be the critical path.
    if (longestPathSoFar == null || thisRuleNode.getPathCost() > longestPathSoFar.getPathCost()) {
      longestPathSoFar = thisRuleNode;
    }
  }

  /**
   * Retrieves the reportable critical path generated by the build rules that were reported.
   *
   * @return The reportable critical path
   */
  public synchronized ImmutableList<ReportableCriticalPathNode> getCriticalPath() {
    ImmutableList.Builder<ReportableCriticalPathNode> criticalPath = ImmutableList.builder();
    CriticalPathNode cursor = longestPathSoFar;

    // Map from rules on the critical path to sibling rules that were the second-longest incoming
    // dependency edge to the next target on the critical path.
    Map<BuildTarget, BuildTarget> siblingMap = Maps.newHashMap();

    // CriticalPathNode forms a singly-linked list that forms the critical path in reverse. Walk
    // the linked list and build it up, node-by-node.
    while (cursor != null) {
      ImmutableReportableCriticalPathNode.Builder reportableNode =
          ImmutableReportableCriticalPathNode.builder();
      BuildRule rule = cursor.getRule();
      BuildTarget ruleTarget = rule.getBuildTarget();
      reportableNode.setTarget(ruleTarget).setPathCostMilliseconds(cursor.getPathCost());
      long ruleExecutionTime = executionTimeMap.getOrDefault(ruleTarget, 0L);
      reportableNode.setExecutionTimeMilliseconds(ruleExecutionTime);
      reportableNode.setType(rule.getType());
      reportableNode.setEventNanoTime(cursor.getEventNanoTime());

      // If we recorded a sibling sub-critical path for this target, record it.
      if (siblingMap.containsKey(ruleTarget)) {
        BuildTarget siblingTarget = siblingMap.get(ruleTarget);
        long siblingExecutionTime = executionTimeMap.getOrDefault(siblingTarget, 0L);
        reportableNode.setClosestSiblingTarget(siblingTarget);
        reportableNode.setClosestSiblingExecutionTimeDelta(
            ruleExecutionTime - siblingExecutionTime);
      }

      if (cursor.getParent() != null) {
        // If we've got more nodes to go in the critical path, find the second-longest incoming
        // edge and save it in the sibling map - we'll use it when constructing the node for the
        // incoming edge.
        final BuildTarget parentTarget = cursor.getParent().getRule().getBuildTarget();
        Optional<BuildRule> secondLongestPathRule =
            cursor.getRule().getBuildDeps().stream()
                .filter(r -> !r.getBuildTarget().equals(parentTarget))
                .max(
                    Comparator.comparing(
                        r -> executionTimeMap.getOrDefault(r.getBuildTarget(), 0L)));

        if (secondLongestPathRule.isPresent()) {
          BuildTarget secondLongestPathTarget = secondLongestPathRule.get().getBuildTarget();
          siblingMap.put(parentTarget, secondLongestPathTarget);
        }
      }

      criticalPath.add(reportableNode.build());
      cursor = cursor.getParent();
    }

    // Since the critical path linked list is built up in reverse, reverse the final list to get the
    // critical path in the correct order.
    return criticalPath.build().reverse();
  }
}