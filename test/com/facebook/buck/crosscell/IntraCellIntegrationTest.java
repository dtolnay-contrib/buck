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

package com.facebook.buck.crosscell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.Cells;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.graph.transformation.executor.DepsAwareExecutor;
import com.facebook.buck.core.graph.transformation.executor.impl.DefaultDepsAwareExecutor;
import com.facebook.buck.core.graph.transformation.model.ComputeResult;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.BuckPaths;
import com.facebook.buck.parser.Parser;
import com.facebook.buck.parser.ParsingContext;
import com.facebook.buck.parser.TestParserFactory;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.testutil.AbstractWorkspace;
import com.facebook.buck.testutil.CloseableResource;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Executors;
import org.hamcrest.Matchers;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;

public class IntraCellIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Rule
  public CloseableResource<DepsAwareExecutor<? super ComputeResult, ?>> executor =
      CloseableResource.of(() -> DefaultDepsAwareExecutor.of(4));

  @SuppressWarnings("PMD.EmptyCatchBlock")
  @Test
  public void shouldTreatCellBoundariesAsVisibilityBoundariesToo()
      throws IOException, InterruptedException, BuildFileParseException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "intracell/visibility", tmp);
    workspace.setUp();

    // We don't need to do a build. It's enough to just parse these things.
    Cells cells = new Cells(workspace.asCellProvider());

    Parser parser = TestParserFactory.create(executor.get(), cells);

    // This parses cleanly
    parser.buildTargetGraph(
        ParsingContext.builder(
                cells, MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor()))
            .build(),
        ImmutableSet.of(BuildTargetFactory.newInstance("//just-a-directory:rule")));

    try {
      // Whereas, because visibility is limited to the same cell, this won't.
      parser.buildTargetGraph(
          ParsingContext.builder(
                  cells, MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor()))
              .build(),
          ImmutableSet.of(BuildTargetFactory.newInstance("child//:child-target")));
      fail("Didn't expect parsing to work because of visibility");
    } catch (HumanReadableException e) {
      // This is expected
    }
  }

  @Test
  public void testEmbeddedBuckOut() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "intracell/visibility", tmp);
    workspace.setUp();
    Cell cell = workspace.asCell();
    Cells cells = workspace.asCells();
    assertEquals(
        cell.getFilesystem().getBuckPaths().getGenDir().toString(),
        MorePaths.pathWithPlatformSeparators("buck-out/gen"));
    Cell childCell =
        cells.getCell(BuildTargetFactory.newInstance("child//:child-target").getCell());
    assertEquals(
        childCell.getFilesystem().getBuckPaths().getGenDir().toString(),
        MorePaths.pathWithPlatformSeparators("../buck-out/cells/child/gen"));
  }

  @Test
  public void testBuckdPicksUpChangesInChildCell() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenarioWithoutDefaultCell(
            this, "intracell/visibility", tmp);
    workspace.setUp();

    String target = "//:reexported-dummy.c";

    Map<String, Map<String, String>> childLocalConfigs =
        ImmutableMap.of("log", ImmutableMap.of("jul_build_log", "true"));
    workspace.writeContentsToPath(
        AbstractWorkspace.convertToBuckConfig(childLocalConfigs), "child-repo/.buckconfig.local");

    Path childRepoRoot = workspace.getPath("child-repo");

    ProcessResult buildResult = workspace.runBuckCommand(childRepoRoot, "build", target);
    buildResult.assertSuccess();
    workspace.getBuildLog(childRepoRoot).assertTargetBuiltLocally(target);

    // Now change the contents of the file and rebuild
    workspace.replaceFileContents("child-repo/dummy.c", "exitCode = 0", "exitCode = 1");

    ProcessResult rebuildResult = workspace.runBuckCommand(childRepoRoot, "build", target);
    rebuildResult.assertSuccess();
    workspace.getBuildLog(childRepoRoot).assertTargetBuiltLocally(target);
  }

  @Test
  public void testBuckProjectGeneratesCorrectAbsolutePaths() throws IOException {
    Assume.assumeThat(Platform.detect(), Matchers.is(Platform.MACOS));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenarioWithoutDefaultCell(
            this, "intracell/visibility", tmp);
    workspace.setUp();

    Map<String, Map<String, String>> childLocalConfigs = ImmutableMap.of();
    workspace.writeContentsToPath(
        AbstractWorkspace.convertToBuckConfig(childLocalConfigs), "child-repo/.buckconfig.local");

    Path childRepoRoot = workspace.getPath("child-repo");

    ProcessResult projectResult =
        workspace.runBuckCommand(
            childRepoRoot, "project", "--ide", "xcode", "//:child-apple-library");
    projectResult.assertSuccess();

    Path outputXCConfig =
        childRepoRoot.resolve(
            "buck-out/cells/parent/gen/"
                + BuildTargetPaths.getBasePathForBaseName(
                    BuckPaths.DEFAULT_BUCK_OUT_INCLUDE_TARGET_CONFIG_HASH,
                    BuildTargetFactory.newInstance("//just-a-directory:jad-apple-library"))
                + "/jad-apple-library-Debug.xcconfig");
    assertTrue(Files.exists(outputXCConfig));
    String xcconfigContents =
        new String(Files.readAllBytes(outputXCConfig), StandardCharsets.UTF_8);
    // The key to this test - make sure that the HEADER_SEARCH_PATHS contains the right header base.
    // HACK: Since the header map base gets added as the last element, we ensure we don't pick up
    // a path component by searching for path + newline. This is obviously fragile if we move the
    // header map base to somewhere else in the search path
    assertTrue(
        xcconfigContents.contains(
            childRepoRoot.resolve("buck-out/cells/parent").toString() + "\n"));
  }
}
