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

package com.facebook.buck.util.versioncontrol;

import com.facebook.buck.log.views.JsonViews;
import com.fasterxml.jackson.annotation.JsonView;
import com.google.common.collect.ImmutableSet;

public interface CommonFastVersionControlStats {

  /* Commit hash of the current revision. */
  @JsonView(JsonViews.MachineReadableLog.class)
  String getCurrentRevisionId();

  /* A list of bookmarks that the current commit is based and also exist in TRACKED_BOOKMARKS */
  @JsonView(JsonViews.MachineReadableLog.class)
  ImmutableSet<String> getBaseBookmarks();

  /* Commit hash of the revision that is the common base between current revision and master. */
  @JsonView(JsonViews.MachineReadableLog.class)
  String getBranchedFromMasterRevisionId();

  /* The timestamp of the base revision */
  @JsonView(JsonViews.MachineReadableLog.class)
  Long getBranchedFromMasterTS();
}
