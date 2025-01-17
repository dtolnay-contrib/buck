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

package com.facebook.buck.android;

import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.util.graph.AbstractBreadthFirstTraversal;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.jvm.java.JavaTest;
import com.google.common.collect.ImmutableSet;
import java.util.Collection;
import java.util.Set;

public class UnsortedAndroidResourceDeps {

  private static final ImmutableSet<Class<? extends BuildRule>> TRAVERSABLE_TYPES =
      ImmutableSet.of(
          AndroidApk.class,
          AndroidInstrumentationApk.class,
          AndroidLibrary.class,
          AndroidResource.class,
          ApkGenrule.class,
          JavaLibrary.class,
          JavaTest.class,
          RobolectricTest.class);

  private final ImmutableSet<HasAndroidResourceDeps> resourceDeps;

  public UnsortedAndroidResourceDeps(ImmutableSet<HasAndroidResourceDeps> resourceDeps) {
    this.resourceDeps = resourceDeps;
  }

  public ImmutableSet<HasAndroidResourceDeps> getResourceDeps() {
    return resourceDeps;
  }

  /**
   * Returns transitive android resource deps which are _not_ sorted topologically, only to be used
   * when the order of the resource rules does not matter, for instance, when graph enhancing
   * UberRDotJava, DummyRDotJava, AaptPackageResources where we only need the deps to correctly
   * order the execution of those buildables.
   */
  public static UnsortedAndroidResourceDeps createFrom(Collection<BuildRule> rules) {

    ImmutableSet.Builder<HasAndroidResourceDeps> androidResources = ImmutableSet.builder();

    // This visitor finds all AndroidResourceRules that are reachable from the specified rules via
    // rules with types in the TRAVERSABLE_TYPES collection.
    AbstractBreadthFirstTraversal<BuildRule> visitor =
        new AbstractBreadthFirstTraversal<BuildRule>(rules) {

          @Override
          public Iterable<BuildRule> visit(BuildRule rule) {
            HasAndroidResourceDeps androidResourceRule = null;
            if (rule instanceof HasAndroidResourceDeps) {
              androidResourceRule = (HasAndroidResourceDeps) rule;
            }
            if (androidResourceRule != null
                && (androidResourceRule.getRes() != null
                    || androidResourceRule.getAssets() != null)) {
              androidResources.add(androidResourceRule);
            }

            // Only certain types of rules should be considered as part of this traversal.
            // For JavaLibrary rules, we need to grab the deps directly from the rule and not from
            // the BuildRuleParams object. BuildRuleParams may hold ABI rules which don't allow
            // us to properly traverse and locate the transitive android resource deps
            Set<BuildRule> depsToVisit;
            if (rule instanceof JavaLibrary) {
              depsToVisit = ((JavaLibrary) rule).getDepsForTransitiveClasspathEntries();
            } else if (rule instanceof AndroidResource) {
              depsToVisit = ((AndroidResource) rule).getDeps();
            } else {
              depsToVisit =
                  TRAVERSABLE_TYPES.contains(rule.getClass())
                      ? rule.getBuildDeps()
                      : ImmutableSet.of();
            }
            return depsToVisit;
          }
        };
    visitor.start();

    return new UnsortedAndroidResourceDeps(androidResources.build());
  }
}
