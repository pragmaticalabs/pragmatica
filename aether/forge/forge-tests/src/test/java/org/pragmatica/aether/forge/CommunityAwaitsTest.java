// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
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

    @Test
    void awaitLiveMembersBelow_neverPassesOnAnAbsentCount() {
        Supplier<String> route = () -> "{\"state\":\"DEGRADED\",\"liveMembers\":null}";

        assertThatThrownBy(() -> CommunityAwaits.awaitLiveMembersBelow(route, FORMED, Duration.ofMillis(300))).isInstanceOf(ConditionTimeoutException.class);
    }

    @Test
    void awaitLiveMembersEqual_waitsForTheFullCount_andAwaitStateForTheNamedState() {
        var route = recoveringRoute();

        CommunityAwaits.awaitLiveMembersEqual(route, FORMED, BOUND);
        CommunityAwaits.awaitState(route, "ACTIVE", BOUND);
    }

    private static void oldPairing(Supplier<String> route) {
        await().atMost(BOUND).until(() -> route.get()
                                               .contains("\"state\":\"DEGRADED\""));
        assertThat(route.get()).contains("\"liveMembers\":2");
    }

    private static Supplier<String> recoveringRoute() {
        var reads = new AtomicInteger();

        return () -> reads.incrementAndGet() > LAG
                     ? body("ACTIVE", FORMED)
                     : body("DEGRADED", FORMED - 1);
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
