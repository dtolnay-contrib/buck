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

package com.facebook.buck.android;

import static com.facebook.buck.android.BinaryType.APK;

import com.android.common.SdkConstants;
import com.facebook.buck.android.apk.KeystoreProperties;
import com.facebook.buck.android.apkmodule.APKModule;
import com.facebook.buck.android.exopackage.ExopackageMode;
import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.io.filesystem.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.unarchive.UnzipStep;
import com.facebook.buck.util.MoreSuppliers;
import com.facebook.buck.util.stream.RichStream;
import com.facebook.buck.zip.ZipScrubberStep;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.io.Files;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

abstract class AndroidBinaryBuildable implements AddsToRuleKey {

  /**
   * This is the path from the root of the APK that should contain the metadata.txt and
   * secondary-N.dex.jar files for secondary dexes.
   */
  static final String SMART_DEX_SECONDARY_DEX_SUBDIR =
      "assets/smart-dex-secondary-program-dex-jars";

  @AddToRuleKey private final EnumSet<ExopackageMode> exopackageModes;

  @AddToRuleKey private final SourcePath androidManifestPath;

  @AddToRuleKey protected final DexFilesInfo dexFilesInfo;
  @AddToRuleKey private final NativeFilesInfo nativeFilesInfo;
  @AddToRuleKey protected final ResourceFilesInfo resourceFilesInfo;

  @AddToRuleKey final boolean packageAssetLibraries;
  @AddToRuleKey final boolean compressAssetLibraries;
  @AddToRuleKey final Optional<CompressionAlgorithm> assetCompressionAlgorithm;

  @AddToRuleKey private final int xzCompressionLevel;

  @AddToRuleKey protected final SourcePath keystorePath;
  @AddToRuleKey private final SourcePath keystorePropertiesPath;

  @AddToRuleKey protected final ImmutableSortedSet<APKModule> apkModules;

  @AddToRuleKey private final ImmutableMap<APKModule, SourcePath> moduleResourceApkPaths;

  protected final BinaryType binaryType;

  // These should be the only things not added to the rulekey.
  private final ProjectFilesystem filesystem;
  private final BuildTarget buildTarget;

  AndroidBinaryBuildable(
      BuildTarget buildTarget,
      ProjectFilesystem filesystem,
      SourcePath keystorePath,
      SourcePath keystorePropertiesPath,
      EnumSet<ExopackageMode> exopackageModes,
      int xzCompressionLevel,
      boolean packageAssetLibraries,
      boolean compressAssetLibraries,
      Optional<CompressionAlgorithm> assetCompressionAlgorithm,
      SourcePath androidManifestPath,
      DexFilesInfo dexFilesInfo,
      NativeFilesInfo nativeFilesInfo,
      ResourceFilesInfo resourceFilesInfo,
      ImmutableSortedSet<APKModule> apkModules,
      ImmutableMap<APKModule, SourcePath> moduleResourceApkPaths,
      BinaryType binaryType) {
    this.filesystem = filesystem;
    this.buildTarget = buildTarget;
    this.keystorePath = keystorePath;
    this.keystorePropertiesPath = keystorePropertiesPath;
    this.exopackageModes = exopackageModes;
    this.xzCompressionLevel = xzCompressionLevel;
    this.androidManifestPath = androidManifestPath;
    this.apkModules = apkModules;
    this.moduleResourceApkPaths = moduleResourceApkPaths;
    this.dexFilesInfo = dexFilesInfo;
    this.nativeFilesInfo = nativeFilesInfo;
    this.packageAssetLibraries = packageAssetLibraries;
    this.compressAssetLibraries = compressAssetLibraries;
    this.assetCompressionAlgorithm = assetCompressionAlgorithm;
    this.resourceFilesInfo = resourceFilesInfo;
    this.binaryType = binaryType;
  }

  @SuppressWarnings("PMD.PrematureDeclaration")
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    SourcePathResolverAdapter pathResolver = context.getSourcePathResolver();
    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    // The `HasInstallableApk` interface needs access to the manifest, so make sure we create our
    // own copy of this so that we don't have a runtime dep on the `AaptPackageResources` step.
    RelPath manifestPath =
        AndroidBinaryPathUtility.getManifestPath(getProjectFilesystem(), getBuildTarget());
    steps.add(
        MkdirStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), manifestPath.getParent())));
    steps.add(
        CopyStep.forFile(
            pathResolver.getRelativePath(getProjectFilesystem(), androidManifestPath),
            manifestPath));
    buildableContext.recordArtifact(manifestPath.getPath());

    dexFilesInfo.proguardTextFilesPath.ifPresent(
        path -> {
          steps.add(createCopyProguardFilesStep(pathResolver, path));
        });

    ImmutableSet.Builder<Path> nativeLibraryDirectoriesBuilder = ImmutableSet.builder();
    // Copy the transitive closure of native-libs-as-assets to a single directory, if any.
    ImmutableSet.Builder<Path> nativeLibraryAsAssetDirectories = ImmutableSet.builder();

    ImmutableSet.Builder<Path> moduleResourcesDirectories = ImmutableSet.builder();

    ImmutableSet.Builder<ModuleInfo> modulesInfo = ImmutableSet.builder();

    ImmutableMap<String, SourcePath> mapOfModuleToSecondaryDexSourcePaths =
        dexFilesInfo.getMapOfModuleToSecondaryDexSourcePaths();

    ImmutableModuleInfo.Builder baseModuleInfo = ImmutableModuleInfo.builder();
    baseModuleInfo.setModuleName("base");

    for (APKModule module : apkModules) {
      processModule(
          module,
          nativeLibraryDirectoriesBuilder,
          nativeLibraryAsAssetDirectories,
          moduleResourcesDirectories,
          steps,
          pathResolver,
          context,
          mapOfModuleToSecondaryDexSourcePaths,
          baseModuleInfo,
          modulesInfo);
    }

    // If non-english strings are to be stored as assets, pass them to ApkBuilder.
    ImmutableSet.Builder<Path> zipFiles = ImmutableSet.builder();
    RichStream.from(resourceFilesInfo.primaryApkAssetsZips)
        .map(sourcePath -> pathResolver.getCellUnsafeRelPath(sourcePath).getPath())
        .forEach(zipFiles::add);

    if (ExopackageMode.enabledForNativeLibraries(exopackageModes)
        && !ExopackageMode.enabledForArch64(exopackageModes)) {
      // We need to include a few dummy native libraries with our application so that Android knows
      // to run it as 32-bit.  Android defaults to 64-bit when no libraries are provided at all,
      // causing us to fail to load our 32-bit exopackage native libraries later.
      String fakeNativeLibraryBundle = System.getProperty("buck.native_exopackage_fake_path");

      if (fakeNativeLibraryBundle == null) {
        throw new RuntimeException("fake native bundle not specified in properties");
      }

      zipFiles.add(Paths.get(fakeNativeLibraryBundle));
    }

    ImmutableSet<Path> allAssetDirectories =
        ImmutableSet.<Path>builder()
            .addAll(moduleResourcesDirectories.build())
            .addAll(nativeLibraryAsAssetDirectories.build())
            .addAll(dexFilesInfo.getSecondaryDexDirs(getProjectFilesystem(), pathResolver))
            .build();

    SourcePathResolverAdapter resolver = context.getSourcePathResolver();
    Path signedApkPath =
        AndroidBinaryPathUtility.getSignedApkPath(filesystem, buildTarget, binaryType);

    AbsPath pathToKeystore = resolver.getAbsolutePath(keystorePath);
    Supplier<KeystoreProperties> keystoreProperties =
        getKeystorePropertiesSupplier(resolver, pathToKeystore);

    ImmutableSet<Path> thirdPartyJars =
        resourceFilesInfo.pathsToThirdPartyJars.stream()
            .map(sourcePath -> resolver.getAbsolutePath(sourcePath).getPath())
            .collect(ImmutableSet.toImmutableSet());
    getBinaryTypeSpecificBuildSteps(
        steps,
        baseModuleInfo,
        keystoreProperties,
        nativeLibraryDirectoriesBuilder,
        allAssetDirectories,
        pathResolver,
        thirdPartyJars,
        zipFiles,
        modulesInfo);

    // The `ApkBuilderStep` delegates to android tools to build a ZIP with timestamps in it, making
    // the output non-deterministic.  So use an additional scrubbing step to zero these out.
    steps.add(ZipScrubberStep.of(getProjectFilesystem().resolve(signedApkPath)));

    return steps.build();
  }

  private void processModule(
      APKModule module,
      ImmutableSet.Builder<Path> nativeLibraryDirectoriesBuilder,
      ImmutableSet.Builder<Path> nativeLibraryAsAssetDirectories,
      ImmutableSet.Builder<Path> moduleResourcesDirectories,
      ImmutableList.Builder<Step> steps,
      SourcePathResolverAdapter pathResolver,
      BuildContext context,
      ImmutableMap<String, SourcePath> mapOfModuleToSecondaryDexSourcePaths,
      ImmutableModuleInfo.Builder baseModuleInfo,
      ImmutableSet.Builder<ModuleInfo> modulesInfo) {
    boolean addThisModule = false;
    Map<Path, String> assetDirectoriesForThisModule = new HashMap<>();
    Set<Path> nativeLibraryDirectoriesForThisModule = new HashSet<>();
    Path resourcesDirectoryForThisModule = null;

    if (mapOfModuleToSecondaryDexSourcePaths.containsKey(module.getName())) {
      addDexFileDirectories(
          pathResolver,
          module,
          mapOfModuleToSecondaryDexSourcePaths,
          assetDirectoriesForThisModule);
    }

    boolean shouldPackageAssetLibraries = packageAssetLibraries || !module.isRootModule();
    if (!ExopackageMode.enabledForNativeLibraries(exopackageModes)
        && nativeFilesInfo.nativeLibsDirs.isPresent()
        && nativeFilesInfo.nativeLibsDirs.get().containsKey(module)) {
      addThisModule = true;
      addNativeDirectory(
          shouldPackageAssetLibraries,
          module,
          pathResolver,
          nativeLibraryDirectoriesBuilder,
          nativeLibraryDirectoriesForThisModule);
    }

    // Package prebuilt libs which need to be loaded by `System.loadLibrary` in the standard dir,
    // even in exopackage builds.
    if (nativeFilesInfo.nativeLibsDirForSystemLoader.isPresent() && module.isRootModule()) {
      addThisModule = true;
      RelPath relativePath =
          pathResolver.getCellUnsafeRelPath(nativeFilesInfo.nativeLibsDirForSystemLoader.get());
      nativeLibraryDirectoriesBuilder.add(relativePath.getPath());
      nativeLibraryDirectoriesForThisModule.add(relativePath.getPath());
    }

    if (shouldPackageAssetLibraries) {
      addThisModule = true;
      addNativeLibraryAsAssetDirectory(
          module, context, nativeLibraryAsAssetDirectories, assetDirectoriesForThisModule, steps);
    }

    if (moduleResourceApkPaths.get(module) != null) {
      addThisModule = true;
      resourcesDirectoryForThisModule =
          addModuleResourceDirectory(module, context, moduleResourcesDirectories, steps);
    }

    if (!addThisModule || binaryType == APK) {
      return;
    }

    if (module.isRootModule()) {
      baseModuleInfo
          .putAllAssetDirectories(ImmutableMap.copyOf(assetDirectoriesForThisModule))
          .addAllNativeLibraryDirectories(
              ImmutableSet.copyOf(nativeLibraryDirectoriesForThisModule));
    } else {
      String moduleName = module.getName();
      modulesInfo.add(
          ImmutableModuleInfo.ofImpl(
              moduleName,
              resourcesDirectoryForThisModule,
              ImmutableSet.<Path>of(),
              ImmutableMap.copyOf(assetDirectoriesForThisModule),
              ImmutableSet.copyOf(nativeLibraryDirectoriesForThisModule),
              ImmutableSet.<Path>of(),
              ImmutableSet.<Path>of()));
    }
  }

  private void addDexFileDirectories(
      SourcePathResolverAdapter pathResolver,
      APKModule module,
      ImmutableMap<String, SourcePath> mapOfModuleToSecondaryDexSourcePaths,
      Map<Path, String> assetDirectoriesForThisModule) {
    File[] dexFiles =
        filesystem
            .getPathForRelativePath(
                pathResolver.getCellUnsafeRelPath(
                    mapOfModuleToSecondaryDexSourcePaths.get(module.getName())))
            .toFile()
            .listFiles();
    if (dexFiles == null) {
      return;
    }

    RelPath current =
        pathResolver.getCellUnsafeRelPath(
            mapOfModuleToSecondaryDexSourcePaths.get(module.getName()));
    String prefix = current.getParent().getParent().relativize(current).toString();
    assetDirectoriesForThisModule.put(current.getPath(), prefix);
  }

  private void addNativeDirectory(
      boolean shouldPackageAssetLibraries,
      APKModule module,
      SourcePathResolverAdapter pathResolver,
      ImmutableSet.Builder<Path> nativeLibraryDirectoriesBuilder,
      Set<Path> nativeLibraryDirectoriesForThisModule) {
    nativeLibraryDirectoriesBuilder.add(
        pathResolver
            .getCellUnsafeRelPath(nativeFilesInfo.nativeLibsDirs.get().get(module))
            .getPath());
    nativeLibraryDirectoriesForThisModule.add(
        pathResolver
            .getCellUnsafeRelPath(nativeFilesInfo.nativeLibsDirs.get().get(module))
            .getPath());
    if (shouldPackageAssetLibraries) {
      return;
    }
    nativeLibraryDirectoriesBuilder.add(
        pathResolver
            .getCellUnsafeRelPath(nativeFilesInfo.nativeLibsAssetsDirs.get().get(module))
            .getPath());
    nativeLibraryDirectoriesForThisModule.add(
        pathResolver
            .getCellUnsafeRelPath(nativeFilesInfo.nativeLibsAssetsDirs.get().get(module))
            .getPath());
  }

  private void addNativeLibraryAsAssetDirectory(
      APKModule module,
      BuildContext context,
      ImmutableSet.Builder<Path> nativeLibraryAsAssetDirectories,
      Map<Path, String> assetDirectoriesForThisModule,
      ImmutableList.Builder<Step> steps) {
    Preconditions.checkState(
        ExopackageMode.enabledForModules(exopackageModes)
            || !ExopackageMode.enabledForResources(exopackageModes));
    // This is a module that is being packed into an AAB - we only want to put the native libraries
    // in the specific module directory not in the generic assets directory
    boolean isModuleInBundle = !(module.isRootModule() || binaryType == APK);
    RelPath pathForNativeLibsAsAssets =
        AndroidBinaryPathUtility.getPathForNativeLibsAsAssets(
            getProjectFilesystem(), getBuildTarget());
    if (isModuleInBundle) {
      pathForNativeLibsAsAssets = pathForNativeLibsAsAssets.resolveRel(module.getName());
    }

    RelPath libSubdirectory =
        pathForNativeLibsAsAssets
            .resolveRel("assets")
            .resolveRel(module.isRootModule() ? "lib" : module.getName());

    getStepsForNativeAssets(
        context,
        steps,
        libSubdirectory,
        module.isRootModule() ? "metadata.txt" : "libs.txt",
        module);

    if (!isModuleInBundle) {
      nativeLibraryAsAssetDirectories.add(pathForNativeLibsAsAssets.getPath());
    }

    assetDirectoriesForThisModule.put(pathForNativeLibsAsAssets.getPath(), "");
  }

  private Path addModuleResourceDirectory(
      APKModule module,
      BuildContext context,
      ImmutableSet.Builder<Path> moduleResourcesDirectories,
      ImmutableList.Builder<Step> steps) {
    SourcePath resourcePath = moduleResourceApkPaths.get(module);

    RelPath moduleResDirectory =
        BuildTargetPaths.getScratchPath(
            getProjectFilesystem(), buildTarget, "__module_res_" + module.getName() + "_%s__");

    Path unpackDirectory = moduleResDirectory.resolve("assets").resolve(module.getName());

    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), unpackDirectory)));
    steps.add(
        new UnzipStep(
            getProjectFilesystem(),
            context.getSourcePathResolver().getAbsolutePath(resourcePath).getPath(),
            unpackDirectory,
            Optional.empty()));

    moduleResourcesDirectories.add(moduleResDirectory.getPath());
    return unpackDirectory;
  }

  private void getStepsForNativeAssets(
      BuildContext context,
      ImmutableList.Builder<Step> steps,
      RelPath libSubdirectory,
      String metadataFilename,
      APKModule module) {

    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), libSubdirectory)));

    // Input asset libraries are sorted in descending filesize order.
    ImmutableSortedSet.Builder<Path> inputAssetLibrariesBuilder =
        ImmutableSortedSet.orderedBy(
            (libPath1, libPath2) -> {
              try {
                ProjectFilesystem filesystem = getProjectFilesystem();
                int filesizeResult =
                    -Long.compare(
                        filesystem.getFileSize(libPath1), filesystem.getFileSize(libPath2));
                int pathnameResult = libPath1.compareTo(libPath2);
                return filesizeResult != 0 ? filesizeResult : pathnameResult;
              } catch (IOException e) {
                return 0;
              }
            });

    if (packageAssetLibraries || !module.isRootModule()) {
      // TODO(cjhopman): This block should probably all be handled by CopyNativeLibraries.
      // TODO(cjhopman): Why is this packaging native libs as assets even when native exopackage is
      // enabled?
      if (nativeFilesInfo.nativeLibsAssetsDirs.isPresent()
          && nativeFilesInfo.nativeLibsAssetsDirs.get().containsKey(module)) {
        // Copy in cxx libraries marked as assets. Filtering and renaming was already done
        // in CopyNativeLibraries.getBuildSteps().
        RelPath cxxNativeLibsSrc =
            context
                .getSourcePathResolver()
                .getCellUnsafeRelPath(nativeFilesInfo.nativeLibsAssetsDirs.get().get(module));
        steps.add(
            CopyStep.forDirectory(
                cxxNativeLibsSrc, libSubdirectory, CopyStep.DirectoryMode.CONTENTS_ONLY));
      }

      // Step that populates a list of libraries and writes a metadata.txt to decompress.
      steps.add(
          createAssetLibrariesMetadataStep(
              libSubdirectory, metadataFilename, module, inputAssetLibrariesBuilder));
    }

    if (compressAssetLibraries || !module.isRootModule()) {
      ImmutableList.Builder<Path> outputAssetLibrariesBuilder = ImmutableList.builder();
      steps.add(
          createRenameAssetLibrariesStep(
              module, inputAssetLibrariesBuilder, outputAssetLibrariesBuilder));
      // Concat and xz compress.
      Path libOutputBlob = libSubdirectory.resolve("libraries.blob");
      steps.add(new ConcatStep(getProjectFilesystem(), outputAssetLibrariesBuilder, libOutputBlob));
      steps.add(
          CompressionAlgorithmCreator.createCompressionStep(
              assetCompressionAlgorithm.orElse(CompressionAlgorithm.XZ),
              getProjectFilesystem(),
              libOutputBlob,
              libSubdirectory.getPath(),
              xzCompressionLevel));
    }
  }

  private AbstractExecutionStep createRenameAssetLibrariesStep(
      APKModule module,
      ImmutableSortedSet.Builder<Path> inputAssetLibrariesBuilder,
      ImmutableList.Builder<Path> outputAssetLibrariesBuilder) {
    return new AbstractExecutionStep("rename_asset_libraries_as_temp_files_" + module.getName()) {
      @Override
      public StepExecutionResult execute(StepExecutionContext context) throws IOException {
        ProjectFilesystem filesystem = getProjectFilesystem();
        for (Path libPath : inputAssetLibrariesBuilder.build()) {
          Path tempPath = libPath.resolveSibling(libPath.getFileName() + "~");
          filesystem.move(libPath, tempPath);
          outputAssetLibrariesBuilder.add(tempPath);
        }
        return StepExecutionResults.SUCCESS;
      }
    };
  }

  private AbstractExecutionStep createAssetLibrariesMetadataStep(
      RelPath libSubdirectory,
      String metadataFilename,
      APKModule module,
      ImmutableSortedSet.Builder<Path> inputAssetLibrariesBuilder) {
    return new AbstractExecutionStep("write_metadata_for_asset_libraries_" + module.getName()) {
      @Override
      public StepExecutionResult execute(StepExecutionContext context) throws IOException {
        ProjectFilesystem filesystem = getProjectFilesystem();
        // Walk file tree to find libraries
        filesystem.walkRelativeFileTree(
            libSubdirectory.getPath(),
            new SimpleFileVisitor<Path>() {
              @Override
              public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                  throws IOException {
                if (!file.getFileName().toString().equals(SdkConstants.FN_WRAP_SH)
                    && !file.toString().endsWith(".so")) {
                  throw new IOException("unexpected file in lib directory");
                }
                inputAssetLibrariesBuilder.add(file);
                return FileVisitResult.CONTINUE;
              }
            });

        // Write a metadata
        ImmutableList.Builder<String> metadataLines = ImmutableList.builder();
        Path metadataOutput = libSubdirectory.resolve(metadataFilename);
        for (Path libPath : inputAssetLibrariesBuilder.build()) {
          // Should return something like x86/libfoo.so
          RelPath relativeLibPath = libSubdirectory.relativize(libPath);
          long filesize = filesystem.getFileSize(libPath);
          String desiredOutput = relativeLibPath.toString();
          String checksum = filesystem.computeSha256(libPath);
          metadataLines.add(desiredOutput + ' ' + filesize + ' ' + checksum);
        }
        ImmutableList<String> metadata = metadataLines.build();
        if (!metadata.isEmpty()) {
          filesystem.writeLinesToPath(metadata, metadataOutput);
        }
        return StepExecutionResults.SUCCESS;
      }
    };
  }

  private Supplier<KeystoreProperties> getKeystorePropertiesSupplier(
      SourcePathResolverAdapter resolver, AbsPath pathToKeystore) {
    return MoreSuppliers.memoize(
        () -> {
          try {
            return KeystoreProperties.createFromPropertiesFile(
                pathToKeystore.getPath(),
                getProjectFilesystem()
                    .getPathForRelativePath(
                        resolver.getAbsolutePath(keystorePropertiesPath).getPath()));
          } catch (IOException e) {
            throw new RuntimeException();
          }
        });
  }

  private CopyStep createCopyProguardFilesStep(
      SourcePathResolverAdapter pathResolver, SourcePath proguardTextFilesPath) {
    return CopyStep.forDirectory(
        pathResolver.getCellUnsafeRelPath(proguardTextFilesPath),
        AndroidBinaryPathUtility.getProguardTextFilesPath(getProjectFilesystem(), getBuildTarget()),
        CopyStep.DirectoryMode.CONTENTS_ONLY);
  }

  public ProjectFilesystem getProjectFilesystem() {
    return filesystem;
  }

  public BuildTarget getBuildTarget() {
    return buildTarget;
  }

  @VisibleForTesting
  static Path getProguardOutputFromInputClasspath(Path proguardConfigDir, Path classpathEntry) {
    // Hehe, this is so ridiculously fragile.
    Preconditions.checkArgument(
        !classpathEntry.isAbsolute(),
        "Classpath entries should be relative rather than absolute paths: %s",
        classpathEntry);
    String obfuscatedName =
        Files.getNameWithoutExtension(classpathEntry.toString()) + "-obfuscated.jar";
    Path dirName = classpathEntry.getParent();
    return proguardConfigDir.resolve(dirName).resolve(obfuscatedName);
  }

  abstract void getBinaryTypeSpecificBuildSteps(
      ImmutableList.Builder<Step> steps,
      ImmutableModuleInfo.Builder baseModuleInfo,
      Supplier<KeystoreProperties> keystoreProperties,
      ImmutableSet.Builder<Path> nativeLibraryDirectoriesBuilder,
      ImmutableSet<Path> allAssetDirectories,
      SourcePathResolverAdapter pathResolver,
      ImmutableSet<Path> thirdPartyJars,
      ImmutableSet.Builder<Path> zipFiles,
      ImmutableSet.Builder<ModuleInfo> modulesInfo);
}
