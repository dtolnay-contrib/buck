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

package com.facebook.buck.features.project.intellij.model;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.util.immutables.BuckStyleValueWithBuilder;
import com.facebook.buck.features.project.intellij.IjProjectPaths;
import com.facebook.buck.features.project.intellij.ProjectRootExclusionMode;
import com.facebook.buck.features.project.intellij.aggregation.AggregationMode;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.immutables.value.Value;

@BuckStyleValueWithBuilder
public abstract class IjProjectConfig {

  public abstract JavaBuckConfig getJavaBuckConfig();

  public abstract BuckConfig getBuckConfig();

  @Value.Default
  public boolean isAutogenerateAndroidFacetSourcesEnabled() {
    return true;
  }

  public abstract Optional<String> getProjectJdkName();

  public abstract Optional<String> getProjectJdkType();

  public abstract Optional<String> getAndroidModuleSdkName();

  public abstract Optional<String> getAndroidGenDir();

  public abstract boolean isFlattenAndroidGenPathWithHash();

  public abstract Optional<String> getAndroidModuleSdkType();

  public abstract Optional<String> getIntellijModuleSdkName();

  public abstract ImmutableSet<String> getIntellijPluginLabels();

  public abstract Optional<String> getJavaModuleSdkName();

  public abstract Optional<String> getJavaModuleSdkType();

  public abstract Optional<String> getPythonModuleSdkName();

  public abstract Optional<String> getPythonModuleSdkType();

  public abstract Optional<String> getProjectLanguageLevel();

  public abstract List<String> getExcludedResourcePaths();

  public abstract ImmutableMap<String, String> getLabelToGeneratedSourcesMap();

  public abstract Optional<Path> getAndroidManifest();

  public abstract boolean isCleanerEnabled();

  public abstract boolean isRemovingUnusedLibrariesEnabled();

  public abstract boolean isExcludeArtifactsEnabled();

  public abstract boolean isIncludeTransitiveDependency();

  public abstract boolean isSkipBuildEnabled();

  public abstract boolean isKeepModuleFilesInModuleDirsEnabled();

  public abstract AggregationMode getAggregationMode();

  public abstract Optional<String> getGeneratedFilesListFilename();

  public abstract String getModuleGroupName();

  public abstract String getProjectRoot();

  public abstract IjProjectPaths getProjectPaths();

  public abstract boolean isAggregatingAndroidResourceModulesEnabled();

  /** Labels that indicate targets that need to be ignored during project generation. */
  public abstract ImmutableSet<String> getIgnoredTargetLabels();

  public abstract int getAggregationLimitForAndroidResourceModule();

  public abstract boolean isGeneratingAndroidManifestEnabled();

  public abstract boolean isSharedAndroidManifestGenerationEnabled();

  public abstract boolean isGeneratingTargetInfoMapEnabled();

  public abstract boolean isGeneratingBinaryTargetInfoEnabled();

  public abstract boolean isGeneratingTargetConfigurationMapEnabled();

  public abstract boolean isGeneratingModuleInfoBinaryIndexEnabled();

  public abstract Optional<String> getOutputUrl();

  public abstract Optional<Path> getExtraCompilerOutputModulesPath();

  public abstract Optional<String> getDefaultAndroidManifestPackageName();

  public abstract Optional<String> getMinAndroidSdkVersion();

  public abstract ImmutableSet<String> getIncludeTestPatterns();

  public abstract ImmutableSet<String> getExcludeTestPatterns();

  public abstract boolean isMultiCellModuleSupportEnabled();

  public abstract boolean isGeneratingDummyRDotJavaEnabled();

  public abstract int getMaxModuleNameLengthBeforeTruncate();

  public abstract int getMaxLibraryNameLengthBeforeTruncate();

  public abstract Optional<Path> getKotlinJavaRuntimeLibraryTemplatePath();

  public abstract ProjectRootExclusionMode getProjectRootExclusionMode();

  public abstract boolean isModuleLibraryEnabled();

  public abstract int getModuleLibraryThreshold();

  public abstract boolean isModuleDependenciesSorted();

  public abstract boolean isPythonBaseModuleTransformEnabled();

  public abstract Optional<String> getBuckOutPathForGeneratedProjectFiles();

  public abstract boolean isRustModuleEnabled();

  public abstract boolean isTargetConfigurationInLibrariesEnabled();

  public abstract boolean isGenerateProjectFilesAsJsonEnabled();

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder extends ImmutableIjProjectConfig.Builder {}
}
