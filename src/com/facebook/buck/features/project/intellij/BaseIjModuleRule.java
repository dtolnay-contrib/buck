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

import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.features.project.intellij.aggregation.AggregationContext;
import com.facebook.buck.features.project.intellij.depsquery.IjDepsQueryResolver;
import com.facebook.buck.features.project.intellij.model.DependencyType;
import com.facebook.buck.features.project.intellij.model.IjModule;
import com.facebook.buck.features.project.intellij.model.IjModuleFactoryResolver;
import com.facebook.buck.features.project.intellij.model.IjModuleRule;
import com.facebook.buck.features.project.intellij.model.IjProjectConfig;
import com.facebook.buck.features.project.intellij.model.folders.IJFolderFactory;
import com.facebook.buck.features.project.intellij.model.folders.IjResourceFolderType;
import com.facebook.buck.features.project.intellij.model.folders.ResourceFolderFactory;
import com.facebook.buck.features.project.intellij.model.folders.SourceFolder;
import com.facebook.buck.features.project.intellij.model.folders.TestFolder;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaPackageFinder;
import com.facebook.buck.jvm.java.JvmLibraryArg;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** Base class for IntelliJ module rules */
public abstract class BaseIjModuleRule<T extends BuildRuleArg> implements IjModuleRule<T> {

  protected final ProjectFilesystem projectFilesystem;
  protected final IjModuleFactoryResolver moduleFactoryResolver;
  private final IjDepsQueryResolver depsQueryResolver;
  protected final IjProjectConfig projectConfig;

  protected BaseIjModuleRule(
      ProjectFilesystem projectFilesystem,
      IjModuleFactoryResolver moduleFactoryResolver,
      IjDepsQueryResolver depsQueryResolver,
      IjProjectConfig projectConfig) {
    this.projectFilesystem = projectFilesystem;
    this.moduleFactoryResolver = moduleFactoryResolver;
    this.depsQueryResolver = depsQueryResolver;
    this.projectConfig = projectConfig;
  }

  /**
   * Calculate the set of directories containing inputs to the target.
   *
   * @param paths inputs to a given target.
   * @return index of path to set of inputs in that path
   */
  protected static ImmutableMultimap<Path, Path> getSourceFoldersToInputsIndex(
      ImmutableCollection<Path> paths) {
    Path defaultParent = Paths.get("");
    return paths.stream()
        .collect(
            ImmutableListMultimap.toImmutableListMultimap(
                path -> {
                  Path parent = path.getParent();
                  return parent == null ? defaultParent : parent;
                },
                path -> path));
  }

  /**
   * Add the set of input paths to the {@link IjModule.Builder} as source folders.
   *
   * @param foldersToInputsIndex mapping of source folders to their inputs.
   * @param wantsPackagePrefix whether folders should be annotated with a package prefix. This only
   *     makes sense when the source folder is Java source code.
   * @param context the module to add the folders to.
   */
  protected void addSourceFolders(
      IJFolderFactory factory,
      ImmutableMultimap<Path, Path> foldersToInputsIndex,
      boolean wantsPackagePrefix,
      ModuleBuildContext context) {
    for (Map.Entry<Path, Collection<Path>> entry : foldersToInputsIndex.asMap().entrySet()) {
      context.addSourceFolder(
          factory.create(
              entry.getKey(),
              wantsPackagePrefix,
              ImmutableSortedSet.copyOf(Ordering.natural(), entry.getValue())));
    }
  }

  protected void addResourceFolders(
      ResourceFolderFactory factory,
      ImmutableMultimap<Path, Path> foldersToInputsIndex,
      Path resourcesRoot,
      ModuleBuildContext context) {
    for (Map.Entry<Path, Collection<Path>> entry : foldersToInputsIndex.asMap().entrySet()) {
      context.addSourceFolder(
          factory.create(
              entry.getKey(),
              resourcesRoot,
              ImmutableSortedSet.copyOf(Ordering.natural(), entry.getValue())));
    }
  }

  private void addDepsAndFolder(
      IJFolderFactory folderFactory,
      DependencyType dependencyType,
      TargetNode<T> targetNode,
      boolean wantsPackagePrefix,
      ModuleBuildContext context,
      ImmutableSet<Path> resourcePaths) {
    ImmutableMultimap<Path, Path> foldersToInputsIndex =
        getSourceFoldersToInputsIndex(
            targetNode.getInputs().stream()
                .map(
                    path ->
                        projectFilesystem
                            .relativize(targetNode.getFilesystem().resolve(path))
                            .getPath())
                .collect(ImmutableList.toImmutableList()));

    if (!resourcePaths.isEmpty()) {
      foldersToInputsIndex =
          foldersToInputsIndex.entries().stream()
              .filter(entry -> !resourcePaths.contains(entry.getValue()))
              .collect(
                  ImmutableListMultimap.toImmutableListMultimap(
                      Map.Entry::getKey, Map.Entry::getValue));
    }

    addSourceFolders(folderFactory, foldersToInputsIndex, wantsPackagePrefix, context);
    addDeps(foldersToInputsIndex, targetNode, dependencyType, context);

    addGeneratedOutputIfNeeded(folderFactory, targetNode, context);

    if (targetNode.getConstructorArg() instanceof JvmLibraryArg) {
      addAnnotationOutputIfNeeded(folderFactory, targetNode, context);
    }
  }

  private void addDepsAndFolder(
      IJFolderFactory folderFactory,
      DependencyType dependencyType,
      TargetNode<T> targetNode,
      boolean wantsPackagePrefix,
      ModuleBuildContext context) {
    addDepsAndFolder(
        folderFactory, dependencyType, targetNode, wantsPackagePrefix, context, ImmutableSet.of());
  }

  protected void addDepsAndSources(
      TargetNode<T> targetNode,
      boolean wantsPackagePrefix,
      ModuleBuildContext context,
      ImmutableSet<Path> resourcePaths) {
    addDepsAndFolder(
        SourceFolder.FACTORY,
        DependencyType.PROD,
        targetNode,
        wantsPackagePrefix,
        context,
        resourcePaths);
  }

  protected void addDepsAndSources(
      TargetNode<T> targetNode, boolean wantsPackagePrefix, ModuleBuildContext context) {
    addDepsAndFolder(
        SourceFolder.FACTORY, DependencyType.PROD, targetNode, wantsPackagePrefix, context);
  }

  protected void addDepsAndTestSources(
      TargetNode<T> targetNode,
      boolean wantsPackagePrefix,
      ModuleBuildContext context,
      ImmutableSet<Path> resourcePaths) {
    addDepsAndFolder(
        TestFolder.FACTORY,
        DependencyType.TEST,
        targetNode,
        wantsPackagePrefix,
        context,
        resourcePaths);
  }

  protected void addDepsAndTestSources(
      TargetNode<T> targetNode, boolean wantsPackagePrefix, ModuleBuildContext context) {
    addDepsAndFolder(
        TestFolder.FACTORY, DependencyType.TEST, targetNode, wantsPackagePrefix, context);
  }

  protected ImmutableSet<Path> getResourcePaths(Collection<SourcePath> resources) {
    return resources.stream()
        .filter(PathSourcePath.class::isInstance)
        .map(PathSourcePath.class::cast)
        .map(path -> projectFilesystem.relativize(Paths.get(path.toString())).getPath())
        .collect(ImmutableSet.toImmutableSet());
  }

  protected ImmutableSet<Path> getResourcePaths(
      Collection<SourcePath> resources, Path resourcesRoot) {
    return resources.stream()
        .filter(PathSourcePath.class::isInstance)
        .map(PathSourcePath.class::cast)
        .map(path -> projectFilesystem.relativize(Paths.get(path.toString())).getPath())
        .filter(path -> path.startsWith(resourcesRoot))
        .collect(ImmutableSet.toImmutableSet());
  }

  protected ImmutableMultimap<Path, Path> getResourcesRootsToResources(
      JavaPackageFinder packageFinder, ImmutableSet<Path> resourcePaths) {
    return resourcePaths.stream()
        .collect(
            ImmutableListMultimap.toImmutableListMultimap(
                path ->
                    MorePaths.stripCommonSuffix(
                            path.getParent(), packageFinder.findJavaPackageFolder(path))
                        .getFirst(),
                Function.identity()));
  }

  // This function should only be called if resources_root is present. If there is no
  // resources_root, then we use the java src_roots option from .buckconfig for the resource root,
  // so marking the containing folder of the resources as a regular source folder will work
  // correctly. On the other hand, if there is a resources_root, then for resources under this root,
  // we need to create java-resource folders with the correct relativeOutputPath set. We also return
  // a filter that removes the resources that we've added, so that folders containing those
  // resources will not be added as regular source folders.
  protected void addResourceFolders(
      IjResourceFolderType ijResourceFolderType,
      ImmutableCollection<Path> resourcePaths,
      Path resourcesRoot,
      ModuleBuildContext context) {
    addResourceFolders(
        ijResourceFolderType.getFactory(),
        getSourceFoldersToInputsIndex(resourcePaths),
        resourcesRoot,
        context);
  }

  private void addDeps(
      ImmutableMultimap<Path, Path> foldersToInputsIndex,
      TargetNode<T> targetNode,
      DependencyType dependencyType,
      ModuleBuildContext context) {
    ImmutableSet.Builder<BuildTarget> builder = ImmutableSet.builder();
    builder.addAll(targetNode.getBuildDeps());
    builder.addAll(depsQueryResolver.getResolvedDeps(targetNode));
    builder.addAll(depsQueryResolver.getResolvedProvidedDeps(targetNode));
    context.addDeps(foldersToInputsIndex.keySet(), builder.build(), dependencyType);
  }

  @SuppressWarnings("unchecked")
  private void addAnnotationOutputIfNeeded(
      IJFolderFactory folderFactory, TargetNode<T> targetNode, ModuleBuildContext context) {
    TargetNode<? extends JvmLibraryArg> jvmLibraryTargetNode =
        (TargetNode<? extends JvmLibraryArg>) targetNode;

    Optional<Path> annotationOutput =
        moduleFactoryResolver.getAnnotationOutputPath(jvmLibraryTargetNode);
    if (!annotationOutput.isPresent()) {
      return;
    }

    Path annotationOutputPath = annotationOutput.get();
    context.addGeneratedSourceCodeFolder(
        targetNode.getBuildTarget(),
        folderFactory.create(
            annotationOutputPath, false, ImmutableSortedSet.of(annotationOutputPath)));

    moduleFactoryResolver
        .getKaptAnnotationOutputPath(jvmLibraryTargetNode)
        .ifPresent(
            path ->
                context.addGeneratedSourceCodeFolder(
                    targetNode.getBuildTarget(),
                    folderFactory.create(path, false, ImmutableSortedSet.of(path))));

    moduleFactoryResolver
        .getKspAnnotationOutputPath(jvmLibraryTargetNode)
        .ifPresent(
            path ->
                context.addGeneratedSourceCodeFolder(
                    targetNode.getBuildTarget(),
                    folderFactory.create(path, false, ImmutableSortedSet.of(path))));
  }

  private void addGeneratedOutputIfNeeded(
      IJFolderFactory folderFactory, TargetNode<T> targetNode, ModuleBuildContext context) {

    ImmutableSet<RelPath> generatedSourcePaths = findConfiguredGeneratedSourcePaths(targetNode);

    for (RelPath generatedSourcePath : generatedSourcePaths) {
      context.addGeneratedSourceCodeFolder(
          targetNode.getBuildTarget(),
          folderFactory.create(
              generatedSourcePath.getPath(),
              false,
              ImmutableSortedSet.of(generatedSourcePath.getPath())));
    }
  }

  private ImmutableSet<RelPath> findConfiguredGeneratedSourcePaths(TargetNode<T> targetNode) {
    ImmutableSet.Builder<RelPath> generatedSourcePaths = ImmutableSet.builder();

    generatedSourcePaths.addAll(findConfiguredGeneratedSourcePathsUsingLabels(targetNode));

    return generatedSourcePaths.build();
  }

  private ImmutableSet<RelPath> findConfiguredGeneratedSourcePathsUsingLabels(
      TargetNode<T> targetNode) {
    BuildTarget buildTarget = targetNode.getBuildTarget();
    ImmutableMap<String, String> labelToGeneratedSourcesMap =
        projectConfig.getLabelToGeneratedSourcesMap();

    return targetNode.getConstructorArg().getLabels().stream()
        .map(labelToGeneratedSourcesMap::get)
        .filter(Objects::nonNull)
        .map(
            pattern -> {
              // Format must have exactly one `%s` component
              String format =
                  pattern
                      .replaceFirst("%name%", "%s")
                      .replace("%name%", buildTarget.getShortNameAndFlavorPostfix());
              return BuildTargetPaths.getGenPath(
                  projectFilesystem.getBuckPaths(), buildTarget, format);
            })
        .collect(ImmutableSet.toImmutableSet());
  }

  @Override
  public void applyDuringAggregation(AggregationContext context, TargetNode<T> targetNode) {
    context.setModuleType(detectModuleType(targetNode));
  }

  /** The default implementation returns the original module path directly */
  @Override
  public Path adjustModulePath(TargetNode<T> targetNode, Path modulePath) {
    return modulePath;
  }
}
