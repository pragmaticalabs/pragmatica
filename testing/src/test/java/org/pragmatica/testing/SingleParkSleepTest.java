/*
 *  Copyright (c) 2025-2026 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.pragmatica.testing;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// Pins the `PARK_SLEEP` pass (#3386) over source fixtures: a `parkNanos` call that no loop encloses is a hit, one inside a loop is not.
/// The call name is built from pieces so this file never carries the call it scans for.
class SingleParkSleepTest {
    private static final String PARK = "LockSupport.park" + "Nanos(1_000_000L);";

    private static List<Integer> hitLines(String... lines) {
        var source = String.join("\n", lines);

        return SingleParkSleep.hits("A.java", source.split("\n", -1), source)
                              .stream()
                              .map(FlakeScan.Hit::line)
                              .toList();
    }

    @Test
    void hits_aSingleCallInAMethod_isReportedAtItsLine() {
        assertThat(hitLines("class A {", "    void sleeps() {", "        " + PARK, "    }", "}")).containsExactly(3);
    }

    @Test
    void hits_anUnqualifiedAndAStaticImportedCall_areReported() {
        assertThat(hitLines("class A {", "    void sleeps() {", "        park" + "Nanos(5);", "    }", "}")).containsExactly(3);
    }

    @Test
    void hits_aCallInAnIfBranch_isReported() {
        assertThat(hitLines("class A {", "    void sleeps(boolean b) {", "        if (b) {", "            " + PARK, "        }", "    }", "}")).containsExactly(4);
    }

    @Test
    void hits_aCallInALambdaHandedToAHelper_isReported() {
        assertThat(hitLines("class A {", "    void sleeps() {", "        run(() -> " + PARK.replace(";", ")") + ";", "    }", "}")).containsExactly(3);
    }

    @Test
    void hits_aBlockLambdaInsideALoop_isStillReported_theLambdaBodyIsABoundary() {
        assertThat(hitLines("class A {", "    void sleeps() {", "        while (true) {", "            run(() -> {", "                " + PARK, "            });", "        }", "    }", "}")).containsExactly(5);
    }

    @Test
    void hits_twoSingleCalls_areBothReported() {
        assertThat(hitLines("class A {", "    void sleeps() {", "        " + PARK, "        " + PARK, "    }", "}")).containsExactly(3, 4);
    }

    @Test
    void hits_aCallInsideAWhileLoop_isNotReported() {
        assertThat(hitLines("class A {", "    void waits() {", "        while (!done()) {", "            " + PARK, "        }", "    }", "}")).isEmpty();
    }

    @Test
    void hits_aCallInsideAForLoopAndADoLoop_isNotReported() {
        assertThat(hitLines("class A {",
                            "    void waits() {",
                            "        for (int i = 0; i < 3; i++) {",
                            "            " + PARK,
                            "        }",
                            "        do {",
                            "            " + PARK,
                            "        } while (!done());",
                            "    }",
                            "}")).isEmpty();
    }

    @Test
    void hits_aCallInABracelessLoopBody_isNotReported() {
        assertThat(hitLines("class A {", "    void waits() {", "        while (!done())", "            " + PARK, "    }", "}")).isEmpty();
    }

    @Test
    void hits_aCallInAnIfInsideALoop_isNotReported() {
        assertThat(hitLines("class A {", "    void waits() {", "        while (!done()) {", "            if (slow()) {", "                " + PARK, "            }", "        }", "    }", "}")).isEmpty();
    }

    @Test
    void hits_aCallInACommentOrAStringLiteral_isNotReported() {
        assertThat(hitLines("class A {", "    // " + PARK, "    String s = \"" + PARK + "\";", "}")).isEmpty();
    }
}
