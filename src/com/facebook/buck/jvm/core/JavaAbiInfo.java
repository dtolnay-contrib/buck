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

package com.facebook.buck.jvm.core;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;

/** Provides information about a java abi. */
public interface JavaAbiInfo extends BaseJavaAbiInfo {

  BuildTarget getBuildTarget();

  ImmutableSortedSet<SourcePath> getJarContents();

  void load(SourcePathResolverAdapter pathResolver) throws IOException;

  void invalidate();
}
