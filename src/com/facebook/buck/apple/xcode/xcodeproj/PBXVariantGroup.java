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

package com.facebook.buck.apple.xcode.xcodeproj;

import com.facebook.buck.apple.xcode.AbstractPBXObjectFactory;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import java.util.Objects;
import javax.annotation.Nullable;

/** Group for referencing localized resources. */
public final class PBXVariantGroup extends PBXGroup {

  private final LoadingCache<VirtualNameAndSourceTreePath, PBXFileReference>
      variantFileReferencesByNameAndSourceTreePath;

  public PBXVariantGroup(
      String name,
      @Nullable String path,
      SourceTree sourceTree,
      AbstractPBXObjectFactory objectFactory) {
    super(name, path, sourceTree, objectFactory);

    variantFileReferencesByNameAndSourceTreePath =
        CacheBuilder.newBuilder()
            .build(
                new CacheLoader<VirtualNameAndSourceTreePath, PBXFileReference>() {
                  @Override
                  public PBXFileReference load(VirtualNameAndSourceTreePath key) {
                    PBXFileReference ref =
                        key.getSourceTreePath()
                            .createFileReference(key.getVirtualName(), objectFactory);
                    getChildren().add(ref);
                    return ref;
                  }
                });
  }

  public PBXFileReference getOrCreateVariantFileReferenceByNameAndSourceTreePath(
      String virtualName, SourceTreePath sourceTreePath) {
    VirtualNameAndSourceTreePath key =
        new VirtualNameAndSourceTreePath(virtualName, sourceTreePath);
    return variantFileReferencesByNameAndSourceTreePath.getUnchecked(key);
  }

  @Override
  public String isa() {
    return "PBXVariantGroup";
  }

  private static class VirtualNameAndSourceTreePath {
    private final String virtualName;
    private final SourceTreePath sourceTreePath;

    public VirtualNameAndSourceTreePath(String virtualName, SourceTreePath sourceTreePath) {
      this.virtualName = virtualName;
      this.sourceTreePath = sourceTreePath;
    }

    public String getVirtualName() {
      return virtualName;
    }

    public SourceTreePath getSourceTreePath() {
      return sourceTreePath;
    }

    @Override
    public boolean equals(Object other) {
      if (!(other instanceof VirtualNameAndSourceTreePath)) {
        return false;
      }

      VirtualNameAndSourceTreePath that = (VirtualNameAndSourceTreePath) other;
      return Objects.equals(this.virtualName, that.virtualName)
          && Objects.equals(this.sourceTreePath, that.sourceTreePath);
    }

    @Override
    public int hashCode() {
      return Objects.hash(virtualName, sourceTreePath);
    }
  }
}
