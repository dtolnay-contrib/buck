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

package com.facebook.buck.android;

import com.facebook.buck.core.config.BuckConfig;
import java.util.OptionalInt;

public class DxConfig {

  private final BuckConfig delegate;

  public DxConfig(BuckConfig delegate) {
    this.delegate = delegate;
  }

  /**
   * @return The dx thread count. If it is not specified, number of threads wil be determined by
   *     hardware capabilities of running host and capped with {@code max_threads} parameter, if
   *     specified
   */
  public OptionalInt getDxThreadCount() {
    return delegate.getInteger("dx", "threads");
  }

  /** @return The dx maximum allowed thread count. */
  public OptionalInt getDxMaxThreadCount() {
    return delegate.getInteger("dx", "max_threads");
  }
}
