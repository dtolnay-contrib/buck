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

package com.facebook.buck.jvm.java;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasEntry;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.jvm.java.runner.FileClassPathRunner;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.Test;

public class JUnitStepTest {
  @Test
  public void testGetShellCommandForJava8() {
    String testClass1 = "com.facebook.buck.shell.JUnitCommandTest";
    String testClass2 = "com.facebook.buck.shell.InstrumentCommandTest";
    Set<String> testClassNames = ImmutableSet.of(testClass1, testClass2);

    String vmArg1 = "-Dname1=value1";
    String vmArg2 = "-Dname1=value2";
    ImmutableList<String> vmArgs = ImmutableList.of(vmArg1, vmArg2);

    BuildId pretendBuildId = new BuildId("pretend-build-id");
    String buildIdArg = String.format("-Dcom.facebook.buck.buildId=%s", pretendBuildId);

    Path modulePath = Paths.get("module/submodule");
    String modulePathArg = String.format("-Dcom.facebook.buck.moduleBasePath=%s", modulePath);

    Path directoryForTestResults = Paths.get("buck-out/gen/theresults/");
    Path testRunnerClasspath = Paths.get("ant-out/classes/junit");
    ProjectFilesystem filesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();
    AbsPath classpathFile = filesystem.resolve("foo");

    int javaVersion = 8;
    JUnitJvmArgs args =
        ImmutableJUnitJvmArgs.builder()
            .setBuildId(pretendBuildId)
            .setBuckModuleBaseSourceCodePath(modulePath)
            .setTargetJavaVersion(javaVersion)
            .setClasspathFile(classpathFile.getPath())
            .setTestRunnerClasspath(testRunnerClasspath)
            .setExtraJvmArgs(vmArgs)
            .setTestType(TestType.JUNIT)
            .setDirectoryForTestResults(directoryForTestResults)
            .addAllTestClasses(testClassNames)
            .setDefaultTestTimeoutMillis(5000L)
            .build();

    JUnitStep junit =
        new JUnitStep(
            filesystem,
            /* nativeLibsEnvironment */ ImmutableMap.of(),
            /* testRuleTimeoutMs */ Optional.empty(),
            /* testCaseTimeoutMs */ Optional.empty(),
            ImmutableMap.of(),
            ImmutableList.of("/foo/bar/custom/java"),
            args,
            false);

    StepExecutionContext executionContext =
        TestExecutionContext.newBuilder().setConsole(new TestConsole(Verbosity.ALL)).build();
    assertEquals(executionContext.getVerbosity(), Verbosity.ALL);
    assertEquals(args.getDefaultTestTimeoutMillis(), 5000L);

    List<String> observedArgs = junit.getShellCommand(executionContext);
    MoreAsserts.assertListEquals(
        ImmutableList.of(
            "/foo/bar/custom/java",
            "-Dbuck.testrunner_classes=" + testRunnerClasspath,
            "-Dbuck.classpath_file=" + classpathFile,
            buildIdArg,
            modulePathArg,
            "-Dapple.awt.UIElement=true",
            "-XX:-MaxFDLimit",
            vmArg1,
            vmArg2,
            "-verbose",
            "-classpath",
            MorePaths.pathWithPlatformSeparators("ant-out/classes/junit"),
            FileClassPathRunner.class.getName(),
            "com.facebook.buck.testrunner.JUnitMain",
            "--output",
            directoryForTestResults.toString(),
            "--default-test-timeout",
            "5000",
            testClass1,
            testClass2),
        observedArgs);
  }

  @Test
  public void testGetShellCommandForJava11() {
    String testClass1 = "com.facebook.buck.shell.JUnitCommandTest";
    String testClass2 = "com.facebook.buck.shell.InstrumentCommandTest";
    Set<String> testClassNames = ImmutableSet.of(testClass1, testClass2);

    String vmArg1 = "-Dname1=value1";
    String vmArg2 = "-Dname1=value2";
    ImmutableList<String> vmArgs = ImmutableList.of(vmArg1, vmArg2);

    BuildId pretendBuildId = new BuildId("pretend-build-id");
    String buildIdArg = String.format("-Dcom.facebook.buck.buildId=%s", pretendBuildId);

    Path modulePath = Paths.get("module/submodule");
    String modulePathArg = String.format("-Dcom.facebook.buck.moduleBasePath=%s", modulePath);

    Path directoryForTestResults = Paths.get("buck-out/gen/theresults/");
    Path testRunnerClasspath = Paths.get("ant-out/classes/junit");
    ProjectFilesystem filesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();
    AbsPath classpathFile = filesystem.resolve("foo");

    int javaVersion = 11;
    JUnitJvmArgs args =
        ImmutableJUnitJvmArgs.builder()
            .setBuildId(pretendBuildId)
            .setBuckModuleBaseSourceCodePath(modulePath)
            .setTargetJavaVersion(javaVersion)
            .setClasspathFile(classpathFile.getPath())
            .setTestRunnerClasspath(testRunnerClasspath)
            .setExtraJvmArgs(vmArgs)
            .setTestType(TestType.JUNIT)
            .setDirectoryForTestResults(directoryForTestResults)
            .addAllTestClasses(testClassNames)
            .setDefaultTestTimeoutMillis(5000L)
            .build();

    JUnitStep junit =
        new JUnitStep(
            filesystem,
            /* nativeLibsEnvironment */ ImmutableMap.of(),
            /* testRuleTimeoutMs */ Optional.empty(),
            /* testCaseTimeoutMs */ Optional.empty(),
            ImmutableMap.of(),
            ImmutableList.of("/foo/bar/custom/java"),
            args,
            false);

    StepExecutionContext executionContext =
        TestExecutionContext.newBuilder().setConsole(new TestConsole(Verbosity.ALL)).build();
    assertEquals(executionContext.getVerbosity(), Verbosity.ALL);
    assertEquals(args.getDefaultTestTimeoutMillis(), 5000L);

    Path classpathArgfile = filesystem.resolve(junit.getClasspathArgfile());

    List<String> observedArgs = junit.getShellCommand(executionContext);
    MoreAsserts.assertListEquals(
        ImmutableList.of(
            "/foo/bar/custom/java",
            buildIdArg,
            modulePathArg,
            "-Dapple.awt.UIElement=true",
            "-XX:-MaxFDLimit",
            vmArg1,
            vmArg2,
            "-verbose",
            "@" + classpathArgfile,
            FileClassPathRunner.class.getName(),
            "com.facebook.buck.testrunner.JUnitMain",
            "--output",
            directoryForTestResults.toString(),
            "--default-test-timeout",
            "5000",
            testClass1,
            testClass2),
        observedArgs);
  }

  @Test
  public void testGetEnvironmentVariables() {
    BuildId pretendBuildId = new BuildId("pretend-build-id");
    Path modulePath = Paths.get("module/submodule");

    Path directoryForTestResults = Paths.get("buck-out/gen/theresults/");
    Path testRunnerClasspath = Paths.get("ant-out/classes/junit");
    ProjectFilesystem filesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();
    AbsPath classpathFile = filesystem.resolve("foo");

    JUnitJvmArgs args =
        ImmutableJUnitJvmArgs.builder()
            .setBuildId(pretendBuildId)
            .setBuckModuleBaseSourceCodePath(modulePath)
            .setTargetJavaVersion(8)
            .setClasspathFile(classpathFile.getPath())
            .setTestRunnerClasspath(testRunnerClasspath)
            .setExtraJvmArgs(ImmutableList.of())
            .setTestType(TestType.JUNIT)
            .setDirectoryForTestResults(directoryForTestResults)
            .addAllTestClasses(ImmutableList.of())
            .build();

    JUnitStep junit =
        new JUnitStep(
            filesystem,
            /* nativeLibsEnvironment */ ImmutableMap.of(),
            /* testRuleTimeoutMs */ Optional.empty(),
            /* testCaseTimeoutMs */ Optional.empty(),
            ImmutableMap.of("FOO", "BAR"),
            ImmutableList.of("/foo/bar/custom/java"),
            args,
            false);

    ImmutableMap<String, String> observedEnvironment =
        junit.getEnvironmentVariables(Platform.detect());
    assertThat(observedEnvironment, hasEntry("FOO", "BAR"));
  }

  @Test
  public void ensureThatDebugFlagCausesJavaDebugCommandFlagToBeAdded() {
    String testClass1 = "com.facebook.buck.shell.JUnitCommandTest";
    String testClass2 = "com.facebook.buck.shell.InstrumentCommandTest";
    Set<String> testClassNames = ImmutableSet.of(testClass1, testClass2);

    String vmArg1 = "-Dname1=value1";
    String vmArg2 = "-Dname1=value2";
    ImmutableList<String> vmArgs = ImmutableList.of(vmArg1, vmArg2);

    BuildId pretendBuildId = new BuildId("pretend-build-id");
    String buildIdArg = String.format("-Dcom.facebook.buck.buildId=%s", pretendBuildId);

    Path modulePath = Paths.get("module/submodule");
    String modulePathArg = String.format("-Dcom.facebook.buck.moduleBasePath=%s", modulePath);

    Path directoryForTestResults = Paths.get("buck-out/gen/theresults/");
    Path testRunnerClasspath = Paths.get("ant-out/classes/junit");

    ProjectFilesystem filesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();
    AbsPath classpathFile = filesystem.resolve("foo");

    int javaVersion = 8;
    JUnitJvmArgs args =
        ImmutableJUnitJvmArgs.builder()
            .setClasspathFile(classpathFile.getPath())
            .setBuildId(pretendBuildId)
            .setBuckModuleBaseSourceCodePath(modulePath)
            .setTargetJavaVersion(javaVersion)
            .setTestRunnerClasspath(testRunnerClasspath)
            .setDebugEnabled(true)
            .setExtraJvmArgs(vmArgs)
            .setTestType(TestType.JUNIT)
            .setDirectoryForTestResults(directoryForTestResults)
            .addAllTestClasses(testClassNames)
            .build();

    JUnitStep junit =
        new JUnitStep(
            filesystem,
            ImmutableMap.of(),
            /* testRuleTimeoutMs */ Optional.empty(),
            /* testCaseTimeoutMs */ Optional.empty(),
            ImmutableMap.of(),
            ImmutableList.of("/foo/bar/custom/java"),
            args,
            false);

    TestConsole console = new TestConsole(Verbosity.ALL);
    StepExecutionContext executionContext =
        TestExecutionContext.newBuilder().setConsole(console).build();

    List<String> observedArgs = junit.getShellCommand(executionContext);
    MoreAsserts.assertListEquals(
        ImmutableList.of(
            "/foo/bar/custom/java",
            "-Dbuck.testrunner_classes=" + testRunnerClasspath,
            "-Dbuck.classpath_file=" + classpathFile,
            buildIdArg,
            modulePathArg,
            "-Dapple.awt.UIElement=true",
            "-XX:-MaxFDLimit",
            "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005",
            vmArg1,
            vmArg2,
            "-verbose",
            "-classpath",
            MorePaths.pathWithPlatformSeparators("ant-out/classes/junit"),
            FileClassPathRunner.class.getName(),
            "com.facebook.buck.testrunner.JUnitMain",
            "--output",
            directoryForTestResults.toString(),
            "--default-test-timeout",
            "0",
            testClass1,
            testClass2),
        observedArgs);

    // TODO(simons): Why does the CapturingPrintStream append spaces?
    assertEquals(
        "Debugging. Suspending JVM. Connect a JDWP debugger to port 5005 to proceed.",
        console.getTextWrittenToStdErr().trim());
  }
}
