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

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.downwardapi.config.DownwardApiConfig;
import com.facebook.buck.jvm.java.ConfiguredCompilerFactory;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.jvm.java.JavaCDBuckConfig;
import com.facebook.buck.jvm.java.JavaConfiguredCompilerFactory;
import com.facebook.buck.jvm.java.JavacFactory;
import com.facebook.buck.jvm.kotlin.KotlinBuckConfig;
import com.facebook.buck.jvm.kotlin.KotlinConfiguredCompilerFactory;
import com.facebook.buck.jvm.scala.ScalaBuckConfig;
import com.facebook.buck.jvm.scala.ScalaConfiguredCompilerFactory;

public class DefaultAndroidLibraryCompilerFactory implements AndroidLibraryCompilerFactory {
  private final JavaBuckConfig javaConfig;
  private final JavaCDBuckConfig javaCDConfig;
  private final ScalaBuckConfig scalaConfig;
  private final KotlinBuckConfig kotlinBuckConfig;
  private final DownwardApiConfig downwardApiConfig;

  public DefaultAndroidLibraryCompilerFactory(
      JavaBuckConfig javaConfig,
      JavaCDBuckConfig javaCDConfig,
      ScalaBuckConfig scalaConfig,
      KotlinBuckConfig kotlinBuckConfig,
      DownwardApiConfig downwardApiConfig) {
    this.javaConfig = javaConfig;
    this.javaCDConfig = javaCDConfig;
    this.scalaConfig = scalaConfig;
    this.kotlinBuckConfig = kotlinBuckConfig;
    this.downwardApiConfig = downwardApiConfig;
  }

  @Override
  public ConfiguredCompilerFactory getCompiler(
      AndroidLibraryDescription.JvmLanguage language,
      JavacFactory javacFactory,
      TargetConfiguration toolchainTargetConfiguration) {
    switch (language) {
      case JAVA:
        return new JavaConfiguredCompilerFactory(
            javaConfig,
            javaCDConfig,
            downwardApiConfig,
            AndroidClasspathProvider::new,
            javacFactory);
      case SCALA:
        return new ScalaConfiguredCompilerFactory(
            scalaConfig, downwardApiConfig, AndroidClasspathProvider::new, javacFactory);
      case KOTLIN:
        return new KotlinConfiguredCompilerFactory(
            kotlinBuckConfig, downwardApiConfig, AndroidClasspathProvider::new, javacFactory);
    }
    throw new HumanReadableException("Unsupported `language` parameter value: %s", language);
  }
}
