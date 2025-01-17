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

package com.facebook.buck.zip;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.util.zip.ZipConstants;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Date;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.apache.commons.compress.archivers.zip.ZipUtil;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;

public class ZipScrubberStepIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void modificationTimes() throws Exception {

    // Create a dummy ZIP file.
    AbsPath zip = tmp.newFile("output.zip");
    try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip.getPath()))) {
      ZipEntry entry = new ZipEntry("file1");
      byte[] data = "data1".getBytes(StandardCharsets.UTF_8);
      entry.setSize(data.length);
      out.putNextEntry(entry);
      out.write(data);
      out.closeEntry();

      entry = new ZipEntry("file2");
      data = "data2".getBytes(StandardCharsets.UTF_8);
      entry.setSize(data.length);
      out.putNextEntry(entry);
      out.write(data);
      out.closeEntry();
    }

    // Execute the zip scrubber step.
    StepExecutionContext executionContext = TestExecutionContext.newInstance();
    ZipScrubberStep step =
        ZipScrubberStep.of(tmp.getRoot().resolve(Paths.get("output.zip")).getPath());
    assertEquals(0, step.execute(executionContext).getExitCode());

    // Iterate over each of the entries, expecting to see all zeros in the time fields.
    Date dosEpoch = new Date(ZipUtil.dosToJavaTime(ZipConstants.DOS_FAKE_TIME));
    try (ZipInputStream is = new ZipInputStream(new FileInputStream(zip.toFile()))) {
      for (ZipEntry entry = is.getNextEntry(); entry != null; entry = is.getNextEntry()) {
        assertThat(entry.getName(), new Date(entry.getTime()), Matchers.equalTo(dosEpoch));
      }
    }
  }
}
