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

package com.facebook.buck.cli;

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.cli.Dot.OutputOrder;
import com.facebook.buck.core.util.graph.DirectedAcyclicGraph;
import com.google.common.base.Functions;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class DotTest {

  @Parameterized.Parameters(name = "sorted={0}")
  public static Collection<Object[]> data() {
    ImmutableList.Builder<Object[]> data = ImmutableList.builder();
    for (Dot.OutputOrder sorted : Dot.OutputOrder.values()) {
      data.add(new Object[] {sorted});
    }
    return data.build();
  }

  @Parameterized.Parameter public Dot.OutputOrder outputOrder;

  @Test
  public void testGenerateDotOutput() throws IOException {
    DirectedAcyclicGraph<String> graph =
        DirectedAcyclicGraph.<String>serialBuilder()
            .addEdge("A", "B")
            .addEdge("B", "C")
            .addEdge("B", "D")
            .addEdge("C", "E")
            .addEdge("D", "E")
            .addEdge("A", "E")
            .addEdge("F", "E")
            .build();

    StringBuilder output = new StringBuilder();
    Dot.builder(graph, "the_graph")
        .setNodeToName(Functions.identity())
        .setNodeToTypeName(Functions.identity())
        .setOutputOrder(outputOrder)
        .build()
        .writeOutput(output);

    assertOutput(
        output.toString(),
        ImmutableSet.of(
            "  A -> B;",
            "  B -> C;",
            "  B -> D;",
            "  C -> E;",
            "  D -> E;",
            "  A -> E;",
            "  F -> E;",
            "  A;",
            "  B;",
            "  C;",
            "  D;",
            "  E;",
            "  F;"),
        false);
  }

  @Test
  public void testGenerateCompactDotOutput() throws IOException {
    // UNDEFINED output order causes a unique problem with dot compact mode, since the node id's
    // might be completely different and therefore you get different edges than you expect.
    assumeTrue(outputOrder != Dot.OutputOrder.UNDEFINED);

    DirectedAcyclicGraph<String> graph =
        DirectedAcyclicGraph.<String>serialBuilder()
            .addEdge("A", "B")
            .addEdge("B", "C")
            .addEdge("B", "D")
            .addEdge("C", "E")
            .addEdge("D", "E")
            .addEdge("A", "E")
            .addEdge("F", "E")
            .build();

    StringBuilder output = new StringBuilder();
    Dot.builder(graph, "the_graph")
        .setNodeToName(Functions.identity())
        .setNodeToTypeName(name -> "A")
        .setOutputOrder(outputOrder)
        .setCompactMode(true)
        .build()
        .writeOutput(output);

    if (outputOrder == OutputOrder.SORTED) {
      assertOutput(
          output.toString(),
          ImmutableSet.of(
              "  1 -> 2;",
              "  1 -> 3;",
              "  2 -> 4;",
              "  2 -> 5;",
              "  4 -> 3;",
              "  5 -> 3;",
              "  6 -> 3;",
              "  1 [style=filled,color=\"#C1C1C0\",label=A];",
              "  2 [style=filled,color=\"#C1C1C0\",label=B];",
              "  3 [style=filled,color=\"#C1C1C0\",label=E];",
              "  4 [style=filled,color=\"#C1C1C0\",label=C];",
              "  5 [style=filled,color=\"#C1C1C0\",label=D];",
              "  6 [style=filled,color=\"#C1C1C0\",label=F];"),
          true);
    } else {
      assertOutput(
          output.toString(),
          ImmutableSet.of(
              "  1 -> 2;",
              "  1 -> 3;",
              "  2 -> 5;",
              "  2 -> 6;",
              "  4 -> 3;",
              "  5 -> 3;",
              "  6 -> 3;",
              "  1 [style=filled,color=\"#C1C1C0\",label=A];",
              "  2 [style=filled,color=\"#C1C1C0\",label=B];",
              "  3 [style=filled,color=\"#C1C1C0\",label=E];",
              "  4 [style=filled,color=\"#C1C1C0\",label=F];",
              "  5 [style=filled,color=\"#C1C1C0\",label=C];",
              "  6 [style=filled,color=\"#C1C1C0\",label=D];"),
          true);
    }
  }

  @Test
  public void testGenerateDotOutputFilter() throws IOException {
    DirectedAcyclicGraph<String> graph =
        DirectedAcyclicGraph.<String>serialBuilder()
            .addEdge("A", "B")
            .addEdge("B", "C")
            .addEdge("B", "D")
            .addEdge("C", "E")
            .addEdge("D", "E")
            .addEdge("A", "E")
            .build();

    ImmutableSet<String> filter =
        ImmutableSet.<String>builder().add("A").add("B").add("C").add("D").build();

    StringBuilder output = new StringBuilder();
    Dot.builder(graph, "the_graph")
        .setNodeToName(Functions.identity())
        .setNodeToTypeName(Functions.identity())
        .setNodesToFilter(filter::contains)
        .setOutputOrder(outputOrder)
        .build()
        .writeOutput(output);

    assertOutput(
        output.toString(),
        ImmutableSet.of("  A -> B;", "  B -> C;", "  B -> D;", "  A;", "  B;", "  C;", "  D;"),
        false);
  }

  @Test
  public void testGenerateDotOutputWithColors() throws IOException {
    DirectedAcyclicGraph<String> graph =
        DirectedAcyclicGraph.<String>serialBuilder().addEdge("A", "B").build();

    StringBuilder output = new StringBuilder();
    Dot.builder(graph, "the_graph")
        .setNodeToName(Functions.identity())
        .setNodeToTypeName(name -> name.equals("A") ? "android_library" : "java_library")
        .setOutputOrder(outputOrder)
        .build()
        .writeOutput(output);

    assertOutput(
        output.toString(),
        ImmutableSet.of(
            "  A -> B;",
            "  A [style=filled,color=springgreen3];",
            "  B [style=filled,color=indianred1];"),
        true);
  }

  @Test
  public void testGenerateDotOutputWithCustomAttributes() throws IOException {
    DirectedAcyclicGraph<String> graph =
        DirectedAcyclicGraph.<String>serialBuilder().addEdge("A", "B").build();

    StringBuilder output = new StringBuilder();
    ImmutableMap<String, ImmutableSortedMap<String, Object>> nodeToAttributeProvider =
        ImmutableMap.of("A", ImmutableSortedMap.of("x", "foo", "y", "b.r"));
    Dot.builder(graph, "the_graph")
        .setNodeToName(Functions.identity())
        .setNodeToTypeName(name -> name.equals("A") ? "android_library" : "java_library")
        .setNodeToAttributes(
            node -> nodeToAttributeProvider.getOrDefault(node, ImmutableSortedMap.of()))
        .setOutputOrder(outputOrder)
        .build()
        .writeOutput(output);

    assertOutput(
        output.toString(),
        ImmutableSet.of(
            "  A -> B;",
            "  A [style=filled,color=springgreen3,buck_x=foo,buck_y=\"b.r\"];",
            "  B [style=filled,color=indianred1];"),
        true);
  }

  @Test
  public void testEscaping() throws IOException {
    DirectedAcyclicGraph<String> graph =
        DirectedAcyclicGraph.<String>serialBuilder()
            .addEdge("A", "//B")
            .addEdge("//B", "C1 C2")
            .addEdge("//B", "D\"")
            .addEdge("Z//E", "Z//F")
            .addEdge("A", "A.B")
            .addEdge("A", "A,B")
            .addEdge("A", "[A]")
            .addEdge("A", "")
            .build();

    StringBuilder output = new StringBuilder();

    Dot.builder(graph, "the_graph")
        .setNodeToName(Functions.identity())
        .setNodeToTypeName(name -> name.equals("A") ? "android_library" : "java_library")
        .setOutputOrder(outputOrder)
        .build()
        .writeOutput(output);

    assertOutput(
        output.toString(),
        ImmutableSet.of(
            "  \"\";",
            "  \"//B\" -> \"C1 C2\";",
            "  \"//B\" -> \"D\\\"\";",
            "  \"//B\";",
            "  \"A,B\";",
            "  \"A.B\";",
            "  \"C1 C2\";",
            "  \"D\\\"\";",
            "  \"Z//E\" -> \"Z//F\";",
            "  \"Z//E\";",
            "  \"Z//F\";",
            "  \"[A]\";",
            "  A -> \"\";",
            "  A -> \"//B\";",
            "  A -> \"A,B\";",
            "  A -> \"A.B\";",
            "  A -> \"[A]\";",
            "  A;"),
        false);
  }

  private static void assertOutput(
      String dotGraph, ImmutableSet<String> expectedEdges, boolean colors) {
    List<String> lines =
        Lists.newArrayList(Splitter.on(System.lineSeparator()).omitEmptyStrings().split(dotGraph));

    assertEquals("digraph the_graph {", lines.get(0));

    // remove attributes because we are not interested what styles and colors are default
    if (!colors) {
      lines = lines.stream().map(p -> p.replaceAll(" \\[.*]", "")).collect(Collectors.toList());
    }

    List<String> edges = lines.subList(1, lines.size() - 1);
    edges.sort(Ordering.natural());
    assertEquals(edges, ImmutableList.copyOf(ImmutableSortedSet.copyOf(expectedEdges)));

    assertEquals("}", lines.get(lines.size() - 1));
  }
}
