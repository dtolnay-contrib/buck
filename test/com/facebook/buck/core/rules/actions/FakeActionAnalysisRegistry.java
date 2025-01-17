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

package com.facebook.buck.core.rules.actions;

import com.facebook.buck.core.rules.analysis.action.ActionAnalysisData;
import com.facebook.buck.core.rules.analysis.action.ActionAnalysisDataKey;
import com.facebook.buck.core.rules.analysis.action.ActionAnalysisDataRegistry;
import com.google.common.collect.ImmutableMap;
import java.util.HashMap;
import java.util.Map;

public class FakeActionAnalysisRegistry implements ActionAnalysisDataRegistry {

  private final Map<ActionAnalysisDataKey, ActionAnalysisData> registry = new HashMap<>();

  @Override
  public void registerAction(ActionAnalysisData actionAnalysisData) {
    registry.put(actionAnalysisData.getKey(), actionAnalysisData);
  }

  public ImmutableMap<ActionAnalysisDataKey, ActionAnalysisData> getRegistered() {
    return ImmutableMap.copyOf(registry);
  }

  public void clear() {
    registry.clear();
  }
}
