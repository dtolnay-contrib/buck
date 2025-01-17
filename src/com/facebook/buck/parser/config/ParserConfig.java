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

package com.facebook.buck.parser.config;

import com.facebook.buck.command.config.BuildBuckConfig;
import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.ConfigView;
import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.FileName;
import com.facebook.buck.core.filesystems.ForwardRelPath;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.rules.analysis.config.RuleAnalysisComputationMode;
import com.facebook.buck.core.rules.analysis.config.RuleAnalysisConfig;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.parser.exceptions.MissingBuildFileException;
import com.facebook.buck.parser.implicit.ImplicitInclude;
import com.facebook.buck.parser.implicit.ImplicitIncludePath;
import com.facebook.buck.parser.options.ImplicitNativeRulesState;
import com.facebook.buck.parser.options.UserDefinedRulesState;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Streams;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;
import org.immutables.value.Value;

@BuckStyleValue
public abstract class ParserConfig implements ConfigView<BuckConfig> {

  public static final boolean DEFAULT_ALLOW_EMPTY_GLOBS = true;
  public static final FileName DEFAULT_BUILD_FILE_NAME = FileName.of("BUCK");
  public static final String BUILDFILE_SECTION_NAME = "buildfile";
  public static final String INCLUDES_PROPERTY_NAME = "includes";
  public static final String PACKAGE_INCLUDES_PROPERTY_NAME = "package_includes";

  private static final long NUM_PARSING_THREADS_DEFAULT = 1L;
  private static final int TARGET_PARSER_THRESHOLD = 100_000;

  private static final int DEFAULT_WATCHMAN_CLOCK_SYNC_TIMEOUT_MS =
      (int) TimeUnit.SECONDS.toMillis(60);

  private static final long DEFAULT_WATCHMAN_WARN_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(3);
  private static final long DEFAULT_WATCHMAN_QUERY_POLL_TIMEOUT_NANOS =
      TimeUnit.SECONDS.toNanos(20);

  public enum GlobHandler {
    PYTHON,
    WATCHMAN
  }

  /** Glob handler supported by Skylark parser. */
  public enum SkylarkGlobHandler {
    JAVA,
    WATCHMAN,
    EDEN_THRIFT,
    /**
     * Like {@link #EDEN_THRIFT}, but crash if eden is not available. Can be used to test that eden
     * is actually picked.
     */
    EDEN_THRIFT_NO_FALLBACK,
    ;

    /** This option has eden. */
    public boolean hasEden() {
      switch (this) {
        case EDEN_THRIFT:
        case EDEN_THRIFT_NO_FALLBACK:
          return true;
          // $CASES-OMITTED$
        default:
          return false;
      }
    }
  }

  public enum WatchmanGlobSanityCheck {
    NONE,
    STAT
  }

  public enum AllowSymlinks {
    ALLOW,
    WARN,
    FORBID
  }

  public enum BuildFileSearchMethod {
    FILESYSTEM_CRAWL,
    WATCHMAN
  }

  /** Control how to check the existence of paths */
  public enum PathsCheckMethod {
    FILESYSTEM,
    WATCHMAN,
    NONE
  }

  /** Control how to check the package boundary */
  public enum PackageBoundaryCheckMethod {
    FILESYSTEM,
    WATCHMAN
  }

  /** Controls whether default flavors should be applied to unflavored targets. */
  public enum ApplyDefaultFlavorsMode {
    DISABLED,
    SINGLE,
    ALL
  }

  @Override
  public abstract BuckConfig getDelegate();

  public static ParserConfig of(BuckConfig delegate) {
    return ImmutableParserConfig.ofImpl(delegate);
  }

  @Value.Lazy
  public boolean getAllowEmptyGlobs() {
    return getDelegate()
        .getValue("build", "allow_empty_globs")
        .map(Boolean::parseBoolean)
        .orElse(DEFAULT_ALLOW_EMPTY_GLOBS);
  }

  @Value.Lazy
  public FileName getBuildFileName() {
    return getDelegate()
        .getValue(BUILDFILE_SECTION_NAME, "name")
        .map(FileName::of)
        .orElse(DEFAULT_BUILD_FILE_NAME);
  }

  /**
   * A (possibly empty) sequence of paths to files that should be included by default when
   * evaluating a build file.
   */
  @Value.Lazy
  public ImmutableList<ImplicitIncludePath> getDefaultIncludes() {
    ImmutableMap<String, String> entries =
        getDelegate().getEntriesForSection(BUILDFILE_SECTION_NAME);
    String includes = Strings.nullToEmpty(entries.get(INCLUDES_PROPERTY_NAME));
    return Streams.stream(Splitter.on(' ').trimResults().omitEmptyStrings().split(includes))
        .map(ImplicitIncludePath::parse)
        .collect(ImmutableList.toImmutableList());
  }

  @Value.Lazy
  public ImmutableMap<String, ImplicitInclude> getPackageImplicitIncludes() {
    return getDelegate().getMap(BUILDFILE_SECTION_NAME, PACKAGE_INCLUDES_PROPERTY_NAME).entrySet()
        .stream()
        .collect(
            ImmutableMap.toImmutableMap(
                Map.Entry::getKey, e -> ImplicitInclude.fromConfigurationString(e.getValue())));
  }

  @Value.Lazy
  public boolean getEnforceBuckPackageBoundary() {
    return getDelegate().getBooleanValue("project", "check_package_boundary", true);
  }

  @Value.Lazy
  public boolean getEnforceCellBoundary() {
    return getDelegate().getBooleanValue("project", "check_cell_boundary", false);
  }

  @Value.Lazy
  public PackageBoundaryCheckMethod getPackageBoundaryCheckMethod() {
    return getDelegate()
        .getEnum("project", "package_boundary_check_method", PackageBoundaryCheckMethod.class)
        .orElse(PackageBoundaryCheckMethod.FILESYSTEM);
  }

  /** A list of absolute paths under which buck package boundary checks should not be performed. */
  @Value.Lazy
  public ImmutableList<Path> getBuckPackageBoundaryExceptions() {
    return getDelegate()
        .getOptionalPathList("project", "package_boundary_exceptions", true)
        .orElse(ImmutableList.of());
  }

  @Value.Lazy
  public Optional<ImmutableList<Path>> getReadOnlyPaths() {
    return getDelegate().getOptionalPathList("project", "read_only_paths", false);
  }

  @Value.Lazy
  public AllowSymlinks getAllowSymlinks() {
    return getDelegate()
        .getEnum("project", "allow_symlinks", AllowSymlinks.class)
        .orElse(AllowSymlinks.FORBID);
  }

  @Value.Lazy
  public BuildFileSearchMethod getBuildFileSearchMethod() {
    return getDelegate()
        .getEnum("project", "build_file_search_method", BuildFileSearchMethod.class)
        .orElse(BuildFileSearchMethod.FILESYSTEM_CRAWL);
  }

  @Value.Lazy
  public PathsCheckMethod getPathsCheckMethod() {
    return getDelegate()
        .getEnum("project", "paths_check_method", PathsCheckMethod.class)
        .orElse(PathsCheckMethod.FILESYSTEM);
  }

  @Value.Lazy
  public GlobHandler getGlobHandler() {
    return getDelegate()
        .getEnum("project", "glob_handler", GlobHandler.class)
        .orElse(GlobHandler.PYTHON);
  }

  @Value.Lazy
  public WatchmanGlobSanityCheck getWatchmanGlobSanityCheck() {
    return getDelegate()
        .getEnum("project", "watchman_glob_sanity_check", WatchmanGlobSanityCheck.class)
        .orElse(WatchmanGlobSanityCheck.STAT);
  }

  @Value.Lazy
  public Optional<Long> getWatchmanQueryTimeoutMs() {
    return getDelegate().getLong("project", "watchman_query_timeout_ms");
  }

  @Value.Lazy
  public int getWatchmanSyncTimeoutMs() {
    OptionalInt syncTimeout = getDelegate().getInteger("project", "watchman_sync_timeout_ms");
    return syncTimeout.isPresent()
        ? syncTimeout.getAsInt()
        : DEFAULT_WATCHMAN_CLOCK_SYNC_TIMEOUT_MS;
  }

  /** Watchman query poll timeout in nanos. */
  @Value.Lazy
  public long getWatchmanQueryPollTimeoutNanos() {
    Optional<Long> queryPollTimeout =
        getDelegate().getLong("project", "watchman_query_poll_timeout_ms");
    return queryPollTimeout
        .map(TimeUnit.MILLISECONDS::toNanos)
        .orElse(DEFAULT_WATCHMAN_QUERY_POLL_TIMEOUT_NANOS);
  }

  /** Watchman query warn timeout in nanos. */
  @Value.Lazy
  public long getWatchmanQueryWarnTimeoutNanos() {
    Optional<Long> warnTimeoutMs =
        getDelegate().getLong("project", "watchman_query_warn_timeout_ms");
    return warnTimeoutMs
        .map(TimeUnit.MILLISECONDS::toNanos)
        .orElse(DEFAULT_WATCHMAN_WARN_TIMEOUT_NANOS);
  }

  @Value.Lazy
  public boolean getWatchCells() {
    return getDelegate().getBooleanValue("project", "watch_cells", true);
  }

  @Value.Lazy
  public boolean getEnableParallelParsing() {
    return getDelegate().getBooleanValue("project", "parallel_parsing", true);
  }

  @Value.Lazy
  public int getNumParsingThreads() {
    if (!getEnableParallelParsing()) {
      return 1;
    }

    int value =
        getDelegate()
            .getLong("project", "parsing_threads")
            .orElse(NUM_PARSING_THREADS_DEFAULT)
            .intValue();

    return Math.min(value, getDelegate().getView(BuildBuckConfig.class).getNumThreads());
  }

  @Value.Lazy
  public ApplyDefaultFlavorsMode getDefaultFlavorsMode() {
    return getDelegate()
        .getEnum("project", "default_flavors_mode", ApplyDefaultFlavorsMode.class)
        .orElse(ApplyDefaultFlavorsMode.SINGLE);
  }

  @Value.Lazy
  public Optional<String> getParserPythonInterpreterPath() {
    return getDelegate().getValue("parser", "python_interpreter");
  }

  /**
   * Returns the module search path PYTHONPATH to set for the parser, as specified by the
   * 'python_path' key of the 'parser' section.
   *
   * @return The PYTHONPATH value or an empty string if not set.
   */
  @Value.Lazy
  public Optional<String> getPythonModuleSearchPath() {
    return getDelegate().getValue("parser", "python_path");
  }

  /**
   * @return whether native build rules are available for users in build files. If not, they are
   *     only accessible in extension files under the 'native' object
   */
  @Value.Lazy
  public ImplicitNativeRulesState getImplicitNativeRulesState() {
    return ImplicitNativeRulesState.of(
        !getDelegate().getBooleanValue("parser", "disable_implicit_native_rules", false));
  }

  /** @return whether Buck should warn about deprecated syntax. */
  @Value.Lazy
  public boolean isWarnAboutDeprecatedSyntax() {
    return getDelegate().getBooleanValue("parser", "warn_about_deprecated_syntax", true);
  }

  /** @return the type of the glob handler used by the Skylark parser. */
  @Value.Lazy
  public SkylarkGlobHandler getSkylarkGlobHandler() {
    // NOTE(xavierd): This is a temporary rollout hack. Since
    // parser.skylark_glob_handler is set in the repository .buckconfig, and
    // the /etc/buckconfig.d/experiments is loaded before the repository
    // config, the experiments file cannot override the .buckconfig. Thus let's
    // check to see if the experiments config is set.
    //
    // TODO(xavierd): Once parser.skylark_glob_handler is set to eden_globber
    // everywhere, remove this condition.
    if (getDelegate().getBooleanValue("experiments", "eden_globber", false)) {
      return SkylarkGlobHandler.EDEN_THRIFT;
    }
    return getDelegate()
        .getEnum("parser", "skylark_glob_handler", SkylarkGlobHandler.class)
        .orElse(SkylarkGlobHandler.JAVA);
  }

  /**
   * @return the parser target threshold. When the current targets produced exceed this value, a
   *     warning is emitted.
   */
  @Value.Lazy
  public int getParserTargetThreshold() {
    return getDelegate().getInteger("parser", "target_threshold").orElse(TARGET_PARSER_THRESHOLD);
  }

  @Value.Lazy
  public boolean getEnableTargetCompatibilityChecks() {
    return getDelegate().getBooleanValue("parser", "enable_target_compatibility_checks", true);
  }

  /**
   * When set, requested target node will fail to configure if platform is not specified either
   * per-target with {@code default_target_platform} or globally with {@code --target-platforms=}
   * command line flag.
   */
  @Value.Lazy
  public boolean getRequireTargetPlatform() {
    // NOTE(nga): the future of this option is unknown at the moment.
    // It is possible that:
    // * we will change this option default to true
    // * we will remove this option (inlining it as true or false)
    return getDelegate().getBooleanValue("parser", "require_target_platform", false);
  }

  @Value.Lazy
  public String getTargetPlatformDetectorSpec() {
    return getDelegate().getValue("parser", "target_platform_detector_spec").orElse("");
  }

  @Value.Lazy
  public String getHostPlatformDetectorSpec() {
    return getDelegate().getValue("parser", "host_platform_detector_spec").orElse("");
  }

  @Value.Lazy
  public boolean getHostConfigurationSwitchEnabled() {
    return getDelegate().getBooleanValue("parser", "host_configuration_switch_enabled", false);
  }

  /**
   * @return a target that points to a {@code platform} rule that describes the target platforms.
   *     This is used when command-line argument is unspecified. Please do not use this option.
   */
  @Value.Lazy
  public Optional<String> getTargetPlatforms() {
    // TODO(nga): remove this option, it exists only for migration,
    //            and in the future platform should be only specified via one of:
    //            * `--target-platforms=` command line argument
    //            * `default_target_platform` argument
    //            * platform detector
    return getDelegate().getValue("parser", "target_platforms");
  }

  /**
   * For use in performance-sensitive code or if you don't care if the build file actually exists,
   * otherwise prefer {@link #getRelativePathToBuildFile(Cell, UnconfiguredBuildTarget,
   * DependencyStack)}.
   *
   * @param cell the cell where the given target is defined
   * @param target target to look up
   * @return path which may or may not exist.
   */
  public ForwardRelPath getRelativePathToBuildFileUnsafe(
      Cell cell, UnconfiguredBuildTarget target) {
    Preconditions.checkArgument(
        cell.getCanonicalName() == target.getCell(),
        "cell '%s' does not match target %s",
        cell.getCanonicalName(),
        target);

    return target
        .getCellRelativeBasePath()
        .getPath()
        .resolve(cell.getBuckConfigView(ParserConfig.class).getBuildFileName());
  }

  /**
   * For use in performance-sensitive code or if you don't care if the build file actually exists,
   * otherwise prefer {@link #getAbsolutePathToBuildFile}.
   *
   * @param cell the cell where the given target is defined
   * @param target target to look up
   * @return path which may or may not exist.
   */
  public AbsPath getAbsolutePathToBuildFileUnsafe(Cell cell, UnconfiguredBuildTarget target) {
    ProjectFilesystem targetFilesystem = cell.getFilesystem();
    return targetFilesystem.resolve(getRelativePathToBuildFileUnsafe(cell, target));
  }

  /** Return path to build file relative to the cell. */
  public ForwardRelPath getRelativePathToBuildFile(
      Cell cell, UnconfiguredBuildTarget target, DependencyStack dependencyStack) {
    ForwardRelPath buildFile = getRelativePathToBuildFileUnsafe(cell, target);
    if (!cell.getFilesystem().isFile(buildFile)) {
      throw new MissingBuildFileException(
          dependencyStack,
          target.getFullyQualifiedName(),
          target
              .getCellRelativeBasePath()
              .getPath()
              .resolve(cell.getBuckConfig().getView(ParserConfig.class).getBuildFileName())
              .toPath(cell.getFilesystem().getFileSystem()));
    }
    return buildFile;
  }

  /**
   * @param cell the cell where the given target is defined
   * @param target target to look up
   * @return an absolute path to a build file that contains the definition of the given target.
   */
  public AbsPath getAbsolutePathToBuildFile(
      Cell cell, UnconfiguredBuildTarget target, DependencyStack dependencyStack)
      throws MissingBuildFileException {
    ForwardRelPath relPath = getRelativePathToBuildFile(cell, target, dependencyStack);
    return cell.getFilesystem().resolve(relPath);
  }

  /**
   * @returns {@code true} if details about paths that violate package boundaries, but were marked
   *     as excepted paths (See {@link #getBuckPackageBoundaryExceptions()})), should be written to
   *     the log.
   */
  @Value.Lazy
  public boolean getLogPackageBoundaryExceptionViolations() {
    return getDelegate()
        .getBooleanValue("project", "log_package_boundary_exception_violations", false);
  }

  /** How package boundary violation should be enforced */
  public enum PackageBoundaryEnforcement {
    DISABLED,
    WARN,
    ENFORCE,
  }

  /**
   * Whether the cell is enforcing buck package boundaries for the package at the passed path.
   *
   * @param path Path of package (or file in a package) relative to the cell root.
   * @return How to enforce buck package boundaries for {@code path}
   */
  public PackageBoundaryEnforcement getPackageBoundaryEnforcementPolicy(ForwardRelPath path) {
    return getPackageBoundaryEnforcementPolicy(
        path.toPath(getDelegate().getFilesystem().getFileSystem()));
  }

  /**
   * Whether the cell is enforcing buck package boundaries for the package at the passed path.
   *
   * @param path Path of package (or file in a package) relative to the cell root.
   * @return How to enforce buck package boundaries for {@code path}
   */
  public PackageBoundaryEnforcement getPackageBoundaryEnforcementPolicy(Path path) {
    if (!getEnforceBuckPackageBoundary()) {
      return PackageBoundaryEnforcement.DISABLED;
    }

    Path absolutePath = getDelegate().getFilesystem().resolve(path);
    ImmutableList<Path> exceptions = getBuckPackageBoundaryExceptions();
    for (Path exception : exceptions) {
      if (absolutePath.startsWith(exception)) {
        return getLogPackageBoundaryExceptionViolations()
            ? PackageBoundaryEnforcement.WARN
            : PackageBoundaryEnforcement.DISABLED;
      }
    }
    return PackageBoundaryEnforcement.ENFORCE;
  }

  /**
   * @return whether to enable user-defined rule in .bzl files and export various symbols (such as
   *     rule()) into the evaluation context. This is disabled if {@link
   *     com.facebook.buck.core.rules.analysis.impl.RuleAnalysisComputation} is also disabled. This
   *     is in progress work, and experimental at this time.
   */
  @Value.Lazy
  public UserDefinedRulesState getUserDefinedRulesState() {
    boolean ragEnabled =
        getDelegate().getView(RuleAnalysisConfig.class).getComputationMode()
            != RuleAnalysisComputationMode.DISABLED;
    UserDefinedRulesState configuredValue =
        getDelegate()
            .getEnum("parser", "user_defined_rules", UserDefinedRulesState.class)
            .orElse(UserDefinedRulesState.DISABLED);
    if (ragEnabled) {
      return configuredValue;
    } else {
      if (configuredValue == UserDefinedRulesState.ENABLED) {
        throw new HumanReadableException(
            "User defined rules are configured as enabled, but rule analysis is disabled. "
                + "Disable UDR with -c parser.user_defined_rules=DISABLED");
      }
      return UserDefinedRulesState.DISABLED;
    }
  }

  /** @return Whether to enable parsing of PACKAGE files and apply their attributes to nodes. */
  @Value.Lazy
  public boolean getEnablePackageFiles() {
    return getDelegate().getBooleanValue("parser", "enable_package_files", false);
  }

  @Value.Lazy
  public int getMissingTargetLevenshteinDistance() {
    return getDelegate().getInteger("parser", "missing_target_levenshtein_distance").orElse(5);
  }
}
