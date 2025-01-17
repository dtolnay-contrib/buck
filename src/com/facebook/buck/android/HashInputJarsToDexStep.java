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

import com.facebook.buck.android.packageable.AndroidPackageableCollection;
import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.java.classes.ClasspathTraversal;
import com.facebook.buck.jvm.java.classes.DefaultClasspathTraverser;
import com.facebook.buck.jvm.java.classes.FileLike;
import com.facebook.buck.jvm.java.classes.FileLikes;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.util.sha1.Sha1HashCode;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Multimap;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Step responsible for hashing dex inputs to be passed to {@link SmartDexingStep}. It goes through
 * each dex input, looks at the classes that went into that jar, and uses the class hashes collected
 * in {@link AndroidPackageableCollection} to calculate the final hash of the input.
 */
public class HashInputJarsToDexStep extends AbstractExecutionStep
    implements SmartDexingStep.DexInputHashesProvider {

  private static final Logger LOG = Logger.get(HashInputJarsToDexStep.class);

  private final ProjectFilesystem filesystem;
  private final Supplier<Set<Path>> primaryInputsToDex;
  private final Optional<Supplier<Multimap<Path, Path>>> secondaryOutputToInputs;

  private final Supplier<ImmutableMap<String, HashCode>> classNamesToHashesSupplier;
  private final ImmutableMap.Builder<Path, Sha1HashCode> dexInputsToHashes;
  private boolean stepFinished;

  public HashInputJarsToDexStep(
      ProjectFilesystem filesystem,
      Supplier<Set<Path>> primaryInputsToDex,
      Optional<Supplier<Multimap<Path, Path>>> secondaryOutputToInputs,
      Supplier<ImmutableMap<String, HashCode>> classNamesToHashesSupplier) {
    super("collect_smart_dex_inputs_hash");
    this.filesystem = filesystem;
    this.primaryInputsToDex = primaryInputsToDex;
    this.secondaryOutputToInputs = secondaryOutputToInputs;
    this.classNamesToHashesSupplier = classNamesToHashesSupplier;

    this.dexInputsToHashes = ImmutableMap.builder();
    this.stepFinished = false;
  }

  @Override
  public StepExecutionResult execute(StepExecutionContext context) throws IOException {
    ImmutableList.Builder<Path> allInputs = ImmutableList.builder();
    allInputs.addAll(primaryInputsToDex.get());
    if (secondaryOutputToInputs.isPresent()) {
      allInputs.addAll(secondaryOutputToInputs.get().get().values());
    }

    Map<String, HashCode> classNamesToHashes = classNamesToHashesSupplier.get();

    for (Path path : allInputs.build()) {
      Hasher hasher = Hashing.sha1().newHasher();
      new DefaultClasspathTraverser()
          .traverse(
              new ClasspathTraversal(
                  Collections.singleton(path),
                  filesystem.getRootPath(),
                  filesystem.getIgnoredPaths()) {
                @Override
                public void visit(FileLike fileLike) {
                  if (FileLikes.isClassFile(fileLike)) {
                    String className = FileLikes.getFileNameWithoutClassSuffix(fileLike);

                    if (classNamesToHashes.containsKey(className)) {
                      HashCode classHash =
                          Objects.requireNonNull(classNamesToHashes.get(className));
                      hasher.putBytes(classHash.asBytes());
                    } else {
                      LOG.warn("%s hashes not found", className);
                    }
                  }
                }
              });
      dexInputsToHashes.put(path, Sha1HashCode.fromHashCode(hasher.hash()));
    }
    stepFinished = true;
    return StepExecutionResults.SUCCESS;
  }

  @Override
  public ImmutableMap<Path, Sha1HashCode> getDexInputHashes() {
    Preconditions.checkState(
        stepFinished,
        "Either the step did not complete successfully or getDexInputHashes() was called before "
            + "it could finish its execution.");
    return dexInputsToHashes.build();
  }
}
