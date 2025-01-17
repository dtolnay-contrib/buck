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

package com.facebook.buck.android.toolchain.ndk;

/** The type of compiler from the Android ndk. */
public enum NdkCompilerType {
  GCC("gcc", "gcc", "g++"),
  CLANG("clang", "clang", "clang++"),
  ;

  public final String name;
  public final String cc;
  public final String cxx;

  NdkCompilerType(String name, String cc, String cxx) {
    this.name = name;
    this.cc = cc;
    this.cxx = cxx;
  }
}
