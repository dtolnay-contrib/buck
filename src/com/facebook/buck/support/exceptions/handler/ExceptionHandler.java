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

package com.facebook.buck.support.exceptions.handler;

/** Abstract base class for handling certain type of exception */
public abstract class ExceptionHandler<T extends Throwable, R> {
  private Class<T> exceptionType;

  public ExceptionHandler(Class<T> type) {
    this.exceptionType = type;
  }

  public boolean canHandleException(Throwable e) {
    return exceptionType.isInstance(e);
  }

  public abstract R handleException(T t);

  Class<T> getExceptionType() {
    return exceptionType;
  }
}
