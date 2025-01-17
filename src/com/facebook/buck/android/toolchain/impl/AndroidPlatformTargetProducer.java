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

package com.facebook.buck.android.toolchain.impl;

import com.facebook.buck.android.toolchain.AdbToolchain;
import com.facebook.buck.android.toolchain.AndroidBuildToolsLocation;
import com.facebook.buck.android.toolchain.AndroidPlatformTarget;
import com.facebook.buck.android.toolchain.AndroidSdkLocation;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.tool.impl.VersionedTool;
import com.facebook.buck.core.toolchain.toolprovider.ToolProvider;
import com.facebook.buck.core.toolchain.toolprovider.impl.ConstantToolProvider;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.util.environment.Platform;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.io.File;
import java.io.FilenameFilter;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AndroidPlatformTargetProducer {

  @VisibleForTesting
  static final Pattern PLATFORM_TARGET_PATTERN =
      Pattern.compile("(?:Google Inc\\.:Google APIs:|android-)(.+)");

  /** @param platformId for the platform, such as "Google Inc.:Google APIs:16" */
  public static AndroidPlatformTarget getTargetForId(
      ProjectFilesystem filesystem,
      String platformId,
      AndroidBuildToolsLocation androidBuildToolsLocation,
      AndroidSdkLocation androidSdkLocation,
      Optional<Supplier<Tool>> aaptOverride,
      Optional<ToolProvider> aapt2Override,
      Optional<ToolProvider> zipalignOverride,
      AdbToolchain adbToolchain) {

    Matcher platformMatcher = PLATFORM_TARGET_PATTERN.matcher(platformId);
    if (platformMatcher.matches()) {
      String apiLevel = platformMatcher.group(1);
      Factory platformTargetFactory;
      if (platformId.contains("Google APIs")) {
        platformTargetFactory = new AndroidWithGoogleApisFactory();
      } else {
        platformTargetFactory = new AndroidWithoutGoogleApisFactory();
      }
      return platformTargetFactory.newInstance(
          filesystem,
          androidBuildToolsLocation,
          androidSdkLocation,
          apiLevel,
          aaptOverride,
          aapt2Override,
          zipalignOverride,
          adbToolchain);
    } else {
      String messagePrefix =
          String.format("The Android SDK for '%s' could not be found. ", platformId);
      throw new HumanReadableException(
          messagePrefix
              + "Must set ANDROID_SDK to point to the absolute path of your Android SDK directory.");
    }
  }

  public static AndroidPlatformTarget getDefaultPlatformTarget(
      ProjectFilesystem filesystem,
      AndroidBuildToolsLocation androidBuildToolsLocation,
      AndroidSdkLocation androidSdkLocation,
      Optional<Supplier<Tool>> aaptOverride,
      Optional<ToolProvider> aapt2Override,
      Optional<ToolProvider> zipalignOverride,
      AdbToolchain platformToolsLocation) {
    return getTargetForId(
        filesystem,
        AndroidPlatformTarget.DEFAULT_ANDROID_PLATFORM_TARGET,
        androidBuildToolsLocation,
        androidSdkLocation,
        aaptOverride,
        aapt2Override,
        zipalignOverride,
        platformToolsLocation);
  }

  private interface Factory {

    AndroidPlatformTarget newInstance(
        ProjectFilesystem filesystem,
        AndroidBuildToolsLocation androidBuildToolsLocation,
        AndroidSdkLocation androidSdkLocation,
        String apiLevel,
        Optional<Supplier<Tool>> aaptOverride,
        Optional<ToolProvider> aapt2Override,
        Optional<ToolProvider> zipalignOverride,
        AdbToolchain adbToolchain);
  }

  /**
   * Given the path to the Android SDK as well as the platform path within the Android SDK, find all
   * the files needed to create the {@link AndroidPlatformTarget}, assuming that the organization of
   * the Android SDK conforms to the ordinary directory structure.
   */
  @VisibleForTesting
  static AndroidPlatformTarget createFromDefaultDirectoryStructure(
      ProjectFilesystem filesystem,
      String name,
      AndroidBuildToolsLocation androidBuildToolsLocation,
      AndroidSdkLocation androidSdkLocation,
      String platformDirectoryPath,
      Set<Path> additionalJarPaths,
      Optional<Supplier<Tool>> aaptOverride,
      Optional<ToolProvider> aapt2Override,
      Optional<ToolProvider> zipalignOverride,
      AdbToolchain adbToolchain) {
    Path androidSdkDir = androidSdkLocation.getSdkRootPath();
    if (!androidSdkDir.isAbsolute()) {
      throw new HumanReadableException(
          "Path to Android SDK must be absolute but was: %s.", androidSdkDir);
    }

    Path platformDirectory = androidSdkDir.resolve(platformDirectoryPath);
    AbsPath androidJar = AbsPath.of(platformDirectory.resolve("android.jar"));

    // Add any libraries found in the optional directory under the Android SDK directory. These
    // go at the head of the bootclasspath before any additional jars.
    File optionalDirectory = platformDirectory.resolve("optional").toFile();
    if (optionalDirectory.exists() && optionalDirectory.isDirectory()) {
      String[] optionalDirList = optionalDirectory.list(new AddonFilter());
      if (optionalDirList != null) {
        Arrays.sort(optionalDirList);
        ImmutableSet.Builder<Path> additionalJars = ImmutableSet.builder();
        for (String file : optionalDirList) {
          additionalJars.add(optionalDirectory.toPath().resolve(file));
        }
        additionalJars.addAll(additionalJarPaths);
        additionalJarPaths = additionalJars.build();
      }
    }

    ImmutableList.Builder<AbsPath> bootclasspathEntriesBuilder =
        ImmutableList.builderWithExpectedSize(1 + additionalJarPaths.size());
    // Make sure android.jar is at the front of the bootclasspath.
    bootclasspathEntriesBuilder.add(androidJar);
    additionalJarPaths.stream()
        .map(filesystem::resolve)
        .map(AbsPath::of)
        .forEach(bootclasspathEntriesBuilder::add);

    // This is the directory under the Android SDK directory that contains the dx script, jack,
    // jill, and binaries.
    Path buildToolsDir = androidBuildToolsLocation.getBuildToolsPath();
    Path buildToolsBinDir = androidBuildToolsLocation.getBuildToolsBinPath();
    String version = buildToolsDir.getFileName().toString();

    String binaryExtension = Platform.detect() == Platform.WINDOWS ? ".exe" : "";

    Path androidFrameworkIdlFile = platformDirectory.resolve("framework.aidl");
    Path proguardJar = androidSdkDir.resolve("tools/proguard/lib/proguard.jar");
    Path proguardConfig = androidSdkDir.resolve("tools/proguard/proguard-android.txt");
    Path optimizedProguardConfig =
        androidSdkDir.resolve("tools/proguard/proguard-android-optimize.txt");

    return AndroidPlatformTarget.of(
        name,
        androidJar.getPath(),
        bootclasspathEntriesBuilder.build(),
        aaptOverride.orElse(
            () ->
                VersionedTool.of(
                    "aapt" + binaryExtension,
                    PathSourcePath.of(
                        filesystem,
                        androidSdkDir
                            .resolve(androidBuildToolsLocation.getAaptPath())
                            .toAbsolutePath()),
                    version)),
        aapt2Override.orElse(
            new ConstantToolProvider(
                VersionedTool.of(
                    "aapt2" + binaryExtension,
                    PathSourcePath.of(
                        filesystem,
                        androidSdkDir
                            .resolve(androidBuildToolsLocation.getAapt2Path())
                            .toAbsolutePath()),
                    version))),
        adbToolchain.getAdbPath(),
        androidSdkDir.resolve(buildToolsBinDir).resolve("aidl" + binaryExtension).toAbsolutePath(),
        zipalignOverride.orElse(
            new ConstantToolProvider(
                VersionedTool.of(
                    "zipalign" + binaryExtension,
                    PathSourcePath.of(
                        filesystem,
                        androidSdkDir
                            .resolve(androidBuildToolsLocation.getZipalignPath())
                            .toAbsolutePath()),
                    version))),
        buildToolsDir
            .resolve(Platform.detect() == Platform.WINDOWS ? "dx.bat" : "dx")
            .toAbsolutePath(),
        buildToolsDir
            .resolve(Platform.detect() == Platform.WINDOWS ? "d8.bat" : "d8")
            .toAbsolutePath(),
        androidFrameworkIdlFile,
        proguardJar,
        proguardConfig,
        optimizedProguardConfig);
  }

  /** Factory to build an AndroidPlatformTarget that corresponds to a given Google API level. */
  private static class AndroidWithGoogleApisFactory implements Factory {

    private static final String API_DIR_SUFFIX = "(?:-([0-9]+))*";

    @Override
    public AndroidPlatformTarget newInstance(
        ProjectFilesystem filesystem,
        AndroidBuildToolsLocation androidBuildToolsLocation,
        AndroidSdkLocation androidSdkLocation,
        String apiLevel,
        Optional<Supplier<Tool>> aaptOverride,
        Optional<ToolProvider> aapt2Override,
        Optional<ToolProvider> zipalignOverride,
        AdbToolchain adbToolchain) {
      // TODO(natthu): Use Paths instead of Strings everywhere in this file.
      Path androidSdkDir = androidSdkLocation.getSdkRootPath();
      File addonsParentDir = androidSdkDir.resolve("add-ons").toFile();
      String apiDirPrefix = "addon-google_apis-google-" + apiLevel;
      Pattern apiDirPattern = Pattern.compile(apiDirPrefix + API_DIR_SUFFIX);

      if (addonsParentDir.isDirectory()) {
        String[] addonsApiDirs =
            addonsParentDir.list((dir, name) -> apiDirPattern.matcher(name).matches());
        Arrays.sort(
            addonsApiDirs,
            new Comparator<String>() {
              @Override
              public int compare(String o1, String o2) {
                return getVersion(o1) - getVersion(o2);
              }

              private int getVersion(String dirName) {
                Matcher matcher = apiDirPattern.matcher(dirName);
                Preconditions.checkState(matcher.matches());
                if (matcher.group(1) != null) {
                  return Integer.parseInt(matcher.group(1));
                }
                return 0;
              }
            });

        ImmutableSet.Builder<Path> additionalJarPaths = ImmutableSet.builder();
        for (String dir : addonsApiDirs) {
          File libsDir = new File(addonsParentDir, dir + "/libs");

          String[] addonFiles;
          if (libsDir.isDirectory()
              && (addonFiles = libsDir.list(new AddonFilter())) != null
              && addonFiles.length != 0) {
            Arrays.sort(addonFiles);
            for (String addonJar : addonFiles) {
              additionalJarPaths.add(libsDir.toPath().resolve(addonJar));
            }

            return createFromDefaultDirectoryStructure(
                filesystem,
                "Google Inc.:Google APIs:" + apiLevel,
                androidBuildToolsLocation,
                androidSdkLocation,
                "platforms/android-" + apiLevel,
                additionalJarPaths.build(),
                aaptOverride,
                aapt2Override,
                zipalignOverride,
                adbToolchain);
          }
        }
      }

      throw new HumanReadableException(
          "Google APIs not found in %s.\n"
              + "Please run '%s/tools/android sdk' and select both 'SDK Platform' and "
              + "'Google APIs' under Android (API %s)",
          new File(addonsParentDir, apiDirPrefix + "/libs").getAbsolutePath(),
          androidSdkDir,
          apiLevel);
    }
  }

  private static class AndroidWithoutGoogleApisFactory implements Factory {

    @Override
    public AndroidPlatformTarget newInstance(
        ProjectFilesystem filesystem,
        AndroidBuildToolsLocation androidBuildToolsLocation,
        AndroidSdkLocation androidSdkLocation,
        String apiLevel,
        Optional<Supplier<Tool>> aaptOverride,
        Optional<ToolProvider> aapt2Override,
        Optional<ToolProvider> zipalignOverride,
        AdbToolchain adbToolchain) {
      return createFromDefaultDirectoryStructure(
          filesystem,
          "android-" + apiLevel,
          androidBuildToolsLocation,
          androidSdkLocation,
          "platforms/android-" + apiLevel,
          /* additionalJarPaths */ ImmutableSet.of(),
          aaptOverride,
          aapt2Override,
          zipalignOverride,
          adbToolchain);
    }
  }

  private static class AddonFilter implements FilenameFilter {

    @Override
    public boolean accept(File dir, String name) {
      return name.endsWith(".jar");
    }
  }
}
