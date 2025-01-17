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

package com.facebook.buck.command.config;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.ConfigView;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.support.cli.config.CliConfig;
import com.facebook.buck.util.MoreMaps;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import java.util.Map;
import org.immutables.value.Value;

/** A view of a config that excludes configuration options that do not invalidate global state. */
@BuckStyleValue
public abstract class ConfigIgnoredByDaemon implements ConfigView<BuckConfig> {

  @Override
  public abstract BuckConfig getDelegate();

  public static ConfigIgnoredByDaemon of(BuckConfig delegate) {
    return ImmutableConfigIgnoredByDaemon.ofImpl(delegate);
  }

  // empty value list means that all values under the corresponding section are ignored
  private static ImmutableMap<String, ImmutableSet<String>> getIgnoreFieldsForDaemonRestart() {
    ImmutableMap.Builder<String, ImmutableSet<String>> ignoreFieldsForDaemonRestartBuilder =
        ImmutableMap.builder();
    ignoreFieldsForDaemonRestartBuilder.put(
        "build", ImmutableSet.of("threads", "delete_temporaries"));
    ignoreFieldsForDaemonRestartBuilder.put(
        "cache",
        ImmutableSet.of(
            "dir",
            "dir_mode",
            "http_mode",
            "http_url",
            "http_client_tls_cert_required",
            "http_client_tls_for_slb",
            "http_client_tls_cert",
            "http_client_tls_cert_env_var",
            "http_client_tls_key",
            "http_client_tls_key_env_var",
            "http_client_tls_ca",
            "http_client_tls_ca_env_var",
            "mode",
            "slb_server_pool"));
    ignoreFieldsForDaemonRestartBuilder.put(
        "client", ImmutableSet.of("id", "session_id", "skip-action-graph-cache", "test_name"));
    ignoreFieldsForDaemonRestartBuilder.put("doctor", ImmutableSet.of("slb_server_pool"));
    ignoreFieldsForDaemonRestartBuilder.put(
        "intellij",
        ImmutableSet.of(
            "android_generated_files_directory",
            "auto_generate_android_facet_sources",
            "buck_out_path_for_generated_files",
            "default_android_manifest_package_name",
            "default_min_android_sdk_version",
            "enable_rust_module",
            "flatten_android_generated_files_path_with_hash",
            "generate_module_info_binary_index",
            "keep_module_files_in_module_dirs",
            "kotlin_java_runtime_library_template_path",
            "max_library_name_length_before_truncate",
            "max_module_name_length_before_truncate",
            "module_dependencies_sorted",
            "multi_cell_module_support",
            "project_root_exclusion_mode",
            "python_base_module_transform",
            "shared_android_manifest_generation",
            "use_module_library"));
    ignoreFieldsForDaemonRestartBuilder.put(
        "log",
        ImmutableSet.of(
            "chrome_trace_generation",
            "compress_traces",
            "max_traces",
            "public_announcements",
            "log_build_id_to_console_enabled",
            "build_details_template",
            "build_details_commands",
            "slb_server_pool"));
    ignoreFieldsForDaemonRestartBuilder.put(
        "project", ImmutableSet.of("ide_prompt", "ide_force_kill"));
    ignoreFieldsForDaemonRestartBuilder.put(
        "test",
        ImmutableSet.of(
            "external_runner", "external_runner_tty", "path_prefixes_to_use_external_runner"));
    ignoreFieldsForDaemonRestartBuilder.put(
        "ui",
        ImmutableSet.of(
            "hide_succeeded_rules_in_log_mode",
            "superconsole",
            "thread_line_limit",
            "thread_line_output_max_columns",
            CliConfig.TRUNCATE_FAILING_COMMAND_CONFIG,
            "warn_on_config_file_overrides",
            "warn_on_config_file_overrides_ignored_files"));
    ignoreFieldsForDaemonRestartBuilder.put("color", ImmutableSet.of("ui"));
    ignoreFieldsForDaemonRestartBuilder.put(
        "version_control", ImmutableSet.of("generate_statistics"));
    ignoreFieldsForDaemonRestartBuilder.put("depsawareexecutor", ImmutableSet.of("type"));
    ignoreFieldsForDaemonRestartBuilder.put("buck2", ImmutableSet.of());
    ignoreFieldsForDaemonRestartBuilder.put("buck2_re_client", ImmutableSet.of());
    return ignoreFieldsForDaemonRestartBuilder.build();
  }

  @Value.Lazy
  public ImmutableMap<String, ImmutableMap<String, String>> getRawConfigForParser() {
    ImmutableMap<String, ImmutableSet<String>> ignoredFields = getIgnoreFieldsForDaemonRestart();
    ImmutableMap<String, ImmutableMap<String, String>> rawSections =
        getDelegate().getConfig().getSectionToEntries();

    // If the raw config doesn't have sections which have ignored fields, then just return it as-is.
    ImmutableSet<String> sectionsWithIgnoredFields = ignoredFields.keySet();
    if (Sets.intersection(rawSections.keySet(), sectionsWithIgnoredFields).isEmpty()) {
      return rawSections;
    }

    // Otherwise, iterate through the config to do finer-grain filtering.
    ImmutableMap.Builder<String, ImmutableMap<String, String>> filtered = ImmutableMap.builder();
    for (Map.Entry<String, ImmutableMap<String, String>> sectionEnt : rawSections.entrySet()) {
      String sectionName = sectionEnt.getKey();

      // If this section doesn't have a corresponding ignored section, then just add it as-is.
      if (!sectionsWithIgnoredFields.contains(sectionName)) {
        filtered.put(sectionEnt);
        continue;
      }

      ImmutableSet<String> ignoredFieldNames = ignoredFields.get(sectionName);
      // if the ignoredFieldNames is mepty we should ignore the full section
      if (ignoredFieldNames.isEmpty()) {
        continue;
      }

      // If none of this section's entries are ignored, then add it as-is.
      ImmutableMap<String, String> fields = sectionEnt.getValue();
      if (Sets.intersection(fields.keySet(), ignoredFieldNames).isEmpty()) {
        filtered.put(sectionEnt);
        continue;
      }

      // Otherwise, filter out the ignored fields.
      ImmutableMap<String, String> remainingKeys =
          ImmutableMap.copyOf(Maps.filterKeys(fields, Predicates.not(ignoredFieldNames::contains)));
      filtered.put(sectionName, remainingKeys);
    }

    return MoreMaps.filterValues(filtered.build(), Predicates.not(Map::isEmpty));
  }
}
