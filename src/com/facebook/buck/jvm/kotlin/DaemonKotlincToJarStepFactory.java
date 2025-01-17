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

package com.facebook.buck.jvm.kotlin;

import static com.facebook.buck.jvm.java.CompilerOutputPaths.getKAPTDepFilePath;
import static com.facebook.buck.jvm.java.CompilerOutputPaths.getKspDepFilePath;
import static com.facebook.buck.jvm.java.JavaPaths.SRC_ZIP;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.Iterables.transform;

import com.facebook.buck.cd.model.java.AbiGenerationMode;
import com.facebook.buck.cd.model.java.FilesystemParams;
import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.ForwardRelPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.io.file.FileExtensionMatcher;
import com.facebook.buck.io.file.GlobPatternMatcher;
import com.facebook.buck.io.file.PathMatcher;
import com.facebook.buck.io.filesystem.BuckPaths;
import com.facebook.buck.io.filesystem.CopySourceMode;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.BuildTargetValue;
import com.facebook.buck.jvm.core.BuildTargetValueExtraParams;
import com.facebook.buck.jvm.core.FilesystemParamsUtils;
import com.facebook.buck.jvm.core.JavaAbis;
import com.facebook.buck.jvm.java.BaseCompileToJarStepFactory;
import com.facebook.buck.jvm.java.BaseJavacToJarStepFactory;
import com.facebook.buck.jvm.java.CompilerOutputPaths;
import com.facebook.buck.jvm.java.CompilerOutputPathsValue;
import com.facebook.buck.jvm.java.CompilerParameters;
import com.facebook.buck.jvm.java.JarParameters;
import com.facebook.buck.jvm.java.JavaExtraParams;
import com.facebook.buck.jvm.java.JavacPluginParams;
import com.facebook.buck.jvm.java.ResolvedJavac;
import com.facebook.buck.jvm.java.ResolvedJavacOptions;
import com.facebook.buck.jvm.java.ResolvedJavacPluginProperties;
import com.facebook.buck.jvm.kotlin.KotlinLibraryDescription.AnnotationProcessingTool;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import com.facebook.buck.step.isolatedsteps.common.CopyIsolatedStep;
import com.facebook.buck.step.isolatedsteps.common.MakeCleanDirectoryIsolatedStep;
import com.facebook.buck.step.isolatedsteps.common.MkdirIsolatedStep;
import com.facebook.buck.step.isolatedsteps.common.ZipIsolatedStep;
import com.facebook.buck.step.isolatedsteps.java.JarDirectoryStep;
import com.facebook.buck.util.stream.RichStream;
import com.facebook.buck.util.zip.ZipCompressionLevel;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Ordering;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * Factory that creates Kotlin related compile build steps, but doesn't depend on any internal build
 * graph data structured. Intended to be used from the Daemon worker.
 */
public class DaemonKotlincToJarStepFactory extends BaseCompileToJarStepFactory<KotlinExtraParams> {

  private static final String PLUGIN = "-P";
  private static final String APT_MODE = "aptMode=";
  private static final String X_PLUGIN_ARG = "-Xplugin=";
  private static final String KAPT3_PLUGIN = "plugin:org.jetbrains.kotlin.kapt3:";
  private static final String AP_CLASSPATH_ARG = KAPT3_PLUGIN + "apclasspath=";
  private static final String AP_PROCESSORS_ARG = KAPT3_PLUGIN + "processors=";
  // output path for generated sources;
  private static final String SOURCES_ARG = KAPT3_PLUGIN + "sources=";
  private static final String CLASSES_ARG = KAPT3_PLUGIN + "classes=";
  // output path for java stubs;
  private static final String STUBS_ARG = KAPT3_PLUGIN + "stubs=";
  private static final String LIGHT_ANALYSIS = KAPT3_PLUGIN + "useLightAnalysis=";
  private static final String CORRECT_ERROR_TYPES = KAPT3_PLUGIN + "correctErrorTypes=";
  private static final String JAVAC_ARG = KAPT3_PLUGIN + "javacArguments=";
  private static final String AP_OPTIONS = KAPT3_PLUGIN + "apoptions=";
  private static final String AP_STATS_REPORT_ARG = KAPT3_PLUGIN + "dumpProcessorStats=";
  private static final String AP_FILE_ACCESS_HIST_REPORT_ARG =
      KAPT3_PLUGIN + "dumpFileAccessHistory=";
  private static final String KAPT_GENERATED = "kapt.kotlin.generated";
  private static final String MODULE_NAME = "-module-name";
  private static final String NO_STDLIB = "-no-stdlib";
  private static final String NO_REFLECT = "-no-reflect";
  private static final String VERBOSE = "-verbose";

  private static final PathMatcher KOTLIN_PATH_MATCHER = FileExtensionMatcher.of("kt");
  private static final PathMatcher SRC_ZIP_MATCHER = GlobPatternMatcher.of("**.src.zip");
  public static final String AP_STATS_REPORT_FILE = "ap_stats.report";
  public static final String KOTLIN_PLUGIN_OUT_PLACEHOLDER = "__codegen_dir__";
  public static final String KSP_PROCESSOR_NAME_PREFIX = "KSP:";

  /**
   * Only add parameters to this constructor that can be supplied both when constructing this
   * factory in a KotlinCD worker, and in the buck main process. Everything else necessary to create
   * compile steps should come in via {@link KotlinExtraParams}.
   */
  public DaemonKotlincToJarStepFactory(boolean hasAnnotationProcessing, boolean withDownwardApi) {
    super(hasAnnotationProcessing, withDownwardApi);
  }

  @Override
  public KotlinExtraParams castExtraParams(ExtraParams extraParams) {
    return (KotlinExtraParams) extraParams;
  }

  @Override
  public void createCompileStep(
      FilesystemParams filesystemParams,
      ImmutableMap<CanonicalCellName, RelPath> cellToPathMappings,
      BuildTargetValue invokingRule,
      CompilerOutputPathsValue compilerOutputPathsValue,
      CompilerParameters parameters,
      Builder<IsolatedStep> steps,
      BuildableContext buildableContext,
      ResolvedJavac resolvedJavac,
      KotlinExtraParams extraParams) {

    BuildTargetValueExtraParams buildTargetValueExtraParams =
        invokingRule
            .getExtraParams()
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "Kotlin compilation to jar factory has to have build target extra params"));

    AbsPath rootPath = getRootPath(filesystemParams);
    BuckPaths buckPaths = buildTargetValueExtraParams.getBuckPaths();
    ImmutableSet<PathMatcher> ignoredPaths =
        FilesystemParamsUtils.getIgnoredPaths(filesystemParams);

    ImmutableSortedSet<RelPath> declaredClasspathEntries = parameters.getClasspathEntries();
    ImmutableSortedSet<RelPath> sourceFilePaths = parameters.getSourceFilePaths();
    RelPath outputDirectory = parameters.getOutputPaths().getClassesDir();
    Path pathToSrcsList = parameters.getOutputPaths().getPathToSourcesList().getPath();

    boolean hasKotlinSources =
        sourceFilePaths.stream().anyMatch(KOTLIN_PATH_MATCHER::matches)
            || sourceFilePaths.stream().anyMatch(SRC_ZIP_MATCHER::matches);

    ImmutableSortedSet.Builder<RelPath> sourceBuilder =
        ImmutableSortedSet.orderedBy(RelPath.comparator()).addAll(sourceFilePaths);
    ImmutableSortedSet.Builder<RelPath> sourceBuilderWithKspOutputs =
        ImmutableSortedSet.orderedBy(RelPath.comparator()).addAll(sourceFilePaths);

    // Only invoke kotlinc if we have kotlin or src zip files.
    if (hasKotlinSources) {
      RelPath sourcesOutput = getAnnotationPath(buckPaths, invokingRule, "__%s_sources__");
      RelPath classesOutput = getAnnotationPath(buckPaths, invokingRule, "__%s_classes__");
      RelPath reportsOutput = getAnnotationPath(buckPaths, invokingRule, "__%s_reports__");

      RelPath kotlincPluginGeneratedOutput =
          getAnnotationPath(buckPaths, invokingRule, "__%s_kotlinc_plugin_generated__");

      // Javac requires that the root directory for generated sources already exist.
      steps.addAll(MakeCleanDirectoryIsolatedStep.of(classesOutput));
      steps.addAll(MakeCleanDirectoryIsolatedStep.of(kotlincPluginGeneratedOutput));
      steps.addAll(MakeCleanDirectoryIsolatedStep.of(sourcesOutput));
      steps.addAll(MakeCleanDirectoryIsolatedStep.of(reportsOutput));

      ImmutableSortedSet.Builder<AbsPath> friendAbsPathsBuilder =
          ImmutableSortedSet.orderedBy(Comparator.comparing(AbsPath::getPath));

      // Currently, kotlinc can't handle commas (`,`) in paths when passed to the `-Xfriend-paths`
      // flag, so if we see a comma, we copy the JAR to a new path w/o one.
      RelPath friendPathScratchDir =
          getScratchPath(buckPaths, invokingRule, "__%s_friend_path_jars__");
      friendPathScratchDir =
          friendPathScratchDir
              .getParent()
              .resolveRel(friendPathScratchDir.getFileName().toString().replace(",", "__"));
      Map<AbsPath, AbsPath> remappedClasspathEntries = new HashMap<>();

      for (AbsPath friendPath : extraParams.getResolvedFriendPaths()) {
        // If this path has a comma, copy to a new location that doesn't have one.
        if (friendPath.getPath().toString().contains(",")) {
          if (remappedClasspathEntries.isEmpty()) {
            steps.add(MkdirIsolatedStep.of(friendPathScratchDir));
          }
          AbsPath dest = rootPath.resolve(friendPathScratchDir.resolve(friendPath.getFileName()));
          steps.add(CopyIsolatedStep.of(friendPath.getPath(), dest.getPath(), CopySourceMode.FILE));
          remappedClasspathEntries.put(friendPath, dest);
          friendPath = dest;
        }
        friendAbsPathsBuilder.add(friendPath);
      }
      ImmutableSortedSet<AbsPath> friendAbsPaths = friendAbsPathsBuilder.build();

      ImmutableSortedSet<AbsPath> allClasspaths =
          ImmutableSortedSet.orderedBy(Comparator.comparing(AbsPath::getPath))
              .addAll(
                  RichStream.from(extraParams.getExtraClassPaths())
                      .map(p -> remappedClasspathEntries.getOrDefault(p, p))
                      .iterator())
              .addAll(
                  RichStream.from(declaredClasspathEntries)
                      .map(rootPath::resolve)
                      .map(AbsPath::normalize)
                      .map(p -> remappedClasspathEntries.getOrDefault(p, p))
                      .iterator())
              .addAll(extraParams.getResolvedKotlinHomeLibraries())
              .build();

      ImmutableMap<String, AbsPath> resolvedKosabiPluginOptionPath =
          extraParams.getResolvedKosabiPluginOptionPath();

      String friendPathsArg = getFriendsPath(friendAbsPaths);
      String moduleName = getModuleName(invokingRule);
      String kotlinPluginGeneratedFullPath =
          rootPath.resolve(kotlincPluginGeneratedOutput).toString();

      Builder<String> annotationProcessingOptionsBuilder = ImmutableList.builder();
      Builder<IsolatedStep> postKotlinCompilationSteps = ImmutableList.builder();

      JavacPluginParams annotationProcessorParams =
          extraParams.getResolvedJavacOptions().getJavaAnnotationProcessorParams();

      prepareKaptProcessorsIfNeeded(
          extraParams.getAnnotationProcessingTool(),
          invokingRule,
          rootPath,
          steps,
          buckPaths,
          ignoredPaths,
          extraParams.getJvmTarget(),
          extraParams.getResolvedStandardLibraryClassPath(),
          extraParams.getResolvedAnnotationProcessingClassPath(),
          outputDirectory,
          sourceBuilder,
          sourcesOutput,
          classesOutput,
          reportsOutput,
          parameters.shouldTrackClassUsage(),
          extraParams.shouldGenerateAnnotationProcessingStats(),
          annotationProcessingOptionsBuilder,
          postKotlinCompilationSteps,
          annotationProcessorParams);

      prepareKspProcessorsIfNeeded(
          extraParams.getKotlinc(),
          extraParams.getAnnotationProcessingTool(),
          invokingRule,
          rootPath,
          steps,
          buckPaths,
          ignoredPaths,
          outputDirectory,
          sourceBuilder,
          sourcesOutput,
          classesOutput,
          reportsOutput,
          parameters.shouldTrackClassUsage(),
          postKotlinCompilationSteps,
          allClasspaths,
          extraParams.getResolvedKotlinHomeLibraries(),
          extraParams.getResolvedKotlinCompilerPlugins(),
          resolvedKosabiPluginOptionPath,
          kotlinPluginGeneratedFullPath,
          buildTargetValueExtraParams.getCellRelativeBasePath(),
          sourceFilePaths,
          pathToSrcsList,
          parameters.getOutputPaths(),
          RelPath.get(filesystemParams.getConfiguredBuckOut().getPath()),
          cellToPathMappings,
          annotationProcessorParams,
          sourceBuilderWithKspOutputs);

      Builder<String> extraArguments =
          ImmutableList.<String>builder()
              .add(friendPathsArg)
              .addAll(
                  getKotlinCompilerPluginsArgs(
                      extraParams.getResolvedKotlinCompilerPlugins(),
                      kotlinPluginGeneratedFullPath,
                      DaemonKotlincToJarStepFactory::isNotKspPlugin))
              .addAll(annotationProcessingOptionsBuilder.build())
              .add(MODULE_NAME)
              .add(moduleName)
              .add(NO_STDLIB)
              .add(NO_REFLECT);

      extraParams
          .getJvmTarget()
          .ifPresent(
              target -> {
                extraArguments.add("-jvm-target");
                extraArguments.add(target);
              });

      extraArguments.addAll(extraParams.getExtraKotlincArguments());

      // minified classpath required fo so-abi
      ImmutableSortedSet<AbsPath> sourceOnlyAbiClasspath =
          ImmutableSortedSet.orderedBy(Comparator.comparing(AbsPath::getPath))
              .addAll(extraParams.getResolvedKotlinHomeLibraries())
              .build();

      // Use jvm-abi-gen for the kotlin part of class-abi, in order to get more accurate class-abi.
      // But since it is a kotlinc plugin, we produce it during library build.
      if (extraParams.shouldUseJvmAbiGen()
          && invokingRule.isLibraryJar()
          && parameters.getAbiGenerationMode() == AbiGenerationMode.CLASS
          && resolvedKosabiPluginOptionPath.containsKey(
              KosabiConfig.PROPERTY_KOSABI_JVM_ABI_GEN_PLUGIN)) {
        // Acquiring jvm-abi-gen plugin's path from KosabiConfig as we are using their fork of
        // jvm-abi-gen
        AbsPath jvmAbiPlugin =
            resolvedKosabiPluginOptionPath.get(KosabiConfig.PROPERTY_KOSABI_JVM_ABI_GEN_PLUGIN);
        extraArguments.add(X_PLUGIN_ARG + jvmAbiPlugin);
        extraArguments.add(PLUGIN);
        extraArguments.add(
            "plugin:com.facebook.jvm.abi.gen:outputDir="
                + rootPath.resolve(
                    JavaAbis.getGenPathForClassAbiPartFromLibraryTarget(
                        getGenPath(buckPaths, invokingRule))));
        extraArguments.add(PLUGIN);
        extraArguments.add("plugin:com.facebook.jvm.abi.gen:earlyTermination=false");
      }

      steps.add(
          new KotlincStep(
              invokingRule,
              outputDirectory.getPath(),
              sourceBuilderWithKspOutputs.build(),
              pathToSrcsList,
              allClasspaths,
              extraParams.getResolvedKotlinHomeLibraries(),
              reportsOutput,
              extraParams.getKotlinc(),
              extraArguments.build(),
              ImmutableList.of(VERBOSE),
              parameters.getOutputPaths(),
              withDownwardApi,
              parameters.shouldTrackClassUsage(),
              RelPath.get(filesystemParams.getConfiguredBuckOut().getPath()),
              cellToPathMappings,
              resolvedKosabiPluginOptionPath,
              sourceOnlyAbiClasspath,
              extraParams.shouldVerifySourceOnlyAbiConstraints()));

      steps.addAll(postKotlinCompilationSteps.build());
    }

    ResolvedJavacOptions resolvedJavacOptions = extraParams.getResolvedJavacOptions();
    if (hasKotlinSources
        && extraParams.getAnnotationProcessingTool() == AnnotationProcessingTool.KAPT) {
      // Only disable javac annotation processing if KAPT definitely ran.
      resolvedJavacOptions =
          resolvedJavacOptions.withJavaAnnotationProcessorParams(JavacPluginParams.EMPTY);
    }

    prepareJavaCompilationIfNeeded(
        invokingRule,
        rootPath,
        steps,
        filesystemParams,
        cellToPathMappings,
        compilerOutputPathsValue,
        parameters,
        buildableContext,
        resolvedJavac,
        resolvedJavacOptions,
        declaredClasspathEntries,
        extraParams.getExtraClassPaths(),
        outputDirectory,
        sourceBuilder);
  }

  /**
   * Creates the necessary steps, folders and parameters needed to run annotation processors using
   * KAPT.
   *
   * <p>This method will do nothing if there are no relevant annotation processors to run.
   */
  private void prepareKaptProcessorsIfNeeded(
      AnnotationProcessingTool annotationProcessingTool,
      BuildTargetValue invokingRule,
      AbsPath rootPath,
      Builder<IsolatedStep> steps,
      BuckPaths buckPaths,
      ImmutableSet<PathMatcher> ignoredPaths,
      Optional<String> jvmTarget,
      AbsPath resolvedKotlinStandardLibraryClassPath,
      AbsPath resolvedAnnotationProcessingClassPath,
      RelPath outputDirectory,
      ImmutableSortedSet.Builder<RelPath> sourceBuilder,
      RelPath sourcesOutput,
      RelPath classesOutput,
      RelPath reportsOutput,
      boolean shouldTrackClassUsage,
      boolean shouldGenerateAnnotationProcessingStats,
      Builder<String> annotationProcessingOptionsBuilder,
      Builder<IsolatedStep> postKotlinCompilationSteps,
      JavacPluginParams javaAnnotationProcessorParams) {

    // We don't need the Kapt processor to run for source-only-abi
    if (invokingRule.isSourceOnlyAbi()) {
      return;
    }

    if (!annotationProcessingTool.equals(AnnotationProcessingTool.KAPT)) {
      return;
    }

    // We need to generate the KAPT generation folder anyway, to help IntelliJ with red symbols.
    RelPath kaptAnnotationGenFolder = getKaptAnnotationGenPath(buckPaths, invokingRule);
    steps.addAll(MakeCleanDirectoryIsolatedStep.of(kaptAnnotationGenFolder));

    ImmutableList<ResolvedJavacPluginProperties> kaptAnnotationProcessors =
        getKaptAnnotationProcessors(getAnnotationProcessors(javaAnnotationProcessorParams));

    // No annotation processors for KAPT
    if (kaptAnnotationProcessors.isEmpty()) {
      return;
    }

    // KAPT folders
    RelPath stubsOutput = getAnnotationPath(buckPaths, invokingRule, "__%s_stubs__");
    RelPath kaptGeneratedOutput =
        getAnnotationPath(buckPaths, invokingRule, "__%s_kapt_generated__");
    RelPath kaptGenOutputFolder = getGenPath(buckPaths, invokingRule, "__%s_kapt_gen_sources__");
    RelPath kaptGenOutput =
        getGenPath(buckPaths, invokingRule, "__%s_kapt_gen_sources__/generated" + SRC_ZIP);

    // Creating KAPT dirs
    steps.addAll(MakeCleanDirectoryIsolatedStep.of(stubsOutput));
    steps.addAll(MakeCleanDirectoryIsolatedStep.of(kaptGeneratedOutput));
    steps.addAll(MakeCleanDirectoryIsolatedStep.of(kaptGenOutputFolder));

    ImmutableList<String> kaptProcessorsArg =
        ImmutableList.copyOf(
            kaptAnnotationProcessors.stream()
                .map(ResolvedJavacPluginProperties::getProcessorNames)
                .flatMap(Set::stream)
                .map(name -> AP_PROCESSORS_ARG + name)
                .collect(Collectors.toList()));

    ImmutableList<String> kaptPluginsClasspath =
        ImmutableList.copyOf(
            kaptAnnotationProcessors.stream()
                .map(p -> p.toUrlClasspath(rootPath))
                .flatMap(List::stream)
                .map(url -> AP_CLASSPATH_ARG + urlToFile(url))
                .collect(Collectors.toList()));

    ImmutableMap.Builder<String, String> apOptions = new ImmutableMap.Builder<>();

    ImmutableSortedSet<String> javacAnnotationProcessorParams =
        javaAnnotationProcessorParams.getParameters();
    for (String param : javacAnnotationProcessorParams) {
      String[] splitParam = param.split("=");
      Preconditions.checkState(splitParam.length == 2);
      apOptions.put(splitParam[0], splitParam[1]);
    }

    Path annotationProcessorsStatsFilePath = reportsOutput.getPath().resolve(AP_STATS_REPORT_FILE);

    Builder<String> kaptPluginOptionsBuilder =
        ImmutableList.<String>builder()
            .add(AP_CLASSPATH_ARG + resolvedAnnotationProcessingClassPath.getPath())
            .add(AP_CLASSPATH_ARG + resolvedKotlinStandardLibraryClassPath.getPath())
            .addAll(kaptPluginsClasspath)
            .addAll(kaptProcessorsArg)
            .add(SOURCES_ARG + rootPath.resolve(sourcesOutput))
            .add(CLASSES_ARG + rootPath.resolve(classesOutput))
            .add(STUBS_ARG + rootPath.resolve(stubsOutput))
            .add(
                AP_OPTIONS
                    + encodeKaptApOptions(
                        apOptions.build(), rootPath.resolve(kaptGeneratedOutput).toString()))
            .add(JAVAC_ARG + encodeOptions(getJavacArguments(jvmTarget)))
            .add(LIGHT_ANALYSIS + "true") // TODO: Provide value as argument
            .add(CORRECT_ERROR_TYPES + "true");

    if (shouldGenerateAnnotationProcessingStats) {
      kaptPluginOptionsBuilder.add(AP_STATS_REPORT_ARG + annotationProcessorsStatsFilePath);
    }

    if (shouldTrackClassUsage) {
      kaptPluginOptionsBuilder.add(
          AP_FILE_ACCESS_HIST_REPORT_ARG + rootPath.resolve(getKAPTDepFilePath(reportsOutput)));
    }

    annotationProcessingOptionsBuilder
        .add(X_PLUGIN_ARG + resolvedAnnotationProcessingClassPath)
        .add(PLUGIN)
        .add(
            KAPT3_PLUGIN
                + APT_MODE
                + "compile,"
                + Joiner.on(",").join(kaptPluginOptionsBuilder.build()));

    postKotlinCompilationSteps.add(
        CopyIsolatedStep.forDirectory(
            sourcesOutput, kaptAnnotationGenFolder, CopySourceMode.DIRECTORY_CONTENTS_ONLY));
    postKotlinCompilationSteps.add(
        CopyIsolatedStep.forDirectory(
            classesOutput, kaptAnnotationGenFolder, CopySourceMode.DIRECTORY_CONTENTS_ONLY));
    postKotlinCompilationSteps.add(
        CopyIsolatedStep.forDirectory(
            kaptGeneratedOutput, kaptAnnotationGenFolder, CopySourceMode.DIRECTORY_CONTENTS_ONLY));

    if (shouldGenerateAnnotationProcessingStats) {
      postKotlinCompilationSteps.add(
          new KaptStatsReportParseStep(annotationProcessorsStatsFilePath, invokingRule));
    }

    postKotlinCompilationSteps.add(
        ZipIsolatedStep.of(
            rootPath,
            kaptGenOutput.getPath(),
            ignoredPaths,
            ImmutableSet.of(),
            false,
            ZipCompressionLevel.DEFAULT,
            kaptAnnotationGenFolder.getPath()));

    // Generated classes should be part of the output. This way generated files
    // such as META-INF dirs will also be added to the final jar.
    postKotlinCompilationSteps.add(
        CopyIsolatedStep.forDirectory(
            classesOutput.getPath(),
            outputDirectory.getPath(),
            CopySourceMode.DIRECTORY_CONTENTS_ONLY));

    sourceBuilder.add(kaptGenOutput);
  }

  /** Initialize all the folders, steps and parameters needed to run KSP plugins for this rule. */
  private void prepareKspProcessorsIfNeeded(
      Kotlinc kotlinc,
      AnnotationProcessingTool annotationProcessingTool,
      BuildTargetValue invokingRule,
      AbsPath rootPath,
      Builder<IsolatedStep> steps,
      BuckPaths buckPaths,
      ImmutableSet<PathMatcher> ignoredPaths,
      RelPath outputDirectory,
      ImmutableSortedSet.Builder<RelPath> sourceBuilder,
      RelPath sourcesOutput,
      RelPath classesOutput,
      RelPath reportsOutput,
      boolean shouldTrackClassUsage,
      Builder<IsolatedStep> postKotlinCompilationSteps,
      ImmutableSortedSet<AbsPath> allClasspaths,
      ImmutableSortedSet<AbsPath> kotlinHomeLibraries,
      ImmutableMap<AbsPath, ImmutableMap<String, String>> resolvedKotlinCompilerPlugins,
      ImmutableMap<String, AbsPath> resolvedKosabiPluginOptionPath,
      String kotlinPluginGeneratedOutFullPath,
      ForwardRelPath projectBaseDir,
      ImmutableSortedSet<RelPath> sourceFilePaths,
      Path pathToSrcsList,
      CompilerOutputPaths compilerOutputPaths,
      RelPath configuredBuckOut,
      ImmutableMap<CanonicalCellName, RelPath> cellToPathMappings,
      JavacPluginParams annotationProcessorParams,
      ImmutableSortedSet.Builder<RelPath> sourceBuilderWithKspOutputs) {

    // Kosabi doesn't support the ksp processors now
    if (invokingRule.isSourceOnlyAbi()) {
      return;
    }

    // The other option is to use JAVAC, and we don't want to use KSP in that case.
    if (!annotationProcessingTool.equals(AnnotationProcessingTool.KAPT)) {
      return;
    }

    // We need to generate the KSP generation folder anyway, to help IntelliJ with red symbols.
    RelPath kspAnnotationGenFolder = getKspAnnotationGenPath(buckPaths, invokingRule);
    steps.addAll(MakeCleanDirectoryIsolatedStep.of(kspAnnotationGenFolder));

    ImmutableList<ResolvedJavacPluginProperties> kspAnnotationProcessors =
        getKspAnnotationProcessors(getAnnotationProcessors(annotationProcessorParams));

    if (kspAnnotationProcessors.isEmpty()) {
      return;
    }

    // KSP folders
    RelPath kspKotlinOutput =
        getAnnotationPath(buckPaths, invokingRule, "__%s_ksp_generated_kotlin__");
    RelPath kspJavaOutput = getAnnotationPath(buckPaths, invokingRule, "__%s_ksp_generated_java__");
    RelPath kspGenOutputFolder = getGenPath(buckPaths, invokingRule, "__%s_ksp_gen_sources__");
    RelPath kspGenOutput =
        getGenPath(buckPaths, invokingRule, "__%s_ksp_gen_sources__/generated" + SRC_ZIP);

    // More KSP folders
    RelPath kspResOutput = getAnnotationPath(buckPaths, invokingRule, "__%s_ksp_res_output__");
    RelPath kspCachesOutput = getAnnotationPath(buckPaths, invokingRule, "__%s_ksp_cache_output__");
    RelPath kspOutput = getAnnotationPath(buckPaths, invokingRule, "__%s_ksp_meta_output__");

    // Creating KSP dirs
    steps.addAll(MakeCleanDirectoryIsolatedStep.of(kspKotlinOutput));
    steps.addAll(MakeCleanDirectoryIsolatedStep.of(kspJavaOutput));
    steps.addAll(MakeCleanDirectoryIsolatedStep.of(kspGenOutputFolder));

    steps.addAll(MakeCleanDirectoryIsolatedStep.of(kspResOutput));
    steps.addAll(MakeCleanDirectoryIsolatedStep.of(kspCachesOutput));
    steps.addAll(MakeCleanDirectoryIsolatedStep.of(kspOutput));

    ImmutableList<String> kspProcessorsClasspathList =
        kspAnnotationProcessors.stream()
            .map(p -> p.toUrlClasspath(rootPath))
            .flatMap(List::stream)
            .map(DaemonKotlincToJarStepFactory::urlToFile)
            .collect(ImmutableList.toImmutableList());
    String kspProcessorsClasspath =
        Joiner.on(File.pathSeparatorChar).join(kspProcessorsClasspathList);

    final String KSP_PLUGIN_ID = "plugin:com.google.devtools.ksp.symbol-processing:";

    Builder<String> kspPluginOptionsBuilder = ImmutableList.builder();
    kspPluginOptionsBuilder
        .add(KSP_PLUGIN_ID + "apclasspath=" + kspProcessorsClasspath)
        .add(KSP_PLUGIN_ID + "projectBaseDir=" + rootPath.resolve(projectBaseDir))
        .add(KSP_PLUGIN_ID + "classOutputDir=" + rootPath.resolve(classesOutput))
        .add(KSP_PLUGIN_ID + "kotlinOutputDir=" + rootPath.resolve(kspKotlinOutput))
        .add(KSP_PLUGIN_ID + "javaOutputDir=" + rootPath.resolve(kspJavaOutput))
        .add(KSP_PLUGIN_ID + "resourceOutputDir=" + rootPath.resolve(classesOutput))
        .add(KSP_PLUGIN_ID + "cachesDir=" + rootPath.resolve(kspCachesOutput))
        .add(KSP_PLUGIN_ID + "kspOutputDir=" + rootPath.resolve(kspOutput));

    // KSP needs the full classpath in order to resolve resources
    String allClasspath =
        Joiner.on(File.pathSeparator)
            .join(transform(allClasspaths, path -> path.getPath().toString()));
    allClasspath = allClasspath.replace(',', '-');
    kspPluginOptionsBuilder.add(KSP_PLUGIN_ID + "apoption=" + "cp=" + allClasspath);

    if (shouldTrackClassUsage) {
      kspPluginOptionsBuilder.add(
          KSP_PLUGIN_ID
              + "apoption="
              + "fileAccessHistoryReportFile="
              + rootPath.resolve(getKspDepFilePath(reportsOutput)));
    }

    Builder<String> kspTriggerBuilder = ImmutableList.builder();
    kspTriggerBuilder
        .addAll(getKspPluginsArgs(resolvedKotlinCompilerPlugins, kotlinPluginGeneratedOutFullPath))
        .add(PLUGIN, Joiner.on(",").join(kspPluginOptionsBuilder.build()));

    // Triggering only the KSP plugin.
    // Currently, the KSP plugin needs its own kotlinc invocation, this will be changed in the
    // future.
    steps.add(
        new KotlincStep(
            invokingRule,
            outputDirectory.getPath(),
            sourceFilePaths,
            pathToSrcsList,
            allClasspaths,
            kotlinHomeLibraries,
            reportsOutput,
            kotlinc,
            kspTriggerBuilder.build(),
            ImmutableList.of(VERBOSE),
            compilerOutputPaths,
            withDownwardApi,
            false,
            configuredBuckOut,
            cellToPathMappings,
            resolvedKosabiPluginOptionPath,
            ImmutableSortedSet.of(),
            false));

    steps.add(
        CopyIsolatedStep.forDirectory(
            kspKotlinOutput, kspAnnotationGenFolder, CopySourceMode.DIRECTORY_CONTENTS_ONLY));
    steps.add(
        CopyIsolatedStep.forDirectory(
            kspJavaOutput, kspAnnotationGenFolder, CopySourceMode.DIRECTORY_CONTENTS_ONLY));
    steps.add(
        CopyIsolatedStep.forDirectory(
            sourcesOutput, kspAnnotationGenFolder, CopySourceMode.DIRECTORY_CONTENTS_ONLY));
    steps.add(
        CopyIsolatedStep.forDirectory(
            classesOutput, kspAnnotationGenFolder, CopySourceMode.DIRECTORY_CONTENTS_ONLY));

    steps.add(
        ZipIsolatedStep.of(
            rootPath,
            kspGenOutput.getPath(),
            ignoredPaths,
            ImmutableSet.of(),
            false,
            ZipCompressionLevel.DEFAULT,
            kspAnnotationGenFolder.getPath()));

    // Generated classes should be part of the output. This way generated files
    // such as META-INF dirs will also be added to the final jar.
    postKotlinCompilationSteps.add(
        CopyIsolatedStep.forDirectory(
            classesOutput.getPath(),
            outputDirectory.getPath(),
            CopySourceMode.DIRECTORY_CONTENTS_ONLY));

    sourceBuilder.add(kspGenOutput);

    // We need another source builder to hold KSP generated sources.
    // This won't be necessary after merging the KSP compilation into the main kotlinc step.
    sourceBuilderWithKspOutputs.add(kspGenOutput);
  }

  /**
   * Prepares the Java compilation step for any Java files left to compile in this rule. This also
   * compiles any Java files generated from annotation processors using KAPT and KSP.
   */
  private void prepareJavaCompilationIfNeeded(
      BuildTargetValue invokingRule,
      AbsPath rootPath,
      Builder<IsolatedStep> steps,
      FilesystemParams filesystemParams,
      ImmutableMap<CanonicalCellName, RelPath> cellToPathMappings,
      CompilerOutputPathsValue compilerOutputPathsValue,
      CompilerParameters parameters,
      BuildableContext buildableContext,
      ResolvedJavac resolvedJavac,
      ResolvedJavacOptions resolvedJavacOptions,
      ImmutableSortedSet<RelPath> declaredClasspathEntries,
      ImmutableList<AbsPath> extraClassPaths,
      RelPath outputDirectory,
      ImmutableSortedSet.Builder<RelPath> sourceBuilder) {

    // Kotlin source-only-abi is only available for pure-kotlin targets
    // It's not applicable for:
    // - Java targets
    // - Mixed targets
    //
    // Buck doesn't check if it runs source-only-abi for non-pure-kotlin targets,
    // source-only-abi applicability for target should be verified externally.
    //
    // source-only-abi.jar packing happens via [KotlincToJarStepFactory::createCompileToJarStepImpl]
    if (invokingRule.isSourceOnlyAbi()) {
      return;
    }

    // Note that this filters out only .kt files, so this keeps both .java and .src.zip files.
    ImmutableSortedSet<RelPath> javaSourceFiles =
        sourceBuilder.build().stream()
            .filter(input -> !KOTLIN_PATH_MATCHER.matches(input))
            .collect(ImmutableSortedSet.toImmutableSortedSet(RelPath.comparator()));

    CompilerParameters javacParameters =
        CompilerParameters.builder()
            .from(parameters)
            .setClasspathEntries(
                ImmutableSortedSet.orderedBy(RelPath.comparator())
                    .add(outputDirectory)
                    .addAll(RichStream.from(extraClassPaths).map(rootPath::relativize).iterator())
                    .addAll(declaredClasspathEntries)
                    .build())
            .setSourceFilePaths(javaSourceFiles)
            .build();

    // Indicate no annotation processing required from this factory.  It is already handled by the
    // Kotlin factory, when it resolves javac's options.
    BaseJavacToJarStepFactory javacToJarStepFactory =
        new BaseJavacToJarStepFactory(false, withDownwardApi);

    javacToJarStepFactory.createCompileStep(
        filesystemParams,
        cellToPathMappings,
        invokingRule,
        compilerOutputPathsValue,
        javacParameters,
        steps,
        buildableContext,
        resolvedJavac,
        JavaExtraParams.of(resolvedJavacOptions));
  }

  @Override
  protected void recordDepFileIfNecessary(
      CompilerOutputPathsValue compilerOutputPathsValue,
      BuildTargetValue buildTargetValue,
      CompilerParameters compilerParameters,
      BuildableContext buildableContext) {
    super.recordDepFileIfNecessary(
        compilerOutputPathsValue, buildTargetValue, compilerParameters, buildableContext);
    if (compilerParameters.shouldTrackClassUsage()) {
      CompilerOutputPaths outputPath =
          compilerOutputPathsValue.getByType(buildTargetValue.getType());
      RelPath depFilePath =
          CompilerOutputPaths.getKotlinDepFilePath(outputPath.getOutputJarDirPath());
      buildableContext.recordArtifact(depFilePath.getPath());
    }
  }

  @Override
  protected void createCompileToJarStepImpl(
      FilesystemParams filesystemParams,
      ImmutableMap<CanonicalCellName, RelPath> cellToPathMappings,
      BuildTargetValue target,
      CompilerOutputPathsValue compilerOutputPathsValue,
      CompilerParameters compilerParameters,
      @Nullable JarParameters abiJarParameters,
      @Nullable JarParameters libraryJarParameters,
      Builder<IsolatedStep> steps,
      BuildableContext buildableContext,
      ResolvedJavac resolvedJavac,
      KotlinExtraParams extraParams) {
    // abiJarParameters present in Kotlin source-only-abi
    // If we don't have abiJarParameters it's OK to delegate execution to the super class
    if (abiJarParameters == null) {
      super.createCompileToJarStepImpl(
          filesystemParams,
          cellToPathMappings,
          target,
          compilerOutputPathsValue,
          compilerParameters,
          null,
          libraryJarParameters,
          steps,
          buildableContext,
          resolvedJavac,
          extraParams);
    } else {
      // We need to process JarDirectoryStep differently if we have abiJarParameters
      // b/c of different paths
      createCompileStep(
          filesystemParams,
          cellToPathMappings,
          target,
          compilerOutputPathsValue,
          compilerParameters,
          steps,
          buildableContext,
          resolvedJavac,
          extraParams);

      steps.add(new JarDirectoryStep(abiJarParameters));
    }
  }

  /**
   * Safely converts a URL to a File path. Use this instead of {@link URL#getFile} to ensure that
   * htmlencoded literals are not present in the file path.
   */
  private static String urlToFile(URL url) {
    try {
      return Paths.get(url.toURI()).toFile().getPath();
    } catch (URISyntaxException e) {
      // In case of error, fall back to the original implementation.
      return url.getFile();
    }
  }

  @Override
  public ImmutableList<RelPath> getDepFilePaths(
      ProjectFilesystem filesystem, BuildTarget buildTarget) {
    BuckPaths buckPaths = filesystem.getBuckPaths();
    RelPath outputPath = CompilerOutputPaths.of(buildTarget, buckPaths).getOutputJarDirPath();

    // Java dependencies file path is needed for Kotlin modules
    // because some Java code can be generated during the build.
    return ImmutableList.of(
        CompilerOutputPaths.getJavaDepFilePath(outputPath),
        CompilerOutputPaths.getKotlinDepFilePath(outputPath));
  }

  private String encodeKaptApOptions(Map<String, String> kaptApOptions, String kaptGeneratedPath) {
    Map<String, String> kaptApOptionsToEncode = new HashMap<>();
    kaptApOptionsToEncode.put(KAPT_GENERATED, kaptGeneratedPath);
    kaptApOptionsToEncode.putAll(kaptApOptions);

    return encodeOptions(kaptApOptionsToEncode);
  }

  private String encodeOptions(Map<String, String> options) {
    try (ByteArrayOutputStream os = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(os)) {

      oos.writeInt(options.size());
      for (Map.Entry<String, String> entry : options.entrySet()) {
        oos.writeUTF(entry.getKey());
        oos.writeUTF(entry.getValue());
      }

      oos.flush();
      return Base64.getEncoder().encodeToString(os.toByteArray());
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private String getFriendsPath(ImmutableCollection<AbsPath> friendPathsSourcePaths) {
    if (friendPathsSourcePaths.isEmpty()) {
      return "";
    }

    // https://youtrack.jetbrains.com/issue/KT-29933
    ImmutableSortedSet<String> absoluteFriendPaths =
        friendPathsSourcePaths.stream()
            .map(AbsPath::toString)
            .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural()));

    return "-Xfriend-paths="
        + absoluteFriendPaths.stream().reduce("", (path1, path2) -> path1 + "," + path2);
  }

  private ImmutableList<String> getKspPluginsArgs(
      ImmutableMap<AbsPath, ImmutableMap<String, String>> resolvedKotlinCompilerPlugins,
      String outputDir) {
    return getKotlinCompilerPluginsArgs(
        resolvedKotlinCompilerPlugins, outputDir, DaemonKotlincToJarStepFactory::isKspPlugin);
  }

  private ImmutableList<String> getKotlinCompilerPluginsArgs(
      ImmutableMap<AbsPath, ImmutableMap<String, String>> resolvedKotlinCompilerPlugins,
      String outputDir,
      Predicate<String> filter) {
    Builder<String> pluginArgs = ImmutableList.builder();
    for (Map.Entry<AbsPath, ImmutableMap<String, String>> entry :
        resolvedKotlinCompilerPlugins.entrySet()) {
      AbsPath pluginPath = entry.getKey();
      ImmutableMap<String, String> pluginOptions = entry.getValue();

      if (!filter.test(pluginPath.toString())) {
        continue;
      }
      // Add plugin basic string, e.g. "-Xplugins=<pluginPath>"
      pluginArgs.add(X_PLUGIN_ARG + pluginPath);

      // If plugin options exist, add plugin option string,
      // e.g. "-P" and "<optionKey>=<optionValue>,<optionKey2>=<optionValue2>,..."
      if (pluginOptions != null && !pluginOptions.isEmpty()) {
        Builder<String> pluginOptionStrings = ImmutableList.builder();

        for (String pluginOptionKey : pluginOptions.keySet()) {
          String pluginOptionValue = pluginOptions.get(pluginOptionKey);

          if (pluginOptionValue.equals(KOTLIN_PLUGIN_OUT_PLACEHOLDER)) {
            pluginOptionValue = outputDir;
          }

          pluginOptionStrings.add(pluginOptionKey + "=" + pluginOptionValue);
        }
        String pluginOptionString = Joiner.on(",").join(pluginOptionStrings.build());
        pluginArgs.add(PLUGIN).add(pluginOptionString);
      }
    }
    return pluginArgs.build();
  }

  private String getModuleName(BuildTargetValue invokingRule) {
    BuildTargetValueExtraParams extraParams = getBuildTargetValueExtraParams(invokingRule);
    return extraParams.getCellRelativeBasePath().toString().replace('/', '.')
        + "."
        + extraParams.getShortName();
  }

  private Map<String, String> getJavacArguments(Optional<String> jvmTarget) {
    Map<String, String> arguments = new HashMap<>();
    jvmTarget.ifPresent(
        target -> {
          arguments.put("-source", target);
          arguments.put("-target", target);
        });
    return arguments;
  }

  private static boolean isKspPlugin(String sourcePath) {
    return sourcePath.contains("symbol-processing");
  }

  private static boolean isNotKspPlugin(String sourcePath) {
    return !isKspPlugin(sourcePath);
  }

  static ImmutableList<ResolvedJavacPluginProperties> getAnnotationProcessors(
      JavacPluginParams annotationProcessorParams) {
    return annotationProcessorParams.isEmpty()
        ? ImmutableList.of()
        : annotationProcessorParams.getPluginProperties().asList();
  }

  static ImmutableList<ResolvedJavacPluginProperties> getKaptAnnotationProcessors(
      ImmutableList<ResolvedJavacPluginProperties> annotationProcessors) {
    return annotationProcessors.stream()
        .filter(prop -> !isKSPProcessor(prop))
        .collect(ImmutableList.toImmutableList());
  }

  static ImmutableList<ResolvedJavacPluginProperties> getKspAnnotationProcessors(
      ImmutableList<ResolvedJavacPluginProperties> annotationProcessors) {
    return annotationProcessors.stream()
        .filter(DaemonKotlincToJarStepFactory::isKSPProcessor)
        .collect(ImmutableList.toImmutableList());
  }

  /**
   * KSP Annotation processors are defined like Java's, but their name starts with
   * KSP_PROCESSOR_NAME_PREFIX
   */
  private static boolean isKSPProcessor(ResolvedJavacPluginProperties prop) {
    return !prop.getProcessorNames().isEmpty()
        && Iterables.getFirst(prop.getProcessorNames(), "").startsWith(KSP_PROCESSOR_NAME_PREFIX);
  }

  public static RelPath getKaptAnnotationGenPath(BuckPaths buckPaths, BuildTarget buildTarget) {
    return getKaptAnnotationGenPath(
        buckPaths, BuildTargetValue.withExtraParams(buildTarget, buckPaths));
  }

  public static RelPath getKspAnnotationGenPath(BuckPaths buckPaths, BuildTarget buildTarget) {
    return getKspAnnotationGenPath(
        buckPaths, BuildTargetValue.withExtraParams(buildTarget, buckPaths));
  }

  private static RelPath getKaptAnnotationGenPath(
      BuckPaths buckPaths, BuildTargetValue buildTargetValue) {
    return getGenPath(buckPaths, buildTargetValue).resolveRel("__kapt_generated__");
  }

  private static RelPath getKspAnnotationGenPath(
      BuckPaths buckPaths, BuildTargetValue buildTargetValue) {
    return getGenPath(buckPaths, buildTargetValue).resolveRel("__ksp_generated__");
  }

  /** Returns annotation path for the given {@code target} and {@code format} */
  public static RelPath getAnnotationPath(
      BuckPaths buckPaths, BuildTargetValue target, String format) {
    checkArgument(!format.startsWith("/"), "format string should not start with a slash");
    return getRelativePath(target, format, buckPaths.getAnnotationDir());
  }

  /** Returns `gen` directory path for the given {@code target} */
  private static RelPath getGenPath(BuckPaths buckPaths, BuildTargetValue buildTargetValue) {
    BuildTargetValueExtraParams extraParams = getBuildTargetValueExtraParams(buildTargetValue);
    String format = extraParams.isFlavored() ? "%s" : "%s__";
    return getGenPath(buckPaths, buildTargetValue, format);
  }

  /** Returns `gen` directory path for the given {@code target} and {@code format} */
  public static RelPath getGenPath(BuckPaths buckPaths, BuildTargetValue target, String format) {
    checkArgument(!format.startsWith("/"), "format string should not start with a slash");
    return getRelativePath(target, format, buckPaths.getGenDir());
  }

  /** Returns `gen` directory path for the given {@code target} and {@code format} */
  public static RelPath getScratchPath(
      BuckPaths buckPaths, BuildTargetValue target, String format) {
    checkArgument(!format.startsWith("/"), "format string should not start with a slash");
    return getRelativePath(target, format, buckPaths.getScratchDir());
  }

  private static RelPath getRelativePath(
      BuildTargetValue target, String format, RelPath directory) {
    return directory.resolve(getBasePath(target, format));
  }

  private static ForwardRelPath getBasePath(BuildTargetValue target, String format) {
    checkArgument(!format.startsWith("/"), "format string should not start with a slash");
    BuildTargetValueExtraParams extraParams = getBuildTargetValueExtraParams(target);
    return extraParams
        .getBasePathForBaseName()
        .resolve(
            BuildTargetPaths.formatLastSegment(format, extraParams.getShortNameAndFlavorPostfix()));
  }

  private static BuildTargetValueExtraParams getBuildTargetValueExtraParams(
      BuildTargetValue invokingRule) {
    return invokingRule
        .getExtraParams()
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "Kotlin compilation to jar factory has to have build target extra params"));
  }
}
