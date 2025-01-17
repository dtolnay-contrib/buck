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

package com.facebook.buck.util;

import com.google.common.collect.ImmutableMap;

public class MockClassLoader extends ClassLoader {
  private final ImmutableMap<String, Class<?>> injectedClasses;

  public MockClassLoader(ClassLoader parent, ImmutableMap<String, Class<?>> injectedClasses) {
    super(parent);
    this.injectedClasses = injectedClasses;
  }

  @Override
  protected Class<?> findClass(String name) throws ClassNotFoundException {
    Class<?> found = injectedClasses.get(name);
    if (found != null) {
      return found;
    }

    return super.findClass(name);
  }
}
