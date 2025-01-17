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

import static com.facebook.buck.features.project.intellij.IjProjectPaths.getUrl;

import com.facebook.buck.features.project.intellij.aggregation.AggregationMode;
import com.facebook.buck.features.project.intellij.lang.android.AndroidResourceFolder;
import com.facebook.buck.features.project.intellij.model.ContentRoot;
import com.facebook.buck.features.project.intellij.model.DependencyType;
import com.facebook.buck.features.project.intellij.model.IjLibrary;
import com.facebook.buck.features.project.intellij.model.IjModule;
import com.facebook.buck.features.project.intellij.model.IjModuleAndroidFacet;
import com.facebook.buck.features.project.intellij.model.IjModuleType;
import com.facebook.buck.features.project.intellij.model.IjProjectConfig;
import com.facebook.buck.features.project.intellij.model.IjProjectElement;
import com.facebook.buck.features.project.intellij.model.ModuleIndexEntry;
import com.facebook.buck.features.project.intellij.model.folders.ExcludeFolder;
import com.facebook.buck.features.project.intellij.model.folders.IjFolder;
import com.facebook.buck.features.project.intellij.model.folders.IjResourceFolderType;
import com.facebook.buck.features.project.intellij.model.folders.IjSourceFolder;
import com.facebook.buck.features.project.intellij.model.folders.ResourceFolder;
import com.facebook.buck.features.project.intellij.model.folders.TestFolder;
import com.facebook.buck.features.project.intellij.writer.IjProjectWriter;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaPackageFinder;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;
import javax.annotation.Nullable;

/**
 * Does the converting of abstract data structures to a format immediately consumable by the
 * StringTemplate-based templates employed by {@link IjProjectWriter}. This is a separate class
 * mainly for testing convenience.
 */
@VisibleForTesting
public class IjProjectTemplateDataPreparer {
  private static final String ANDROID_MANIFEST_TEMPLATE_PARAMETER = "android_manifest";
  private static final String APK_PATH_TEMPLATE_PARAMETER = "apk_path";
  private static final String ASSETS_FOLDER_TEMPLATE_PARAMETER = "asset_folder";
  private static final String PROGUARD_CONFIG_TEMPLATE_PARAMETER = "proguard_config";
  private static final String RESOURCES_RELATIVE_PATH_TEMPLATE_PARAMETER = "res";

  private static final String EMPTY_STRING = "";

  private final JavaPackageFinder javaPackageFinder;
  private final IjModuleGraph moduleGraph;
  private final ProjectFilesystem projectFilesystem;
  private final IjProjectConfig projectConfig;
  private final IjProjectPaths projectPaths;
  private final IjSourceRootSimplifier sourceRootSimplifier;
  private final ImmutableSet<Path> referencedFolderPaths;
  private final ImmutableSet<Path> filesystemTraversalBoundaryPaths;
  private final ImmutableSet<IjModule> modulesToBeWritten;
  private final ImmutableSet<IjLibrary> allLibraries;
  private final ImmutableSet<IjLibrary> projectLibrariesToBeWritten;

  public IjProjectTemplateDataPreparer(
      JavaPackageFinder javaPackageFinder,
      IjModuleGraph moduleGraph,
      ProjectFilesystem projectFilesystem,
      IjProjectConfig projectConfig) {
    this.javaPackageFinder = javaPackageFinder;
    this.moduleGraph = moduleGraph;
    this.projectFilesystem = projectFilesystem;
    this.projectConfig = projectConfig;
    this.projectPaths = projectConfig.getProjectPaths();
    this.sourceRootSimplifier = new IjSourceRootSimplifier(javaPackageFinder);
    this.modulesToBeWritten = createModulesToBeWritten(moduleGraph);
    this.allLibraries = moduleGraph.getLibraries();
    this.projectLibrariesToBeWritten = moduleGraph.getProjectLibraries();
    this.filesystemTraversalBoundaryPaths =
        createFilesystemTraversalBoundaryPathSet(modulesToBeWritten);
    this.referencedFolderPaths = createReferencedFolderPathsSet(modulesToBeWritten);
  }

  private static void addPathAndParents(Set<Path> pathSet, Path path) {
    do {
      pathSet.add(path);
      path = path.getParent();
    } while (path != null && !pathSet.contains(path));
  }

  public static ImmutableSet<Path> createReferencedFolderPathsSet(ImmutableSet<IjModule> modules) {
    Set<Path> pathSet = new HashSet<>();
    for (IjModule module : modules) {
      addPathAndParents(pathSet, module.getModuleBasePath());
      for (IjFolder folder : module.getFolders()) {
        addPathAndParents(pathSet, folder.getPath());
      }
    }
    return ImmutableSet.copyOf(pathSet);
  }

  public ImmutableSet<Path> createFilesystemTraversalBoundaryPathSet(
      ImmutableSet<IjModule> modules) {
    return Stream.concat(
            modules.stream().map(IjModule::getModuleBasePath),
            Stream.of(projectPaths.getIdeaConfigDir()))
        .collect(ImmutableSet.toImmutableSet());
  }

  public static ImmutableSet<Path> createPackageLookupPathSet(IjModuleGraph moduleGraph) {
    ImmutableSet.Builder<Path> builder = ImmutableSet.builder();

    for (IjModule module : moduleGraph.getModules()) {
      for (IjFolder folder : module.getFolders()) {
        if (!folder.getWantsPackagePrefix()) {
          continue;
        }
        Optional<Path> firstJavaFile =
            folder.getInputs().stream()
                .filter(input -> input.getFileName().toString().endsWith(".java"))
                .findFirst();
        if (firstJavaFile.isPresent()) {
          builder.add(firstJavaFile.get());
        }
      }
    }

    return builder.build();
  }

  private ImmutableSet<IjModule> createModulesToBeWritten(IjModuleGraph graph) {
    Path rootModuleBasePath = Paths.get(projectConfig.getProjectRoot());
    boolean hasRootModule =
        graph.getModules().stream()
            .anyMatch(module -> rootModuleBasePath.equals(module.getModuleBasePath()));

    ImmutableSet<IjModule> supplementalModules = ImmutableSet.of();
    if (!hasRootModule) {
      supplementalModules =
          ImmutableSet.of(
              IjModule.builder()
                  .setModuleBasePath(rootModuleBasePath)
                  .setTargets(ImmutableSet.of())
                  .setModuleType(IjModuleType.UNKNOWN_MODULE)
                  .build());
    }

    return Stream.concat(graph.getModules().stream(), supplementalModules.stream())
        .collect(ImmutableSet.toImmutableSet());
  }

  public ImmutableSet<IjModule> getModulesToBeWritten() {
    return modulesToBeWritten;
  }

  public ImmutableSet<IjLibrary> getAllLibraries() {
    return allLibraries;
  }

  public ImmutableSet<IjLibrary> getProjectLibrariesToBeWritten() {
    return projectLibrariesToBeWritten;
  }

  private ImmutableList<ContentRoot> createContentRoots(
      IjModule module, ImmutableCollection<IjFolder> folders) {
    Path contentRootPath = module.getModuleBasePath();
    ImmutableListMultimap<Path, IjFolder> simplifiedFolders =
        sourceRootSimplifier.simplify(
            contentRootPath.toString().isEmpty() ? 0 : contentRootPath.getNameCount(),
            folders,
            contentRootPath,
            filesystemTraversalBoundaryPaths);

    IjFolderToIjSourceFolderTransform transformToFolder =
        new IjFolderToIjSourceFolderTransform(module);
    Map<String, ImmutableList<IjSourceFolder>> sources = Maps.newTreeMap();
    sources.put(contentRootPath.toString(), ImmutableList.of());
    simplifiedFolders
        .asMap()
        .forEach(
            (contentRoot, contentRootFolders) -> {
              ImmutableList<IjSourceFolder> sourceFolders =
                  contentRootFolders.stream()
                      .map(transformToFolder)
                      .sorted()
                      .collect(ImmutableList.toImmutableList());
              sources.put(contentRoot.toString(), sourceFolders);
            });
    ImmutableList.Builder<ContentRoot> contentRootsBuilder = ImmutableList.builder();
    for (Map.Entry<String, ImmutableList<IjSourceFolder>> entry : sources.entrySet()) {
      String url = getUrl(projectPaths.getModuleQualifiedPath(Paths.get(entry.getKey()), module));
      contentRootsBuilder.add(ContentRoot.of(url, entry.getValue()));
    }
    return contentRootsBuilder.build();
  }

  public ImmutableCollection<IjFolder> createExcludes(IjModule module) throws IOException {
    Path moduleBasePath = module.getModuleBasePath();
    if (!projectFilesystem.exists(moduleBasePath)) {
      return ImmutableList.of();
    }
    ImmutableList.Builder<IjFolder> excludesBuilder = ImmutableList.builder();
    boolean isTopLevelOnly =
        moduleBasePath.toString().isEmpty()
            && projectConfig.getProjectRootExclusionMode() == ProjectRootExclusionMode.TOP_LEVEL;
    projectFilesystem.walkRelativeFileTree(
        moduleBasePath,
        new FileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            // This is another module that's nested in this one. The entire subtree will be handled
            // When we create excludes for that module.
            if (filesystemTraversalBoundaryPaths.contains(dir) && !moduleBasePath.equals(dir)) {
              return FileVisitResult.SKIP_SUBTREE;
            }

            if (isRootAndroidResourceDirectory(module, dir)) {
              return FileVisitResult.SKIP_SUBTREE;
            }

            if (!referencedFolderPaths.contains(dir)
                || isTopLevelOnly && !moduleBasePath.equals(dir) && dir.getParent() == null) {
              excludesBuilder.add(new ExcludeFolder(dir));
              return FileVisitResult.SKIP_SUBTREE;
            }

            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFileFailed(Path file, IOException exc) {
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
            return FileVisitResult.CONTINUE;
          }
        },
        false);
    return excludesBuilder.build();
  }

  private boolean isRootAndroidResourceDirectory(IjModule module, Path dir) {
    if (!module.getAndroidFacet().isPresent()) {
      return false;
    }

    for (Path resourcePath : module.getAndroidFacet().get().getResourcePaths()) {
      if (dir.equals(resourcePath)) {
        return true;
      }
    }

    return false;
  }

  public ImmutableList<ContentRoot> getContentRoots(IjModule module) throws IOException {
    ImmutableList<IjFolder> sourcesAndExcludes =
        Stream.concat(module.getFolders().stream(), createExcludes(module).stream())
            .sorted()
            .collect(ImmutableList.toImmutableList());
    return createContentRoots(module, sourcesAndExcludes);
  }

  public ImmutableSet<IjSourceFolder> getGeneratedSourceFolders(IjModule module) {
    return module.getGeneratedSourceCodeFolders().stream()
        .map(new IjFolderToIjSourceFolderTransform(module))
        .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural()));
  }

  public ImmutableSet<IjDependencyListBuilder.DependencyEntry> getDependencies(
      IjModule module, @Nullable Function<IjLibrary, IjLibrary> moduleLibraryTransformer) {
    ImmutableMap<IjProjectElement, DependencyType> deps = moduleGraph.getDepsFor(module);
    IjDependencyListBuilder dependencyListBuilder =
        new IjDependencyListBuilder(projectConfig.isModuleDependenciesSorted());

    for (Map.Entry<IjProjectElement, DependencyType> entry : deps.entrySet()) {
      IjProjectElement element = entry.getKey();
      DependencyType dependencyType = entry.getValue();
      if (moduleLibraryTransformer != null
          && element instanceof IjLibrary
          && ((IjLibrary) element).getLevel() == IjLibrary.Level.MODULE) {
        element = moduleLibraryTransformer.apply((IjLibrary) element);
      }
      element.addAsDependency(dependencyType, dependencyListBuilder);
    }
    return dependencyListBuilder.build();
  }

  public Optional<String> getFirstResourcePackageFromDependencies(IjModule module) {
    ImmutableMap<IjModule, DependencyType> deps = moduleGraph.getDependentModulesFor(module);
    for (IjModule dep : deps.keySet()) {
      Optional<IjModuleAndroidFacet> facet = dep.getAndroidFacet();
      if (facet.isPresent()) {
        Optional<String> packageName = facet.get().getPackageName();
        if (packageName.isPresent()) {
          return packageName;
        }
      }
    }
    return Optional.empty();
  }

  public ImmutableSortedSet<ModuleIndexEntry> getModuleIndexEntries() {
    String moduleGroupName = projectConfig.getModuleGroupName();
    String extension = projectConfig.isGenerateProjectFilesAsJsonEnabled() ? ".json" : ".iml";
    boolean needToPutModuleToGroup = !moduleGroupName.isEmpty();
    return modulesToBeWritten.stream()
        .map(
            module -> {
              Path moduleOutputFilePath =
                  projectPaths.getModuleFilePath(module, projectConfig, extension);
              String fileUrl = getUrl(projectPaths.getProjectQualifiedPath(moduleOutputFilePath));
              Path moduleOutputFileRelativePath =
                  projectPaths.getProjectRelativePath(moduleOutputFilePath);
              // The root project module cannot belong to any group.
              String group =
                  (module.getModuleBasePath().toString().isEmpty() || !needToPutModuleToGroup)
                      ? null
                      : moduleGroupName;
              return ModuleIndexEntry.of(fileUrl, moduleOutputFileRelativePath, group);
            })
        .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural()));
  }

  public Map<String, Object> getAndroidProperties(IjModule module) {
    Map<String, Object> androidProperties = new HashMap<>();
    Optional<IjModuleAndroidFacet> androidFacetOptional = module.getAndroidFacet();

    boolean isAndroidFacetPresent = androidFacetOptional.isPresent();
    androidProperties.put("enabled", isAndroidFacetPresent);

    Path basePath = module.getModuleBasePath();

    Optional<Path> extraCompilerOutputPath = projectConfig.getExtraCompilerOutputModulesPath();
    if (isAndroidFacetPresent
        || (extraCompilerOutputPath.isPresent()
            && basePath.toString().contains(extraCompilerOutputPath.get().toString()))) {
      addAndroidCompilerOutputPath(androidProperties, module);
    }

    if (!isAndroidFacetPresent) {
      return androidProperties;
    }

    IjModuleAndroidFacet androidFacet = androidFacetOptional.get();

    androidProperties.put("is_android_library_project", androidFacet.isAndroidLibrary());
    androidProperties.put("project_type", androidFacet.getAndroidProjectType().getId());
    androidProperties.put("autogenerate_sources", androidFacet.autogenerateSources());
    androidProperties.put(
        "disallow_user_configuration",
        projectConfig.isAggregatingAndroidResourceModulesEnabled()
            && projectConfig.getAggregationMode() != AggregationMode.NONE);

    addAndroidApkPaths(androidProperties, module, androidFacet);
    addAndroidAssetPaths(androidProperties, module, androidFacet);
    addAndroidGenPath(androidProperties, androidFacet, module);
    addAndroidManifestPath(androidProperties, module, androidFacet);
    addAndroidProguardPath(androidProperties, androidFacet);
    addAndroidResourcePaths(androidProperties, module, androidFacet);

    return androidProperties;
  }

  private void addAndroidApkPaths(
      Map<String, Object> androidProperties, IjModule module, IjModuleAndroidFacet androidFacet) {
    if (androidFacet.isAndroidLibrary()) {
      return;
    }

    Path apkPath =
        Paths.get(IjAndroidHelper.getAndroidApkDir(projectFilesystem))
            .resolve(module.getModuleBasePath())
            .resolve(module.getName() + ".apk");
    androidProperties.put(
        APK_PATH_TEMPLATE_PARAMETER, projectPaths.getModuleRelativePath(apkPath, module));
  }

  private void addAndroidAssetPaths(
      Map<String, Object> androidProperties, IjModule module, IjModuleAndroidFacet androidFacet) {
    if (androidFacet.isAndroidLibrary()) {
      return;
    }
    ImmutableSet<Path> assetPaths = androidFacet.getAssetPaths();
    if (assetPaths.isEmpty()) {
      return;
    }
    Set<Path> relativeAssetPaths = new HashSet<>(assetPaths.size());
    for (Path assetPath : assetPaths) {
      relativeAssetPaths.add(projectPaths.getModuleRelativePath(assetPath, module));
    }
    androidProperties.put(
        ASSETS_FOLDER_TEMPLATE_PARAMETER, "/" + Joiner.on(";/").join(relativeAssetPaths));
  }

  private void addAndroidGenPath(
      Map<String, Object> androidProperties, IjModuleAndroidFacet androidFacet, IjModule module) {

    androidProperties.put(
        "module_gen_path",
        IjProjectPaths.getAndroidFacetRelativePath(
            projectPaths.getModuleRelativePath(androidFacet.getGeneratedSourcePath(), module)));
    androidProperties.put(
        "module_gen_url",
        getUrl(projectPaths.getModuleQualifiedPath(androidFacet.getGeneratedSourcePath(), module)));
  }

  private void addAndroidManifestPath(
      Map<String, Object> androidProperties, IjModule module, IjModuleAndroidFacet androidFacet) {
    Optional<Path> androidManifestPath =
        androidFacet.getAndroidManifestPath(projectFilesystem, projectConfig);

    if (androidManifestPath.isEmpty()) {
      return;
    }

    Path manifestPath =
        projectPaths.getModuleRelativePath(
            projectPaths.getProjectRelativePath(androidManifestPath.get()), module);

    if (!IjModuleAndroidFacet.ANDROID_MANIFEST.equals(manifestPath.toString())) {
      androidProperties.put(
          ANDROID_MANIFEST_TEMPLATE_PARAMETER,
          IjProjectPaths.getAndroidFacetRelativePath(manifestPath));
    }
  }

  private void addAndroidProguardPath(
      Map<String, Object> androidProperties, IjModuleAndroidFacet androidFacet) {
    androidFacet
        .getProguardConfigPath()
        .ifPresent(
            proguardPath ->
                androidProperties.put(PROGUARD_CONFIG_TEMPLATE_PARAMETER, proguardPath));
  }

  private void addAndroidResourcePaths(
      Map<String, Object> androidProperties, IjModule module, IjModuleAndroidFacet androidFacet) {
    ImmutableSet<Path> resourcePaths = androidFacet.getResourcePaths();
    if (resourcePaths.isEmpty()) {
      androidProperties.put(RESOURCES_RELATIVE_PATH_TEMPLATE_PARAMETER, EMPTY_STRING);
    } else {
      Set<String> relativeResourcePaths = new HashSet<>(resourcePaths.size());
      for (Path resourcePath : resourcePaths) {
        relativeResourcePaths.add(
            IjProjectPaths.toRelativeString(resourcePath, projectPaths.getModuleDir(module)));
      }

      androidProperties.put(
          RESOURCES_RELATIVE_PATH_TEMPLATE_PARAMETER, Joiner.on(";").join(relativeResourcePaths));
    }
  }

  /**
   * IntelliJ may not be able to find classes on the compiler output path if the jars are retrieved
   * from the network cache.
   */
  private void addAndroidCompilerOutputPath(
      Map<String, Object> androidProperties, IjModule module) {
    // The compiler output path is relative to the project root
    Optional<Path> compilerOutputPath = module.getCompilerOutputPath();
    if (compilerOutputPath.isPresent()) {
      androidProperties.put(
          "compiler_output_path",
          getUrl(projectPaths.getModuleQualifiedPath(compilerOutputPath.get(), module)));
    }
  }

  private class IjFolderToIjSourceFolderTransform implements Function<IjFolder, IjSourceFolder> {
    private IjModule module;
    private Optional<IjModuleAndroidFacet> androidFacet;

    IjFolderToIjSourceFolderTransform(IjModule module) {
      this.module = module;
      androidFacet = module.getAndroidFacet();
    }

    @Override
    public IjSourceFolder apply(IjFolder input) {
      String packagePrefix;
      if (input instanceof AndroidResourceFolder
          && androidFacet.isPresent()
          && androidFacet.get().getPackageName().isPresent()) {
        packagePrefix = androidFacet.get().getPackageName().get();
      } else {
        packagePrefix = getPackagePrefix(input);
      }
      return createSourceFolder(input, packagePrefix);
    }

    private IjSourceFolder createSourceFolder(IjFolder folder, @Nullable String packagePrefix) {
      Path relativeOutputPath = null;
      IjResourceFolderType ijResourceFolderType = IjResourceFolderType.JAVA_RESOURCE;
      if (folder instanceof ResourceFolder) {
        ResourceFolder resourceFolder = (ResourceFolder) folder;
        relativeOutputPath = resourceFolder.getRelativeOutputPath();
        ijResourceFolderType = resourceFolder.getResourceFolderType();
      }

      return IjSourceFolder.of(
          folder.getIjName(),
          getUrl(projectPaths.getModuleQualifiedPath(folder.getPath(), module)),
          projectPaths.getModuleRelativePath(folder.getPath(), module),
          folder instanceof TestFolder,
          folder.isResourceFolder(),
          ijResourceFolderType,
          relativeOutputPath,
          packagePrefix);
    }

    @Nullable
    private String getPackagePrefix(IjFolder folder) {
      if (!folder.getWantsPackagePrefix()) {
        return null;
      }
      Path fileToLookupPackageIn;
      if (!folder.getInputs().isEmpty()
          && folder.getInputs().first().getParent().equals(folder.getPath())) {
        fileToLookupPackageIn = folder.getInputs().first();
      } else {
        fileToLookupPackageIn = folder.getPath().resolve("notfound");
      }
      String packagePrefix = javaPackageFinder.findJavaPackage(fileToLookupPackageIn);
      if (packagePrefix.isEmpty()) {
        // It doesn't matter either way, but an empty prefix looks confusing.
        return null;
      }
      return packagePrefix;
    }
  }
}
