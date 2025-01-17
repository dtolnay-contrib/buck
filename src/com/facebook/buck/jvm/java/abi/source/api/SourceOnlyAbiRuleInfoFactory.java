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

package com.facebook.buck.jvm.java.abi.source.api;

import com.facebook.buck.util.liteinfersupport.Nullable;
import javax.lang.model.element.Element;
import javax.lang.model.util.Elements;
import javax.tools.JavaFileManager;

/** Used to create the SourceOnlyAbiRuleInfo for a target given a JavaFileManager. */
public interface SourceOnlyAbiRuleInfoFactory {
  SourceOnlyAbiRuleInfo create(JavaFileManager fileManager);

  /** Provides information related to source-only abi support. */
  interface SourceOnlyAbiRuleInfo {

    @Nullable
    String getOwningTarget(Elements elements, Element element);

    boolean elementIsAvailableForSourceOnlyAbi(Elements elements, Element element);

    String getRuleName();

    boolean ruleIsRequiredForSourceOnlyAbi();
  }
}
