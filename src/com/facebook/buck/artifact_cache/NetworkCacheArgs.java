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

package com.facebook.buck.artifact_cache;

import com.facebook.buck.artifact_cache.config.ArtifactCacheMode;
import com.facebook.buck.artifact_cache.config.CacheReadMode;
import com.facebook.buck.core.model.TargetConfigurationSerializer;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.util.immutables.BuckStyleValueWithBuilder;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.slb.HttpService;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.util.Optional;
import java.util.function.Function;

@BuckStyleValueWithBuilder
interface NetworkCacheArgs {
  Function<String, UnconfiguredBuildTarget> getUnconfiguredBuildTargetFactory();

  TargetConfigurationSerializer getTargetConfigurationSerializer();

  String getCacheName();

  ArtifactCacheMode getCacheMode();

  String getRepository();

  String getScheduleType();

  HttpService getFetchClient();

  HttpService getStoreClient();

  CacheReadMode getCacheReadMode();

  ProjectFilesystem getProjectFilesystem();

  BuckEventBus getBuckEventBus();

  ListeningExecutorService getHttpFetchExecutorService();

  ListeningExecutorService getHttpWriteExecutorService();

  String getErrorTextTemplate();

  int getErrorTextLimit();

  Optional<Long> getMaxStoreSizeBytes();
}
