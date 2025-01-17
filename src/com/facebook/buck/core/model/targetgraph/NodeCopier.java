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

package com.facebook.buck.core.model.targetgraph;

import com.facebook.buck.core.description.arg.ConstructorArg;
import com.facebook.buck.core.model.Flavor;
import com.google.common.collect.ImmutableSet;

/**
 * Provides method for copying TargetNodes.
 *
 * <p>Primarily just used to break dependency between TargetNode and TargetNodeFactory.
 */
public interface NodeCopier {
  <T extends ConstructorArg> TargetNode<T> copyNodeWithFlavors(
      TargetNode<T> node, ImmutableSet<Flavor> flavors);
}
