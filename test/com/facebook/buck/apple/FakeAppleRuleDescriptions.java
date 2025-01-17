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

package com.facebook.buck.apple;

import com.facebook.buck.apple.toolchain.AppleCxxPlatform;
import com.facebook.buck.apple.toolchain.AppleCxxPlatformsProvider;
import com.facebook.buck.apple.toolchain.ApplePlatform;
import com.facebook.buck.apple.toolchain.AppleSdk;
import com.facebook.buck.apple.toolchain.AppleSdkPaths;
import com.facebook.buck.apple.toolchain.CodeSignIdentity;
import com.facebook.buck.apple.toolchain.CodeSignIdentityStore;
import com.facebook.buck.apple.toolchain.ProvisioningProfileStore;
import com.facebook.buck.apple.toolchain.UnresolvedAppleCxxPlatform;
import com.facebook.buck.apple.toolchain.impl.AppleCxxPlatforms;
import com.facebook.buck.apple.toolchain.impl.StaticUnresolvedAppleCxxPlatform;
import com.facebook.buck.apple.toolchain.impl.XcodeToolFinder;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.plugin.impl.BuckPluginManagerFactory;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.toolchain.impl.ToolchainProviderBuilder;
import com.facebook.buck.cxx.CxxBinaryFactory;
import com.facebook.buck.cxx.CxxBinaryFlavored;
import com.facebook.buck.cxx.CxxBinaryImplicitFlavors;
import com.facebook.buck.cxx.CxxBinaryMetadataFactory;
import com.facebook.buck.cxx.CxxLibraryFactory;
import com.facebook.buck.cxx.CxxLibraryFlavored;
import com.facebook.buck.cxx.CxxLibraryImplicitFlavors;
import com.facebook.buck.cxx.CxxLibraryMetadataFactory;
import com.facebook.buck.cxx.config.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.facebook.buck.cxx.toolchain.UnresolvedCxxPlatform;
import com.facebook.buck.cxx.toolchain.impl.DefaultCxxPlatforms;
import com.facebook.buck.cxx.toolchain.impl.StaticUnresolvedCxxPlatform;
import com.facebook.buck.infer.InferConfig;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.swift.SwiftBuckConfig;
import com.facebook.buck.swift.SwiftLibraryDescription;
import com.facebook.buck.swift.toolchain.SwiftPlatformsProvider;
import com.facebook.buck.swift.toolchain.UnresolvedSwiftPlatform;
import com.facebook.buck.swift.toolchain.impl.StaticUnresolvedSwiftPlatform;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

/** Utility class holding pre-made fake Apple rule descriptions for use in tests. */
public class FakeAppleRuleDescriptions {
  // Utility class, do not instantiate.
  private FakeAppleRuleDescriptions() {}

  private static final BuckConfig DEFAULT_BUCK_CONFIG =
      FakeBuckConfig.builder()
          .setSections(
              "[apple]",
              "default_debug_info_format_for_tests = NONE",
              "default_debug_info_format_for_binaries = NONE",
              "default_debug_info_format_for_libraries = NONE",
              "[swift]",
              "prefix_serialized_debugging_options = true")
          .build();

  public static final AppleSdk DEFAULT_MACOSX_SDK =
      AppleSdk.builder()
          .setApplePlatform(ApplePlatform.MACOSX)
          .setName("macosx")
          .setArchitectures(ImmutableList.of("x86_64"))
          .setVersion("10.10")
          .setToolchains(ImmutableList.of())
          .build();

  public static final AppleSdk DEFAULT_IPHONEOS_SDK =
      AppleSdk.builder()
          .setApplePlatform(ApplePlatform.IPHONEOS)
          .setName("iphoneos")
          .setArchitectures(ImmutableList.of("i386", "x86_64"))
          .setVersion("8.0")
          .setToolchains(ImmutableList.of())
          .build();

  public static final AppleSdk DEFAULT_WATCHOS_SDK =
      AppleSdk.builder()
          .setApplePlatform(ApplePlatform.WATCHOS)
          .setName("watchos")
          .setArchitectures(ImmutableList.of("armv7k"))
          .setVersion("2.0")
          .setToolchains(ImmutableList.of())
          .build();

  public static final ProjectFilesystem FAKE_PROJECT_FILESYSTEM;

  static {
    ProjectFilesystem filesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();
    Stream.of(
            "Platforms/iPhoneOS.platform/Developer/usr/bin/libtool",
            "Toolchains/XcodeDefault.xctoolchain/usr/bin/ar",
            "Toolchains/XcodeDefault.xctoolchain/usr/bin/clang",
            "Toolchains/XcodeDefault.xctoolchain/usr/bin/clang++",
            "Toolchains/XcodeDefault.xctoolchain/usr/bin/dsymutil",
            "Toolchains/XcodeDefault.xctoolchain/usr/bin/dwarfdump",
            "Toolchains/XcodeDefault.xctoolchain/usr/bin/ld",
            "Toolchains/XcodeDefault.xctoolchain/usr/bin/libtool",
            "Toolchains/XcodeDefault.xctoolchain/usr/bin/lipo",
            "Toolchains/XcodeDefault.xctoolchain/usr/bin/nm",
            "Toolchains/XcodeDefault.xctoolchain/usr/bin/ranlib",
            "Toolchains/XcodeDefault.xctoolchain/usr/bin/strip",
            "Toolchains/XcodeDefault.xctoolchain/usr/bin/swiftc",
            "Toolchains/XcodeDefault.xctoolchain/usr/bin/swift-stdlib-tool",
            "Tools/otest",
            "usr/bin/actool",
            "usr/bin/ibtool",
            "usr/bin/momc",
            "usr/bin/copySceneKitAssets",
            "usr/bin/xctest")
        .forEach(
            path -> {
              Path actualPath = filesystem.getPath(path);
              try {
                Files.createDirectories(actualPath.getParent());
                Files.createFile(actualPath);
              } catch (IOException e) {
                throw new RuntimeException(e);
              }
            });
    FAKE_PROJECT_FILESYSTEM = filesystem;
  }

  public static final AppleSdkPaths DEFAULT_MACOSX_SDK_PATHS =
      AppleSdkPaths.builder()
          .setDeveloperPath(FAKE_PROJECT_FILESYSTEM.getPath("."))
          .addToolchainPaths(FAKE_PROJECT_FILESYSTEM.getPath("Toolchains/XcodeDefault.xctoolchain"))
          .setPlatformPath(FAKE_PROJECT_FILESYSTEM.getPath("Platforms/MacOSX.platform"))
          .setPlatformSourcePath(
              PathSourcePath.of(
                  FAKE_PROJECT_FILESYSTEM,
                  FAKE_PROJECT_FILESYSTEM.getPath("Platforms/MacOSX.platform")))
          .setSdkPath(
              FAKE_PROJECT_FILESYSTEM.getPath(
                  "Platforms/MacOSX.platform/Developer/SDKs/MacOSX.sdk"))
          .setSdkSourcePath(
              PathSourcePath.of(
                  FAKE_PROJECT_FILESYSTEM,
                  FAKE_PROJECT_FILESYSTEM.getPath(
                      "Platforms/MacOSX.platform/Developer/SDKs/MacOSX.sdk")))
          .build();

  public static final AppleSdkPaths DEFAULT_IPHONEOS_SDK_PATHS =
      AppleSdkPaths.builder()
          .setDeveloperPath(FAKE_PROJECT_FILESYSTEM.getPath("."))
          .addToolchainPaths(FAKE_PROJECT_FILESYSTEM.getPath("Toolchains/XcodeDefault.xctoolchain"))
          .setPlatformPath(FAKE_PROJECT_FILESYSTEM.getPath("Platforms/iPhoneOS.platform"))
          .setPlatformSourcePath(
              PathSourcePath.of(
                  FAKE_PROJECT_FILESYSTEM,
                  FAKE_PROJECT_FILESYSTEM.getPath("Platforms/iPhoneOS.platform")))
          .setSdkPath(
              FAKE_PROJECT_FILESYSTEM.getPath(
                  "Platforms/iPhoneOS.platform/Developer/SDKs/iPhoneOS.sdk"))
          .setSdkSourcePath(
              PathSourcePath.of(
                  FAKE_PROJECT_FILESYSTEM,
                  FAKE_PROJECT_FILESYSTEM.getPath(
                      "Platforms/iPhoneOS.platform/Developer/SDKs/iPhoneOS.sdk")))
          .build();

  public static final AppleCxxPlatforms.XcodeBuildVersionCache FAKE_XCODE_BUILD_VERSION_CACHE =
      new AppleCxxPlatforms.XcodeBuildVersionCache() {
        @Override
        protected Optional<String> lookup(Path developerDir) {
          return Optional.of("0A0000");
        }
      };

  public static final AppleCxxPlatform DEFAULT_IPHONEOS_ARMV7_PLATFORM =
      AppleCxxPlatforms.buildWithXcodeToolFinder(
          FAKE_PROJECT_FILESYSTEM,
          DEFAULT_IPHONEOS_SDK,
          "8.0",
          "armv7",
          DEFAULT_IPHONEOS_SDK_PATHS,
          DEFAULT_BUCK_CONFIG,
          new XcodeToolFinder(DEFAULT_BUCK_CONFIG.getView(AppleConfig.class)),
          FAKE_XCODE_BUILD_VERSION_CACHE);

  public static final AppleCxxPlatform DEFAULT_IPHONEOS_ARM64_PLATFORM =
      AppleCxxPlatforms.buildWithXcodeToolFinder(
          FAKE_PROJECT_FILESYSTEM,
          DEFAULT_IPHONEOS_SDK,
          "8.0",
          "arm64",
          DEFAULT_IPHONEOS_SDK_PATHS,
          DEFAULT_BUCK_CONFIG,
          new XcodeToolFinder(DEFAULT_BUCK_CONFIG.getView(AppleConfig.class)),
          FAKE_XCODE_BUILD_VERSION_CACHE);

  public static final AppleCxxPlatform DEFAULT_WATCHOS_ARMV7K_PLATFORM =
      AppleCxxPlatforms.buildWithXcodeToolFinder(
          FAKE_PROJECT_FILESYSTEM,
          DEFAULT_WATCHOS_SDK,
          "2.0",
          "armv7k",
          DEFAULT_IPHONEOS_SDK_PATHS,
          DEFAULT_BUCK_CONFIG,
          new XcodeToolFinder(DEFAULT_BUCK_CONFIG.getView(AppleConfig.class)),
          FAKE_XCODE_BUILD_VERSION_CACHE);

  public static final AppleCxxPlatform DEFAULT_WATCHOS_ARM6432_PLATFORM =
      AppleCxxPlatforms.buildWithXcodeToolFinder(
          FAKE_PROJECT_FILESYSTEM,
          DEFAULT_WATCHOS_SDK,
          "2.0",
          "arm64_32",
          DEFAULT_IPHONEOS_SDK_PATHS,
          DEFAULT_BUCK_CONFIG,
          new XcodeToolFinder(DEFAULT_BUCK_CONFIG.getView(AppleConfig.class)),
          FAKE_XCODE_BUILD_VERSION_CACHE);

  public static final AppleCxxPlatform DEFAULT_MACOSX_X86_64_PLATFORM =
      AppleCxxPlatforms.buildWithXcodeToolFinder(
          FAKE_PROJECT_FILESYSTEM,
          DEFAULT_MACOSX_SDK,
          "8.0",
          "x86_64",
          DEFAULT_MACOSX_SDK_PATHS,
          DEFAULT_BUCK_CONFIG,
          new XcodeToolFinder(DEFAULT_BUCK_CONFIG.getView(AppleConfig.class)),
          FAKE_XCODE_BUILD_VERSION_CACHE);

  public static final UnresolvedCxxPlatform DEFAULT_PLATFORM =
      new StaticUnresolvedCxxPlatform(
          DefaultCxxPlatforms.build(
              Platform.MACOS,
              new CxxBuckConfig(DEFAULT_BUCK_CONFIG),
              CxxPlatformUtils.DEFAULT_DOWNWARD_API_CONFIG));

  public static final FlavorDomain<UnresolvedCxxPlatform> DEFAULT_APPLE_FLAVOR_DOMAIN =
      FlavorDomain.of(
          "Fake iPhone C/C++ Platform",
          DEFAULT_PLATFORM,
          new StaticUnresolvedCxxPlatform(DEFAULT_IPHONEOS_ARMV7_PLATFORM.getCxxPlatform()),
          new StaticUnresolvedCxxPlatform(DEFAULT_IPHONEOS_ARM64_PLATFORM.getCxxPlatform()),
          new StaticUnresolvedCxxPlatform(DEFAULT_MACOSX_X86_64_PLATFORM.getCxxPlatform()),
          new StaticUnresolvedCxxPlatform(DEFAULT_WATCHOS_ARMV7K_PLATFORM.getCxxPlatform()));

  public static final FlavorDomain<UnresolvedAppleCxxPlatform>
      DEFAULT_APPLE_CXX_PLATFORM_FLAVOR_DOMAIN =
          FlavorDomain.of(
              "Fake Apple C++ Platforms",
              StaticUnresolvedAppleCxxPlatform.of(
                  DEFAULT_IPHONEOS_ARMV7_PLATFORM, DEFAULT_IPHONEOS_ARMV7_PLATFORM.getFlavor()),
              StaticUnresolvedAppleCxxPlatform.of(
                  DEFAULT_IPHONEOS_ARM64_PLATFORM, DEFAULT_IPHONEOS_ARM64_PLATFORM.getFlavor()),
              StaticUnresolvedAppleCxxPlatform.of(
                  DEFAULT_MACOSX_X86_64_PLATFORM, DEFAULT_MACOSX_X86_64_PLATFORM.getFlavor()),
              StaticUnresolvedAppleCxxPlatform.of(
                  DEFAULT_WATCHOS_ARMV7K_PLATFORM, DEFAULT_WATCHOS_ARMV7K_PLATFORM.getFlavor()),
              StaticUnresolvedAppleCxxPlatform.of(
                  DEFAULT_WATCHOS_ARM6432_PLATFORM, DEFAULT_WATCHOS_ARM6432_PLATFORM.getFlavor()));

  public static final FlavorDomain<UnresolvedSwiftPlatform> DEFAULT_SWIFT_PLATFORM_FLAVOR_DOMAIN =
      FlavorDomain.of(
          "Fake Swift Platform",
          StaticUnresolvedSwiftPlatform.of(
              DEFAULT_IPHONEOS_ARMV7_PLATFORM.getSwiftPlatform(),
              DEFAULT_IPHONEOS_ARMV7_PLATFORM.getFlavor()),
          StaticUnresolvedSwiftPlatform.of(
              DEFAULT_IPHONEOS_ARM64_PLATFORM.getSwiftPlatform(),
              DEFAULT_IPHONEOS_ARM64_PLATFORM.getFlavor()),
          StaticUnresolvedSwiftPlatform.of(
              DEFAULT_MACOSX_X86_64_PLATFORM.getSwiftPlatform(),
              DEFAULT_MACOSX_X86_64_PLATFORM.getFlavor()),
          StaticUnresolvedSwiftPlatform.of(
              DEFAULT_WATCHOS_ARMV7K_PLATFORM.getSwiftPlatform(),
              DEFAULT_WATCHOS_ARMV7K_PLATFORM.getFlavor()));

  public static SwiftLibraryDescription createSwiftLibraryDescription(BuckConfig buckConfig) {
    return new SwiftLibraryDescription(
        createTestToolchainProviderForSwiftPlatform(
            DEFAULT_SWIFT_PLATFORM_FLAVOR_DOMAIN, DEFAULT_APPLE_CXX_PLATFORM_FLAVOR_DOMAIN),
        CxxPlatformUtils.DEFAULT_CONFIG,
        new SwiftBuckConfig(buckConfig),
        CxxPlatformUtils.DEFAULT_DOWNWARD_API_CONFIG);
  }

  public static final SwiftLibraryDescription SWIFT_LIBRARY_DESCRIPTION =
      createSwiftLibraryDescription(DEFAULT_BUCK_CONFIG);

  /** A fake apple_library description with an iOS platform for use in tests. */
  public static final AppleLibraryDescription LIBRARY_DESCRIPTION =
      createAppleLibraryDescription(DEFAULT_BUCK_CONFIG);

  public static AppleLibraryDescription createAppleLibraryDescription(BuckConfig buckConfig) {
    ToolchainProvider toolchainProvider =
        createTestToolchainProvider(
            DEFAULT_APPLE_CXX_PLATFORM_FLAVOR_DOMAIN, DEFAULT_SWIFT_PLATFORM_FLAVOR_DOMAIN);
    CxxLibraryImplicitFlavors cxxLibraryImplicitFlavors =
        new CxxLibraryImplicitFlavors(toolchainProvider, CxxPlatformUtils.DEFAULT_CONFIG);
    CxxLibraryFlavored cxxLibraryFlavored =
        new CxxLibraryFlavored(toolchainProvider, CxxPlatformUtils.DEFAULT_CONFIG);
    CxxLibraryFactory cxxLibraryFactory =
        new CxxLibraryFactory(
            toolchainProvider,
            CxxPlatformUtils.DEFAULT_CONFIG,
            InferConfig.of(buckConfig),
            CxxPlatformUtils.DEFAULT_DOWNWARD_API_CONFIG);
    CxxLibraryMetadataFactory cxxLibraryMetadataFactory =
        new CxxLibraryMetadataFactory(
            toolchainProvider,
            buckConfig.getFilesystem(),
            CxxPlatformUtils.DEFAULT_CONFIG,
            CxxPlatformUtils.DEFAULT_DOWNWARD_API_CONFIG);
    XCodeDescriptions xcodeDescriptions =
        XCodeDescriptionsFactory.create(BuckPluginManagerFactory.createPluginManager());

    return new AppleLibraryDescription(
        toolchainProvider,
        xcodeDescriptions,
        SWIFT_LIBRARY_DESCRIPTION,
        buckConfig.getView(AppleConfig.class),
        new CxxBuckConfig(buckConfig),
        new SwiftBuckConfig(buckConfig),
        CxxPlatformUtils.DEFAULT_DOWNWARD_API_CONFIG,
        cxxLibraryImplicitFlavors,
        cxxLibraryFlavored,
        cxxLibraryFactory,
        cxxLibraryMetadataFactory);
  }

  /** A fake apple_binary description with an iOS platform for use in tests. */
  public static final AppleBinaryDescription BINARY_DESCRIPTION = createAppleBinaryDescription();

  private static AppleBinaryDescription createAppleBinaryDescription() {
    ToolchainProvider toolchainProvider =
        new ToolchainProviderBuilder()
            .withToolchain(
                CxxPlatformsProvider.DEFAULT_NAME,
                CxxPlatformsProvider.of(
                    new StaticUnresolvedCxxPlatform(
                        DEFAULT_IPHONEOS_ARMV7_PLATFORM.getCxxPlatform()),
                    DEFAULT_APPLE_FLAVOR_DOMAIN))
            .build();
    CxxBinaryImplicitFlavors cxxBinaryImplicitFlavors =
        new CxxBinaryImplicitFlavors(toolchainProvider, CxxPlatformUtils.DEFAULT_CONFIG);
    CxxBinaryFactory cxxBinaryFactory =
        new CxxBinaryFactory(
            toolchainProvider,
            CxxPlatformUtils.DEFAULT_CONFIG,
            CxxPlatformUtils.DEFAULT_DOWNWARD_API_CONFIG,
            InferConfig.of(DEFAULT_BUCK_CONFIG));
    CxxBinaryMetadataFactory cxxBinaryMetadataFactory =
        new CxxBinaryMetadataFactory(toolchainProvider);
    CxxBinaryFlavored cxxBinaryFlavored =
        new CxxBinaryFlavored(toolchainProvider, CxxPlatformUtils.DEFAULT_CONFIG);
    XCodeDescriptions xcodeDescriptions =
        XCodeDescriptionsFactory.create(BuckPluginManagerFactory.createPluginManager());

    return new AppleBinaryDescription(
        createTestToolchainProviderForApplePlatform(DEFAULT_APPLE_CXX_PLATFORM_FLAVOR_DOMAIN),
        xcodeDescriptions,
        SWIFT_LIBRARY_DESCRIPTION,
        DEFAULT_BUCK_CONFIG.getView(AppleConfig.class),
        CxxPlatformUtils.DEFAULT_CONFIG,
        DEFAULT_BUCK_CONFIG.getView(SwiftBuckConfig.class),
        CxxPlatformUtils.DEFAULT_DOWNWARD_API_CONFIG,
        cxxBinaryImplicitFlavors,
        cxxBinaryFactory,
        cxxBinaryMetadataFactory,
        cxxBinaryFlavored);
  }

  /** A fake apple_bundle description with an iOS platform for use in tests. */
  public static final AppleBundleDescription BUNDLE_DESCRIPTION =
      new AppleBundleDescription(
          createTestToolchainProviderForApplePlatform(DEFAULT_APPLE_CXX_PLATFORM_FLAVOR_DOMAIN),
          XCodeDescriptionsFactory.create(BuckPluginManagerFactory.createPluginManager()),
          BINARY_DESCRIPTION,
          LIBRARY_DESCRIPTION,
          DEFAULT_BUCK_CONFIG.getView(AppleConfig.class),
          CxxPlatformUtils.DEFAULT_CONFIG,
          DEFAULT_BUCK_CONFIG.getView(SwiftBuckConfig.class),
          CxxPlatformUtils.DEFAULT_DOWNWARD_API_CONFIG);

  /** A fake apple_test description with an iOS platform for use in tests. */
  public static final AppleTestDescription TEST_DESCRIPTION =
      new AppleTestDescription(
          createTestToolchainProviderForApplePlatform(DEFAULT_APPLE_CXX_PLATFORM_FLAVOR_DOMAIN),
          XCodeDescriptionsFactory.create(BuckPluginManagerFactory.createPluginManager()),
          DEFAULT_BUCK_CONFIG.getView(AppleConfig.class),
          CxxPlatformUtils.DEFAULT_CONFIG,
          DEFAULT_BUCK_CONFIG.getView(SwiftBuckConfig.class),
          CxxPlatformUtils.DEFAULT_DOWNWARD_API_CONFIG,
          LIBRARY_DESCRIPTION);

  private static ToolchainProvider createTestToolchainProviderForSwiftPlatform(
      FlavorDomain<UnresolvedSwiftPlatform> swiftFlavorDomain,
      FlavorDomain<UnresolvedAppleCxxPlatform> appleCxxPlatformFlavorDomain) {
    return new ToolchainProviderBuilder()
        .withToolchain(
            SwiftPlatformsProvider.DEFAULT_NAME, SwiftPlatformsProvider.of(swiftFlavorDomain))
        .withToolchain(
            CxxPlatformsProvider.DEFAULT_NAME,
            CxxPlatformsProvider.of(DEFAULT_PLATFORM, DEFAULT_APPLE_FLAVOR_DOMAIN))
        .withToolchain(
            AppleCxxPlatformsProvider.DEFAULT_NAME,
            AppleCxxPlatformsProvider.of(appleCxxPlatformFlavorDomain))
        .build();
  }

  private static ToolchainProvider createTestToolchainProviderForApplePlatform(
      FlavorDomain<UnresolvedAppleCxxPlatform> appleCxxPlatformFlavorDomain) {
    return new ToolchainProviderBuilder()
        .withToolchain(
            AppleCxxPlatformsProvider.DEFAULT_NAME,
            AppleCxxPlatformsProvider.of(appleCxxPlatformFlavorDomain))
        .withToolchain(
            CodeSignIdentityStore.DEFAULT_NAME,
            fromIdentities(ImmutableList.of(CodeSignIdentity.AD_HOC)))
        .withToolchain(ProvisioningProfileStore.DEFAULT_NAME, ProvisioningProfileStore.empty())
        .withToolchain(
            CxxPlatformsProvider.DEFAULT_NAME,
            CxxPlatformsProvider.of(DEFAULT_PLATFORM, DEFAULT_APPLE_FLAVOR_DOMAIN))
        .build();
  }

  private static ToolchainProvider createTestToolchainProvider(
      FlavorDomain<UnresolvedAppleCxxPlatform> appleCxxPlatformFlavorDomain,
      FlavorDomain<UnresolvedSwiftPlatform> swiftFlavorDomain) {
    return new ToolchainProviderBuilder()
        .withToolchain(
            AppleCxxPlatformsProvider.DEFAULT_NAME,
            AppleCxxPlatformsProvider.of(appleCxxPlatformFlavorDomain))
        .withToolchain(
            SwiftPlatformsProvider.DEFAULT_NAME, SwiftPlatformsProvider.of(swiftFlavorDomain))
        .withToolchain(
            CodeSignIdentityStore.DEFAULT_NAME,
            fromIdentities(ImmutableList.of(CodeSignIdentity.AD_HOC)))
        .withToolchain(ProvisioningProfileStore.DEFAULT_NAME, ProvisioningProfileStore.empty())
        .withToolchain(
            CxxPlatformsProvider.DEFAULT_NAME,
            CxxPlatformsProvider.of(DEFAULT_PLATFORM, DEFAULT_APPLE_FLAVOR_DOMAIN))
        .build();
  }

  private static CodeSignIdentityStore fromIdentities(Iterable<CodeSignIdentity> identities) {
    return CodeSignIdentityStore.of(Suppliers.ofInstance(ImmutableList.copyOf(identities)));
  }
}
