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

package com.facebook.buck.cli.bootstrapper;

import java.util.Arrays;

/**
 * This class sets up a separate ClassLoader for most of Buck's implementation, leaving only the
 * bare minimum bootstrapping classes (and a few classes for compatibility with library code that is
 * not ClassLoader-aware) in the system ClassLoader. This is done so that annotation processors do
 * not have their classpaths polluted with Buck's dependencies when Buck is compiling Java code
 * in-process.
 *
 * <p>Under JSR-199, when the Java compiler is run in-process it uses a ClassLoader that is a child
 * of the system ClassLoader. In order for annotation processors to access the Compiler Tree API
 * (which lives in tools.jar with the compiler itself), they must be loaded with a ClassLoader
 * descended from the compiler's. If Buck used the system ClassLoader as a normal Java application
 * would, this would result in annotation processors getting Buck's versions of Guava, Jackson, etc.
 * instead of their own.
 */
public final class ClassLoaderBootstrapper {

  private static final ClassLoader classLoader = ClassLoaderFactory.withEnv().create();

  private ClassLoaderBootstrapper() {}

  /** Main method */
  public static void main(String[] args) {
    // Some things (notably Jetty) use the context class loader to load stuff
    Thread.currentThread().setContextClassLoader(classLoader);

    String mainClassName = args[0];
    String[] remainingArgs = Arrays.copyOfRange(args, 1, args.length);
    ClassLoaderBootstrapperUtils.invokeMainMethod(classLoader, mainClassName, remainingArgs);
  }

  public static Class<?> loadClass(String name) {
    try {
      return classLoader.loadClass(name);
    } catch (ClassNotFoundException e) {
      throw new NoClassDefFoundError(name);
    }
  }
}
