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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.jvm.java.JavacPluginProperties.Type;
import com.facebook.buck.jvm.java.javax.SynchronizedToolProvider;
import com.facebook.buck.util.ClassLoaderCache;
import java.io.IOException;
import org.junit.Test;

public class AnnotationProcessorFactoryTest {

  @Test
  public void testAnnotationProcessorClassloadersNotReusedIfMarkedUnsafe() {
    assertFalse(
        isAnnotationProcessorClassLoaderReused(
            "some.Processor", // processor
            false)); // safe processors
  }

  @Test
  public void testAnnotationProcessorClassloadersReusedIfMarkedSafe() {
    assertTrue(
        isAnnotationProcessorClassLoaderReused(
            "some.Processor", // processor
            true)); // safe processors
  }

  private boolean isAnnotationProcessorClassLoaderReused(
      String annotationProcessor, boolean canReuseClasspath) {
    SourcePath classpath = FakeSourcePath.of("some/path/to.jar");
    ClassLoader baseClassLoader = SynchronizedToolProvider.getSystemToolClassLoader();
    ClassLoaderCache classLoaderCache = new ClassLoaderCache();
    String buildTarget = BuildTargetFactory.newInstance("//:test").getFullyQualifiedName();
    SourcePathResolverAdapter sourcePathResolver =
        new TestActionGraphBuilder().getSourcePathResolver();
    AbsPath rootPath = new FakeProjectFilesystem().getRootPath();
    ResolvedJavacPluginProperties processorGroup =
        ResolvedJavacPluginProperties.of(
            JavacPluginProperties.builder()
                .setType(Type.ANNOTATION_PROCESSOR)
                .addClasspathEntries(classpath)
                .addProcessorNames(annotationProcessor)
                .setCanReuseClassLoader(canReuseClasspath)
                .setDoesNotAffectAbi(false)
                .setSupportsAbiGenerationFromSource(false)
                .build(),
            sourcePathResolver,
            rootPath);

    try (AnnotationProcessorFactory factory1 =
            new AnnotationProcessorFactory(null, baseClassLoader, classLoaderCache, buildTarget);
        AnnotationProcessorFactory factory2 =
            new AnnotationProcessorFactory(null, baseClassLoader, classLoaderCache, buildTarget)) {
      ClassLoader classLoader1 = factory1.getClassLoaderForProcessorGroup(processorGroup, rootPath);
      ClassLoader classLoader2 = factory2.getClassLoaderForProcessorGroup(processorGroup, rootPath);
      return classLoader1 == classLoader2;
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }
}
