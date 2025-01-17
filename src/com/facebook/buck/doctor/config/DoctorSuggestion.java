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

package com.facebook.buck.doctor.config;

import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.util.Optional;

@BuckStyleValue
@JsonDeserialize(as = ImmutableDoctorSuggestion.class)
public interface DoctorSuggestion {

  StepStatus getStatus();

  Optional<String> getArea();

  String getSuggestion();

  enum StepStatus {
    OK("\u2705", "OK"),
    WARNING("\u2757", "Warning"),
    ERROR("\u274C", "Error"),
    UNKNOWN("\u2753", "Unknown");

    private final String emoji;
    private final String text;

    StepStatus(String emoji, String text) {
      this.emoji = emoji;
      this.text = text;
    }

    public String getEmoji() {
      return this.emoji;
    }

    public String getText() {
      return this.text;
    }
  }

  static DoctorSuggestion of(
      DoctorSuggestion.StepStatus status, Optional<String> area, String suggestion) {
    return ImmutableDoctorSuggestion.ofImpl(status, area, suggestion);
  }
}
