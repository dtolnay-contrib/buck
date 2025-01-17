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

import com.facebook.buck.android.resources.filter.DrawableFinder;
import com.facebook.buck.android.resources.filter.FilteredDirectoryCopier;
import com.facebook.buck.android.resources.filter.FilteringPredicate;
import com.facebook.buck.android.resources.filter.ResourceFilters;
import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.ProjectFilesystemUtils;
import com.facebook.buck.io.pathformat.PathFormatter;
import com.facebook.buck.shell.BashStep;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.util.Escaper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/**
 * This {@link com.facebook.buck.step.Step} copies {@code res} directories to a different location,
 * while filtering out certain resources.
 */
public class FilterResourcesSteps {

  private static final Logger LOG = Logger.get(FilterResourcesSteps.class);

  private final ProjectFilesystem filesystem;
  private final ImmutableBiMap<Path, Path> inResDirToOutResDirMap;
  private final boolean filterByDensity;
  private final boolean enableStringWhitelisting;
  private final ImmutableSet<Path> whitelistedStringDirs;
  private final ImmutableSet<String> packagedLocales;
  private final ImmutableSet<String> locales;
  private final CopyStep copyStep = new CopyStep();
  private final ScaleStep scaleStep = new ScaleStep();
  @Nullable private final Set<ResourceFilters.Density> targetDensities;
  @Nullable private final ImageScaler imageScaler;

  /**
   * Creates a command that filters a specified set of directories.
   *
   * @param inResDirToOutResDirMap set of {@code res} directories to filter
   * @param filterByDensity whether to filter all resources by DPI
   * @param enableStringWhitelisting whether to filter strings based on a whitelist
   * @param whitelistedStringDirs set of directories containing string resource files that must not
   *     be filtered out.
   * @param packagedLocales set of locales that must not be filtered out.
   * @param locales set of locales that the localized strings.xml files within {@code values-*}
   *     directories should be filtered by. This is useful if there are multiple apps that support a
   *     different set of locales that share a module. If empty, no filtering is performed.
   * @param targetDensities densities we're interested in keeping (e.g. {@code mdpi}, {@code hdpi}
   *     etc.) Only applicable if filterByDensity is true
   * @param imageScaler if not null, use the {@link ImageScaler} to downscale higher-density
   *     drawables for which we weren't able to find an image file of the proper density (as opposed
   */
  @VisibleForTesting
  FilterResourcesSteps(
      ProjectFilesystem filesystem,
      ImmutableBiMap<Path, Path> inResDirToOutResDirMap,
      boolean filterByDensity,
      boolean enableStringWhitelisting,
      ImmutableSet<Path> whitelistedStringDirs,
      ImmutableSet<String> packagedLocales,
      ImmutableSet<String> locales,
      @Nullable Set<ResourceFilters.Density> targetDensities,
      @Nullable ImageScaler imageScaler) {

    Preconditions.checkArgument(!filterByDensity || targetDensities != null);

    this.filesystem = filesystem;
    this.inResDirToOutResDirMap = inResDirToOutResDirMap;
    this.filterByDensity = filterByDensity;
    this.enableStringWhitelisting = enableStringWhitelisting;
    this.whitelistedStringDirs = whitelistedStringDirs;
    this.packagedLocales = packagedLocales;
    this.locales = locales;
    this.targetDensities = targetDensities;
    this.imageScaler = imageScaler;
  }

  private class CopyStep implements Step {
    @Override
    public StepExecutionResult execute(StepExecutionContext context) throws IOException {
      LOG.info(
          "FilterResourcesSteps: canDownscale: %s. imageScalar non-null: %s.",
          canDownscale(context), imageScaler != null);
      // Create filtered copies of all resource directories. These will be passed to aapt instead.
      FilteredDirectoryCopier.copyDirs(
          filesystem.getRootPath(),
          ProjectFilesystemUtils.getIgnoreFilter(
              filesystem.getRootPath(), true, filesystem.getIgnoredPaths()),
          inResDirToOutResDirMap,
          getFilteringPredicate(context));
      return StepExecutionResults.SUCCESS;
    }

    @Override
    public String getShortName() {
      return "resource_filtering-copy";
    }

    @Override
    public String getDescription(StepExecutionContext context) {
      return "Copy resources, filtering by density";
    }
  }

  private class ScaleStep implements Step {
    @Override
    public StepExecutionResult execute(StepExecutionContext context)
        throws IOException, InterruptedException {
      if (canDownscale(context) && filterByDensity) {
        scaleUnmatchedDrawables(context);
      }
      return StepExecutionResults.SUCCESS;
    }

    @Override
    public String getShortName() {
      return "resource_filtering-scale";
    }

    @Override
    public String getDescription(StepExecutionContext context) {
      return "Scale resources to the appropriate density";
    }
  }

  public Step getCopyStep() {
    return copyStep;
  }

  public Step getScaleStep() {
    return scaleStep;
  }

  private boolean canDownscale(StepExecutionContext context) {
    return imageScaler != null && imageScaler.isAvailable(context);
  }

  @VisibleForTesting
  Predicate<Path> getFilteringPredicate(StepExecutionContext context) throws IOException {
    return FilteringPredicate.getFilteringPredicate(
        filesystem.getRootPath(),
        ProjectFilesystemUtils.getIgnoreFilter(
            filesystem.getRootPath(), true, filesystem.getIgnoredPaths()),
        inResDirToOutResDirMap,
        filterByDensity,
        targetDensities,
        canDownscale(context),
        locales,
        packagedLocales,
        enableStringWhitelisting,
        whitelistedStringDirs);
  }

  /**
   * Looks through filtered drawables for files not of the target density and replaces them with
   * scaled versions.
   *
   * <p>Any drawables found by this step didn't have equivalents in the target density. If they are
   * of a higher density, we can replicate what Android does and downscale them at compile-time.
   */
  private void scaleUnmatchedDrawables(StepExecutionContext context)
      throws IOException, InterruptedException {
    ResourceFilters.Density targetDensity = ResourceFilters.Density.ORDERING.max(targetDensities);

    // Go over all the images that remain after filtering.
    Collection<Path> drawables =
        DrawableFinder.findDrawables(
            filesystem.getRootPath(),
            inResDirToOutResDirMap.values(),
            ProjectFilesystemUtils.getIgnoreFilter(
                filesystem.getRootPath(), true, filesystem.getIgnoredPaths()));
    for (Path drawable : drawables) {
      String drawableFileName = drawable.getFileName().toString();
      if (drawableFileName.endsWith(".xml")) {
        // Skip SVG and network drawables.
        continue;
      }
      if (drawableFileName.endsWith(".9.png")) {
        // Skip nine-patch for now.
        continue;
      }
      if (drawableFileName.endsWith(".webp")) {
        // Skip webp for now.
        continue;
      }

      ResourceFilters.Qualifiers qualifiers = ResourceFilters.Qualifiers.from(drawable.getParent());
      ResourceFilters.Density density = qualifiers.density;

      // If the image has a qualifier but it's not the right one.
      Objects.requireNonNull(targetDensities);
      if (!targetDensities.contains(density)) {

        double factor = targetDensity.value() / density.value();
        if (factor >= 1.0) {
          // There is no point in up-scaling, or converting between drawable and drawable-mdpi.
          continue;
        }

        Path tmpFile = filesystem.createTempFile("scaled_", drawableFileName);
        Objects.requireNonNull(imageScaler);
        imageScaler.scale(factor, drawable, tmpFile, context);

        long oldSize = filesystem.getFileSize(drawable);
        long newSize = filesystem.getFileSize(tmpFile);
        if (newSize > oldSize) {
          // Don't keep the new one if it is larger than the old one.
          filesystem.deleteFileAtPath(tmpFile);
          continue;
        }

        // Replace density qualifier with target density using regular expression to match
        // the qualifier in the context of a path to a drawable.
        String fromDensity = (density == ResourceFilters.Density.NO_QUALIFIER ? "" : "-") + density;
        Path destination =
            Paths.get(
                PathFormatter.pathWithUnixSeparators(drawable)
                    .replaceFirst(
                        "((?:^|/)drawable[^/]*)" + Pattern.quote(fromDensity) + "(-|$|/)",
                        "$1-" + targetDensity + "$2"));

        // Make sure destination folder exists and perform downscaling.
        filesystem.createParentDirs(destination);
        filesystem.move(tmpFile, destination);

        // Delete source file.
        filesystem.deleteFileAtPath(drawable);

        // Delete newly-empty directories to prevent missing resources errors in apkbuilder.
        Path parent = drawable.getParent();
        if (filesystem.getDirectoryContents(parent).isEmpty()) {
          filesystem.deleteFileAtPath(parent);
        }
      }
    }
  }

  public interface ImageScaler {
    boolean isAvailable(StepExecutionContext context);

    void scale(double factor, Path source, Path destination, StepExecutionContext context)
        throws IOException, InterruptedException;
  }

  /**
   * Implementation of {@link ImageScaler} that uses ImageMagick's {@code convert} command.
   *
   * @see <a href="http://www.imagemagick.org/script/index.php">ImageMagick</a>
   */
  static class ImageMagickScaler implements ImageScaler {
    private final AbsPath workingDirectory;
    private final boolean withDownwardApi;

    public ImageMagickScaler(AbsPath workingDirectory, boolean withDownwardApi) {
      this.workingDirectory = workingDirectory;
      this.withDownwardApi = withDownwardApi;
    }

    @Override
    public boolean isAvailable(StepExecutionContext context) {
      return new ExecutableFinder()
          .getOptionalExecutable(Paths.get("convert"), context.getEnvironment())
          .isPresent();
    }

    @Override
    public void scale(double factor, Path source, Path destination, StepExecutionContext context)
        throws IOException, InterruptedException {
      Step convertStep =
          new BashStep(
              workingDirectory,
              ProjectFilesystemUtils.relativize(
                  context.getRuleCellRoot(), context.getBuildCellRootPath()),
              withDownwardApi,
              "convert",
              "-adaptive-resize",
              (int) (factor * 100) + "%",
              Escaper.escapeAsBashString(source),
              Escaper.escapeAsBashString(destination));

      if (!convertStep.execute(context).isSuccess()) {
        throw new HumanReadableException("Cannot scale " + source + " to " + destination);
      }
    }
  }

  /** Helper class for interpreting the resource_filter argument to android_binary(). */
  public static class ResourceFilter implements AddsToRuleKey {

    static final ResourceFilter EMPTY_FILTER = new ResourceFilter(ImmutableList.of());

    // TODO(cjhopman): This shouldn't be stringified
    @AddToRuleKey(stringify = true)
    private final Set<String> filter;

    // TODO(cjhopman): Should these be added to the ruleKey?
    private final Set<ResourceFilters.Density> densities;
    private final boolean downscale;

    public ResourceFilter(List<String> resourceFilter) {
      this.filter = ImmutableSet.copyOf(resourceFilter);
      this.densities = new HashSet<>();

      boolean downscale = false;
      for (String component : filter) {
        if ("downscale".equals(component)) {
          downscale = true;
        } else {
          densities.add(ResourceFilters.Density.from(component));
        }
      }

      this.downscale = downscale;
    }

    public boolean shouldDownscale() {
      return isEnabled() && downscale;
    }

    @Nullable
    public Set<ResourceFilters.Density> getDensities() {
      return densities;
    }

    public boolean isEnabled() {
      return !densities.isEmpty();
    }

    public String getDescription() {
      return filter.toString();
    }

    @VisibleForTesting
    Set<String> getFilter() {
      return filter;
    }
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {
    @Nullable private ProjectFilesystem filesystem;
    @Nullable private ImmutableBiMap<Path, Path> inResDirToOutResDirMap;
    @Nullable private ResourceFilter resourceFilter;
    private ImmutableSet<Path> whitelistedStringDirs = ImmutableSet.of();
    private ImmutableSet<String> packagedLocales = ImmutableSet.of();
    private ImmutableSet<String> locales = ImmutableSet.of();
    private boolean enableStringWhitelisting = false;
    private boolean withDownwardApi = false;

    private Builder() {}

    public Builder setProjectFilesystem(ProjectFilesystem filesystem) {
      this.filesystem = filesystem;
      return this;
    }

    public Builder setInResToOutResDirMap(ImmutableBiMap<Path, Path> inResDirToOutResDirMap) {
      this.inResDirToOutResDirMap = inResDirToOutResDirMap;
      return this;
    }

    public Builder setResourceFilter(ResourceFilter resourceFilter) {
      this.resourceFilter = resourceFilter;
      return this;
    }

    public Builder enableStringWhitelisting() {
      this.enableStringWhitelisting = true;
      return this;
    }

    public Builder withDownwardApi(boolean withDownwardApi) {
      this.withDownwardApi = withDownwardApi;
      return this;
    }

    public Builder setWhitelistedStringDirs(ImmutableSet<Path> whitelistedStringDirs) {
      this.whitelistedStringDirs = whitelistedStringDirs;
      return this;
    }

    public Builder setPackagedLocales(ImmutableSet<String> packagedLocales) {
      this.packagedLocales = packagedLocales;
      return this;
    }

    public Builder setLocales(ImmutableSet<String> locales) {
      this.locales = locales;
      return this;
    }

    public FilterResourcesSteps build() {
      Objects.requireNonNull(filesystem);
      Objects.requireNonNull(resourceFilter);
      LOG.info("FilterResourcesSteps.Builder: resource filter: %s", resourceFilter);
      Objects.requireNonNull(inResDirToOutResDirMap);
      return new FilterResourcesSteps(
          filesystem,
          inResDirToOutResDirMap,
          /* filterByDensity */ resourceFilter.isEnabled(),
          enableStringWhitelisting,
          whitelistedStringDirs,
          packagedLocales,
          locales,
          resourceFilter.getDensities(),
          resourceFilter.shouldDownscale()
              ? new ImageMagickScaler(filesystem.getRootPath(), withDownwardApi)
              : null);
    }
  }
}
