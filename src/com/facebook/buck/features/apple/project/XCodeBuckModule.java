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

package com.facebook.buck.features.apple.project;

import com.facebook.buck.core.module.BuckModule;
import com.facebook.buck.features.alias.AliasModule;
import com.facebook.buck.features.filegroup.FilegroupModule;
import com.facebook.buck.features.halide.HalideModule;
import com.facebook.buck.features.js.JsModule;

/** Buck module with a project generator for XCode. */
@BuckModule(
    dependencies = {
      AliasModule.class,
      FilegroupModule.class,
      HalideModule.class,
      JsModule.class,
    })
public class XCodeBuckModule {}
