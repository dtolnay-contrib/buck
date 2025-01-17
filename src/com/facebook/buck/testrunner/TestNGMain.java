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

package com.facebook.buck.testrunner;

/**
 * Launcher for TestNG.
 *
 * <p>Expected arguments are:
 *
 * <ul>
 *   <li>(string) output directory
 *   <li>(long) default timeout in milliseconds (0 for no timeout)
 *   <li>(string) newline separated list of test selectors
 *   <li>(string...) fully-qualified names of test classes
 * </ul>
 *
 * <p>IMPORTANT! This class limits itself to types that are available in both the JDK and Android
 * Java API. The objective is to limit the set of files added to the ClassLoader that runs the test,
 * as not to interfere with the results of the test.
 */
public class TestNGMain {

  private TestNGMain() {
    // Launcher class.
  }

  public static void main(String[] args) {
    // Ensure that both testng and hamcrest are on the classpath
    CheckDependency.requiresClass("testng", "org.testng.TestNG");
    CheckDependency.requiresClass("hamcrest", "org.hamcrest.Description");

    TestNGRunner runner = new TestNGRunner();
    runner.parseArgs(args);
    runner.runAndExit();
  }
}
