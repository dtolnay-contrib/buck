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

package com.facebook.buck.apple.toolchain.impl;

import com.dd.plist.NSDictionary;
import com.dd.plist.NSObject;
import com.dd.plist.NSString;
import com.dd.plist.PropertyListFormatException;
import com.dd.plist.PropertyListParser;
import com.facebook.buck.apple.AppleConfig;
import com.facebook.buck.apple.AppleLibraryDescriptionSwiftEnhancer;
import com.facebook.buck.apple.AppleStripFlags;
import com.facebook.buck.apple.common.AppleCompilerTargetTriple;
import com.facebook.buck.apple.toolchain.AppleCxxPlatform;
import com.facebook.buck.apple.toolchain.ApplePlatform;
import com.facebook.buck.apple.toolchain.AppleSdk;
import com.facebook.buck.apple.toolchain.AppleSdkPaths;
import com.facebook.buck.apple.toolchain.AppleToolchain;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.UserFlavor;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.tool.impl.CommandTool;
import com.facebook.buck.core.toolchain.tool.impl.VersionedTool;
import com.facebook.buck.core.toolchain.toolprovider.impl.ConstantToolProvider;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.cxx.config.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.ArchiveContents;
import com.facebook.buck.cxx.toolchain.ArchiverProvider;
import com.facebook.buck.cxx.toolchain.BsdArchiver;
import com.facebook.buck.cxx.toolchain.CompilerProvider;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxToolProvider;
import com.facebook.buck.cxx.toolchain.DebugPathSanitizer;
import com.facebook.buck.cxx.toolchain.HeaderVerification;
import com.facebook.buck.cxx.toolchain.PicType;
import com.facebook.buck.cxx.toolchain.PosixNmSymbolNameTool;
import com.facebook.buck.cxx.toolchain.PrefixMapDebugPathSanitizer;
import com.facebook.buck.cxx.toolchain.PreprocessorProvider;
import com.facebook.buck.cxx.toolchain.StripStyle;
import com.facebook.buck.cxx.toolchain.ToolType;
import com.facebook.buck.cxx.toolchain.impl.CxxPlatforms;
import com.facebook.buck.cxx.toolchain.linker.LinkerProvider;
import com.facebook.buck.cxx.toolchain.linker.impl.DefaultLinkerProvider;
import com.facebook.buck.cxx.toolchain.linker.impl.Linkers;
import com.facebook.buck.downwardapi.config.DownwardApiConfig;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.swift.SwiftBuckConfig;
import com.facebook.buck.swift.toolchain.SwiftPlatform;
import com.facebook.buck.swift.toolchain.impl.SwiftPlatformFactory;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.stream.RichStream;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.text.ParseException;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import javax.xml.parsers.ParserConfigurationException;
import org.xml.sax.SAXException;

/**
 * Utility class to create Objective-C/C/C++/Objective-C++ platforms to support building iOS and Mac
 * OS X products with Xcode.
 */
public class AppleCxxPlatforms {

  private static final Logger LOG = Logger.get(AppleCxxPlatforms.class);

  // Utility class, do not instantiate.
  private AppleCxxPlatforms() {}

  private static final String USR_BIN = "usr/bin";

  public static ImmutableList<AppleCxxPlatform> buildAppleCxxPlatforms(
      Optional<ImmutableMap<AppleSdk, AppleSdkPaths>> sdkPaths,
      Optional<ImmutableMap<String, AppleToolchain>> toolchains,
      ProjectFilesystem filesystem,
      BuckConfig buckConfig) {
    if (!sdkPaths.isPresent() || !toolchains.isPresent()) {
      return ImmutableList.of();
    }

    AppleConfig appleConfig = buckConfig.getView(AppleConfig.class);
    ImmutableList.Builder<AppleCxxPlatform> appleCxxPlatformsBuilder = ImmutableList.builder();

    XcodeToolFinder xcodeToolFinder = new XcodeToolFinder(appleConfig);
    XcodeBuildVersionCache xcodeBuildVersionCache = new XcodeBuildVersionCache();
    sdkPaths
        .get()
        .forEach(
            (sdk, appleSdkPaths) -> {
              String targetSdkVersion =
                  appleConfig.getTargetSdkVersion(sdk.getApplePlatform()).orElse(sdk.getVersion());
              LOG.debug("SDK %s using default version %s", sdk, targetSdkVersion);
              for (String architecture : sdk.getArchitectures()) {
                appleCxxPlatformsBuilder.add(
                    buildWithXcodeToolFinder(
                        filesystem,
                        sdk,
                        targetSdkVersion,
                        architecture,
                        appleSdkPaths,
                        buckConfig,
                        xcodeToolFinder,
                        xcodeBuildVersionCache));
              }
            });
    return appleCxxPlatformsBuilder.build();
  }

  private static VersionedTool getXcodeToolWithToolPath(
      ProjectFilesystem filesystem,
      Path toolPath,
      AppleConfig appleConfig,
      String toolName,
      String toolVersion) {
    return VersionedTool.of(
        Joiner.on('-').join(ImmutableList.of("apple", toolName)),
        PathSourcePath.of(filesystem, toolPath),
        appleConfig.getXcodeToolVersion(toolName, toolVersion));
  }

  private static Optional<Tool> getOptionalXcodeTool(
      ProjectFilesystem filesystem,
      ImmutableList<Path> toolSearchPaths,
      XcodeToolFinder xcodeToolFinder,
      AppleConfig appleConfig,
      String toolName,
      String toolVersion) {
    Optional<Path> maybeToolPath = getOptionalToolPath(toolName, toolSearchPaths, xcodeToolFinder);
    return maybeToolPath.map(
        toolPath ->
            getXcodeToolWithToolPath(filesystem, toolPath, appleConfig, toolName, toolVersion));
  }

  private static VersionedTool getXcodeTool(
      ProjectFilesystem filesystem,
      ImmutableList<Path> toolSearchPaths,
      XcodeToolFinder xcodeToolFinder,
      AppleConfig appleConfig,
      String toolName,
      String toolVersion) {
    return getXcodeToolWithToolPath(
        filesystem,
        getToolPath(toolName, toolSearchPaths, xcodeToolFinder),
        appleConfig,
        toolName,
        toolVersion);
  }

  @VisibleForTesting
  public static AppleCxxPlatform buildWithXcodeToolFinder(
      ProjectFilesystem filesystem,
      AppleSdk targetSdk,
      String minVersion,
      String targetArchitecture,
      AppleSdkPaths sdkPaths,
      BuckConfig buckConfig,
      XcodeToolFinder xcodeToolFinder,
      XcodeBuildVersionCache xcodeBuildVersionCache) {
    AppleCxxPlatform.Builder platformBuilder = AppleCxxPlatform.builder();

    ImmutableList.Builder<Path> toolSearchPathsBuilder = ImmutableList.builder();
    // Search for tools from most specific to least specific.
    toolSearchPathsBuilder
        .add(sdkPaths.getSdkPath().resolve(USR_BIN))
        .add(sdkPaths.getSdkPath().resolve("Developer").resolve(USR_BIN))
        .add(sdkPaths.getPlatformPath().resolve("Developer").resolve(USR_BIN));
    for (Path toolchainPath : sdkPaths.getToolchainPaths()) {
      toolSearchPathsBuilder.add(toolchainPath.resolve(USR_BIN));
    }
    if (sdkPaths.getDeveloperPath().isPresent()) {
      toolSearchPathsBuilder.add(sdkPaths.getDeveloperPath().get().resolve(USR_BIN));
      toolSearchPathsBuilder.add(sdkPaths.getDeveloperPath().get().resolve("Tools"));
    }

    AppleConfig appleConfig = buckConfig.getView(AppleConfig.class);
    SwiftBuckConfig swiftBuckConfig = new SwiftBuckConfig(buckConfig);

    // TODO(beng): Add more and better cflags.
    ImmutableList.Builder<String> cflagsBuilder = ImmutableList.builder();
    cflagsBuilder.add("-isysroot", sdkPaths.getSdkPath().toString());
    if (appleConfig.getTargetTripleEnabled()) {
      AppleCompilerTargetTriple triple = getAppleTargetTripleForSdk(targetSdk, targetArchitecture);
      cflagsBuilder.add("-target", triple.getUnversionedTriple());
    } else {
      cflagsBuilder.add("-arch", targetArchitecture);
    }
    cflagsBuilder.add(targetSdk.getApplePlatform().getMinVersionFlagPrefix() + minVersion);

    for (String frameworksPath : targetSdk.getAdditionalSystemFrameworkSearchPaths()) {
      cflagsBuilder.add("-iframework", frameworksPath);
    }
    for (String headerSearchPath : targetSdk.getAdditionalSystemHeaderSearchPaths()) {
      cflagsBuilder.add("-isystem", headerSearchPath);
    }

    // Populate Xcode version keys from Xcode's own Info.plist if available.
    Optional<String> xcodeVersion = Optional.empty();
    Optional<String> xcodeBuildVersion = Optional.empty();
    Optional<Path> developerPath = sdkPaths.getDeveloperPath();
    if (developerPath.isPresent()) {
      Path xcodeBundlePath = developerPath.get().getParent();
      if (xcodeBundlePath != null) {
        Path xcodeInfoPlistPath = xcodeBundlePath.resolve("Info.plist");
        try (InputStream stream = Files.newInputStream(xcodeInfoPlistPath)) {
          NSDictionary parsedXcodeInfoPlist = (NSDictionary) PropertyListParser.parse(stream);

          NSObject xcodeVersionObject = parsedXcodeInfoPlist.objectForKey("DTXcode");
          if (xcodeVersionObject != null) {
            xcodeVersion = Optional.of(xcodeVersionObject.toString());
            platformBuilder.setXcodeVersion(xcodeVersion);
          }
        } catch (IOException e) {
          LOG.warn(
              "Error reading Xcode's info plist %s; ignoring Xcode versions", xcodeInfoPlistPath);
        } catch (PropertyListFormatException
            | ParseException
            | ParserConfigurationException
            | SAXException e) {
          LOG.warn("Error in parsing %s; ignoring Xcode versions", xcodeInfoPlistPath);
        }
      }

      xcodeBuildVersion = xcodeBuildVersionCache.lookup(developerPath.get());
      platformBuilder.setXcodeBuildVersion(xcodeBuildVersion);
      LOG.debug("Xcode build version is: " + xcodeBuildVersion.orElse("<absent>"));
    }

    ImmutableList.Builder<String> versions = ImmutableList.builder();
    versions.add(targetSdk.getVersion());

    boolean shouldEmbedBitcode = shouldEmbedBitcode(xcodeVersion);
    if (shouldEmbedBitcode && targetSdk.getApplePlatform().equals(ApplePlatform.WATCHOS)) {
      cflagsBuilder.add("-fembed-bitcode");
    }

    ImmutableList<String> toolchainVersions =
        targetSdk.getToolchains().stream()
            .map(AppleToolchain::getVersion)
            .flatMap(RichStream::from)
            .collect(ImmutableList.toImmutableList());
    if (toolchainVersions.isEmpty()) {
      if (!xcodeBuildVersion.isPresent()) {
        throw new HumanReadableException("Failed to read toolchain versions and Xcode version.");
      }
      versions.add(xcodeBuildVersion.get());
    } else {
      versions.addAll(toolchainVersions);
    }

    String version = Joiner.on(':').join(versions.build());

    ImmutableList<Path> toolSearchPaths = toolSearchPathsBuilder.build();

    Tool clangPath =
        getXcodeTool(filesystem, toolSearchPaths, xcodeToolFinder, appleConfig, "clang", version);

    Tool clangXxPath =
        getXcodeTool(filesystem, toolSearchPaths, xcodeToolFinder, appleConfig, "clang++", version);

    Tool ar =
        getXcodeTool(filesystem, toolSearchPaths, xcodeToolFinder, appleConfig, "ar", version);

    Tool ranlib =
        getXcodeTool(filesystem, toolSearchPaths, xcodeToolFinder, appleConfig, "ranlib", version);

    Tool strip =
        getXcodeTool(filesystem, toolSearchPaths, xcodeToolFinder, appleConfig, "strip", version);

    Tool nm =
        getXcodeTool(filesystem, toolSearchPaths, xcodeToolFinder, appleConfig, "nm", version);

    Tool actool =
        getXcodeTool(filesystem, toolSearchPaths, xcodeToolFinder, appleConfig, "actool", version);

    Tool ibtool =
        getXcodeTool(filesystem, toolSearchPaths, xcodeToolFinder, appleConfig, "ibtool", version);

    Tool libtool =
        getXcodeTool(filesystem, toolSearchPaths, xcodeToolFinder, appleConfig, "libtool", version);

    Tool momc =
        getXcodeTool(filesystem, toolSearchPaths, xcodeToolFinder, appleConfig, "momc", version);

    Tool xctest =
        getXcodeTool(filesystem, toolSearchPaths, xcodeToolFinder, appleConfig, "xctest", version);

    Tool dsymutil =
        getXcodeTool(
            filesystem, toolSearchPaths, xcodeToolFinder, appleConfig, "dsymutil", version);

    Optional<Tool> dwarfdump =
        getOptionalXcodeTool(
            filesystem, toolSearchPaths, xcodeToolFinder, appleConfig, "dwarfdump", version);

    // We are seeing a stack overflow in dsymutil during (fat) LTO
    // builds. Upstream dsymutil was patched to avoid recursion in the
    // offending path in https://reviews.llvm.org/D48899, and
    // https://reviews.llvm.org/D45172 mentioned that there is much
    // more stack space available when single threaded.
    if (appleConfig.shouldWorkAroundDsymutilLTOStackOverflowBug()) {
      dsymutil = new CommandTool.Builder(dsymutil).addArg("-num-threads=1").build();
    }

    Tool lipo =
        getXcodeTool(filesystem, toolSearchPaths, xcodeToolFinder, appleConfig, "lipo", version);

    Optional<SourcePath> watchKitStubBinaryPath =
        targetSdk
            .getApplePlatform()
            .getWatchKitStubBinaryPath()
            .map(input -> PathSourcePath.of(filesystem, sdkPaths.getSdkPath().resolve(input)));

    UserFlavor targetFlavor =
        UserFlavor.of(
            Flavor.replaceInvalidCharacters(targetSdk.getName() + "-" + targetArchitecture),
            String.format("SDK: %s, architecture: %s", targetSdk.getName(), targetArchitecture));
    CxxBuckConfig cxxBuckConfig =
        appleConfig.useFlavoredCxxSections()
            ? new CxxBuckConfig(buckConfig, targetFlavor)
            : new CxxBuckConfig(buckConfig);

    ImmutableBiMap.Builder<Path, String> sanitizerPaths = ImmutableBiMap.builder();
    sanitizerPaths.put(sdkPaths.getSdkPath(), "/APPLE_SDKROOT");
    sanitizerPaths.put(sdkPaths.getPlatformPath(), "/APPLE_PLATFORM_DIR");
    if (sdkPaths.getDeveloperPath().isPresent()) {
      sanitizerPaths.put(sdkPaths.getDeveloperPath().get(), "/APPLE_DEVELOPER_DIR");
    }

    DebugPathSanitizer compilerDebugPathSanitizer =
        new PrefixMapDebugPathSanitizer(".", sanitizerPaths.build());

    ImmutableList<String> cflags = cflagsBuilder.build();

    ImmutableMap.Builder<String, String> macrosBuilder = ImmutableMap.builder();
    macrosBuilder.put("SDKROOT", sdkPaths.getSdkPath().toString());
    macrosBuilder.put("PLATFORM_DIR", sdkPaths.getPlatformPath().toString());
    macrosBuilder.put("CURRENT_ARCH", targetArchitecture);
    if (sdkPaths.getDeveloperPath().isPresent()) {
      macrosBuilder.put("DEVELOPER_DIR", sdkPaths.getDeveloperPath().get().toString());
    }
    ImmutableMap<String, String> macros = macrosBuilder.build();

    Optional<String> buildVersion = Optional.empty();
    Path platformVersionPlistPath = sdkPaths.getPlatformPath().resolve("version.plist");
    try (InputStream versionPlist = Files.newInputStream(platformVersionPlistPath)) {
      NSDictionary versionInfo = (NSDictionary) PropertyListParser.parse(versionPlist);
      if (versionInfo != null) {
        NSObject productBuildVersion = versionInfo.objectForKey("ProductBuildVersion");
        if (productBuildVersion != null) {
          buildVersion = Optional.of(productBuildVersion.toString());
        } else {
          LOG.warn(
              "In %s, missing ProductBuildVersion. Build version will be unset for this platform.",
              platformVersionPlistPath);
        }
      } else {
        LOG.warn(
            "Empty version plist in %s. Build version will be unset for this platform.",
            platformVersionPlistPath);
      }
    } catch (NoSuchFileException e) {
      LOG.warn(
          "%s does not exist. Build version will be unset for this platform.",
          platformVersionPlistPath);
    } catch (PropertyListFormatException
        | SAXException
        | ParserConfigurationException
        | ParseException
        | IOException e) {
      // Some other error occurred, print the exception since it may contain error details.
      LOG.warn(
          e,
          "Failed to parse %s. Build version will be unset for this platform.",
          platformVersionPlistPath);
    }

    PreprocessorProvider aspp =
        new PreprocessorProvider(
            new ConstantToolProvider(clangPath), CxxToolProvider.Type.CLANG, ToolType.ASPP);
    CompilerProvider as =
        new CompilerProvider(
            new ConstantToolProvider(clangPath),
            CxxToolProvider.Type.CLANG,
            ToolType.AS,
            cxxBuckConfig.getDetailedUntrackedHeaderMessages());
    PreprocessorProvider cpp =
        new PreprocessorProvider(
            new ConstantToolProvider(clangPath), CxxToolProvider.Type.CLANG, ToolType.CPP);
    CompilerProvider cc =
        new CompilerProvider(
            new ConstantToolProvider(clangPath),
            CxxToolProvider.Type.CLANG,
            ToolType.CC,
            cxxBuckConfig.getDetailedUntrackedHeaderMessages());
    PreprocessorProvider cxxpp =
        new PreprocessorProvider(
            new ConstantToolProvider(clangXxPath), CxxToolProvider.Type.CLANG, ToolType.CXXPP);
    CompilerProvider cxx =
        new CompilerProvider(
            new ConstantToolProvider(clangXxPath),
            CxxToolProvider.Type.CLANG,
            ToolType.CXX,
            cxxBuckConfig.getDetailedUntrackedHeaderMessages());
    ImmutableList.Builder<String> whitelistBuilder = ImmutableList.builder();
    whitelistBuilder.add("^" + Pattern.quote(sdkPaths.getSdkPath().toString()) + "\\/.*");
    whitelistBuilder.add(
        "^"
            + Pattern.quote(sdkPaths.getPlatformPath() + "/Developer/Library/Frameworks")
            + "\\/.*");
    for (Path toolchainPath : sdkPaths.getToolchainPaths()) {
      LOG.debug("Apple toolchain path: %s", toolchainPath);
      try {
        whitelistBuilder.add("^" + Pattern.quote(toolchainPath.toRealPath().toString()) + "\\/.*");
      } catch (IOException e) {
        LOG.warn(e, "Apple toolchain path could not be resolved: %s", toolchainPath);
      }
    }
    HeaderVerification headerVerification =
        cxxBuckConfig
            .getHeaderVerificationOrIgnore()
            .withPlatformWhitelist(whitelistBuilder.build());
    LOG.debug(
        "Headers verification platform whitelist: %s", headerVerification.getPlatformWhitelist());
    ImmutableList<String> ldFlags =
        getLdFlags(targetSdk, filesystem, xcodeToolFinder, toolSearchPaths, appleConfig, version);
    ImmutableList<String> combinedLdFlags =
        ImmutableList.<String>builder().addAll(cflags).addAll(ldFlags).build();
    ImmutableList<Arg> cflagsArgs = ImmutableList.copyOf(StringArg.from(cflags));

    DownwardApiConfig downwardApiConfig = buckConfig.getView(DownwardApiConfig.class);
    boolean stripSwift = appleConfig.getStripSwiftSymbolsEnabled();

    CxxPlatform cxxPlatform =
        CxxPlatforms.build(
            targetFlavor,
            Platform.MACOS,
            cxxBuckConfig,
            downwardApiConfig,
            as,
            aspp,
            cc,
            cxx,
            cpp,
            cxxpp,
            new DefaultLinkerProvider(
                LinkerProvider.Type.DARWIN,
                new ConstantToolProvider(clangXxPath),
                cxxBuckConfig.shouldCacheLinks(),
                cxxBuckConfig.shouldUploadToCache(),
                cxxBuckConfig.getFocusedDebuggingEnabled(),
                cxxBuckConfig.getLinkPathNormalizationArgsEnabled()),
            StringArg.from(combinedLdFlags),
            ImmutableMultimap.of(),
            new ConstantToolProvider(strip),
            ArchiverProvider.from(new BsdArchiver(ar)),
            ArchiveContents.NORMAL,
            Optional.of(new ConstantToolProvider(ranlib)),
            new PosixNmSymbolNameTool(
                new ConstantToolProvider(nm), downwardApiConfig.isEnabledForApple()),
            cflagsArgs,
            ImmutableList.of(),
            cflagsArgs,
            ImmutableList.of(),
            cflagsArgs,
            ImmutableList.of(),
            "dylib",
            "%s.dylib",
            "a",
            "o",
            Optional.empty(),
            compilerDebugPathSanitizer,
            macros,
            Optional.empty(),
            headerVerification,
            cxxBuckConfig.getPublicHeadersSymlinksEnabled(),
            cxxBuckConfig.getPrivateHeadersSymlinksEnabled(),
            PicType.PIC,
            Optional.empty(),
            Optional.of(AppleStripFlags.getStripArgs(StripStyle.DEBUGGING_SYMBOLS, stripSwift)),
            Optional.of(AppleStripFlags.getStripArgs(StripStyle.NON_GLOBAL_SYMBOLS, stripSwift)),
            Optional.of(AppleStripFlags.getStripArgs(StripStyle.ALL_SYMBOLS, stripSwift)),
            Optional.empty(),
            Optional.empty());

    ImmutableList.Builder<Path> swiftOverrideSearchPathBuilder = ImmutableList.builder();
    AppleSdkPaths.Builder swiftSdkPathsBuilder = AppleSdkPaths.builder().from(sdkPaths);
    Optional<SwiftPlatform> swiftPlatform =
        getSwiftPlatform(
            AppleLibraryDescriptionSwiftEnhancer.createSwiftTargetTriple(
                targetArchitecture, targetSdk, minVersion),
            version,
            targetSdk,
            swiftSdkPathsBuilder.build(),
            appleConfig.shouldLinkSystemSwift(),
            shouldEmbedBitcode,
            swiftBuckConfig.getPrefixSerializedDebuggingOptions(),
            swiftOverrideSearchPathBuilder.addAll(toolSearchPaths).build(),
            xcodeToolFinder,
            filesystem);

    platformBuilder
        .setCxxPlatform(cxxPlatform)
        .setSwiftPlatform(swiftPlatform)
        .setAppleSdk(targetSdk)
        .setAppleSdkPaths(sdkPaths)
        .setMinVersion(minVersion)
        .setBuildVersion(buildVersion)
        .setActool(actool)
        .setLibtool(libtool)
        .setIbtool(ibtool)
        .setMomc(momc)
        .setCopySceneKitAssets(
            getOptionalTool(
                "copySceneKitAssets", toolSearchPaths, xcodeToolFinder, version, filesystem))
        .setXctest(xctest)
        .setDsymutil(dsymutil)
        .setDwarfdump(dwarfdump)
        .setLipo(lipo)
        .setWatchKitStubBinary(watchKitStubBinaryPath)
        .setCodesignAllocate(
            getOptionalTool(
                "codesign_allocate", toolSearchPaths, xcodeToolFinder, version, filesystem))
        .setCodesignProvider(appleConfig.getCodesignProvider());

    return platformBuilder.build();
  }

  private static boolean shouldEmbedBitcode(Optional<String> xcodeVersion) {
    if (xcodeVersion.isEmpty()) return true;

    try {
      // Bitcode is no longer required from Xcode 14
      return Integer.parseInt(xcodeVersion.get()) < 1400;
    } catch (NumberFormatException e) {
      return true;
    }
  }

  private static AppleCompilerTargetTriple getAppleTargetTripleForSdk(
      AppleSdk targetSdk, String targetArchitecture) {
    Optional<String> sdkVendor = targetSdk.getTargetTripleVendor();
    Optional<String> sdkPlatform = targetSdk.getTargetTriplePlatformName();
    if (sdkVendor.isPresent() && sdkPlatform.isPresent()) {
      return AppleCompilerTargetTriple.of(
          targetArchitecture,
          sdkVendor.get(),
          sdkPlatform.get(),
          Optional.empty(),
          targetSdk.getTargetTripleEnvironment());
    }

    return AppleCompilerTargetTriple.of(
        targetArchitecture,
        "apple",
        targetSdk.getApplePlatform().getPlatformName(),
        Optional.empty(),
        Optional.empty());
  }

  private static ImmutableList<String> getLdFlags(
      AppleSdk targetSdk,
      ProjectFilesystem filesystem,
      XcodeToolFinder xcodeToolFinder,
      ImmutableList<Path> toolSearchPaths,
      AppleConfig appleConfig,
      String version) {
    ImmutableList.Builder<String> builder = ImmutableList.builder();

    boolean setSDKVersion;
    switch (appleConfig.shouldAddLinkerSDKVersion()) {
      case ALWAYS:
        setSDKVersion = true;
        break;
      case NEVER:
        setSDKVersion = false;
        break;
      case AUTO:
      default:
        setSDKVersion = shouldSetSDKVersion(filesystem, xcodeToolFinder, toolSearchPaths, version);
    }

    if (setSDKVersion) {
      builder.addAll(Linkers.iXlinker("-sdk_version", targetSdk.getVersion()));
    }

    if (appleConfig.linkAllObjC()) {
      builder.addAll(Linkers.iXlinker("-ObjC"));
    }

    for (String libSearchPath : targetSdk.getAdditionalLibrarySearchPaths()) {
      builder.add("-L" + libSearchPath);
    }

    return builder.build();
  }

  private static Optional<SwiftPlatform> getSwiftPlatform(
      AppleCompilerTargetTriple swiftTarget,
      String version,
      AppleSdk sdk,
      AppleSdkPaths sdkPaths,
      boolean shouldLinkSystemSwift,
      boolean shouldEmbedBitcode,
      boolean prefixSerializedDebuggingOptions,
      ImmutableList<Path> toolSearchPaths,
      XcodeToolFinder xcodeToolFinder,
      ProjectFilesystem filesystem) {
    String platformName = sdk.getApplePlatform().getName();

    // catalyst uses the macosx swift stdlib
    if (platformName.equals("maccatalyst")) {
      platformName = "macosx";
    }

    ImmutableList.Builder<String> swiftStdlibToolParamsBuilder = ImmutableList.builder();
    swiftStdlibToolParamsBuilder
        .add("--copy")
        .add("--verbose")
        .add("--strip-bitcode")
        .add("--platform")
        .add(platformName);
    for (Path toolchainPath : sdkPaths.getToolchainPaths()) {
      swiftStdlibToolParamsBuilder.add("--toolchain").add(toolchainPath.toString());
      applySourceLibrariesParamIfNeeded(swiftStdlibToolParamsBuilder, toolchainPath, platformName);
    }

    Optional<VersionedTool> swiftc =
        getOptionalToolWithParams(
            "swiftc", toolSearchPaths, xcodeToolFinder, version, ImmutableList.of(), filesystem);
    Optional<VersionedTool> swiftStdLibTool =
        getOptionalToolWithParams(
            "swift-stdlib-tool",
            toolSearchPaths,
            xcodeToolFinder,
            version,
            swiftStdlibToolParamsBuilder.build(),
            filesystem);

    return swiftc.map(
        tool ->
            SwiftPlatformFactory.build(
                sdk,
                sdkPaths,
                tool,
                swiftStdLibTool,
                shouldLinkSystemSwift,
                shouldEmbedBitcode,
                prefixSerializedDebuggingOptions,
                swiftTarget));
  }

  private static void applySourceLibrariesParamIfNeeded(
      ImmutableList.Builder<String> swiftStdlibToolParamsBuilder,
      Path toolchainPath,
      String platformName) {
    for (Path runtimePath :
        SwiftPlatformFactory.findSwiftRuntimePaths(toolchainPath, platformName)) {
      swiftStdlibToolParamsBuilder.add("--source-libraries").add(runtimePath.toString());
    }
  }

  private static Optional<VersionedTool> getOptionalTool(
      String tool,
      ImmutableList<Path> toolSearchPaths,
      XcodeToolFinder xcodeToolFinder,
      String version,
      ProjectFilesystem filesystem) {
    return getOptionalToolWithParams(
        tool, toolSearchPaths, xcodeToolFinder, version, ImmutableList.of(), filesystem);
  }

  private static Optional<VersionedTool> getOptionalToolWithParams(
      String tool,
      ImmutableList<Path> toolSearchPaths,
      XcodeToolFinder xcodeToolFinder,
      String version,
      ImmutableList<String> params,
      ProjectFilesystem filesystem) {
    return xcodeToolFinder
        .getToolPath(toolSearchPaths, tool)
        .map(
            input -> VersionedTool.of(tool, PathSourcePath.of(filesystem, input), version, params));
  }

  private static Optional<Path> getOptionalToolPath(
      String tool, ImmutableList<Path> toolSearchPaths, XcodeToolFinder xcodeToolFinder) {
    return xcodeToolFinder.getToolPath(toolSearchPaths, tool);
  }

  private static Path getToolPath(
      String tool, ImmutableList<Path> toolSearchPaths, XcodeToolFinder xcodeToolFinder) {
    Optional<Path> result = getOptionalToolPath(tool, toolSearchPaths, xcodeToolFinder);
    if (!result.isPresent()) {
      throw new HumanReadableException("Cannot find tool %s in paths %s", tool, toolSearchPaths);
    }
    return result.get();
  }

  private static boolean shouldSetSDKVersion(
      ProjectFilesystem filesystem,
      XcodeToolFinder xcodeToolFinder,
      ImmutableList<Path> toolSearchPaths,
      String version) {
    // If the Clang driver detects ld version at 520 or above, it will pass -platform_version,
    // otherwise it will pass -<platform>_version_min. As -platform_version is incompatible
    // with -sdk_version (which Buck passes), we should only be passing -sdk_version if we
    // believe the driver will not pass it.
    // https://reviews.llvm.org/rG25ce33a6e4f3b13732c0f851e68390dc2acb9123
    // However, Xcode 11.3.1's toolchain (with linker version ld64-530) does not handle not having
    // the sdk_version properly (determining the target SDK improperly, etc) so we do this for
    // 11.4.1 where it is warning that is treated as an error when fatal_warnings is enabled.
    Optional<Double> ldVersion =
        getLdVersion(filesystem, xcodeToolFinder, toolSearchPaths, version);
    if (ldVersion.isPresent()) {
      return ldVersion.get() < 556.6;
    }
    return true;
  }

  private static Optional<Double> getLdVersion(
      ProjectFilesystem filesystem,
      XcodeToolFinder xcodeToolFinder,
      ImmutableList<Path> toolSearchPaths,
      String version) {

    Optional<VersionedTool> ld =
        getOptionalTool("ld", toolSearchPaths, xcodeToolFinder, version, filesystem);

    // If no ld we found, we can't get it's version
    if (!ld.isPresent()) {
      return Optional.empty();
    }

    ProcessExecutor executor = new DefaultProcessExecutor(Console.createNullConsole());
    ProcessExecutorParams processExecutorParams =
        ProcessExecutorParams.builder()
            .setCommand(ImmutableList.of(ld.get().getPath().toString(), "-v"))
            .build();
    Set<ProcessExecutor.Option> options = EnumSet.of(ProcessExecutor.Option.EXPECTING_STD_OUT);
    ProcessExecutor.Result result;
    try {
      result =
          executor.launchAndExecute(
              processExecutorParams,
              options,
              /* stdin */ Optional.empty(),
              /* timeOutMs */ Optional.empty(),
              /* timeOutHandler */ Optional.empty());
    } catch (InterruptedException | IOException e) {
      LOG.debug("Could not execute ld -v, continuing with setting the sdk_version.");
      return Optional.empty();
    }

    if (result.getExitCode() != 0) {
      throw new RuntimeException(
          result.getMessageForUnexpectedResult(ld.get().getPath().toString() + " -v"));
    }

    Double parsedVersion = 0.0;
    try {
      // We expect the version string to be of the form "@(#)PROGRAM:ld  PROJECT:ld64-556.6"
      String versionStr = result.getStderr().get().split("\n")[0];
      parsedVersion = Double.parseDouble(versionStr.split("ld64-")[1]);
    } catch (Exception e) {
      LOG.debug(
          "Unable to parse the ld version from %s, continuing with setting the sdk_version.",
          result.getStderr().get());
    }

    return Optional.of(parsedVersion);
  }

  @VisibleForTesting
  public static class XcodeBuildVersionCache {
    private final Map<Path, Optional<String>> cache = new HashMap<>();

    /**
     * Returns the Xcode build version. This is an alphanumeric string as output by {@code
     * xcodebuild -version} and shown in the About Xcode window. This value is embedded into the
     * plist of app bundles built by Xcode, under the field named {@code DTXcodeBuild}
     *
     * @param developerDir Path to developer dir, i.e. /Applications/Xcode.app/Contents/Developer
     * @return the xcode build version if found, nothing if it fails to be found, or the version
     *     plist file cannot be read.
     */
    protected Optional<String> lookup(Path developerDir) {
      return cache.computeIfAbsent(
          developerDir,
          ignored -> {
            Path versionPlist = developerDir.getParent().resolve("version.plist");
            NSString result;
            try {
              NSDictionary dict =
                  (NSDictionary) PropertyListParser.parse(Files.readAllBytes(versionPlist));
              result = (NSString) dict.get("ProductBuildVersion");
            } catch (IOException
                | ClassCastException
                | SAXException
                | PropertyListFormatException
                | ParseException e) {
              LOG.warn(
                  e,
                  "%s: Cannot find xcode build version, file is in an invalid format.",
                  versionPlist);
              return Optional.empty();
            } catch (ParserConfigurationException e) {
              throw new IllegalStateException("plist parser threw unexpected exception", e);
            }
            if (result != null) {
              return Optional.of(result.toString());
            } else {
              LOG.warn(
                  "%s: Cannot find xcode build version, file is in an invalid format.",
                  versionPlist);
              return Optional.empty();
            }
          });
    }
  }
}
