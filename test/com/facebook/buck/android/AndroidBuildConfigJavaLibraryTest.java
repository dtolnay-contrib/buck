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

package com.facebook.buck.android;

import static com.facebook.buck.jvm.java.JavaCompilationConstants.DEFAULT_JAVAC;
import static com.facebook.buck.jvm.java.JavaCompilationConstants.DEFAULT_JAVACD_PARAMS;
import static com.facebook.buck.jvm.java.JavaCompilationConstants.DEFAULT_JAVAC_OPTIONS;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.android.build_config.BuildConfigFields;
import com.facebook.buck.android.packageable.AndroidPackageableCollection;
import com.facebook.buck.android.packageable.AndroidPackageableCollector;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.parser.exceptions.NoSuchBuildTargetException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.Collections;
import java.util.Optional;
import org.junit.Test;

public class AndroidBuildConfigJavaLibraryTest {

  @Test
  public void testAddToCollector() throws NoSuchBuildTargetException {
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//foo:bar");
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    FakeProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    AndroidBuildConfigJavaLibrary buildConfigJavaLibrary =
        AndroidBuildConfigDescription.createBuildRule(
            buildTarget,
            projectFilesystem,
            "com.example.buck",
            /* values */ BuildConfigFields.fromFieldDeclarations(
                Collections.singleton("String foo = \"bar\"")),
            /* valuesFile */ Optional.empty(),
            /* useConstantExpressions */ false,
            DEFAULT_JAVAC,
            DEFAULT_JAVAC_OPTIONS,
            graphBuilder,
            false,
            false,
            DEFAULT_JAVACD_PARAMS);

    AndroidPackageableCollector collector = new AndroidPackageableCollector(buildTarget);
    buildConfigJavaLibrary.addToCollector(graphBuilder, collector);
    AndroidPackageableCollection collection = collector.build();
    assertEquals(
        ImmutableMap.of(
            "com.example.buck",
            BuildConfigFields.fromFields(
                ImmutableList.of(BuildConfigFields.Field.of("String", "foo", "\"bar\"")))),
        collection.getBuildConfigs());
  }

  @Test
  public void testBuildConfigHasCorrectProperties() throws NoSuchBuildTargetException {
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//foo:bar");
    BuildConfigFields fields =
        BuildConfigFields.fromFieldDeclarations(Collections.singleton("String KEY = \"value\""));
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    AndroidBuildConfigJavaLibrary buildConfigJavaLibrary =
        AndroidBuildConfigDescription.createBuildRule(
            buildTarget,
            projectFilesystem,
            "com.example.buck",
            /* values */ fields,
            /* valuesFile */ Optional.empty(),
            /* useConstantExpressions */ false,
            DEFAULT_JAVAC,
            DEFAULT_JAVAC_OPTIONS,
            graphBuilder,
            false,
            false,
            DEFAULT_JAVACD_PARAMS);
    AndroidBuildConfig buildConfig = buildConfigJavaLibrary.getAndroidBuildConfig();
    assertEquals("com.example.buck", buildConfig.getJavaPackage());
    assertEquals(fields, buildConfig.getBuildConfigFields());
  }
}
