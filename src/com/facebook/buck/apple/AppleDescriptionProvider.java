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

package com.facebook.buck.apple;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.description.Description;
import com.facebook.buck.core.description.DescriptionCreationContext;
import com.facebook.buck.core.model.targetgraph.DescriptionProvider;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.cxx.CxxBinaryFactory;
import com.facebook.buck.cxx.CxxBinaryFlavored;
import com.facebook.buck.cxx.CxxBinaryImplicitFlavors;
import com.facebook.buck.cxx.CxxBinaryMetadataFactory;
import com.facebook.buck.cxx.CxxLibraryFactory;
import com.facebook.buck.cxx.CxxLibraryFlavored;
import com.facebook.buck.cxx.CxxLibraryImplicitFlavors;
import com.facebook.buck.cxx.CxxLibraryMetadataFactory;
import com.facebook.buck.cxx.config.CxxBuckConfig;
import com.facebook.buck.downwardapi.config.DownwardApiConfig;
import com.facebook.buck.infer.InferConfig;
import com.facebook.buck.swift.SwiftBuckConfig;
import com.facebook.buck.swift.SwiftLibraryDescription;
import java.util.Arrays;
import java.util.Collection;
import org.pf4j.Extension;

@Extension
public class AppleDescriptionProvider implements DescriptionProvider {
  @Override
  public Collection<Description<?>> getDescriptions(DescriptionCreationContext context) {
    ToolchainProvider toolchainProvider = context.getToolchainProvider();
    BuckConfig config = context.getBuckConfig();
    SwiftBuckConfig swiftBuckConfig = new SwiftBuckConfig(config);
    CxxBuckConfig cxxBuckConfig = new CxxBuckConfig(config);
    InferConfig inferConfig = InferConfig.of(config);
    AppleConfig appleConfig = config.getView(AppleConfig.class);
    DownwardApiConfig downwardApiConfig = config.getView(DownwardApiConfig.class);

    CxxBinaryImplicitFlavors cxxBinaryImplicitFlavors =
        new CxxBinaryImplicitFlavors(toolchainProvider, cxxBuckConfig);
    CxxBinaryFactory cxxBinaryFactory =
        new CxxBinaryFactory(toolchainProvider, cxxBuckConfig, downwardApiConfig, inferConfig);
    CxxBinaryMetadataFactory cxxBinaryMetadataFactory =
        new CxxBinaryMetadataFactory(toolchainProvider);
    CxxBinaryFlavored cxxBinaryFlavored = new CxxBinaryFlavored(toolchainProvider, cxxBuckConfig);

    CxxLibraryImplicitFlavors cxxLibraryImplicitFlavors =
        new CxxLibraryImplicitFlavors(toolchainProvider, cxxBuckConfig);
    CxxLibraryFlavored cxxLibraryFlavored =
        new CxxLibraryFlavored(toolchainProvider, cxxBuckConfig);
    CxxLibraryFactory cxxLibraryFactory =
        new CxxLibraryFactory(toolchainProvider, cxxBuckConfig, inferConfig, downwardApiConfig);
    CxxLibraryMetadataFactory cxxLibraryMetadataFactory =
        new CxxLibraryMetadataFactory(
            toolchainProvider, config.getFilesystem(), cxxBuckConfig, downwardApiConfig);

    SwiftLibraryDescription swiftLibraryDescription =
        new SwiftLibraryDescription(
            toolchainProvider, cxxBuckConfig, swiftBuckConfig, downwardApiConfig);

    XCodeDescriptions xcodeDescriptions =
        XCodeDescriptionsFactory.create(context.getPluginManager());

    AppleLibraryDescription appleLibraryDescription =
        new AppleLibraryDescription(
            toolchainProvider,
            xcodeDescriptions,
            swiftLibraryDescription,
            appleConfig,
            cxxBuckConfig,
            swiftBuckConfig,
            downwardApiConfig,
            cxxLibraryImplicitFlavors,
            cxxLibraryFlavored,
            cxxLibraryFactory,
            cxxLibraryMetadataFactory);

    AppleBinaryDescription appleBinaryDescription =
        new AppleBinaryDescription(
            toolchainProvider,
            xcodeDescriptions,
            swiftLibraryDescription,
            appleConfig,
            cxxBuckConfig,
            swiftBuckConfig,
            downwardApiConfig,
            cxxBinaryImplicitFlavors,
            cxxBinaryFactory,
            cxxBinaryMetadataFactory,
            cxxBinaryFlavored);

    return Arrays.asList(
        new AppleAssetCatalogDescription(),
        new AppleResourceDescription(),
        new CoreDataModelDescription(),
        new XcodePrebuildScriptDescription(),
        new XcodePostbuildScriptDescription(),
        appleLibraryDescription,
        new PrebuiltAppleFrameworkDescription(toolchainProvider, cxxBuckConfig),
        appleBinaryDescription,
        new ApplePackageDescription(
            toolchainProvider,
            context.getSandboxExecutionStrategy(),
            appleConfig,
            downwardApiConfig),
        new AppleBundleDescription(
            toolchainProvider,
            xcodeDescriptions,
            appleBinaryDescription,
            appleLibraryDescription,
            appleConfig,
            cxxBuckConfig,
            swiftBuckConfig,
            downwardApiConfig),
        new AppleTestDescription(
            toolchainProvider,
            xcodeDescriptions,
            appleConfig,
            cxxBuckConfig,
            swiftBuckConfig,
            downwardApiConfig,
            appleLibraryDescription),
        new SceneKitAssetsDescription(),
        new AppleToolchainSetDescription(),
        new AppleToolchainDescription());
  }
}
