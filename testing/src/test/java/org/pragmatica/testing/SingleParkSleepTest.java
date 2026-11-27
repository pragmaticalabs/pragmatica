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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// Pins the `PARK_SLEEP` pass (#3386) over source fixtures: a `parkNanos` call that no deadline or state loop encloses is a hit, one inside such a loop is not.
/// The call name is built from pieces so this file never carries the call it scans for.
///
/// #3850: the invariant is that every park waits to a DEADLINE or to a RE-CHECKED STATE, and the NEAREST enclosing loop decides. The rows
/// pin each decision of that rule (counted loop, bound, guard before or after the park, `do`, `for` update clock) and the blind spots the
/// issue named (a lambda in a `case` arm, Unicode identifiers). The named limit, a negative observation that also exits on state, is
/// pinned as accepted: it is not coverage, the enforcer is the conversion of the sites to `Pause`.
class SingleParkSleepTest {
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
                            "        for (; !done(); ) {",
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

    @Test
    void hits_aCountOnlyForLoop_isReported() {
        assertThat(hitLines(countOnlyLoop())).as(GATE_SCOPE).containsExactly(4);
    }

    @Test
    void hits_aCountOnlyLoopNestedInADeadlineLoop_isReported_theNearestLoopDecides() {
        assertThat(hitLines(nestedCountOnly())).as(GATE_SCOPE).containsExactly(5);
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

    @Test
    void hits_aCountedRetryWhoseOnlyExitReadsTheCounter_isReported_noPassRechecksState() {
        assertThat(hitLines(counterOnlyExit())).as(GATE_SCOPE).containsExactly(8);
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

    @Test
    void hits_aCountedLoopWithNoExitAtAll_isReported_theResultOfEachPassIsDiscarded() {
        assertThat(hitLines(counterGuardedPark())).as(GATE_SCOPE).containsExactly(6);
    }

    private static String[] unconditionalLoop() {
        return new String[]{"class A {", "    void spins() {", "        while (true) {", "            tick();", "            " + PARK, "        }", "    }", "}"};
    }

    @Test
    void hits_aLoopThatReadsNothing_isReported() {
        assertThat(hitLines(unconditionalLoop())).as(GATE_SCOPE).containsExactly(5);
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

    @Test
    void hits_aBlockLambdaInACaseArmInsideALoop_isReported_theLambdaBodyIsABoundary() {
        assertThat(hitLines(caseArmLambdaInLoop())).as(GATE_SCOPE).containsExactly(6);
    }

    private static String[] unicodeIdentifierAroundThePark() {
        return new String[]{"class A {", "    void pauses() {", "        \u00e9park" + "Nanos(5);", "    }", "}"};
    }

    @Test
    void hits_aMethodWhoseNameEndsInTheParkName_isNotAParkCall_unicodeLettersAreIdentifierLetters() {
        assertThat(hitLines(unicodeIdentifierAroundThePark())).isEmpty();
    }

    private static String[] unicodeIdentifierBeforeAWhile() {
        return new String[]{"class A {", "    void \u03b1while(int x) {", "        if (ok()) {", "            return;", "        }", "        " + PARK, "    }", "}"};
    }

    @Test
    void hits_aMethodNamedWithAUnicodeLetterBeforeWhile_isNotALoop_itsParkIsReported() {
        assertThat(hitLines(unicodeIdentifierBeforeAWhile())).as(GATE_SCOPE).containsExactly(6);
    }

    // ---- Further rows of the classification: each pins one decision of the nearest-loop rule. ----

    @Test
    void hits_aCountedRetryWhoseStateGuardComesAfterThePark_isReported_theGuardMustBeReachedBeforeThePark() {
        assertThat(hitLines("class A {",
                            "    boolean retries() {",
                            "        for (var attempt = 0; attempt < 3; attempt++) {",
                            "            " + PARK,
                            "            if (ok()) {",
                            "                return true;",
                            "            }",
                            "        }",
                            "        return false;",
                            "    }",
                            "}")).as(GATE_SCOPE).containsExactly(4);
    }

    @Test
    void hits_aCountedLoopBoundedByANamedVariable_isReported_aBareBoundComparedWithTheCounterIsNotState() {
        assertThat(hitLines("class A {",
                            "    void paces(int frames) {",
                            "        for (int i = 0; i < frames; i++) {",
                            "            " + PARK,
                            "        }",
                            "    }",
                            "}")).as(GATE_SCOPE).containsExactly(4);
    }

    @Test
    void hits_aForEachOverData_isReported_itWalksItemsItDoesNotWaitForAnything() {
        assertThat(hitLines("class A {",
                            "    void paces(List<String> items) {",
                            "        for (var item : items) {",
                            "            " + PARK,
                            "        }",
                            "    }",
                            "}")).as(GATE_SCOPE).containsExactly(4);
    }

    @Test
    void hits_aWhileWhoseCounterIsSteppedInTheBody_isReported_theCounterIsNotState() {
        assertThat(hitLines("class A {",
                            "    void paces() {",
                            "        while (n < 5) {",
                            "            n++;",
                            "            " + PARK,
                            "        }",
                            "    }",
                            "}")).as(GATE_SCOPE).containsExactly(5);
    }

    @Test
    void hits_aDeadlineReadInTheForUpdate_isNotReported_theClassicParkToDeadlineLoop() {
        assertThat(hitLines("class A {",
                            "    void pauses() {",
                            "        for (var left = until - System.nanoTime(); left > 0; left = until - System.nanoTime()) {",
                            "            " + PARK,
                            "        }",
                            "    }",
                            "}")).isEmpty();
    }

    @Test
    void hits_aDoLoopWhoseConditionReadsState_isNotReportedAndOneThatReadsNothingIs() {
        assertThat(hitLines("class A {",
                            "    void waits() {",
                            "        do {",
                            "            " + PARK,
                            "        } while (!done());",
                            "        do {",
                            "            " + PARK,
                            "        } while (true);",
                            "    }",
                            "}")).containsExactly(7);
    }

    @Test
    void hits_aStateGuardInsideAnotherBlockOfTheLoopBody_isReported_onlyTheTopLevelOfTheBodyCounts() {
        assertThat(hitLines("class A {",
                            "    void spins() {",
                            "        while (true) {",
                            "            if (more()) {",
                            "                if (ok()) {",
                            "                    return;",
                            "                }",
                            "            }",
                            "            " + PARK,
                            "        }",
                            "    }",
                            "}")).as(GATE_SCOPE).containsExactly(9);
    }

    @Test
    void hits_aGuardWhoseBlockHoldsThePark_isReported_thePassDoesNotExitBeforeIt() {
        assertThat(hitLines("class A {",
                            "    void spins() {",
                            "        while (true) {",
                            "            if (ok()) {",
                            "                " + PARK,
                            "                return;",
                            "            }",
                            "        }",
                            "    }",
                            "}")).as(GATE_SCOPE).containsExactly(5);
    }

    @Test
    void hits_aBracelessGuardBeforeThePark_isNotReported() {
        assertThat(hitLines("class A {",
                            "    void waits() {",
                            "        while (true) {",
                            "            if (ok()) return;",
                            "            " + PARK,
                            "        }",
                            "    }",
                            "}")).isEmpty();
    }

    @Test
    void hits_aGuardThatDoesNotExit_isReported_itOnlyReadsState() {
        assertThat(hitLines("class A {",
                            "    void spins() {",
                            "        while (true) {",
                            "            if (ok()) {",
                            "                log();",
                            "            }",
                            "            " + PARK,
                            "        }",
                            "    }",
                            "}")).as(GATE_SCOPE).containsExactly(7);
    }

    @Test
    void hits_aBracelessCountOnlyLoop_isReported() {
        assertThat(hitLines("class A {",
                            "    void paces() {",
                            "        for (int i = 0; i < 5; i++)",
                            "            " + PARK,
                            "    }",
                            "}")).as(GATE_SCOPE).containsExactly(4);
    }

    @Test
    void hits_aBreakGuardBeforeThePark_isNotReported() {
        assertThat(hitLines("class A {",
                            "    void waits() {",
                            "        while (true) {",
                            "            if (ready()) {",
                            "                break;",
                            "            }",
                            "            " + PARK,
                            "        }",
                            "    }",
                            "}")).isEmpty();
    }

    @Test
    void hits_aWallClockOrInstantDeadlineInTheCondition_isNotReported() {
        // each condition reads a clock and nothing else (no variable holds the deadline), so the clock alone makes it a deadline loop
        assertThat(hitLines("class A {",
                            "    void waits() {",
                            "        while (System.currentTimeMillis() < 5_000L) {",
                            "            " + PARK,
                            "        }",
                            "        while (Instant.now().isBefore(Instant.MAX)) {",
                            "            " + PARK,
                            "        }",
                            "        while (System.nanoTime() < 5_000L) {",
                            "            " + PARK,
                            "        }",
                            "    }",
                            "}")).isEmpty();
    }

    @Test
    void hits_aClockReadOnceInTheForInitialiser_isReported_onlyAClockReReadByTheLoopIsADeadline() {
        assertThat(hitLines("class A {",
                            "    void paces() {",
                            "        for (var t = System.nanoTime(); n < 5; n++) {",
                            "            " + PARK,
                            "        }",
                            "    }",
                            "}")).as(GATE_SCOPE).containsExactly(4);
    }

    @Test
    void hits_aCounterAssignedInTheBodyAndDeclaredInTheHeader_isReported_theDeclarationMakesItACounter() {
        assertThat(hitLines("class A {",
                            "    void paces() {",
                            "        for (var i = 0; i < 5; ) {",
                            "            i = i + 1;",
                            "            " + PARK,
                            "        }",
                            "    }",
                            "}")).as(GATE_SCOPE).containsExactly(5);
    }

    @Test
    void hits_aCounterSteppedByAPrefixOperatorInTheBody_isReported() {
        assertThat(hitLines("class A {",
                            "    void paces() {",
                            "        while (n < 5) {",
                            "            ++n;",
                            "            " + PARK,
                            "        }",
                            "    }",
                            "}")).as(GATE_SCOPE).containsExactly(5);
    }

    @Test
    void hits_aBoundWrittenBeforeTheCounter_isReported() {
        assertThat(hitLines("class A {",
                            "    void paces(int frames) {",
                            "        for (int i = 0; frames > i; i++) {",
                            "            " + PARK,
                            "        }",
                            "    }",
                            "}")).as(GATE_SCOPE).containsExactly(4);
    }

    @Test
    void hits_aBracelessGuardWhoseStatementOnlyHoldsAnExitInsideALambda_isReported() {
        assertThat(hitLines("class A {",
                            "    void spins() {",
                            "        while (true) {",
                            "            if (ok()) run(() -> { return; });",
                            "            " + PARK,
                            "        }",
                            "    }",
                            "}")).as(GATE_SCOPE).containsExactly(5);
    }

    @Test
    void hits_aClassNamedWithAUnicodeLetterBeforeDo_isNotADoLoop_itsInitialiserParkIsReported_theWhileThatFollowsIsNotItsCondition() {
        assertThat(hitLines("class A {",
                            "    void m() {",
                            "        class \u03b1do {",
                            "            {",
                            "                " + PARK,
                            "            }",
                            "        }",
                            "        while (!done()) {}",
                            "    }",
                            "}")).as(GATE_SCOPE).containsExactly(5);
    }

    @Test
    void hits_aQualifierWithAUnicodeLetterInABracelessLoop_isStripped_theLoopStillEnclosesTheCall() {
        assertThat(hitLines("class A {", "    void waits() {", "        while (!done()) \u00e9.park" + "Nanos(5);", "    }", "}")).isEmpty();
    }

    @Test
    void hits_aCallLambdaWhoseNameStartsWithCase_isABoundary_itIsNotACaseLabel() {
        assertThat(hitLines("class A {",
                            "    void waits() {",
                            "        while (!done()) {",
                            "            caseHandler.run(() -> {",
                            "                " + PARK,
                            "            });",
                            "        }",
                            "    }",
                            "}")).as(GATE_SCOPE).containsExactly(5);
    }

    @Test
    void hits_aLabelledIfWithAUnicodeLabelInsideAStateLoop_isControlFlow_notABoundary() {
        assertThat(hitLines("class A {",
                            "    void waits() {",
                            "        while (!done()) {",
                            "            \u00e9: if (slow()) {",
                            "                " + PARK,
                            "            }",
                            "        }",
                            "    }",
                            "}")).isEmpty();
    }

    @Test
    void hits_aCountBoundedByAConstantArrayLength_isReported_constantsAndMembersAreNotState() {
        assertThat(hitLines("class A {",
                            "    void paces() {",
                            "        for (int i = 0; i < FRAMES.length; i++) {",
                            "            " + PARK,
                            "        }",
                            "    }",
                            "}")).as(GATE_SCOPE).containsExactly(4);
    }

    @Test
    void hits_aCounterSteppedWithPlusEqualsInTheBody_isReported() {
        assertThat(hitLines("class A {",
                            "    void paces() {",
                            "        while (n < 5) {",
                            "            n += 1;",
                            "            " + PARK,
                            "        }",
                            "    }",
                            "}")).as(GATE_SCOPE).containsExactly(5);
    }

    @Test
    void hits_aCounterSteppedOnlyInTheForUpdate_isReported_everyNameOfTheUpdateIsACounter() {
        assertThat(hitLines("class A {",
                            "    void paces() {",
                            "        for (; n < 5; n = n + 1) {",
                            "            " + PARK,
                            "        }",
                            "    }",
                            "}")).as(GATE_SCOPE).containsExactly(4);
    }

    @Test
    void hits_aLoopWhoseConditionComparesALiteralWithAName_isNotReported_withNoCounterTheNameIsState() {
        assertThat(hitLines("class A {",
                            "    void waits() {",
                            "        while (0 != pending) {",
                            "            " + PARK,
                            "        }",
                            "    }",
                            "}")).isEmpty();
    }

    @Test
    void hits_aBoundReadThroughACall_isNotReported_aNamedLimitANameWithAMemberReadsAsState() {
        assertThat(hitLines("class A {",
                            "    void paces() {",
                            "        for (int i = 0; i < frames.size(); i++) {",
                            "            " + PARK,
                            "        }",
                            "    }",
                            "}")).isEmpty();
    }

    @Test
    void hits_aParkThatIsTheBodyOfABracelessIfInTheLoop_isReported_itIsNotAGuard() {
        assertThat(hitLines("class A {",
                            "    void spins() {",
                            "        while (true) {",
                            "            if (ok()) park" + "Nanos(5);",
                            "        }",
                            "    }",
                            "}")).as(GATE_SCOPE).containsExactly(4);
    }

    @Test
    void hits_aParkInsideTheCallOfABracelessIfStatement_isReported_theStatementHasNoExitYet() {
        assertThat(hitLines("class A {",
                            "    void spins() {",
                            "        while (true) {",
                            "            if (ok()) log(" + PARK.replace(";", ")") + ";",
                            "        }",
                            "    }",
                            "}")).as(GATE_SCOPE).containsExactly(4);
    }

    @Test
    void hits_aLoopKeywordAfterAUnicodeNamedCall_isReadFromTheRealKeyword() {
        assertThat(hitLines("class A {",
                            "    void waits() {",
                            "        if (\u03b1for(1)) while (!done()) {",
                            "            " + PARK,
                            "        }",
                            "    }",
                            "}")).isEmpty();
    }

    // ---- Whole repository: no park is refused anywhere. The count-only census of 7292523b36 (s56/facts-i3850a.md), plus the two negative
    // observations, were converted to Pause; the positive control is that the scan reached every file that held one. ----

    private static final List<String> CONVERTED_FILES = List.of("aether/aether-stream/src/test/java/org/pragmatica/aether/stream/DivergentTailPromotionTest.java",
                                                                "aether/aether-stream/src/test/java/org/pragmatica/aether/stream/OwnerActivationTest.java",
                                                                "aether/ember/src/test/java/org/pragmatica/aether/ember/EmberForwardLaneTripwireTest.java",
                                                                "aether/forge/forge-tests/src/test/java/org/pragmatica/aether/forge/StreamFanoutConsumerTest.java",
                                                                "aether/forge/forge-tests/src/test/java/org/pragmatica/aether/forge/StreamOwnerKillWritesResumeTest.java",
                                                                "integrations/consensus/src/test/java/org/pragmatica/consensus/net/quic/QuicDialAttemptTest.java",
                                                                "integrations/consensus/src/test/java/org/pragmatica/consensus/net/quic/QuicLaneBacklogTest.java",
                                                                "integrations/consensus/src/test/java/org/pragmatica/consensus/net/quic/QuicLaneEndMarkerFinLossTest.java");

    private static Path repositoryRoot() {
        var dir = Path.of("").toAbsolutePath();

        while (dir != null && !Files.isDirectory(dir.resolve("testing/src/main/java"))) {
            dir = dir.getParent();
        }
        return dir == null ? Path.of("").toAbsolutePath() : dir;
    }

    @Test
    void theQuicEndMarkerNegativeObservation_isAPause_theGateCannotSeeItsShape() throws IOException {
        var file = repositoryRoot().resolve("integrations/consensus/src/test/java/org/pragmatica/consensus/net/quic/QuicLaneEndMarkerFinLossTest.java");
        var source = Files.readString(file);
        var start = source.indexOf("private static boolean stillBlocked(");
        var method = source.substring(start, source.indexOf("/// Returns once", start));

        assertThat(start).as("positive control: the method is where the test looks").isPositive();
        assertThat(method).as("a wait that shows something does NOT happen needs elapsed time (#3850 named limit)").contains("Pause.pause(").doesNotContain("park" + "Nanos");
    }

    @Test
    void hits_theWholeRepository_hasNoParkSleepHit_everyCountOnlyAndNegativeObservationSiteIsConverted() throws IOException {
        var scan = FlakeScan.scan(repositoryRoot());

        assertThat(scan.examinedFiles()).as("positive control: the scan reached every converted file").containsAll(CONVERTED_FILES);
        assertThat(scan.hits().stream().filter(hit -> hit.kind() == FlakeScan.Kind.PARK_SLEEP).map(FlakeScan.Hit::key)).as(GATE_SCOPE).isEmpty();
    }
}
