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

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.toolprovider.ToolProvider;
import com.google.common.collect.ImmutableList;

public interface ArchiverProvider {

  Archiver resolve(BuildRuleResolver resolver, TargetConfiguration targetConfiguration);

  Iterable<BuildTarget> getParseTimeDeps(TargetConfiguration targetConfiguration);

  static ArchiverProvider from(Archiver archiver) {
    return new ArchiverProvider() {

      @Override
      public Archiver resolve(BuildRuleResolver resolver, TargetConfiguration targetConfiguration) {
        return archiver;
      }

      @Override
      public Iterable<BuildTarget> getParseTimeDeps(TargetConfiguration targetConfiguration) {
        return ImmutableList.of();
      }
    };
  }

  /** Creates an appropriate ArchiverProvider instance for the given parameters. */
  static ArchiverProvider from(ToolProvider toolProvider, Type type) {
    return new ArchiverProvider() {
      @Override
      public Archiver resolve(BuildRuleResolver resolver, TargetConfiguration targetConfiguration) {
        Tool archiver = toolProvider.resolve(resolver, targetConfiguration);
        switch (type) {
          case BSD:
            return new BsdArchiver(archiver);
          case GNU:
            return new GnuArchiver(archiver);
          case WINDOWS:
            return new WindowsArchiver(archiver);
          case WINDOWS_CLANG:
            return new ClangWindowsArchiver(archiver);
          default:
            // This shouldn't be reachable.
            throw new RuntimeException();
        }
      }

      @Override
      public Iterable<BuildTarget> getParseTimeDeps(TargetConfiguration targetConfiguration) {
        return toolProvider.getParseTimeDeps(targetConfiguration);
      }
    };
  }

  /**
   * Optional type that can be specified by cxx.archiver_type to indicate the given archiver is
   * llvm-lib.
   */
  enum Type {
    BSD,
    GNU,
    WINDOWS,
    WINDOWS_CLANG,
  }
}
