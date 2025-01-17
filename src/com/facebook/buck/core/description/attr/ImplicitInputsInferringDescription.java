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

package com.facebook.buck.core.description.attr;

import com.facebook.buck.core.filesystems.ForwardRelPath;
import com.facebook.buck.core.model.UnflavoredBuildTarget;
import com.facebook.buck.rules.param.ParamName;
import com.facebook.buck.util.collect.TwoArraysImmutableHashMap;
import com.google.common.collect.ImmutableSet;

/**
 * While building up the target graph, we infer implicit inputs of a rule if certain fields are
 * absent (e.g. src field). Any {@link com.facebook.buck.core.description.Description} that
 * implements this interface can modify its implicit inputs by poking at the raw build rule params.
 */
public interface ImplicitInputsInferringDescription {

  ImmutableSet<ForwardRelPath> inferInputsFromAttributes(
      UnflavoredBuildTarget buildTarget, TwoArraysImmutableHashMap<ParamName, Object> attributes);
}
