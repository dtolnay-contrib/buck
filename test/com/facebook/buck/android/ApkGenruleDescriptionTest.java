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

import static org.hamcrest.MatcherAssert.assertThat;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.BuildTargetWithOutputs;
import com.facebook.buck.core.model.OutputLabel;
import com.facebook.buck.core.model.targetgraph.FakeTargetNodeBuilder;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.impl.FakeBuildRule;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.rules.macros.ClasspathMacro;
import com.facebook.buck.rules.macros.StringWithMacrosUtils;
import java.nio.file.Paths;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Test;

public class ApkGenruleDescriptionTest {

  @Test
  public void testClasspathTransitiveDepsBecomeFirstOrderDeps() {
    BuildTarget installableApkTarget = BuildTargetFactory.newInstance("//:installable");
    TargetNode<?> installableApkNode =
        FakeTargetNodeBuilder.build(new FakeInstallable(installableApkTarget));
    TargetNode<?> transitiveDepNode =
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//exciting:dep"))
            .addSrc(Paths.get("Dep.java"))
            .build();
    TargetNode<?> depNode =
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//exciting:target"))
            .addSrc(Paths.get("Other.java"))
            .addDep(transitiveDepNode.getBuildTarget())
            .build();
    TargetNode<?> genruleNode =
        ApkGenruleBuilder.create(BuildTargetFactory.newInstance("//:rule"))
            .setOut("out")
            .setCmd(
                StringWithMacrosUtils.format(
                    "%s",
                    ClasspathMacro.of(
                        BuildTargetWithOutputs.of(
                            depNode.getBuildTarget(), OutputLabel.defaultLabel()))))
            .setApk(installableApkTarget)
            .build();

    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(installableApkNode, transitiveDepNode, depNode, genruleNode);
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);

    BuildRule transitiveDep = graphBuilder.requireRule(transitiveDepNode.getBuildTarget());
    BuildRule dep = graphBuilder.requireRule(depNode.getBuildTarget());
    BuildRule genrule = graphBuilder.requireRule(genruleNode.getBuildTarget());

    assertThat(genrule.getBuildDeps(), Matchers.hasItems(dep, transitiveDep));
  }

  private static class FakeInstallable extends FakeBuildRule implements HasInstallableApk {

    final SourcePath apkPath =
        ExplicitBuildTargetSourcePath.of(getBuildTarget(), Paths.get("buck-out", "app.apk"));

    public FakeInstallable(BuildTarget buildTarget) {
      super(buildTarget);
    }

    @Override
    public ApkInfo getApkInfo() {
      return ImmutableApkInfo.ofImpl(FakeSourcePath.of("nothing"), apkPath, Optional.empty());
    }

    @Override
    public SourcePath getSourcePathToOutput() {
      return apkPath;
    }
  }
}
