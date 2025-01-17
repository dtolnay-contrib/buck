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

package com.facebook.buck.intellij.ideabuck.build;

import com.google.common.collect.Iterables;

/** The descriptor of buck command. */
public class BuckCommand {
  private static final String VERBOSITY_TAG = "-v";
  private static final String VERBOSITY_LEVEL = "0";
  private static final String TEST_VERBOSITY_LEVEL = "1";
  // Visual commands
  public static final BuckCommand BUILD_V_0 =
      new BuckCommand("build", VERBOSITY_TAG, VERBOSITY_LEVEL);
  public static final BuckCommand BUILD = new BuckCommand("build");
  public static final BuckCommand INSTALL_V_0 =
      new BuckCommand("install", VERBOSITY_TAG, VERBOSITY_LEVEL);
  public static final BuckCommand INSTALL = new BuckCommand("install");
  public static final BuckCommand KILL = new BuckCommand("kill");
  public static final BuckCommand TEST =
      new BuckCommand("test", VERBOSITY_TAG, TEST_VERBOSITY_LEVEL);
  public static final BuckCommand UNINSTALL =
      new BuckCommand("uninstall", VERBOSITY_TAG, VERBOSITY_LEVEL);
  public static final BuckCommand PROJECT =
      new BuckCommand("project", VERBOSITY_TAG, VERBOSITY_LEVEL);
  public static final BuckCommand AUDIT = new BuckCommand("audit");
  public static final BuckCommand RUN = new BuckCommand("run");
  // Internal commands
  public static final BuckCommand QUERY = new BuckCommand("query", "--json");

  /** Command name passed to buck. */
  private final String name;

  private final String[] parameters;

  public BuckCommand(String name, String... parameters) {
    this.name = name;
    this.parameters = parameters;
  }

  public BuckCommand(String name, Iterable<String> parameters) {
    this.name = name;
    this.parameters = Iterables.toArray(parameters, String.class);
  }

  public String name() {
    return name;
  }

  public String[] getParameters() {
    return parameters;
  }

  @Override
  public String toString() {
    return name;
  }
}
