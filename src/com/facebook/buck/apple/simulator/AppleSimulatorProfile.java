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

package com.facebook.buck.apple.simulator;

import com.facebook.buck.core.util.immutables.BuckStyleValueWithBuilder;
import com.google.common.collect.ImmutableSet;

/** Immutable value type containing metadata about an Apple simulator. */
@BuckStyleValueWithBuilder
interface AppleSimulatorProfile {
  /**
   * Set of integers describing which Apple product families this simulator supports (1: iPhone, 2:
   * iPad, 4: Apple Watch, etc.)
   *
   * <p>We don't return an enum here since new identifiers are introduced over time, and we don't
   * want to lost the information at this level.
   */
  ImmutableSet<Integer> getSupportedProductFamilyIDs();

  /**
   * Set of strings containing the architectures supported by this simulator (i.e., i386, x86_64,
   * etc.)
   */
  ImmutableSet<String> getSupportedArchitectures();
}
