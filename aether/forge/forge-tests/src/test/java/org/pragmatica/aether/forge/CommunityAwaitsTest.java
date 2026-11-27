// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;


/// #3812: the committed state is visible before the leader's count follows it. The route below is a stand-in that commits DEGRADED on its
/// first read and lets the count lag by `LAG` reads (the injected delay between the commit and the count); every body it serves is
/// one the product can produce.
class CommunityAwaitsTest {
    private static final int FORMED = 3;
    private static final int LAG = 4;
    private static final Duration BOUND = Duration.ofSeconds(5);

    /// The pairing the flake asserted: DEGRADED awaited on one read, then the bound asserted on the NEXT read. Against a count that lags
    /// the commit it is red. This pins that the stand-in really produces the pair, so the passing test below is not vacuous.
    @Test
    void pairingStateAndCountAcrossReads_failsWhenTheCountLagsTheCommit() {
        var route = laggingRoute();

        assertThatThrownBy(() -> oldPairing(route)).isInstanceOf(AssertionError.class);
    }

    @Test
    void eachFieldAwaitedOnItsOwn_passesThroughTheLag() {
        var route = laggingRoute();

        CommunityAwaits.awaitState(route, "DEGRADED", BOUND);
        CommunityAwaits.awaitLiveMembersBelow(route, FORMED, BOUND);
    }

    /// The enforcer for the strictness of every await. Each await is one row (field, comparison against a bound), and an ABSENT field never
    /// satisfies any row. Every row runs against each of {absent, bound-1, bound, bound+1} and its exact truth value is asserted, so a
    /// comparator change or an absent-passes change in any row turns a cell red. A true cell must return; a false cell must time out.
    @Test
    void everyAwaitRow_holdsItsExactTruthValueOnAbsentBelowAtAndAboveItsBound() {
        var mismatches = new ArrayList<String>();

        rows().forEach(row -> row.cells()
                                 .forEach((body, expected) -> checkCell(row, body, expected, mismatches)));
        assertThat(mismatches).isEmpty();
    }

    private static void checkCell(Row row, String body, boolean expected, List<String> mismatches) {
        Supplier<String> route = () -> body;

        if (satisfied(row, route) != expected) {
            mismatches.add(row.name() + " on " + body + " expected " + expected);
        }
    }

    private static boolean satisfied(Row row, Supplier<String> route) {
        try {
            row.await().accept(route, expectedTimeout());

            return true;
        } catch (ConditionTimeoutException e) {
            return false;
        }
    }

    private static Duration expectedTimeout() {
        return Duration.ofMillis(300);
    }

    private static List<Row> rows() {
        return List.of(new Row("awaitState(DEGRADED)",
                               (route, atMost) -> CommunityAwaits.awaitState(route, "DEGRADED", atMost),
                               stateCells()),
                       new Row("awaitLiveMembersBelow(FORMED)",
                               (route, atMost) -> CommunityAwaits.awaitLiveMembersBelow(route, FORMED, atMost),
                               countCells(FORMED - 1)),
                       new Row("awaitLiveMembersEqual(FORMED)",
                               (route, atMost) -> CommunityAwaits.awaitLiveMembersEqual(route, FORMED, atMost),
                               countCells(FORMED)));
    }

    /// `state` is an equality row: absent and every value other than the target (a different state, a superstring) is false.
    private static Map<String, Boolean> stateCells() {
        var cells = new LinkedHashMap<String, Boolean>();

        cells.put("{\"liveMembers\":2}", false);
        cells.put(body("ACTIVE", FORMED), false);
        cells.put(body("DEGRADED_2", FORMED), false);
        cells.put(body("DEGRADED", FORMED), true);

        return cells;
    }

    /// A count row, keyed by its bodies at {absent, FORMED-1, FORMED, FORMED+1}; `trueAt` is the one count that satisfies it
    /// (`FORMED-1` for the strict-below row, `FORMED` for the equality row).
    private static Map<String, Boolean> countCells(int trueAt) {
        var cells = new LinkedHashMap<String, Boolean>();

        cells.put("{\"state\":\"DEGRADED\",\"liveMembers\":null}", false);
        for (var count : new int[]{FORMED - 1, FORMED, FORMED + 1}) {
            cells.put(body("DEGRADED", count), count == trueAt);
        }

        return cells;
    }

    private record Row(String name, BiConsumer<Supplier<String>, Duration> await, Map<String, Boolean> cells) {}

    private static void oldPairing(Supplier<String> route) {
        await().atMost(BOUND).until(() -> route.get()
                                               .contains("\"state\":\"DEGRADED\""));
        assertThat(route.get()).contains("\"liveMembers\":2");
    }

    private static Supplier<String> laggingRoute() {
        var reads = new AtomicInteger();

        return () -> body("DEGRADED",
                          reads.incrementAndGet() > LAG
                          ? FORMED - 1
                          : FORMED);
    }

    private static String body(String state, int liveMembers) {
        return "{\"communityId\":\"c\",\"state\":\"" + state
             + "\",\"memberCount\":3,\"liveMembers\":" + liveMembers
             + "}";
    }
}
