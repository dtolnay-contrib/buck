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

package com.facebook.buck.testutil.integration;

import static com.facebook.buck.util.string.MoreStrings.linesToText;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.dd.plist.BinaryPropertyListParser;
import com.dd.plist.NSDictionary;
import com.dd.plist.NSObject;
import com.facebook.buck.cli.DaemonMode;
import com.facebook.buck.cli.MainForTests;
import com.facebook.buck.cli.MainRunner;
import com.facebook.buck.command.config.BuildBuckConfig;
import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.CellConfig;
import com.facebook.buck.core.cell.CellProvider;
import com.facebook.buck.core.cell.Cells;
import com.facebook.buck.core.cell.impl.DefaultCellPathResolver;
import com.facebook.buck.core.cell.impl.LocalCellProviderFactory;
import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.exceptions.HumanReadableExceptionAugmentor;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.PathWrapper;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.parser.buildtargetparser.ParsingUnconfiguredBuildTargetViewFactory;
import com.facebook.buck.core.plugin.impl.BuckPluginManagerFactory;
import com.facebook.buck.core.rules.knowntypes.DefaultKnownNativeRuleTypesFactory;
import com.facebook.buck.core.toolchain.ToolchainProviderFactory;
import com.facebook.buck.core.toolchain.impl.DefaultToolchainProviderFactory;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.file.MostFiles;
import com.facebook.buck.io.filesystem.BuckPaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.io.filesystem.impl.DefaultProjectFilesystemFactory;
import com.facebook.buck.io.watchman.WatchmanError;
import com.facebook.buck.io.watchman.WatchmanFactory;
import com.facebook.buck.io.watchman.WatchmanWatcher;
import com.facebook.buck.io.windowsfs.WindowsFS;
import com.facebook.buck.jvm.java.JavaCompilationConstants;
import com.facebook.buck.jvm.java.javax.SynchronizedToolProvider;
import com.facebook.buck.jvm.java.version.utils.JavaVersionUtils;
import com.facebook.buck.logd.client.FileOutputStreamFactory;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.support.bgtasks.AsyncBackgroundTaskManager;
import com.facebook.buck.support.bgtasks.BackgroundTaskManager;
import com.facebook.buck.support.exceptions.handler.ExceptionHandler;
import com.facebook.buck.support.exceptions.handler.ExceptionHandlerRegistryFactory;
import com.facebook.buck.support.state.BuckGlobalStateLifecycleManager;
import com.facebook.buck.testutil.AbstractWorkspace;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.CommandLineException;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.ErrorLogger;
import com.facebook.buck.util.ErrorLogger.LogImpl;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.Threads;
import com.facebook.buck.util.config.Config;
import com.facebook.buck.util.config.Configs;
import com.facebook.buck.util.config.RawConfig;
import com.facebook.buck.util.environment.EnvVariablesProvider;
import com.facebook.buck.util.string.MoreStrings;
import com.facebook.buck.util.trace.ChromeTraceParser;
import com.facebook.buck.util.trace.ChromeTraceParser.ChromeTraceEventMatcher;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.CharMatcher;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import javax.annotation.Nullable;
import org.hamcrest.Matchers;
import org.pf4j.PluginManager;

/**
 * {@link ProjectWorkspace} is a directory that contains a Buck project, complete with build files.
 *
 * <p>When {@link #setUp()} is invoked, the project files are cloned from a directory of testdata
 * into a tmp directory according to the following rule:
 *
 * <ul>
 *   <li>Files with the {@code .fixture} extension will be copied and renamed without the extension.
 *   <li>Files with the {@code .expected} extension will not be copied.
 * </ul>
 *
 * After {@link #setUp()} is invoked, the test should invoke Buck in that directory. As this is an
 * integration test, we expect that files will be written as a result of invoking Buck.
 *
 * <p>After Buck has been run, invoke {@link #verify()} to verify that Buck wrote the correct files.
 * For each file in the testdata directory with the {@code .expected} extension, {@link #verify()}
 * will check that a file with the same relative path (but without the {@code .expected} extension)
 * exists in the tmp directory. If not, {@link org.junit.Assert#fail()} will be invoked.
 */
public class ProjectWorkspace extends AbstractWorkspace {

  /**
   * For this file to be generated, log.jul_build_log needs to be set to true. This is done by
   * default in setUp()
   */
  public static final String PATH_TO_BUILD_LOG = "buck-out/bin/build.log";

  public static final String TEST_CELL_LOCATION =
      "test/com/facebook/buck/testutil/integration/testlibs";

  private static final String[] TEST_CELL_DIRECTORIES_TO_LINK = {
    "config", "third-party",
  };

  private boolean isSetUp = false;
  private final Path templatePath;
  private final boolean addBuckRepoCell;
  private final ProcessExecutor processExecutor;
  @Nullable private ProjectFilesystemAndConfig projectFilesystemAndConfig;
  @Nullable private MainRunner.KnownRuleTypesFactoryFactory knownRuleTypesFactoryFactory;
  @Nullable private BuckGlobalStateLifecycleManager buckDaemonState;

  private static class ProjectFilesystemAndConfig {

    private final ProjectFilesystem projectFilesystem;
    private final Config config;

    private ProjectFilesystemAndConfig(ProjectFilesystem projectFilesystem, Config config) {
      this.projectFilesystem = projectFilesystem;
      this.config = config;
    }
  }

  @VisibleForTesting
  ProjectWorkspace(Path templateDir, Path targetFolder, boolean addBuckRepoCell) {
    super(targetFolder);
    this.templatePath = templateDir;
    this.addBuckRepoCell = addBuckRepoCell;
    this.processExecutor = new DefaultProcessExecutor(new TestConsole());
  }

  @VisibleForTesting
  ProjectWorkspace(Path templateDir, Path targetFolder) {
    this(templateDir, targetFolder, true);
  }

  @VisibleForTesting
  ProjectWorkspace(AbsPath templateDir, AbsPath targetFolder) {
    this(templateDir.getPath(), targetFolder.getPath());
  }

  private ProjectFilesystemAndConfig getProjectFilesystemAndConfig() throws IOException {
    if (projectFilesystemAndConfig == null) {
      Config config =
          Configs.createDefaultConfig(
              destPath,
              Configs.getRepoConfigurationFiles(destPath),
              RawConfig.of(
                  ImmutableMap.of(
                      "project",
                      ImmutableMap.of(
                          "buck_out_include_target_config_hash",
                          Boolean.toString(
                              TestProjectFilesystems
                                  .BUCK_OUT_INCLUDE_TARGET_CONFIG_HASH_FOR_TEST)))));
      projectFilesystemAndConfig =
          new ProjectFilesystemAndConfig(
              TestProjectFilesystems.createProjectFilesystem(destPath, config), config);
    }
    return projectFilesystemAndConfig;
  }

  public ProjectWorkspace setUp() throws IOException {
    addTemplateToWorkspace(templatePath);

    if (addBuckRepoCell) {
      Path bucklibRoot = setupBuckLib();
      addBuckConfigLocalOption("repositories", "buck", bucklibRoot.toString());
    }

    addBuckConfigLocalOptions(
        ImmutableMap.of(
            // Enable the JUL build log.  This log is very verbose but rarely useful,
            // so it's disabled by default.
            "log",
            ImmutableMap.of("jul_build_log", "true"),
            // Enable hashed buck-out paths in test until it is turned on by default for everyone
            "project",
            ImmutableMap.of(
                "buck_out_include_target_config_hash",
                Boolean.toString(
                    TestProjectFilesystems.BUCK_OUT_INCLUDE_TARGET_CONFIG_HASH_FOR_TEST)),
            // Starting from version 12.2.0, Xcode sets default minimum deployment target to 11.0,
            // in order to be able to run tests without specifying it in every .buckconfig file
            // we set it here.
            "apple",
            ImmutableMap.of("macosx_target_sdk_version", "10.15")));

    addBuckConfigLocalOption("build", "are_external_actions_enabled", Boolean.FALSE.toString());

    buckDaemonState = new BuckGlobalStateLifecycleManager();

    isSetUp = true;
    return this;
  }

  private Path setupBuckLib() throws IOException {
    Path bucklibRoot = createBucklibRoot();
    createSymlinkToBuckTestRepository(bucklibRoot);
    saveBucklibConfig(bucklibRoot);
    createWatchmanConfig(bucklibRoot);
    return bucklibRoot;
  }

  private Path createBucklibRoot() throws IOException {
    Path root = Files.createTempDirectory("buck-testlib").toRealPath().normalize();
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  try {
                    MostFiles.deleteRecursivelyIfExists(root);
                  } catch (IOException e) {
                    throw new RuntimeException(e);
                  }
                }));
    return root;
  }

  private void createSymlinkToBuckTestRepository(Path bucklib) throws IOException {
    for (String directory : TEST_CELL_DIRECTORIES_TO_LINK) {
      Path directoryPath = bucklib.resolve(directory);
      MorePaths.createSymLink(
          new WindowsFS(),
          directoryPath,
          Paths.get(TEST_CELL_LOCATION).resolve(directory).toAbsolutePath());
    }
  }

  private static void saveBucklibConfig(Path bucklibRoot) throws IOException {
    Map<String, Map<String, String>> configs = prepareBucklibConfig();
    String contents = convertToBuckConfig(configs);
    Files.write(bucklibRoot.resolve(".buckconfig"), contents.getBytes(UTF_8));
  }

  private static Map<String, Map<String, String>> prepareBucklibConfig() {
    Map<String, Map<String, String>> configs = new HashMap<>();
    configs.put(
        "project",
        ImmutableMap.of(
            "allow_symlinks",
            "ALLOW",
            "read_only_paths",
            Joiner.on(", ").join(TEST_CELL_DIRECTORIES_TO_LINK)));
    return configs;
  }

  public BuckPaths getBuckPaths() throws IOException {
    return getProjectFilesystemAndConfig().projectFilesystem.getBuckPaths();
  }

  public ProcessResult runBuckBuild(String... args) {
    return runBuckBuild(ImmutableMap.of(), args);
  }

  public ProcessResult runBuckBuild(ImmutableMap<String, String> env, String... args) {
    return runBuckBuild(env, this.destPath, args);
  }

  public ProcessResult runBuckBuild(ImmutableMap<String, String> env, Path root, String... args) {
    String[] totalArgs = new String[args.length + 1];
    totalArgs[0] = "build";
    System.arraycopy(args, 0, totalArgs, 1, args.length);
    return runBuckCommandWithEnvironmentOverrides(root, env, totalArgs);
  }

  public ProcessResult runBuckTest(String... args) {
    String[] totalArgs = new String[args.length + 1];
    totalArgs[0] = "test";
    System.arraycopy(args, 0, totalArgs, 1, args.length);
    return runBuckCommand(totalArgs);
  }

  private ImmutableMap<String, String> buildMultipleAndReturnStringOutputs(
      ImmutableMap<String, String> env, Path buildRoot, String... args) {
    // Add in `--show-output` to the build, so we can parse the output paths after the fact.
    ImmutableList<String> buildArgs =
        ImmutableList.<String>builder().add("--show-output").add(args).build();
    ProcessResult buildResult = runBuckBuild(env, buildRoot, buildArgs.toArray(new String[0]));
    buildResult.assertSuccess();

    // Build outputs are contained on stdout
    return parseShowOutputStdoutAsStrings(buildResult.getStdout());
  }

  /**
   * Parses the output of a --show-output build command into an easy to use map.
   *
   * @param stdout The stdout of the --show-output build command.
   * @return The map of target => target output string. The value is relative to the Buck root of
   *     the invoked command.
   */
  public ImmutableMap<String, String> parseShowOutputStdoutAsStrings(String stdout) {
    List<String> lines =
        Splitter.on(CharMatcher.anyOf(System.lineSeparator()))
            .trimResults()
            .omitEmptyStrings()
            .splitToList(stdout);

    Splitter lineSplitter = Splitter.on(' ').trimResults();
    ImmutableMap.Builder<String, String> builder = ImmutableMap.builder();
    for (String line : lines) {
      List<String> fields = lineSplitter.splitToList(line);
      assertThat(
          String.format("Target %s has no outputs.", fields.isEmpty() ? "" : fields.get(0)),
          fields,
          Matchers.hasSize(2));
      builder.put(fields.get(0), fields.get(1));
    }

    return builder.build();
  }

  public ImmutableMap<String, Path> buildMultipleAndReturnOutputs(String... args) {
    return buildMultipleAndReturnOutputs(ImmutableMap.of(), args);
  }

  public ImmutableMap<String, Path> buildMultipleAndReturnOutputs(
      ImmutableMap<String, String> env, String... args) {
    return buildMultipleAndReturnOutputs(env, this.destPath, args);
  }

  public ImmutableMap<String, Path> buildMultipleAndReturnOutputs(
      ImmutableMap<String, String> env, Path buildRoot, String[] args) {
    return buildMultipleAndReturnStringOutputs(env, buildRoot, args).entrySet().stream()
        .collect(
            ImmutableMap.toImmutableMap(
                Map.Entry::getKey, entry -> buildRoot.resolve(entry.getValue())));
  }

  public Path buildAndReturnOutput(String... args) {
    return buildAndReturnOutput(ImmutableMap.of(), args);
  }

  public Path buildAndReturnOutput(ImmutableMap<String, String> env, String... args) {
    return buildAndReturnOutput(env, this.destPath, args);
  }

  public Path buildAndReturnOutput(Path root, String... args) {
    return buildAndReturnOutput(ImmutableMap.of(), root, args);
  }

  public Path buildAndReturnOutput(
      ImmutableMap<String, String> env, Path buildRoot, String[] args) {
    ImmutableMap<String, Path> outputs = buildMultipleAndReturnOutputs(env, buildRoot, args);

    // Verify we only have a single output.
    assertThat(
        String.format(
            "expected only a single build target in command `%s`: %s",
            ImmutableList.copyOf(args), outputs),
        outputs.entrySet(),
        Matchers.hasSize(1));

    return outputs.values().iterator().next();
  }

  public ImmutableMap<String, Path> buildMultipleAndReturnRelativeOutputs(String... args) {
    return buildMultipleAndReturnRelativeOutputs(this.destPath, args);
  }

  public ImmutableMap<String, Path> buildMultipleAndReturnRelativeOutputs(
      Path root, String[] args) {
    return buildMultipleAndReturnStringOutputs(ImmutableMap.of(), root, args).entrySet().stream()
        .collect(
            ImmutableMap.toImmutableMap(Map.Entry::getKey, entry -> Paths.get(entry.getValue())));
  }

  public Path buildAndReturnRelativeOutput(String... args) {
    ImmutableMap<String, Path> outputs = buildMultipleAndReturnRelativeOutputs(args);

    // Verify we only have a single output.
    assertThat(
        String.format(
            "expected only a single build target in command `%s`: %s",
            ImmutableList.copyOf(args), outputs),
        outputs.entrySet(),
        Matchers.hasSize(1));

    return outputs.values().iterator().next();
  }

  public ProcessExecutor.Result runJar(Path jar, ImmutableList<String> vmArgs, String... args)
      throws IOException, InterruptedException {
    List<String> command =
        ImmutableList.<String>builder()
            .addAll(JavaCompilationConstants.DEFAULT_JAVA_COMMAND_PREFIX)
            .addAll(vmArgs)
            .add("-jar")
            .add(jar.toString())
            .addAll(ImmutableList.copyOf(args))
            .build();
    return doRunCommand(command);
  }

  public ProcessExecutor.Result runJar(Path jar, String... args)
      throws IOException, InterruptedException {
    return runJar(jar, ImmutableList.of(), args);
  }

  public ProcessExecutor.Result runCommand(String exe, String... args)
      throws IOException, InterruptedException {
    List<String> command =
        ImmutableList.<String>builder().add(exe).addAll(ImmutableList.copyOf(args)).build();
    return runCommand(command);
  }

  public ProcessExecutor.Result runCommand(Iterable<String> command)
      throws IOException, InterruptedException {
    return doRunCommand(command);
  }

  private ProcessExecutor.Result doRunCommand(Iterable<String> command)
      throws IOException, InterruptedException {
    ProcessExecutorParams params =
        ProcessExecutorParams.builder()
            .setCommand(command)
            .setDirectory(destPath.toAbsolutePath())
            .build();
    return processExecutor.launchAndExecute(params);
  }

  /**
   * Runs Buck with the specified list of command-line arguments.
   *
   * @param args to pass to {@code buck}, so that could be {@code ["build", "//path/to:target"]},
   *     {@code ["project"]}, etc.
   * @return the result of running Buck, which includes the exit code, stdout, and stderr.
   */
  @Override
  public ProcessResult runBuckCommand(String... args) {
    return runBuckCommand(destPath, args);
  }

  @Override
  public ProcessResult runBuckCommand(ImmutableMap<String, String> environment, String... args) {
    return runBuckCommandWithEnvironmentOverrides(destPath, environment, args);
  }

  public ProcessResult runBuckCommand(Path repoRoot, String... args) {
    return runBuckCommandWithEnvironmentOverrides(repoRoot, ImmutableMap.of(), args);
  }

  public ProcessResult runBuckCommandWithEnvironmentOverrides(
      Path repoRoot, ImmutableMap<String, String> environmentOverrides, String... args) {
    assertTrue("setUp() must be run before this method is invoked", isSetUp);
    TestConsole testConsole = new TestConsole();
    InputStream stdin = new ByteArrayInputStream("".getBytes());

    ImmutableMap<String, String> sanizitedEnv =
        EnvironmentSanitizer.getSanitizedEnvForTests(environmentOverrides);

    MainForTests main =
        new MainForTests(
            testConsole,
            stdin,
            knownRuleTypesFactoryFactory == null
                ? DefaultKnownNativeRuleTypesFactory::new
                : knownRuleTypesFactoryFactory,
            repoRoot,
            repoRoot.toAbsolutePath().resolve(relativeWorkingDir).normalize().toString(),
            sanizitedEnv,
            DaemonMode.DAEMON);

    return launchAndRunMain(main, testConsole, buckDaemonState, args);
  }

  private ProcessResult launchAndRunMain(
      MainForTests main,
      TestConsole testConsole,
      BuckGlobalStateLifecycleManager buckDaemonState,
      String... args) {
    BackgroundTaskManager manager = AsyncBackgroundTaskManager.of();

    try {
      MainRunner mainRunner =
          main.prepareMainRunner(manager, buckDaemonState, new MainForTests.TestCommandManager());
      ExitCode exitCode;

      // TODO (buck_team): this code repeats the one in Main and thus wants generalization
      HumanReadableExceptionAugmentor augmentor =
          new HumanReadableExceptionAugmentor(ImmutableMap.of());
      StringBuilder errorMessage = new StringBuilder();
      ErrorLogger logger =
          new ErrorLogger(
              new LogImpl() {
                @Override
                public void logUserVisible(String message) {
                  errorMessage.append("\n");
                  errorMessage.append(message);
                }

                @Override
                public void logUserVisibleInternalError(String message) {
                  errorMessage.append("\n");
                  errorMessage.append(linesToText("Buck encountered an internal error", message));
                }

                @Override
                public void logVerbose(Throwable e) {
                  // yes, do nothing
                }
              },
              augmentor);

      try {
        exitCode =
            mainRunner.runMainWithExitCode(
                new FileOutputStreamFactory(),
                WatchmanWatcher.FreshInstanceAction.NONE,
                System.nanoTime(),
                ImmutableList.copyOf(args),
                t -> {
                  throw t;
                });
      } catch (Throwable t) {
        logger.logException(t);
        exitCode =
            ExceptionHandlerRegistryFactory.create(
                    new ExceptionHandler<InterruptedException, ExitCode>(
                        InterruptedException.class) {
                      @Override
                      public ExitCode handleException(InterruptedException e) {
                        e.printStackTrace(testConsole.getStdErr());
                        Threads.interruptCurrentThread();
                        return ExitCode.BUILD_ERROR;
                      }
                    },
                    new ExceptionHandler<CommandLineException, ExitCode>(
                        CommandLineException.class) {
                      @Override
                      public ExitCode handleException(CommandLineException e) {
                        testConsole.getStdErr().println(e.getMessage());
                        return ExitCode.COMMANDLINE_ERROR;
                      }
                    },
                    new ExceptionHandler<BuildFileParseException, ExitCode>(
                        BuildFileParseException.class) {
                      @Override
                      public ExitCode handleException(BuildFileParseException e) {
                        testConsole.getStdErr().println(e.getHumanReadableErrorMessage());
                        return ExitCode.PARSE_ERROR;
                      }
                    })
                .handleException(t);
      }

      return new ProcessResult(
          exitCode,
          testConsole.getTextWrittenToStdOut(),
          testConsole.getTextWrittenToStdErr() + errorMessage.toString());
    } finally {
      if (JavaVersionUtils.getMajorVersion() < 9) {
        // javac has a global cache of zip/jar file content listings. It determines the validity of
        // a given cache entry based on the modification time of the zip file in question. In normal
        // usage, this is fine. However, in tests, we often will do a build, change something, and
        // then rapidly do another build. If this happens quickly, javac can be operating from
        // incorrect information when reading a jar file, resulting in "bad class file" or
        // "corrupted zip file" errors. We work around this for testing purposes by reaching inside
        // the compiler and clearing the cache.
        try {
          Class<?> cacheClass =
              Class.forName(
                  "com.sun.tools.javac.file.ZipFileIndexCache",
                  false,
                  SynchronizedToolProvider.getSystemToolClassLoader());

          Method getSharedInstanceMethod = cacheClass.getMethod("getSharedInstance");
          Method clearCacheMethod = cacheClass.getMethod("clearCache");

          Object cache = getSharedInstanceMethod.invoke(cacheClass);
          clearCacheMethod.invoke(cache);
        } catch (ClassNotFoundException
            | IllegalAccessException
            | InvocationTargetException
            | NoSuchMethodException e) {
          throw new RuntimeException(e);
        }
      }
    }
  }

  /**
   * Runs an event-driven parser on {@code buck-out/log/build.trace}, which is a symlink to the
   * trace of the most recent invocation of Buck (which may not have been a {@code buck build}).
   *
   * <p>Warning: If running buckd, make sure `daemon.flush_events_before_exit=true` so that the
   * trace is materialized before this is ran.
   *
   * @see ChromeTraceParser#parse(Path, Set)
   */
  public Map<ChromeTraceEventMatcher<?>, Object> parseTraceFromMostRecentBuckInvocation(
      Set<ChromeTraceEventMatcher<?>> matchers) throws IOException {
    ProjectFilesystem projectFilesystem = getProjectFilesystemAndConfig().projectFilesystem;
    ChromeTraceParser parser = new ChromeTraceParser(projectFilesystem);
    return parser.parse(
        projectFilesystem.getBuckPaths().getLogDir().resolve("build.trace"), matchers);
  }

  public void enableDirCache() throws IOException {
    addBuckConfigLocalOption("cache", "mode", "dir");
  }

  public void enableOutOfProcessExecution() throws IOException {
    addBuckConfigLocalOption(
        BuildBuckConfig.BUILD_SECTION, BuildBuckConfig.EXTERNAL_ACTIONS_FLAG_PROPERTY_NAME, true);
  }

  public void disableOutOfProcessExecution() throws IOException {
    addBuckConfigLocalOption(
        BuildBuckConfig.BUILD_SECTION, BuildBuckConfig.EXTERNAL_ACTIONS_FLAG_PROPERTY_NAME, false);
  }

  public void setKnownRuleTypesFactoryFactory(
      @Nullable MainRunner.KnownRuleTypesFactoryFactory knownRuleTypesFactoryFactory) {
    this.knownRuleTypesFactoryFactory = knownRuleTypesFactoryFactory;
  }

  public void resetBuildLogFile() throws IOException {
    writeContentsToPath("", PATH_TO_BUILD_LOG);
  }

  public BuckBuildLog getBuildLog() throws IOException {
    return getBuildLog(getDestPath());
  }

  public BuckBuildLog getBuildLog(Path root) throws IOException {
    return BuckBuildLog.fromLogContents(
        root, Files.readAllLines(root.resolve(PATH_TO_BUILD_LOG), UTF_8));
  }

  public ProjectFilesystem getProjectFileSystem() throws IOException {
    return getProjectFilesystemAndConfig().projectFilesystem;
  }

  public Config getConfig() throws IOException {
    return getProjectFilesystemAndConfig().config;
  }

  public Cell asCell() throws IOException {
    CellProvider cellProvider = asCellProvider();
    return cellProvider.getCellByCanonicalCellName(CanonicalCellName.rootCell());
  }

  public Cells asCells() throws IOException {
    return new Cells(asCellProvider());
  }

  public CellProvider asCellProvider() throws IOException {
    ProjectFilesystemAndConfig filesystemAndConfig = getProjectFilesystemAndConfig();
    ProjectFilesystem filesystem = filesystemAndConfig.projectFilesystem;
    Config config = filesystemAndConfig.config;

    DefaultCellPathResolver rootCellCellPathResolver =
        DefaultCellPathResolver.create(filesystem.getRootPath(), config);

    ImmutableMap<String, String> env = EnvVariablesProvider.getSystemEnv();
    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(config.getRawConfig())
            .setFilesystem(filesystem)
            .build();

    PluginManager pluginManager = BuckPluginManagerFactory.createPluginManager();
    ExecutableFinder executableFinder = new ExecutableFinder();

    ToolchainProviderFactory toolchainProviderFactory =
        new DefaultToolchainProviderFactory(pluginManager, env, processExecutor, executableFinder);

    return LocalCellProviderFactory.create(
        filesystem,
        buckConfig,
        CellConfig.EMPTY_INSTANCE,
        rootCellCellPathResolver,
        toolchainProviderFactory,
        new DefaultProjectFilesystemFactory(),
        new ParsingUnconfiguredBuildTargetViewFactory(),
        new WatchmanFactory.NullWatchman("ProjectWorkspace", WatchmanError.TEST),
        Optional.empty());
  }

  public BuildTarget newBuildTarget(String fullyQualifiedName) {
    return BuildTargetFactory.newInstance(fullyQualifiedName);
  }

  public void assertFilesEqual(Path expected, Path actual) throws IOException {
    assertFilesEqual(expected, actual, s -> s);
  }

  public void assertFilesEqual(RelPath expected, RelPath actual) throws IOException {
    assertFilesEqual(expected.getPath(), actual.getPath());
  }

  private void assertFilesEqual(
      Path expected, Path actual, Function<String, String> normalizeObservedContent)
      throws IOException {
    if (!expected.isAbsolute()) {
      expected = templatePath.resolve(expected);
    }
    if (!actual.isAbsolute()) {
      actual = destPath.resolve(actual);
    }
    if (!Files.isRegularFile(actual)) {
      fail("Expected file " + actual + " could not be found.");
    }

    String extension = MorePaths.getFileExtension(actual);
    String cleanPathToObservedFile =
        MoreStrings.withoutSuffix(templatePath.relativize(expected).toString(), EXPECTED_SUFFIX);

    switch (extension) {
        // For Apple .plist and .stringsdict files, we define equivalence if:
        // 1. The two files are the same type (XML or binary)
        // 2. If binary: unserialized objects are deeply-equivalent.
        //    Otherwise, fall back to exact string match.
      case "plist":
      case "stringsdict":
        NSObject expectedObject;
        try {
          expectedObject = BinaryPropertyListParser.parse(expected.toFile());
        } catch (Exception e) {
          // Not binary format.
          expectedObject = null;
        }

        NSObject observedObject;
        try {
          observedObject = BinaryPropertyListParser.parse(actual.toFile());
        } catch (Exception e) {
          // Not binary format.
          observedObject = null;
        }

        assertEquals(
            String.format(
                "In %s, expected plist to be of %s type.",
                cleanPathToObservedFile, (expectedObject != null) ? "binary" : "XML"),
            (expectedObject != null),
            (observedObject != null));

        if (expectedObject != null) {
          // These keys depend on the locally installed version of Xcode, so ignore them
          // in comparisons.
          String[] ignoredKeys = {
            "DTSDKName",
            "DTPlatformName",
            "DTPlatformVersion",
            "MinimumOSVersion",
            "DTSDKBuild",
            "DTPlatformBuild",
            "DTXcode",
            "DTXcodeBuild"
          };
          if (observedObject instanceof NSDictionary && expectedObject instanceof NSDictionary) {
            for (String key : ignoredKeys) {
              ((NSDictionary) observedObject).remove(key);
              ((NSDictionary) expectedObject).remove(key);
            }
          }

          assertEquals(
              String.format(
                  "In %s, expected binary plist contents to match.", cleanPathToObservedFile),
              expectedObject,
              observedObject);
          break;
        } else {
          assertFileContentsEqual(expected, actual, false, normalizeObservedContent);
        }
        break;
      case "iml":
      case "xml":
        assertFileContentsEqual(expected, actual, true, normalizeObservedContent);
        break;
      default:
        assertFileContentsEqual(expected, actual, false, normalizeObservedContent);
    }
  }

  private enum FileType {
    DEFAULT,
    XML,
    JSLIB,
  }

  private void assertFileContentsEqual(
      Path expectedFile,
      Path observedFile,
      boolean isXml,
      Function<String, String> normalizeObservedContent)
      throws IOException {
    String cleanPathToObservedFile =
        MoreStrings.withoutSuffix(
            templatePath.relativize(expectedFile).toString(), EXPECTED_SUFFIX);

    String expectedFileContent = getFileContents(expectedFile);
    String observedFileContent = new String(Files.readAllBytes(observedFile), UTF_8);

    // It is possible, on Windows, to have Git keep "\n"-style newlines, or convert them to
    // "\r\n"-style newlines.  Support both ways by normalizing to "\n"-style newlines.
    // See https://help.github.com/articles/dealing-with-line-endings/ for more information.
    expectedFileContent = expectedFileContent.replace("\r\n", "\n");
    observedFileContent = observedFileContent.replace("\r\n", "\n");

    if (isXml) {
      // `javax.xml.Transformer` has different (buggy) behavior on Java 9+ vs. Java 8-. See
      // http://java9.wtf/xml-transformer/ for the details. In Java 9+, spurious empty lines get
      // inserted, and leading whitespacing can be different, so we normalize before comparison.
      // This has apparently been fixed in Java 14 (see JDK-8223291).

      // Remove leading whitespace.
      expectedFileContent = expectedFileContent.replaceAll("(?m)^[ \t]+", "");
      observedFileContent = observedFileContent.replaceAll("(?m)^[ \t]+", "");

      // Remove empty lines.
      expectedFileContent = expectedFileContent.replaceAll("\n+", "\n");
      observedFileContent = observedFileContent.replaceAll("\n+", "\n");
    }

    // buck-out dir contains a config hash that can't be hardcoded to expected files.
    observedFileContent =
        BuckOutConfigHashPlaceholder.replaceHashByPlaceholder(observedFileContent);

    observedFileContent = normalizeObservedContent.apply(observedFileContent);

    // TODO(gabrielrc): Remove this after we land the new config hash changes
    observedFileContent = BuckOutConfigHashPlaceholder.removePlaceholder(observedFileContent);
    expectedFileContent = BuckOutConfigHashPlaceholder.removePlaceholder(expectedFileContent);

    assertEquals(
        String.format(
            "In %s, expected content of %s to match that of %s.",
            cleanPathToObservedFile, expectedFileContent, observedFileContent),
        expectedFileContent,
        observedFileContent);
  }

  /**
   * For every file in the template directory whose name ends in {@code .expected}, checks that an
   * equivalent file has been written in the same place under the destination directory.
   *
   * @param templateSubdirectory An optional subdirectory to check. Only files in this directory
   *     will be compared.
   */
  private void assertPathsEqual(
      Path templateSubdirectory,
      Path destinationSubdirectory,
      Function<String, String> normalizeObservedContent)
      throws IOException {
    SimpleFileVisitor<Path> copyDirVisitor =
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            String fileName = file.getFileName().toString();
            if (fileName.endsWith(EXPECTED_SUFFIX) && !fileName.endsWith(SKIP_SUFFIX)) {
              // Get File for the file that should be written, but without the ".expected" suffix.
              Path generatedFileWithSuffix =
                  destinationSubdirectory.resolve(templateSubdirectory.relativize(file));
              Path directory = generatedFileWithSuffix.getParent();
              Path observedFile = directory.resolve(MorePaths.getNameWithoutExtension(file));
              assertFilesEqual(file, observedFile, normalizeObservedContent);
            }
            return FileVisitResult.CONTINUE;
          }
        };

    Files.walkFileTree(templateSubdirectory, copyDirVisitor);
  }

  public void verify(Path templateSubdirectory, Path destinationSubdirectory) throws IOException {
    verify(templateSubdirectory, destinationSubdirectory, s -> s);
  }

  public void verify(RelPath templateSubdirectory, RelPath destinationSubdirectory)
      throws IOException {
    verify(templateSubdirectory.getPath(), destinationSubdirectory.getPath());
  }

  public void verify() throws IOException {
    assertPathsEqual(templatePath, destPath, s -> s);
  }

  public void verify(
      Path templateSubdirectory,
      Path destinationSubdirectory,
      Function<String, String> normalizeObservedContent)
      throws IOException {
    assertPathsEqual(
        templatePath.resolve(templateSubdirectory),
        destPath.resolve(destinationSubdirectory),
        normalizeObservedContent);
  }

  public void verify(
      Path templateSubdirectory,
      PathWrapper destinationSubdirectory,
      Function<String, String> normalizeObservedContent)
      throws IOException {
    verify(templateSubdirectory, destinationSubdirectory.getPath(), normalizeObservedContent);
  }

  public Path getGenPath(BuildTarget buildTarget, String format) throws IOException {
    return getProjectFileSystem()
        .resolve(
            BuildTargetPaths.getGenPath(getProjectFileSystem().getBuckPaths(), buildTarget, format))
        .getPath();
  }

  public Path getScratchPath(BuildTarget buildTarget, String format) throws IOException {
    return getProjectFileSystem()
        .resolve(BuildTargetPaths.getScratchPath(getProjectFileSystem(), buildTarget, format))
        .getPath();
  }

  public AbsPath getLastOutputDir() throws IOException {
    return getProjectFileSystem().getRootPath().resolve(getBuckPaths().getLastOutputDir());
  }

  public void verify(Path subdirectory) throws IOException {
    Preconditions.checkArgument(
        !subdirectory.isAbsolute(),
        "'verify(subdirectory)' takes a relative path, but received '%s'",
        subdirectory);
    assertPathsEqual(templatePath.resolve(subdirectory), destPath.resolve(subdirectory), s -> s);
  }
}
