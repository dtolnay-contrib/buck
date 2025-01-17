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

package com.facebook.buck.intellij.ideabuck.lang.psi;

import com.facebook.buck.intellij.ideabuck.lang.BcfgFileType;
import com.intellij.psi.tree.IElementType;

/** Base {@link IElementType} for {@code .buckconfig} files. */
public class BcfgElementType extends IElementType {

  public BcfgElementType(String debugName) {
    super(debugName, BcfgFileType.INSTANCE.getLanguage());
  }
}
