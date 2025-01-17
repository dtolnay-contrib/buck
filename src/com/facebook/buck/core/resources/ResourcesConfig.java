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

package com.facebook.buck.core.resources;

import com.facebook.buck.command.config.BuildBuckConfig;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.ConfigView;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.util.concurrent.ConcurrencyLimit;
import com.facebook.buck.util.concurrent.ResourceAllocationFairness;
import com.facebook.buck.util.concurrent.ResourceAmounts;
import com.facebook.buck.util.concurrent.ResourceAmountsEstimator;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Files;
import java.nio.file.Path;
import org.immutables.value.Value;

@BuckStyleValue
public abstract class ResourcesConfig implements ConfigView<BuckConfig> {
  public static final String RESOURCES_SECTION_HEADER = "resources";
  public static final String RESOURCES_PER_RULE_SECTION_HEADER = "resources_per_rule";

  @Override
  public abstract BuckConfig getDelegate();

  public static ResourcesConfig of(BuckConfig delegate) {
    return ImmutableResourcesConfig.ofImpl(delegate);
  }

  @Value.Lazy
  public ResourceAllocationFairness getResourceAllocationFairness() {
    return getDelegate()
        .getEnum(
            RESOURCES_SECTION_HEADER,
            "resource_allocation_fairness",
            ResourceAllocationFairness.class)
        .orElse(ResourceAllocationFairness.FAIR);
  }

  @Value.Lazy
  public boolean isResourceAwareSchedulingEnabled() {
    return getDelegate()
        .getBooleanValue(RESOURCES_SECTION_HEADER, "resource_aware_scheduling_enabled", false);
  }

  @Value.Lazy
  public ImmutableMap<String, ResourceAmounts> getResourceAmountsPerRuleType() {
    ImmutableMap.Builder<String, ResourceAmounts> result = ImmutableMap.builder();
    ImmutableMap<String, String> entries =
        getDelegate().getEntriesForSection(RESOURCES_PER_RULE_SECTION_HEADER);
    for (String ruleName : entries.keySet()) {
      ImmutableList<String> configAmounts =
          getDelegate().getListWithoutComments(RESOURCES_PER_RULE_SECTION_HEADER, ruleName);
      Preconditions.checkArgument(
          configAmounts.size() == ResourceAmounts.RESOURCE_TYPE_COUNT,
          "Buck config entry [%s].%s contains %s values, but expected to contain %s values "
              + "in the following order: cpu, memory, disk_io, network_io",
          RESOURCES_PER_RULE_SECTION_HEADER,
          ruleName,
          configAmounts.size(),
          ResourceAmounts.RESOURCE_TYPE_COUNT);
      ResourceAmounts amounts =
          ResourceAmounts.of(
              Integer.parseInt(configAmounts.get(0)),
              Integer.parseInt(configAmounts.get(1)),
              Integer.parseInt(configAmounts.get(2)),
              Integer.parseInt(configAmounts.get(3)));
      result.put(ruleName, amounts);
    }
    return result.build();
  }

  @Value.Lazy
  public int getManagedThreadCount() {
    BuildBuckConfig buildBuckConfig = getDelegate().getView(BuildBuckConfig.class);
    if (!isResourceAwareSchedulingEnabled()) {
      return buildBuckConfig.getNumThreads();
    }
    return getDelegate()
        .getLong(RESOURCES_SECTION_HEADER, "managed_thread_count")
        .orElse(
            (long) buildBuckConfig.getNumThreads()
                + buildBuckConfig.getDefaultMaximumNumberOfThreads())
        .intValue();
  }

  @Value.Lazy
  public ResourceAmounts getDefaultResourceAmounts() {
    if (!isResourceAwareSchedulingEnabled()) {
      return ResourceAmounts.of(1, 0, 0, 0);
    }
    return ResourceAmounts.of(
        getDelegate()
            .getInteger(RESOURCES_SECTION_HEADER, "default_cpu_amount")
            .orElse(ResourceAmountsEstimator.DEFAULT_CPU_AMOUNT),
        getDelegate()
            .getInteger(RESOURCES_SECTION_HEADER, "default_memory_amount")
            .orElse(ResourceAmountsEstimator.DEFAULT_MEMORY_AMOUNT),
        getDelegate()
            .getInteger(RESOURCES_SECTION_HEADER, "default_disk_io_amount")
            .orElse(ResourceAmountsEstimator.DEFAULT_DISK_IO_AMOUNT),
        getDelegate()
            .getInteger(RESOURCES_SECTION_HEADER, "default_network_io_amount")
            .orElse(ResourceAmountsEstimator.DEFAULT_NETWORK_IO_AMOUNT));
  }

  @Value.Lazy
  public ResourceAmounts getMaximumResourceAmounts() {
    ResourceAmounts estimated = ResourceAmountsEstimator.getEstimatedAmounts();
    return ResourceAmounts.of(
        getDelegate().getView(BuildBuckConfig.class).getNumThreads(estimated.getCpu()),
        getDelegate()
            .getInteger(RESOURCES_SECTION_HEADER, "max_memory_resource")
            .orElse(estimated.getMemory()),
        getDelegate()
            .getInteger(RESOURCES_SECTION_HEADER, "max_disk_io_resource")
            .orElse(estimated.getDiskIO()),
        getDelegate()
            .getInteger(RESOURCES_SECTION_HEADER, "max_network_io_resource")
            .orElse(estimated.getNetworkIO()));
  }

  /**
   * Construct a default ConcurrencyLimit instance from this config.
   *
   * @return New instance of ConcurrencyLimit.
   */
  @Value.Lazy
  public ConcurrencyLimit getConcurrencyLimit() {
    return new ConcurrencyLimit(
        getDelegate().getView(BuildBuckConfig.class).getNumThreads(),
        getResourceAllocationFairness(),
        getManagedThreadCount(),
        getDefaultResourceAmounts(),
        getMaximumResourceAmounts());
  }

  /**
   * Whether this Buck invocation should try to record the resource utilization of processes it
   * spawns.
   */
  @Value.Lazy
  public boolean shouldRecordProcessResourceUsage() {
    // TODO(swgillespie) Implementing process resource recording on non-Linux platforms
    if (Platform.detect() != Platform.LINUX) {
      return false;
    }

    // Check to ensure the binaries we need are present
    Path binPath = Path.of("/", "usr", "bin");
    if (!Files.exists(binPath.resolve("time")) || !Files.exists(binPath.resolve("perf.real"))) {
      return false;
    }

    return getDelegate()
        .getBooleanValue(RESOURCES_SECTION_HEADER, "record_process_resource_usage", false);
  }
}
