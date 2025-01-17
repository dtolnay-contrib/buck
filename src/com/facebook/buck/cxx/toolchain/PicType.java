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

package com.facebook.buck.cxx.toolchain;

import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorConvertible;
import com.facebook.buck.core.model.InternalFlavor;
import com.google.common.collect.ImmutableList;

/**
 * Different compilation modes for PIC (position-independent code) and PDC (position-dependent
 * code).
 */
public enum PicType implements FlavorConvertible {

  // Generate position-independent code (e.g. for use in shared libraries).
  PIC(InternalFlavor.of("pic")) {
    @Override
    public ImmutableList<String> getFlags(Compiler compiler) {
      return compiler.getPicFlags();
    }
  },

  // Generate position-dependent code.
  PDC(InternalFlavor.of("no-pic")) {
    @Override
    public ImmutableList<String> getFlags(Compiler compiler) {
      return compiler.getPdcFlags();
    }
  };

  private final InternalFlavor flavor;

  PicType(InternalFlavor flavor) {
    this.flavor = flavor;
  }

  public abstract ImmutableList<String> getFlags(Compiler compiler);

  @Override
  public Flavor getFlavor() {
    return flavor;
  }
}
