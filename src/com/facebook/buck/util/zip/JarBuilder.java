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

package com.facebook.buck.util.zip;

import static java.util.Comparator.comparing;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.util.stream.RichStream;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.io.ByteStreams;
import com.google.common.io.CharStreams;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.function.Predicate;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import javax.annotation.Nullable;

public class JarBuilder {

  private interface ResourceMergeStrategy {
    byte[] merge(Set<String> files);

    static final ResourceMergeStrategy JOIN_MERGE_STRATEGY =
        new ResourceMergeStrategy() {
          private Joiner joiner = Joiner.on("\n");

          @Override
          public byte[] merge(Set<String> files) {
            return joiner.join(files).getBytes();
          }
        };

    /**
     * Property files in Spring have unique keys, some of them containing comma-seprated lists.
     * These lists have to be merged together.
     */
    static final ResourceMergeStrategy PROPERTIES_MERGE_STRATEGY =
        new ResourceMergeStrategy() {
          @Override
          public byte[] merge(Set<String> files) {
            Properties mergedProps = new Properties();
            for (String file : files) {
              Properties props = new Properties();
              try {
                props.load(new StringReader(file));
                for (String propName : props.stringPropertyNames()) {
                  String values = props.getProperty(propName);
                  String mergedValues = mergedProps.getProperty(propName);
                  mergedProps.setProperty(
                      propName, mergedValues == null ? values : mergedValues + "," + values);
                }
              } catch (IOException ex) {
                throw new HumanReadableException("Unable to merge properties", file, ex);
              }
            }
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            try {
              mergedProps.store(byteArrayOutputStream, "");
              return byteArrayOutputStream.toByteArray();
            } catch (IOException ex) {
              throw new HumanReadableException("Unable to merge properties", ex);
            }
          }
        };
  }

  private enum MergeableResource implements Predicate<String>, ResourceMergeStrategy {
    SERVICES("META-INF/services/", ResourceMergeStrategy.JOIN_MERGE_STRATEGY),
    SPRING_SCHEMAS("META-INF/spring.schemas", ResourceMergeStrategy.JOIN_MERGE_STRATEGY),
    SPRING_HANDLERS("META-INF/spring.handlers", ResourceMergeStrategy.JOIN_MERGE_STRATEGY),
    SPRING_FACTORIES("META-INF/spring.factories", ResourceMergeStrategy.PROPERTIES_MERGE_STRATEGY),
    SPRING_TOOLING("META-INF/spring.tooling", ResourceMergeStrategy.JOIN_MERGE_STRATEGY);

    private final Predicate<String> predicate;
    private final ResourceMergeStrategy mergeStrategy;

    MergeableResource(String pathPrefix, ResourceMergeStrategy mergeStrategy) {
      this.predicate = entryName -> entryName.startsWith(pathPrefix) && !entryName.endsWith("/");
      this.mergeStrategy = mergeStrategy;
    }

    @Override
    public boolean test(String entryName) {
      return predicate.test(entryName);
    }

    public static boolean check(String entryName) {
      return MergeableResource.fromFileName(entryName) != null;
    }

    public static MergeableResource fromFileName(String entryName) {
      return Arrays.stream(MergeableResource.values())
          .filter(mergeableResource -> mergeableResource.predicate.test(entryName))
          .findAny()
          .orElse(null);
    }

    @Override
    public byte[] merge(Set<String> files) {
      return mergeStrategy.merge(files);
    }
  }

  public interface Observer {

    Observer IGNORING = (jarFile, entrySupplier) -> {};

    void onDuplicateEntry(String jarFile, JarEntrySupplier entrySupplier);
  }

  private Observer observer = Observer.IGNORING;
  @Nullable private Path outputFile;
  @Nullable private String mainClass;
  @Nullable private Path manifestFile;
  private boolean shouldMergeManifests;
  private boolean shouldHashEntries;
  private Predicate<? super CustomZipEntry> removeEntryPredicate = entry -> false;
  private final List<JarEntryContainer> sourceContainers = new ArrayList<>();
  private final List<JarEntryContainer> overrideSourceContainers = new ArrayList<>();
  private final Set<String> alreadyAddedEntries = new HashSet<>();
  private final Map<String, Set<String>> mergeableResources = new HashMap<>();

  public JarBuilder setObserver(Observer observer) {
    this.observer = observer;
    return this;
  }

  public JarBuilder setOverrideEntriesToJar(Stream<AbsPath> entriesToJar) {
    return setOverrideEntriesToJar(entriesToJar::iterator);
  }

  public JarBuilder setOverrideEntriesToJar(Iterable<AbsPath> entriesToJar) {
    return addToSourceContainers(entriesToJar, overrideSourceContainers);
  }

  public JarBuilder setEntriesToJar(Stream<AbsPath> entriesToJar) {
    return setEntriesToJar(entriesToJar::iterator);
  }

  public JarBuilder setEntriesToJar(Iterable<AbsPath> entriesToJar) {
    return addToSourceContainers(entriesToJar, sourceContainers);
  }

  private JarBuilder addToSourceContainers(
      Iterable<AbsPath> entriesToJar, List<JarEntryContainer> sourceContainers) {
    RichStream.from(entriesToJar)
        .map(AbsPath::getPath)
        .map(JarEntryContainer::of)
        .forEach(sourceContainers::add);

    return this;
  }

  public JarBuilder addEntry(JarEntrySupplier supplier) {
    sourceContainers.add(new SingletonJarEntryContainer(supplier));
    return this;
  }

  public JarBuilder addEntryContainer(JarEntryContainer container) {
    sourceContainers.add(container);
    return this;
  }

  public JarBuilder setMainClass(String mainClass) {
    this.mainClass = mainClass;
    return this;
  }

  public JarBuilder setManifestFile(@Nullable Path manifestFile) {
    Preconditions.checkArgument(manifestFile == null || manifestFile.isAbsolute());
    this.manifestFile = manifestFile;
    return this;
  }

  public JarBuilder setShouldMergeManifests(boolean shouldMergeManifests) {
    this.shouldMergeManifests = shouldMergeManifests;
    return this;
  }

  public JarBuilder setShouldHashEntries(boolean shouldHashEntries) {
    this.shouldHashEntries = shouldHashEntries;
    return this;
  }

  public JarBuilder setRemoveEntryPredicate(
      Predicate<? super CustomZipEntry> removeEntryPredicate) {
    this.removeEntryPredicate = removeEntryPredicate;
    return this;
  }

  public int createJarFile(Path outputFile) throws IOException {
    Preconditions.checkArgument(outputFile.isAbsolute());
    try (CustomJarOutputStream jar =
        ZipOutputStreams.newJarOutputStream(
            outputFile, ZipOutputStreams.HandleDuplicates.APPEND_TO_ZIP)) {
      jar.setEntryHashingEnabled(shouldHashEntries);
      this.outputFile = outputFile;

      // Write the manifest first.
      writeManifest(jar);

      // Sort entries across all suppliers
      List<JarEntrySupplier> sortedEntries = new ArrayList<>();
      addToEntries(sortedEntries, sourceContainers);
      sortedEntries.sort(comparing(supplier -> supplier.getEntry().getName()));

      // add override entries
      addToEntries(sortedEntries, overrideSourceContainers);

      addEntriesToJar(sortedEntries, jar);

      addMergeableResources(jar);

      if (mainClass != null && !classPresent(mainClass)) {
        throw new HumanReadableException("ERROR: Main class %s does not exist.", mainClass);
      }

      // Clean up any open file handles that we may have
      closeSourceContainers(sourceContainers);
      closeSourceContainers(overrideSourceContainers);

      return 0;
    }
  }

  private void addToEntries(
      List<JarEntrySupplier> sortedEntries, List<JarEntryContainer> sourceContainers)
      throws IOException {
    for (JarEntryContainer sourceContainer : sourceContainers) {
      sourceContainer.stream().forEach(sortedEntries::add);
    }
  }

  private void closeSourceContainers(List<JarEntryContainer> sourceContainers) throws IOException {
    for (JarEntryContainer sourceContainer : sourceContainers) {
      sourceContainer.close();
    }
  }

  private void addMergeableResources(CustomJarOutputStream jar) throws IOException {
    for (String entryName : mergeableResources.keySet()) {
      CustomZipEntry entry = new CustomZipEntry(entryName);
      jar.putNextEntry(entry);
      Set<String> files = mergeableResources.get(entryName);
      jar.write(MergeableResource.fromFileName(entryName).merge(files));
      jar.closeEntry();
    }
  }

  private void writeManifest(CustomJarOutputStream jar) throws IOException {
    mkdirs("META-INF/", jar);
    DeterministicManifest manifest = jar.getManifest();
    manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");

    if (shouldMergeManifests) {
      for (JarEntryContainer sourceContainer : sourceContainers) {
        Manifest readManifest = sourceContainer.getManifest();
        if (readManifest != null) {
          merge(manifest, readManifest);
        }
      }
    }

    // Even if not merging manifests, we should include the one the user gave us. We do this last
    // so that values from the user overwrite values from merged manifests.
    if (manifestFile != null) {
      try (InputStream stream = Files.newInputStream(manifestFile)) {
        Manifest readManifest = new Manifest(stream);
        merge(manifest, readManifest);
      }
    }

    // We may have merged manifests and over-written the user-supplied main class. Add it back.
    if (mainClass != null) {
      manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, mainClass);
    }

    jar.writeManifest();
  }

  private boolean classPresent(String className) {
    String classPath = classNameToPath(className);

    return alreadyAddedEntries.contains(classPath);
  }

  private String classNameToPath(String className) {
    return className.replace('.', '/') + ".class";
  }

  public static String pathToClassName(String relativePath) {
    String entry = relativePath;
    if (relativePath.contains(".class")) {
      entry = relativePath.replace('/', '.').replace(".class", "");
    }
    return entry;
  }

  private void addEntriesToJar(Iterable<JarEntrySupplier> entries, CustomJarOutputStream jar)
      throws IOException {
    for (JarEntrySupplier entrySupplier : entries) {
      addEntryToJar(entrySupplier, jar);
    }
  }

  private void addEntryToJar(JarEntrySupplier entrySupplier, CustomJarOutputStream jar)
      throws IOException {
    CustomZipEntry entry = entrySupplier.getEntry();
    String entryName = entry.getName();

    // We already read the manifest. No need to read it again
    if (JarFile.MANIFEST_NAME.equals(entryName)) {
      return;
    }

    // Check if the entry belongs to the blacklist and it should be excluded from the Jar.
    if (removeEntryPredicate.test(entrySupplier.getEntry())) {
      return;
    }

    mkdirs(getParentDir(entryName), jar);

    // We're in the process of merging a bunch of different jar files. These typically contain
    // just ".class" files and the manifest, but they can also include things like license files
    // from third party libraries and config files. We should include those license files within
    // the jar we're creating. Extracting them is left as an exercise for the consumer of the
    // jar.  Because we don't know which files are important, the only ones we skip are
    // duplicate class files.
    if (!isDuplicateAllowed(entryName) && !alreadyAddedEntries.add(entryName)) {
      if (!entryName.endsWith("/")) {
        observer.onDuplicateEntry(String.valueOf(outputFile), entrySupplier);
      }
      return;
    }

    // Collect all mergeable resources together for later merging and addition to the output jar
    if (MergeableResource.check(entryName)) {
      try (InputStream entryInputStream =
          Objects.requireNonNull(entrySupplier.getInputStreamSupplier().get())) {
        Set<String> existingResources =
            mergeableResources.computeIfAbsent(entryName, (m) -> new LinkedHashSet<>());
        existingResources.add(
            CharStreams.toString(new InputStreamReader(entryInputStream, StandardCharsets.UTF_8)));
      }
      return;
    }

    jar.putNextEntry(entry);
    try (InputStream entryInputStream = entrySupplier.getInputStreamSupplier().get()) {
      if (entryInputStream != null) {
        // Null stream means a directory
        ByteStreams.copy(entryInputStream, jar);
      }
    }
    jar.closeEntry();
  }

  private void mkdirs(String name, CustomJarOutputStream jar) throws IOException {
    if (name.isEmpty()) {
      return;
    }

    Preconditions.checkArgument(name.endsWith("/"));
    if (alreadyAddedEntries.contains(name)) {
      return;
    }

    String parent = getParentDir(name);
    mkdirs(parent, jar);

    jar.putNextEntry(new CustomZipEntry(name));
    jar.closeEntry();
    alreadyAddedEntries.add(name);
  }

  private String getParentDir(String name) {
    int length = name.lastIndexOf('/') + 1;
    if (length == name.length()) {
      length = name.lastIndexOf('/', length - 2) + 1;
    }
    return name.substring(0, length);
  }

  /**
   * Merge entries from two Manifests together, with existing attributes being overwritten.
   *
   * @param into The Manifest to modify.
   * @param from The Manifest to copy from.
   */
  private void merge(Manifest into, Manifest from) {

    Attributes attributes = from.getMainAttributes();
    if (attributes != null) {
      for (Map.Entry<Object, Object> attribute : attributes.entrySet()) {
        into.getMainAttributes().put(attribute.getKey(), attribute.getValue());
      }
    }

    Map<String, Attributes> entries = from.getEntries();
    if (entries != null) {
      for (Map.Entry<String, Attributes> entry : entries.entrySet()) {
        Attributes existing = into.getAttributes(entry.getKey());
        if (existing == null) {
          existing = new Attributes();
          into.getEntries().put(entry.getKey(), existing);
        }
        existing.putAll(entry.getValue());
      }
    }
  }

  private boolean isDuplicateAllowed(String name) {
    return MergeableResource.check(name) || (!name.endsWith(".class") && !name.endsWith("/"));
  }

  private static class SingletonJarEntryContainer implements JarEntryContainer {

    private final JarEntrySupplier supplier;

    @Nullable private Manifest manifest;

    private SingletonJarEntryContainer(JarEntrySupplier supplier) {
      this.supplier = supplier;
    }

    @Nullable
    @Override
    public Manifest getManifest() throws IOException {
      if (manifest == null && supplier.getEntry().getName().equals(JarFile.MANIFEST_NAME)) {
        try (InputStream manifestStream = supplier.getInputStreamSupplier().get()) {
          manifest = new Manifest(manifestStream);
        }
      }
      return manifest;
    }

    @Override
    public Stream<JarEntrySupplier> stream() {
      return Stream.of(supplier);
    }

    @Override
    public void close() {}
  }
}
