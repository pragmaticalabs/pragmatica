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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// Pins the `PARK_SLEEP` pass (#3386) over source fixtures: a `parkNanos` call that no loop encloses is a hit, one inside a loop is not.
/// The call name is built from pieces so this file never carries the call it scans for.
///
/// #3850 (lane A): the invariant is that every park waits to a DEADLINE or to a RE-CHECKED STATE; the rows prefixed `ruled_` pin the
/// RULED behaviour and are `@Disabled` while the gate still enforces only "a loop encloses the call". Each is shadowed by an enabled
/// `tripwire_` row that asserts what the gate does TODAY and says what lane B enables; lane B deletes each tripwire and enables its
/// `ruled_` row in the same change as the gate. The rows without either prefix hold at the base and must keep holding.
class SingleParkSleepTest {
    private static final String ENABLE = "#3850 lane B: enable the ruled_ row of the same name, delete this tripwire: ";
    private static final String GATE_SCOPE = "the gate refuses a park whose nearest enclosing loop is neither a deadline nor a re-checked state";
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

    @Test
    void hits_aCallInAnIfInsideALoopInsideABlockLambdaInCallParentheses_isNotReported_theLambdaBodyEndsItsStatementsNormally() {
        assertThat(hitLines("class A {",
                            "    void waits() {",
                            "        run(() -> {",
                            "            while (!done()) {",
                            "                tick();",
                            "                if (slow()) {",
                            "                    " + PARK,
                            "                }",
                            "            }",
                            "        });",
                            "    }",
                            "}")).isEmpty();
    }

    @Test
    void hits_aCallInACaseArmOrDefaultArmInsideALoop_isNotReported() {
        assertThat(hitLines("class A {",
                            "    void waits(int k) {",
                            "        while (!done()) {",
                            "            switch (k) {",
                            "                case 1 -> {",
                            "                    " + PARK,
                            "                }",
                            "                default -> {",
                            "                    " + PARK,
                            "                }",
                            "            }",
                            "        }",
                            "    }",
                            "}")).isEmpty();
    }

    @Test
    void hits_aCallInACaseArmOutsideAnyLoop_isReported() {
        assertThat(hitLines("class A {",
                            "    void sleeps(int k) {",
                            "        switch (k) {",
                            "            case 1 -> {",
                            "                " + PARK,
                            "            }",
                            "            default -> {}",
                            "        }",
                            "    }",
                            "}")).containsExactly(5);
    }

    @Test
    void hits_aCharLiteralHoldingABrace_isBlanked_soItOpensNoBlock() {
        assertThat(hitLines("class A {",
                            "    void waits() {",
                            "        while (!done()) {",
                            "            char open = '{';",
                            "            " + PARK,
                            "        }",
                            "    }",
                            "}")).isEmpty();
    }

    @Test
    void hits_theHitText_isTheSourceLineStripped() {
        var source = String.join("\n", "class A {", "    void sleeps() {", "        " + PARK + "  ", "    }", "}");

        var hits = SingleParkSleep.hits("A.java", source.split("\n", -1), source);

        assertThat(hits).extracting(FlakeScan.Hit::text).containsExactly(PARK);
    }

    // ---- #3850: deadline-or-state invariant. Failure input 1: a count-only loop is a single-call sleep. ----

    private static String[] countOnlyLoop() {
        return new String[]{"class A {", "    void paces() {", "        for (int i = 0; i < 5; i++) {", "            " + PARK, "        }", "    }", "}"};
    }

    @Disabled("#3850 lane B")
    @Test
    void ruled_aCountOnlyForLoop_isReported() {
        assertThat(hitLines(countOnlyLoop())).as(GATE_SCOPE).containsExactly(4);
    }

    @Test
    void tripwire_aCountOnlyForLoop_isStillAcceptedAtTheBase() {
        assertThat(hitLines(countOnlyLoop())).as(ENABLE + "ruled_aCountOnlyForLoop_isReported").isEmpty();
    }

    @Disabled("#3850 lane B")
    @Test
    void ruled_aCountOnlyLoopNestedInADeadlineLoop_isReported_theNearestLoopDecides() {
        assertThat(hitLines(nestedCountOnly())).as(GATE_SCOPE).containsExactly(5);
    }

    @Test
    void tripwire_aCountOnlyLoopNestedInADeadlineLoop_isStillAcceptedAtTheBase() {
        assertThat(hitLines(nestedCountOnly())).as(ENABLE + "ruled_aCountOnlyLoopNestedInADeadlineLoop_isReported_theNearestLoopDecides").isEmpty();
    }

    private static String[] nestedCountOnly() {
        return new String[]{"class A {",
                            "    void paces() {",
                            "        while (System.nanoTime() < deadline) {",
                            "            for (int i = 0; i < 3; i++) {",
                            "                " + PARK,
                            "            }",
                            "        }",
                            "    }",
                            "}"};
    }

    // ---- Failure inputs 2 and 3: a deadline loop and a state loop are accepted (hold at the base, must keep holding). ----

    @Test
    void hits_aDeadlineLoop_isNotReported() {
        assertThat(hitLines("class A {",
                            "    void waits() {",
                            "        while (System.nanoTime() < deadline) {",
                            "            tick();",
                            "            " + PARK,
                            "        }",
                            "    }",
                            "}")).isEmpty();
    }

    @Test
    void hits_aStateLoopOverAnAtomicFlag_isNotReported_bracedAndBraceless() {
        assertThat(hitLines("class A {",
                            "    void waits() {",
                            "        while (!done.get()) " + PARK,
                            "        while (!done.get()) {",
                            "            " + PARK,
                            "        }",
                            "    }",
                            "}")).isEmpty();
    }

    // ---- Failure input 4: a counted retry. Accepted only when the count is bounded AND each pass re-checks state. ----

    @Test
    void hits_aBoundedCountedRetryWhoseEveryPassExitsOnState_isNotReported() {
        assertThat(hitLines("class A {",
                            "    void retries() {",
                            "        for (int a = 1; a <= 3; a++) {",
                            "            if (ok()) {",
                            "                return;",
                            "            }",
                            "            " + PARK,
                            "        }",
                            "    }",
                            "}")).isEmpty();
    }

    @Test
    void hits_theForgeBackOffShape_stateExitThenAParkGuardedByTheCounter_isNotReported() {
        assertThat(hitLines("class A {",
                            "    String retries() {",
                            "        for (int attempt = 1; attempt <= 3; attempt++) {",
                            "            last = post();",
                            "            if (!last.contains(\"error\")) {",
                            "                return last;",
                            "            }",
                            "            if (attempt < 3) {",
                            "                " + PARK,
                            "            }",
                            "        }",
                            "        return last;",
                            "    }",
                            "}")).isEmpty();
    }

    @Test
    void hits_aCountBoundedLoopWhoseConditionReadsState_isNotReported() {
        assertThat(hitLines("class A {",
                            "    void waits() {",
                            "        for (int i = 0; i < 100 && !done(); i++) {",
                            "            " + PARK,
                            "        }",
                            "    }",
                            "}")).isEmpty();
    }

    private static String[] counterOnlyExit() {
        return new String[]{"class A {",
                            "    void retries() {",
                            "        for (int a = 1; a <= 3; a++) {",
                            "            call();",
                            "            if (a == 3) {",
                            "                return;",
                            "            }",
                            "            " + PARK,
                            "        }",
                            "    }",
                            "}"};
    }

    @Disabled("#3850 lane B")
    @Test
    void ruled_aCountedRetryWhoseOnlyExitReadsTheCounter_isReported_noPassRechecksState() {
        assertThat(hitLines(counterOnlyExit())).as(GATE_SCOPE).containsExactly(8);
    }

    @Test
    void tripwire_aCountedRetryWhoseOnlyExitReadsTheCounter_isStillAcceptedAtTheBase() {
        assertThat(hitLines(counterOnlyExit())).as(ENABLE + "ruled_aCountedRetryWhoseOnlyExitReadsTheCounter_isReported_noPassRechecksState").isEmpty();
    }

    private static String[] counterGuardedPark() {
        return new String[]{"class A {",
                            "    void retries() {",
                            "        for (int a = 1; a <= 3; a++) {",
                            "            call();",
                            "            if (a < 3) {",
                            "                " + PARK,
                            "            }",
                            "        }",
                            "    }",
                            "}"};
    }

    @Disabled("#3850 lane B")
    @Test
    void ruled_aCountedLoopWithNoExitAtAll_isReported_theResultOfEachPassIsDiscarded() {
        assertThat(hitLines(counterGuardedPark())).as(GATE_SCOPE).containsExactly(6);
    }

    @Test
    void tripwire_aCountedLoopWithNoExitAtAll_isStillAcceptedAtTheBase() {
        assertThat(hitLines(counterGuardedPark())).as(ENABLE + "ruled_aCountedLoopWithNoExitAtAll_isReported_theResultOfEachPassIsDiscarded").isEmpty();
    }

    private static String[] unconditionalLoop() {
        return new String[]{"class A {", "    void spins() {", "        while (true) {", "            tick();", "            " + PARK, "        }", "    }", "}"};
    }

    @Disabled("#3850 lane B")
    @Test
    void ruled_aLoopThatReadsNothing_isReported() {
        assertThat(hitLines(unconditionalLoop())).as(GATE_SCOPE).containsExactly(5);
    }

    @Test
    void tripwire_aLoopThatReadsNothing_isStillAcceptedAtTheBase() {
        assertThat(hitLines(unconditionalLoop())).as(ENABLE + "ruled_aLoopThatReadsNothing_isReported").isEmpty();
    }

    // ---- Failure input 5: the blind spots named in #3850. ----

    @Test
    void hits_aNegativeObservationLoopThatAlsoExitsOnState_isNotReported_aNamedLimitNotCoverage() {
        // QuicLaneEndMarkerFinLossTest:229 shape: the park waits to show that something does NOT happen, so it should be a Pause; the
        // exit on state (the thing happened) makes it look like a wait for state. Telling the two apart is not syntactically decidable
        // (CTO ruling 11-27): the gate accepts it, and the converted sites are the enforcer. This row pins the limit, it is not coverage.
        assertThat(hitLines("class A {",
                            "    boolean stillQuiet() {",
                            "        for (var check = 0; check < 5; check++) {",
                            "            if (stream.isWritable()) {",
                            "                return false;",
                            "            }",
                            "            " + PARK,
                            "        }",
                            "        return true;",
                            "    }",
                            "}")).isEmpty();
    }

    @Test
    void hits_aDeadlinePollWhoseDeadlineIsReadInTheBody_isNotReported_theThreeRepoShapes() {
        // DurableEntityForgeTest:798 (throw), Issue1117ReadinessTracerTest:478 (return), QuicSimultaneousDialLaneTest:145 (fail(...)): a
        // classifier that reads only the loop condition refuses all three; the clock sits in a guard of the body.
        assertThat(hitLines("class A {",
                            "    void throwing() {",
                            "        while (true) {",
                            "            if (System.nanoTime() >= deadline) {",
                            "                throw new AssertionError(\"x\");",
                            "            }",
                            "            " + PARK,
                            "        }",
                            "    }",
                            "    List<S> returning() {",
                            "        while (true) {",
                            "            var atMs = (System.nanoTime() - start) / 1_000_000;",
                            "            if (atMs >= CAP) {",
                            "                return samples;",
                            "            }",
                            "            " + PARK,
                            "        }",
                            "    }",
                            "    void failing() {",
                            "        for (;;) {",
                            "            if (System.nanoTime() > deadline) {",
                            "                fail(\"x\");",
                            "            }",
                            "            " + PARK,
                            "        }",
                            "    }",
                            "}")).isEmpty();
    }

    private static String[] caseArmLambdaInLoop() {
        return new String[]{"class A {",
                            "    void waits(int k) {",
                            "        while (!done()) {",
                            "            switch (k) {",
                            "                case 1 -> run(() -> {",
                            "                    " + PARK,
                            "                });",
                            "                default -> {}",
                            "            }",
                            "        }",
                            "    }",
                            "}"};
    }

    @Disabled("#3850 lane B")
    @Test
    void ruled_aBlockLambdaInACaseArmInsideALoop_isReported_theLambdaBodyIsABoundary() {
        assertThat(hitLines(caseArmLambdaInLoop())).as(GATE_SCOPE).containsExactly(6);
    }

    @Test
    void tripwire_aBlockLambdaInACaseArmInsideALoop_isStillAcceptedAtTheBase() {
        assertThat(hitLines(caseArmLambdaInLoop())).as(ENABLE + "ruled_aBlockLambdaInACaseArmInsideALoop_isReported_theLambdaBodyIsABoundary").isEmpty();
    }

    private static String[] unicodeIdentifierAroundThePark() {
        return new String[]{"class A {", "    void pauses() {", "        \u00e9park" + "Nanos(5);", "    }", "}"};
    }

    @Disabled("#3850 lane B")
    @Test
    void ruled_aMethodWhoseNameEndsInTheParkName_isNotAParkCall_unicodeLettersAreIdentifierLetters() {
        assertThat(hitLines(unicodeIdentifierAroundThePark())).isEmpty();
    }

    @Test
    void tripwire_aMethodWhoseNameEndsInTheParkName_isStillReportedAtTheBase() {
        assertThat(hitLines(unicodeIdentifierAroundThePark())).as(ENABLE + "ruled_aMethodWhoseNameEndsInTheParkName_isNotAParkCall_unicodeLettersAreIdentifierLetters")
                                                              .containsExactly(3);
    }

    private static String[] unicodeIdentifierBeforeAWhile() {
        return new String[]{"class A {", "    void \u03b1while(int x) {", "        " + PARK, "    }", "}"};
    }

    @Disabled("#3850 lane B")
    @Test
    void ruled_aMethodNamedWithAUnicodeLetterBeforeWhile_isNotALoop_itsParkIsReported() {
        assertThat(hitLines(unicodeIdentifierBeforeAWhile())).as(GATE_SCOPE).containsExactly(3);
    }

    @Test
    void tripwire_aMethodNamedWithAUnicodeLetterBeforeWhile_isStillTakenForALoopAtTheBase() {
        assertThat(hitLines(unicodeIdentifierBeforeAWhile())).as(ENABLE + "ruled_aMethodNamedWithAUnicodeLetterBeforeWhile_isNotALoop_itsParkIsReported").isEmpty();
    }

    // ---- Whole repository: the count-only census at 7292523b36 (s56/facts-i3850a.md), file -> number of count-only sites in it. ----

    private static final Map<String, Integer> COUNT_ONLY_SITES = Map.of("aether/aether-stream/src/test/java/org/pragmatica/aether/stream/DivergentTailPromotionTest.java", 1,
                                                                        "aether/aether-stream/src/test/java/org/pragmatica/aether/stream/OwnerActivationTest.java", 3,
                                                                        "aether/ember/src/test/java/org/pragmatica/aether/ember/EmberForwardLaneTripwireTest.java", 1,
                                                                        "aether/forge/forge-tests/src/test/java/org/pragmatica/aether/forge/StreamFanoutConsumerTest.java", 1,
                                                                        "aether/forge/forge-tests/src/test/java/org/pragmatica/aether/forge/StreamOwnerKillWritesResumeTest.java", 1,
                                                                        "integrations/consensus/src/test/java/org/pragmatica/consensus/net/quic/QuicDialAttemptTest.java", 1,
                                                                        "integrations/consensus/src/test/java/org/pragmatica/consensus/net/quic/QuicLaneBacklogTest.java", 1);

    private static Path repositoryRoot() {
        var dir = Path.of("").toAbsolutePath();

        while (dir != null && !Files.isDirectory(dir.resolve("testing/src/main/java"))) {
            dir = dir.getParent();
        }
        return dir == null ? Path.of("").toAbsolutePath() : dir;
    }

    private static Map<String, Long> parkSleepHitsInTheCensusFiles() throws IOException {
        var root = repositoryRoot();
        var scan = FlakeScan.scan(root);

        assertThat(scan.examinedFiles()).as("positive control: the scan reached every file of the count-only census").containsAll(COUNT_ONLY_SITES.keySet());
        return COUNT_ONLY_SITES.keySet()
                               .stream()
                               .collect(Collectors.toMap(file -> file,
                                                                          file -> scan.hits()
                                                                                      .stream()
                                                                                      .filter(hit -> hit.kind() == FlakeScan.Kind.PARK_SLEEP && hit.file().equals(file))
                                                                                      .count()));
    }

    @Disabled("#3850 lane B")
    @Test
    void ruled_theWholeRepository_hasNoCountOnlyParkLoop_theGateRefusesEachCensusSiteUntilItIsConverted() throws IOException {
        var hits = parkSleepHitsInTheCensusFiles();

        var expected = COUNT_ONLY_SITES.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, site -> site.getValue().longValue()));

        assertThat(hits).as(GATE_SCOPE).isEqualTo(expected);
    }

    @Test
    void tripwire_theWholeRepository_stillAcceptsEveryCountOnlyCensusSiteAtTheBase() throws IOException {
        assertThat(parkSleepHitsInTheCensusFiles().values()).as(ENABLE + "ruled_theWholeRepository_hasNoCountOnlyParkLoop_theGateRefusesEachCensusSiteUntilItIsConverted")
                                                            .containsOnly(0L);
    }
}
