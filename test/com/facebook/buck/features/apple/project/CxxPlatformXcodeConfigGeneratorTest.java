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

package com.facebook.buck.features.apple.project;

import static org.hamcrest.MatcherAssert.assertThat;

import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.rules.args.StringArg;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.LinkedHashMap;
import org.hamcrest.Matchers;
import org.junit.Test;

public class CxxPlatformXcodeConfigGeneratorTest {

  private static final CxxPlatform DEFAULT_PLATFORM = CxxPlatformUtils.DEFAULT_PLATFORM;
  private static final SourcePathResolverAdapter DEFAULT_PATH_RESOLVER =
      CxxPlatformUtils.DEFAULT_PATH_RESOLVER;

  @Test
  public void testResultHasCommonBuildConfigurations() {
    ImmutableMap<String, ImmutableMap<String, String>> buildConfigs =
        CxxPlatformXcodeConfigGenerator.getDefaultXcodeBuildConfigurationsFromCxxPlatform(
            DEFAULT_PLATFORM, new LinkedHashMap<>(), DEFAULT_PATH_RESOLVER);
    assertThat(
        buildConfigs.keySet(),
        Matchers.hasItem(CxxPlatformXcodeConfigGenerator.DEBUG_BUILD_CONFIGURATION_NAME));
    assertThat(
        buildConfigs.keySet(),
        Matchers.hasItem(CxxPlatformXcodeConfigGenerator.RELEASE_BUILD_CONFIGURATION_NAME));
    assertThat(
        buildConfigs.keySet(),
        Matchers.hasItem(CxxPlatformXcodeConfigGenerator.PROFILE_BUILD_CONFIGURATION_NAME));
  }

  @Test
  public void testResultHasCorrectSdkRootTakenFromAppendConfig() {
    LinkedHashMap<String, String> appendConfig = new LinkedHashMap<>();
    appendConfig.put(CxxPlatformXcodeConfigGenerator.SDKROOT, "somesdkroot");
    ImmutableMap<String, ImmutableMap<String, String>> buildConfigs =
        CxxPlatformXcodeConfigGenerator.getDefaultXcodeBuildConfigurationsFromCxxPlatform(
            DEFAULT_PLATFORM, appendConfig, DEFAULT_PATH_RESOLVER);
    ImmutableMap<String, String> config =
        buildConfigs.get(CxxPlatformXcodeConfigGenerator.DEBUG_BUILD_CONFIGURATION_NAME);
    assertThat(
        config.get(CxxPlatformXcodeConfigGenerator.SDKROOT),
        Matchers.equalTo(appendConfig.get(CxxPlatformXcodeConfigGenerator.SDKROOT)));
  }

  @Test
  public void testResultHasIphoneOsSdkRootTakenFromIphoneSimulatorFlavor() {
    CxxPlatform platform =
        CxxPlatform.builder()
            .from(DEFAULT_PLATFORM)
            .setFlavor(InternalFlavor.of("iphonesimulator-x86_64"))
            .build();
    ImmutableMap<String, ImmutableMap<String, String>> buildConfigs =
        CxxPlatformXcodeConfigGenerator.getDefaultXcodeBuildConfigurationsFromCxxPlatform(
            platform, new LinkedHashMap<>(), DEFAULT_PATH_RESOLVER);
    ImmutableMap<String, String> config =
        buildConfigs.get(CxxPlatformXcodeConfigGenerator.DEBUG_BUILD_CONFIGURATION_NAME);
    assertThat(config.get(CxxPlatformXcodeConfigGenerator.SDKROOT), Matchers.equalTo("iphoneos"));
  }

  @Test
  public void testResultHasIphoneOsSdkRootTakenFromIphoneOsFlavor() {
    CxxPlatform platform =
        CxxPlatform.builder()
            .from(DEFAULT_PLATFORM)
            .setFlavor(InternalFlavor.of("iphoneos-9.1"))
            .build();
    ImmutableMap<String, ImmutableMap<String, String>> buildConfigs =
        CxxPlatformXcodeConfigGenerator.getDefaultXcodeBuildConfigurationsFromCxxPlatform(
            platform, new LinkedHashMap<>(), DEFAULT_PATH_RESOLVER);
    ImmutableMap<String, String> config =
        buildConfigs.get(CxxPlatformXcodeConfigGenerator.DEBUG_BUILD_CONFIGURATION_NAME);
    assertThat(config.get(CxxPlatformXcodeConfigGenerator.SDKROOT), Matchers.equalTo("iphoneos"));
  }

  @Test
  public void testResultHasMacOsxSdkRootTakenFromMacOsxFlavor() {
    CxxPlatform platform =
        CxxPlatform.builder()
            .from(DEFAULT_PLATFORM)
            .setFlavor(InternalFlavor.of("macosx-12.0"))
            .build();
    ImmutableMap<String, ImmutableMap<String, String>> buildConfigs =
        CxxPlatformXcodeConfigGenerator.getDefaultXcodeBuildConfigurationsFromCxxPlatform(
            platform, new LinkedHashMap<>(), DEFAULT_PATH_RESOLVER);
    ImmutableMap<String, String> config =
        buildConfigs.get(CxxPlatformXcodeConfigGenerator.DEBUG_BUILD_CONFIGURATION_NAME);
    assertThat(config.get(CxxPlatformXcodeConfigGenerator.SDKROOT), Matchers.equalTo("macosx"));
  }

  @Test
  public void testResultHasDeploymentTargetValueTakenFromPlatformCxxflags() {
    CxxPlatform platform =
        CxxPlatform.builder()
            .from(DEFAULT_PLATFORM)
            .setFlavor(InternalFlavor.of("macosx-12.0"))
            .setCxxflags(StringArg.from(ImmutableList.of("-mmacosx-version-min=10.8")))
            .build();
    ImmutableMap<String, ImmutableMap<String, String>> buildConfigs =
        CxxPlatformXcodeConfigGenerator.getDefaultXcodeBuildConfigurationsFromCxxPlatform(
            platform, new LinkedHashMap<>(), DEFAULT_PATH_RESOLVER);
    ImmutableMap<String, String> config =
        buildConfigs.get(CxxPlatformXcodeConfigGenerator.DEBUG_BUILD_CONFIGURATION_NAME);
    assertThat(config.get("MACOSX_DEPLOYMENT_TARGET"), Matchers.equalTo("10.8"));
  }

  @Test
  public void testResultHasNoArchSetToAllowAutomaticDeviceSwitchInXcode() {
    CxxPlatform platform =
        CxxPlatform.builder()
            .from(DEFAULT_PLATFORM)
            .setCxxflags(StringArg.from(ImmutableList.of("-arch", "x86-64")))
            .build();
    ImmutableMap<String, ImmutableMap<String, String>> buildConfigs =
        CxxPlatformXcodeConfigGenerator.getDefaultXcodeBuildConfigurationsFromCxxPlatform(
            platform, new LinkedHashMap<>(), DEFAULT_PATH_RESOLVER);
    ImmutableMap<String, String> config =
        buildConfigs.get(CxxPlatformXcodeConfigGenerator.DEBUG_BUILD_CONFIGURATION_NAME);
    assertThat(config.get(CxxPlatformXcodeConfigGenerator.ARCHS), Matchers.nullValue());
  }

  @Test
  public void testResultHasCxxLanguageStandardValueTakenFromPlatformCxxflags() {
    CxxPlatform platform =
        CxxPlatform.builder()
            .from(DEFAULT_PLATFORM)
            .setCxxflags(StringArg.from(ImmutableList.of("-std=somevalue")))
            .build();
    ImmutableMap<String, ImmutableMap<String, String>> buildConfigs =
        CxxPlatformXcodeConfigGenerator.getDefaultXcodeBuildConfigurationsFromCxxPlatform(
            platform, new LinkedHashMap<>(), DEFAULT_PATH_RESOLVER);
    ImmutableMap<String, String> config =
        buildConfigs.get(CxxPlatformXcodeConfigGenerator.DEBUG_BUILD_CONFIGURATION_NAME);
    assertThat(
        config.get(CxxPlatformXcodeConfigGenerator.CLANG_CXX_LANGUAGE_STANDARD),
        Matchers.equalTo("somevalue"));
  }

  @Test
  public void testResultHasCxxLanguageStardardValueTakenFromAppendConfigIfPresent() {
    LinkedHashMap<String, String> appendConfig = new LinkedHashMap<>();
    appendConfig.put(CxxPlatformXcodeConfigGenerator.CLANG_CXX_LANGUAGE_STANDARD, "value");

    CxxPlatform platform =
        CxxPlatform.builder()
            .from(DEFAULT_PLATFORM)
            .setCxxflags(StringArg.from(ImmutableList.of("-std=cxxflagsvalue")))
            .build();
    ImmutableMap<String, ImmutableMap<String, String>> buildConfigs =
        CxxPlatformXcodeConfigGenerator.getDefaultXcodeBuildConfigurationsFromCxxPlatform(
            platform, appendConfig, DEFAULT_PATH_RESOLVER);
    ImmutableMap<String, String> config =
        buildConfigs.get(CxxPlatformXcodeConfigGenerator.DEBUG_BUILD_CONFIGURATION_NAME);
    assertThat(
        config.get(CxxPlatformXcodeConfigGenerator.CLANG_CXX_LANGUAGE_STANDARD),
        Matchers.equalTo(
            appendConfig.get(CxxPlatformXcodeConfigGenerator.CLANG_CXX_LANGUAGE_STANDARD)));
  }

  @Test
  public void testResultHasCxxLibraryValueTakenFromPlatformCxxflags() {
    CxxPlatform platform =
        CxxPlatform.builder()
            .from(DEFAULT_PLATFORM)
            .setCxxflags(StringArg.from(ImmutableList.of("-stdlib=somevalue")))
            .build();
    ImmutableMap<String, ImmutableMap<String, String>> buildConfigs =
        CxxPlatformXcodeConfigGenerator.getDefaultXcodeBuildConfigurationsFromCxxPlatform(
            platform, new LinkedHashMap<>(), DEFAULT_PATH_RESOLVER);
    ImmutableMap<String, String> config =
        buildConfigs.get(CxxPlatformXcodeConfigGenerator.DEBUG_BUILD_CONFIGURATION_NAME);
    assertThat(
        config.get(CxxPlatformXcodeConfigGenerator.CLANG_CXX_LIBRARY),
        Matchers.equalTo("somevalue"));
  }

  @Test
  public void testResultHasCxxLibraryValueTakenFromAppendConfigIfPresent() {
    LinkedHashMap<String, String> appendConfig = new LinkedHashMap<>();
    appendConfig.put(CxxPlatformXcodeConfigGenerator.CLANG_CXX_LIBRARY, "value");

    CxxPlatform platform =
        CxxPlatform.builder()
            .from(DEFAULT_PLATFORM)
            .setCxxflags(StringArg.from(ImmutableList.of("-stdlib=cxxflagsvalue")))
            .build();
    ImmutableMap<String, ImmutableMap<String, String>> buildConfigs =
        CxxPlatformXcodeConfigGenerator.getDefaultXcodeBuildConfigurationsFromCxxPlatform(
            platform, appendConfig, DEFAULT_PATH_RESOLVER);
    ImmutableMap<String, String> config =
        buildConfigs.get(CxxPlatformXcodeConfigGenerator.DEBUG_BUILD_CONFIGURATION_NAME);
    assertThat(
        config.get(CxxPlatformXcodeConfigGenerator.CLANG_CXX_LIBRARY),
        Matchers.equalTo(appendConfig.get(CxxPlatformXcodeConfigGenerator.CLANG_CXX_LIBRARY)));
  }

  @Test
  public void testResultHasOtherCxxFlagsTakenFromPlatformCxxflags() {
    CxxPlatform platform =
        CxxPlatform.builder()
            .from(DEFAULT_PLATFORM)
            .setCxxflags(StringArg.from(ImmutableList.of("-Wno-warning", "-someflag", "-g")))
            .build();
    ImmutableMap<String, ImmutableMap<String, String>> buildConfigs =
        CxxPlatformXcodeConfigGenerator.getDefaultXcodeBuildConfigurationsFromCxxPlatform(
            platform, new LinkedHashMap<>(), DEFAULT_PATH_RESOLVER);
    ImmutableMap<String, String> config = buildConfigs.get("Debug");
    assertThat(
        config.get(CxxPlatformXcodeConfigGenerator.OTHER_CPLUSPLUSFLAGS),
        Matchers.equalTo("-Wno-warning -someflag -g"));
  }

  @Test
  public void testResultHasOtherCxxFlagsTakenFromPlatformCxxflagsAndMergedWithAppendConfig() {
    LinkedHashMap<String, String> appendConfig = new LinkedHashMap<>();
    appendConfig.put(CxxPlatformXcodeConfigGenerator.OTHER_CPLUSPLUSFLAGS, "-flag1 -flag2");
    CxxPlatform platform =
        CxxPlatform.builder()
            .from(DEFAULT_PLATFORM)
            .setCxxflags(StringArg.from(ImmutableList.of("-Wno-warning", "-someflag", "-g")))
            .build();
    ImmutableMap<String, ImmutableMap<String, String>> buildConfigs =
        CxxPlatformXcodeConfigGenerator.getDefaultXcodeBuildConfigurationsFromCxxPlatform(
            platform, appendConfig, DEFAULT_PATH_RESOLVER);
    ImmutableMap<String, String> config =
        buildConfigs.get(CxxPlatformXcodeConfigGenerator.DEBUG_BUILD_CONFIGURATION_NAME);
    assertThat(
        config.get(CxxPlatformXcodeConfigGenerator.OTHER_CPLUSPLUSFLAGS),
        Matchers.equalTo("-flag1 -flag2 -Wno-warning -someflag -g"));
  }

  @Test
  public void testResultHasValuesMergedFromAppendConfig() {
    String someCrazyKey = "SOME_CRAZY_KEY";

    LinkedHashMap<String, String> appendConfig = new LinkedHashMap<>();
    appendConfig.put(someCrazyKey, "value");

    CxxPlatform platform =
        CxxPlatform.builder()
            .from(DEFAULT_PLATFORM)
            .setCxxflags(StringArg.from(ImmutableList.of("-Wno-warning", "-someflag", "-g")))
            .build();
    ImmutableMap<String, ImmutableMap<String, String>> buildConfigs =
        CxxPlatformXcodeConfigGenerator.getDefaultXcodeBuildConfigurationsFromCxxPlatform(
            platform, appendConfig, DEFAULT_PATH_RESOLVER);
    ImmutableMap<String, String> config =
        buildConfigs.get(CxxPlatformXcodeConfigGenerator.DEBUG_BUILD_CONFIGURATION_NAME);
    assertThat(
        config.get(CxxPlatformXcodeConfigGenerator.OTHER_CPLUSPLUSFLAGS),
        Matchers.equalTo("-Wno-warning -someflag -g"));
    assertThat(config.get(someCrazyKey), Matchers.equalTo(appendConfig.get(someCrazyKey)));
  }

  @Test
  public void testAllBuildConfigurationsHaveSameConfigs() {
    String someCrazyKey = "SOME_CRAZY_KEY";

    LinkedHashMap<String, String> appendConfig = new LinkedHashMap<>();
    appendConfig.put(someCrazyKey, "value");

    CxxPlatform platform =
        CxxPlatform.builder()
            .from(DEFAULT_PLATFORM)
            .setFlavor(InternalFlavor.of("macosx-12.0"))
            .setCxxflags(
                StringArg.from(
                    ImmutableList.of("-Wno-warning", "-someflag", "-mmacosx-version-min=10.8")))
            .build();
    ImmutableMap<String, ImmutableMap<String, String>> buildConfigs =
        CxxPlatformXcodeConfigGenerator.getDefaultXcodeBuildConfigurationsFromCxxPlatform(
            platform, appendConfig, DEFAULT_PATH_RESOLVER);
    ImmutableMap<String, String> debugConfig =
        buildConfigs.get(CxxPlatformXcodeConfigGenerator.DEBUG_BUILD_CONFIGURATION_NAME);
    ImmutableMap<String, String> releaseConfig =
        buildConfigs.get(CxxPlatformXcodeConfigGenerator.RELEASE_BUILD_CONFIGURATION_NAME);
    ImmutableMap<String, String> profileConfig =
        buildConfigs.get(CxxPlatformXcodeConfigGenerator.PROFILE_BUILD_CONFIGURATION_NAME);
    assertThat(debugConfig, Matchers.equalTo(releaseConfig));
    assertThat(releaseConfig, Matchers.equalTo(profileConfig));
  }
}
