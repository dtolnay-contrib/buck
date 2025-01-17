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

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.util.graph.AbstractBreadthFirstTraversal;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkTarget;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A helper class for building the included and excluded omnibus roots to pass to the omnibus
 * builder.
 */
@BuckStyleValue
public abstract class OmnibusRoots {

  /** @return the {@link NativeLinkTarget} roots that are included in omnibus linking. */
  public abstract ImmutableMap<BuildTarget, NativeLinkTarget> getIncludedRoots();

  /** @return the {@link NativeLinkable} roots that are excluded from omnibus linking. */
  public abstract ImmutableMap<BuildTarget, NativeLinkable> getExcludedRoots();

  public static Builder builder(
      ImmutableSet<BuildTarget> excludes, ActionGraphBuilder graphBuilder) {
    return new Builder(excludes, graphBuilder);
  }

  public static class Builder {
    private final ImmutableSet<BuildTarget> excludes;
    private final ActionGraphBuilder graphBuilder;

    private final Map<BuildTarget, NativeLinkTarget> includedRoots = new LinkedHashMap<>();
    private final Map<BuildTarget, NativeLinkable> excludedRoots = new LinkedHashMap<>();

    private Builder(ImmutableSet<BuildTarget> excludes, ActionGraphBuilder graphBuilder) {
      this.excludes = excludes;
      this.graphBuilder = graphBuilder;
    }

    /** Add a root which is included in omnibus linking. */
    public void addIncludedRoot(NativeLinkTarget root) {
      includedRoots.put(root.getBuildTarget(), root);
    }

    /** Add a root which is excluded from omnibus linking. */
    public void addExcludedRoot(NativeLinkable root) {
      excludedRoots.put(root.getBuildTarget(), root);
    }

    /**
     * Add a node which may qualify as either an included root, and excluded root, or neither.
     *
     * @return whether the node was added as a root.
     */
    public void addPotentialRoot(
        NativeLinkable node, boolean includePrivateLinkerFlags, boolean preferStrippedObjects) {
      Optional<NativeLinkTarget> target =
          node.getNativeLinkTarget(graphBuilder, includePrivateLinkerFlags, preferStrippedObjects);
      if (target.isPresent()
          && !excludes.contains(node.getBuildTarget())
          && node.supportsOmnibusLinking()) {
        addIncludedRoot(target.get());
      } else {
        addExcludedRoot(node);
      }
    }

    private ImmutableMap<BuildTarget, NativeLinkable> buildExcluded() {
      Map<BuildTarget, NativeLinkable> excluded = new LinkedHashMap<>(excludedRoots);

      // Find all excluded nodes reachable from the included roots.
      Map<BuildTarget, NativeLinkable> includedRootDeps = new LinkedHashMap<>();
      for (NativeLinkTarget target : includedRoots.values()) {
        for (NativeLinkable linkable : target.getNativeLinkTargetDeps(graphBuilder)) {
          includedRootDeps.put(linkable.getBuildTarget(), linkable);
        }
      }
      new AbstractBreadthFirstTraversal<NativeLinkable>(includedRootDeps.values()) {
        @Override
        public Iterable<NativeLinkable> visit(NativeLinkable linkable) throws RuntimeException {
          if (!linkable.supportsOmnibusLinking()) {
            excluded.put(linkable.getBuildTarget(), linkable);
            return ImmutableSet.of();
          }
          return Iterables.concat(
              linkable.getNativeLinkableDeps(graphBuilder),
              linkable.getNativeLinkableExportedDeps(graphBuilder));
        }
      }.start();

      // Prepare the final map of excluded roots, starting with the pre-defined ones.
      Map<BuildTarget, NativeLinkable> updatedExcludedRoots = new LinkedHashMap<>(excludedRoots);

      // Recursively expand the excluded nodes including any preloaded deps, as we'll need this full
      // list to know which roots to exclude from omnibus linking.
      new AbstractBreadthFirstTraversal<NativeLinkable>(excluded.values()) {
        @Override
        public Iterable<NativeLinkable> visit(NativeLinkable linkable) {
          if (includedRoots.containsKey(linkable.getBuildTarget())) {
            updatedExcludedRoots.put(linkable.getBuildTarget(), linkable);
          }
          return Iterables.concat(
              linkable.getNativeLinkableDeps(graphBuilder),
              linkable.getNativeLinkableExportedDeps(graphBuilder));
        }
      }.start();

      return ImmutableMap.copyOf(updatedExcludedRoots);
    }

    private ImmutableMap<BuildTarget, NativeLinkTarget> buildIncluded(
        ImmutableSet<BuildTarget> excluded) {
      return ImmutableMap.copyOf(
          Maps.filterKeys(includedRoots, Predicates.not(excluded::contains)));
    }

    public boolean isEmpty() {
      return includedRoots.isEmpty() && excludedRoots.isEmpty();
    }

    public OmnibusRoots build() {
      ImmutableMap<BuildTarget, NativeLinkable> excluded = buildExcluded();
      ImmutableMap<BuildTarget, NativeLinkTarget> included = buildIncluded(excluded.keySet());
      return ImmutableOmnibusRoots.ofImpl(included, excluded);
    }
  }
}
