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

package com.facebook.buck.android.support.exopackage;

import java.lang.reflect.Field;

class Reflect {
  private Reflect() {}

  static Object getField(Object object, Class<?> clazz, String fieldName)
      throws NoSuchFieldException, IllegalAccessException {
    Field field = clazz.getDeclaredField(fieldName);
    field.setAccessible(true);
    return field.get(object);
  }

  static void setField(Object object, Class<?> clazz, String fieldName, Object fieldValue)
      throws NoSuchFieldException, IllegalAccessException {
    Field field = clazz.getDeclaredField(fieldName);
    field.setAccessible(true);
    field.set(object, fieldValue);
  }
}
