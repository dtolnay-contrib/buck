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

package com.facebook.buck.file;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.OutputLabel;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.file.downloader.Downloader;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.HashCode;
import java.net.URI;
import java.nio.file.Path;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class HttpFileBinaryTest {

  public @Rule TemporaryPaths temporaryDir = new TemporaryPaths();

  @Test
  public void executableCommandIsCorrect() {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    ProjectFilesystem filesysten =
        TestProjectFilesystems.createProjectFilesystem(temporaryDir.getRoot());
    BuildRuleParams params =
        new BuildRuleParams(
            ImmutableSortedSet::of, ImmutableSortedSet::of, ImmutableSortedSet.of());
    Downloader downloader = (eventBus, uri, output) -> false;
    ImmutableList<URI> uris = ImmutableList.of(URI.create("https://example.com"));
    HashCode sha256 =
        HashCode.fromString("f2ca1bb6c7e907d06dafe4687e579fce76b37e4e93b7605022da52e6ccc26fd2");

    HttpFileBinary binary =
        new HttpFileBinary(target, filesysten, params, downloader, uris, sha256, "foo.exe");

    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    graphBuilder.addToIndex(binary);

    Tool tool = binary.getExecutableCommand(OutputLabel.defaultLabel());

    Path expectedPath =
        filesysten.resolve(
            BuildTargetPaths.getGenPath(filesysten.getBuckPaths(), target, "%s")
                .resolve("foo.exe"));

    Assert.assertEquals(
        ImmutableList.of(expectedPath.toString()),
        tool.getCommandPrefix(graphBuilder.getSourcePathResolver()));
  }
}
