/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.junit.Test;

public class EnvTest {

  @Test
  public void test() throws Exception {
    assertEquals(System.getenv("A"), "B");
    String fileName = System.getenv("FILE");
    Path path = Paths.get(fileName);
    assertTrue("File " + fileName + " should exist", Files.exists(path));
    List<String> strings = Files.readAllLines(path);
    assertEquals("File should have one string inside", 1, strings.size());
    assertEquals("location works", strings.get(0));
  }
}
