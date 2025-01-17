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

package com.facebook.buck.log.views;

/**
 * This defines the different views an object can have when serialized/de-serialized using Jackson.
 *
 * @see <a href="http://wiki.fasterxml.com/JacksonJsonViews">JacksonJsonViews</a>
 */
public class JsonViews {

  /** View for events for {@link com.facebook.buck.event.listener.MachineReadableLoggerListener}. */
  public static class MachineReadableLog {}
}
