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

package com.facebook.buck.features.project.intellij;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.features.project.intellij.aggregation.AggregationMode;
import com.facebook.buck.features.project.intellij.model.IjProjectConfig;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;

public class IjProjectBuckConfig {

  private static final String PROJECT_BUCK_CONFIG_SECTION = "project";
  private static final String INTELLIJ_BUCK_CONFIG_SECTION = "intellij";
  // As a default value, 200 is large enough but not so close to file system's limit of the length
  // of a file name (255) thus we don't have to worry about doing careful boundary checks
  private static final int DEFAULT_MAX_MODULE_NAME_LENGTH = 200;
  private static final int DEFAULT_MAX_LIBRARY_NAME_LENGTH = 200;

  private IjProjectBuckConfig() {}

  public static IjProjectConfig create(
      BuckConfig buckConfig,
      @Nullable AggregationMode aggregationMode,
      @Nullable String generatedFilesListFilename,
      String projectRoot,
      String moduleGroupName,
      boolean isCleanerEnabled,
      boolean removeUnusedLibraries,
      boolean excludeArtifacts,
      boolean includeTransitiveDependencies,
      boolean skipBuild,
      boolean keepModuleFilesInModuleDirsEnabled,
      boolean generateProjectFilesAsJsonEnabled,
      ImmutableSet<String> includeTestPatterns,
      ImmutableSet<String> excludeTestPatterns) {
    Optional<String> excludedResourcePathsOption =
        buckConfig.getValue(INTELLIJ_BUCK_CONFIG_SECTION, "excluded_resource_paths");

    Iterable<String> excludedResourcePaths;
    if (excludedResourcePathsOption.isPresent()) {
      excludedResourcePaths =
          Sets.newHashSet(
              Splitter.on(',')
                  .omitEmptyStrings()
                  .trimResults()
                  .split(excludedResourcePathsOption.get()));
    } else {
      excludedResourcePaths = Collections.emptyList();
    }

    Map<String, String> labelToGeneratedSourcesMap =
        buckConfig.getMap(INTELLIJ_BUCK_CONFIG_SECTION, "generated_sources_label_map");

    Optional<Path> androidManifest =
        buckConfig.getPath(INTELLIJ_BUCK_CONFIG_SECTION, "default_android_manifest_path", false);

    keepModuleFilesInModuleDirsEnabled =
        buckConfig.getBooleanValue(
                INTELLIJ_BUCK_CONFIG_SECTION, "keep_module_files_in_module_dirs", false)
            || keepModuleFilesInModuleDirsEnabled;

    generateProjectFilesAsJsonEnabled =
        buckConfig.getBooleanValue(
                INTELLIJ_BUCK_CONFIG_SECTION, "generate-project-files-as-json", false)
            || generateProjectFilesAsJsonEnabled;

    return createBuilder(buckConfig)
        .setExcludedResourcePaths(excludedResourcePaths)
        .setLabelToGeneratedSourcesMap(labelToGeneratedSourcesMap)
        .setAndroidManifest(androidManifest)
        .setCleanerEnabled(isCleanerEnabled)
        .setKeepModuleFilesInModuleDirsEnabled(keepModuleFilesInModuleDirsEnabled)
        .setGenerateProjectFilesAsJsonEnabled(generateProjectFilesAsJsonEnabled)
        .setRemovingUnusedLibrariesEnabled(
            isRemovingUnusedLibrariesEnabled(removeUnusedLibraries, buckConfig))
        .setExcludeArtifactsEnabled(isExcludingArtifactsEnabled(excludeArtifacts, buckConfig))
        .setSkipBuildEnabled(
            skipBuild
                || buckConfig.getBooleanValue(PROJECT_BUCK_CONFIG_SECTION, "skip_build", false))
        .setAggregationMode(getAggregationMode(aggregationMode, buckConfig))
        .setGeneratedFilesListFilename(Optional.ofNullable(generatedFilesListFilename))
        .setProjectRoot(projectRoot)
        .setProjectPaths(new IjProjectPaths(projectRoot, keepModuleFilesInModuleDirsEnabled))
        .setIncludeTransitiveDependency(
            isIncludingTransitiveDependencyEnabled(includeTransitiveDependencies, buckConfig))
        .setModuleGroupName(getModuleGroupName(moduleGroupName, buckConfig))
        .setIncludeTestPatterns(includeTestPatterns)
        .setExcludeTestPatterns(excludeTestPatterns)
        .build();
  }

  static IjProjectConfig.Builder createBuilder(BuckConfig buckConfig) {
    return IjProjectConfig.builder()
        .setAutogenerateAndroidFacetSourcesEnabled(
            buckConfig.getBooleanValue(
                INTELLIJ_BUCK_CONFIG_SECTION, "auto_generate_android_facet_sources", true))
        .setJavaBuckConfig(buckConfig.getView(JavaBuckConfig.class))
        .setBuckConfig(buckConfig)
        .setProjectJdkName(buckConfig.getValue(INTELLIJ_BUCK_CONFIG_SECTION, "jdk_name"))
        .setProjectJdkType(buckConfig.getValue(INTELLIJ_BUCK_CONFIG_SECTION, "jdk_type"))
        .setAndroidModuleSdkName(
            buckConfig.getValue(INTELLIJ_BUCK_CONFIG_SECTION, "android_module_sdk_name"))
        .setAndroidGenDir(
            buckConfig.getValue(INTELLIJ_BUCK_CONFIG_SECTION, "android_generated_files_directory"))
        .setFlattenAndroidGenPathWithHash(
            buckConfig.getBooleanValue(
                INTELLIJ_BUCK_CONFIG_SECTION,
                "flatten_android_generated_files_path_with_hash",
                false))
        .setAndroidModuleSdkType(
            buckConfig.getValue(INTELLIJ_BUCK_CONFIG_SECTION, "android_module_sdk_type"))
        .setIntellijModuleSdkName(
            buckConfig.getValue(INTELLIJ_BUCK_CONFIG_SECTION, "intellij_module_sdk_name"))
        .setIntellijPluginLabels(
            buckConfig.getListWithoutComments(
                INTELLIJ_BUCK_CONFIG_SECTION, "intellij_plugin_labels"))
        .setJavaModuleSdkName(
            buckConfig.getValue(INTELLIJ_BUCK_CONFIG_SECTION, "java_module_sdk_name"))
        .setJavaModuleSdkType(
            buckConfig.getValue(INTELLIJ_BUCK_CONFIG_SECTION, "java_module_sdk_type"))
        .setPythonModuleSdkName(
            buckConfig.getValue(INTELLIJ_BUCK_CONFIG_SECTION, "python_module_sdk_name"))
        .setPythonModuleSdkType(
            buckConfig.getValue(INTELLIJ_BUCK_CONFIG_SECTION, "python_module_sdk_type"))
        .setProjectLanguageLevel(
            buckConfig.getValue(INTELLIJ_BUCK_CONFIG_SECTION, "language_level"))
        .setIgnoredTargetLabels(
            buckConfig.getListWithoutComments(
                INTELLIJ_BUCK_CONFIG_SECTION, "ignored_target_labels"))
        .setAggregatingAndroidResourceModulesEnabled(
            buckConfig.getBooleanValue(
                INTELLIJ_BUCK_CONFIG_SECTION, "aggregate_android_resource_modules", false))
        .setAggregationLimitForAndroidResourceModule(
            buckConfig
                .getInteger(
                    INTELLIJ_BUCK_CONFIG_SECTION, "android_resource_module_aggregation_limit")
                .orElse(Integer.MAX_VALUE))
        .setGeneratingAndroidManifestEnabled(
            buckConfig.getBooleanValue(
                INTELLIJ_BUCK_CONFIG_SECTION, "generate_android_manifest", false))
        .setSharedAndroidManifestGenerationEnabled(
            buckConfig.getBooleanValue(
                INTELLIJ_BUCK_CONFIG_SECTION, "shared_android_manifest_generation", false))
        .setGeneratingTargetInfoMapEnabled(
            buckConfig.getBooleanValue(
                INTELLIJ_BUCK_CONFIG_SECTION, "generate_target_info_map", false))
        .setGeneratingBinaryTargetInfoEnabled(
            buckConfig.getBooleanValue(
                INTELLIJ_BUCK_CONFIG_SECTION, "generate_binary_target_info", false))
        .setGeneratingTargetConfigurationMapEnabled(
            buckConfig.getBooleanValue(
                INTELLIJ_BUCK_CONFIG_SECTION, "generate_target_configuration_map", false))
        .setGeneratingModuleInfoBinaryIndexEnabled(
            buckConfig.getBooleanValue(
                INTELLIJ_BUCK_CONFIG_SECTION, "generate_module_info_binary_index", false))
        .setOutputUrl(
            buckConfig.getValue(INTELLIJ_BUCK_CONFIG_SECTION, "project_compiler_output_url"))
        .setExtraCompilerOutputModulesPath(
            buckConfig.getPath(
                INTELLIJ_BUCK_CONFIG_SECTION, "extra_compiler_output_modules_path", false))
        .setDefaultAndroidManifestPackageName(
            buckConfig.getValue(
                INTELLIJ_BUCK_CONFIG_SECTION, "default_android_manifest_package_name"))
        .setMinAndroidSdkVersion(
            buckConfig.getValue(INTELLIJ_BUCK_CONFIG_SECTION, "default_min_android_sdk_version"))
        .setMultiCellModuleSupportEnabled(
            buckConfig.getBooleanValue(
                INTELLIJ_BUCK_CONFIG_SECTION, "multi_cell_module_support", false))
        .setGeneratingDummyRDotJavaEnabled(
            buckConfig.getBooleanValue(
                INTELLIJ_BUCK_CONFIG_SECTION, "generate_dummy_r_dot_java", true))
        .setMaxModuleNameLengthBeforeTruncate(
            buckConfig
                .getInteger(INTELLIJ_BUCK_CONFIG_SECTION, "max_module_name_length_before_truncate")
                .orElse(DEFAULT_MAX_MODULE_NAME_LENGTH))
        .setMaxLibraryNameLengthBeforeTruncate(
            buckConfig
                .getInteger(INTELLIJ_BUCK_CONFIG_SECTION, "max_library_name_length_before_truncate")
                .orElse(DEFAULT_MAX_LIBRARY_NAME_LENGTH))
        .setProjectRootExclusionMode(
            buckConfig
                .getValue(INTELLIJ_BUCK_CONFIG_SECTION, "project_root_exclusion_mode")
                .map(ProjectRootExclusionMode::fromString)
                .orElse(ProjectRootExclusionMode.RECURSIVE))
        .setModuleLibraryEnabled(
            buckConfig.getBooleanValue(INTELLIJ_BUCK_CONFIG_SECTION, "use_module_library", false))
        .setModuleLibraryThreshold(
            buckConfig
                .getInteger(INTELLIJ_BUCK_CONFIG_SECTION, "module_library_threshold")
                .orElse(-1))
        .setModuleDependenciesSorted(
            buckConfig.getBooleanValue(
                INTELLIJ_BUCK_CONFIG_SECTION, "module_dependencies_sorted", false))
        .setPythonBaseModuleTransformEnabled(
            buckConfig
                .getBoolean(INTELLIJ_BUCK_CONFIG_SECTION, "python_base_module_transform")
                .orElse(false))
        .setKotlinJavaRuntimeLibraryTemplatePath(
            buckConfig.getPath(
                INTELLIJ_BUCK_CONFIG_SECTION, "kotlin_java_runtime_library_template_path"))
        .setBuckOutPathForGeneratedProjectFiles(
            buckConfig.getValue(INTELLIJ_BUCK_CONFIG_SECTION, "buck_out_path_for_generated_files"))
        .setRustModuleEnabled(
            buckConfig.getBooleanValue(INTELLIJ_BUCK_CONFIG_SECTION, "enable_rust_module", false))
        .setTargetConfigurationInLibrariesEnabled(
            buckConfig.getBooleanValue(
                INTELLIJ_BUCK_CONFIG_SECTION, "target_configuration_in_libraries_enabled", true));
  }

  private static String getModuleGroupName(String moduleGroupName, BuckConfig buckConfig) {
    String name = moduleGroupName;
    if (null == name) {
      name =
          buckConfig.getValue(INTELLIJ_BUCK_CONFIG_SECTION, "module_group_name").orElse("modules");
    }
    return name;
  }

  private static boolean isRemovingUnusedLibrariesEnabled(
      boolean removeUnusedLibraries, BuckConfig buckConfig) {
    return removeUnusedLibraries
        || buckConfig.getBooleanValue(
            INTELLIJ_BUCK_CONFIG_SECTION, "remove_unused_libraries", false);
  }

  private static boolean isIncludingTransitiveDependencyEnabled(
      boolean includeTransitiveDependency, BuckConfig buckConfig) {
    return includeTransitiveDependency
        || buckConfig.getBooleanValue(
            INTELLIJ_BUCK_CONFIG_SECTION, "include_transitive_dependencies", false);
  }

  private static boolean isExcludingArtifactsEnabled(
      boolean excludeArtifacts, BuckConfig buckConfig) {
    return excludeArtifacts
        || buckConfig.getBooleanValue(PROJECT_BUCK_CONFIG_SECTION, "exclude_artifacts", false);
  }

  private static AggregationMode getAggregationMode(
      @Nullable AggregationMode aggregationMode, BuckConfig buckConfig) {
    if (aggregationMode != null) {
      return aggregationMode;
    }
    Optional<AggregationMode> aggregationModeFromConfig =
        buckConfig
            .getValue(PROJECT_BUCK_CONFIG_SECTION, "intellij_aggregation_mode")
            .map(AggregationMode::fromString);
    return aggregationModeFromConfig.orElse(AggregationMode.AUTO);
  }
}
