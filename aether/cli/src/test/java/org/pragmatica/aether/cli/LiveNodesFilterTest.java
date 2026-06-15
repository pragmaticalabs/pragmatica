// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Unit tests for `LiveNodesFilter` — the client-side post-processing for the
/// `GET /api/nodes/live` document (harness-resilience spec A2) and the `reachable`
/// extraction for `GET /api/nodes/endpoint/{id}` (A1).
class LiveNodesFilterTest {

    private static final String LIVE_DOC = """
            {"nodes":[\
            {"nodeId":"node-1","address":"10.0.0.7:7100","role":"CORE","swimAlive":true,"reportedState":"READY"},\
            {"nodeId":"node-2","address":"10.0.0.8:7100","role":"CORE","swimAlive":true,"reportedState":"READY"},\
            {"nodeId":"node-3","address":null,"role":"CORE","swimAlive":false,"reportedState":"READY"}\
            ],"liveCount":2,"zombieCount":1}""";

    @Test
    void onlyAlive_dropsZombies_andRecomputesCounts() {
        var json = LiveNodesFilter.onlyAlive(LIVE_DOC);

        assertTrue(json.contains("\"node-1\""));
        assertTrue(json.contains("\"node-2\""));
        assertFalse(json.contains("\"node-3\""));
        assertTrue(json.contains("\"liveCount\":2"));
        assertTrue(json.contains("\"zombieCount\":0"));
    }

    @Test
    void onlyAlive_allDead_emitsEmptyArrayAndZeroCounts() {
        var allDead = """
                {"nodes":[\
                {"nodeId":"node-9","address":null,"role":"CORE","swimAlive":false,"reportedState":"READY"}\
                ],"liveCount":0,"zombieCount":1}""";

        var json = LiveNodesFilter.onlyAlive(allDead);

        assertFalse(json.contains("\"node-9\""));
        assertTrue(json.contains("\"liveCount\":0"));
        assertTrue(json.contains("\"zombieCount\":0"));
    }

    @Test
    void onlyAlive_unparseableInput_returnsOriginalUnchanged() {
        var garbage = "not json at all";

        assertEquals(garbage, LiveNodesFilter.onlyAlive(garbage));
    }

    @Test
    void isReachable_trueField_readsTrue() {
        assertTrue(LiveNodesFilter.isReachable("{\"nodeId\":\"node-1\",\"address\":\"10.0.0.7:7100\",\"reachable\":true}"));
    }

    @Test
    void isReachable_falseField_readsFalse() {
        assertFalse(LiveNodesFilter.isReachable("{\"nodeId\":\"node-1\",\"address\":\"10.0.0.7:7100\",\"reachable\":false}"));
    }

    @Test
    void isReachable_absentField_defaultsToFalse() {
        assertFalse(LiveNodesFilter.isReachable("{\"nodeId\":\"node-1\",\"address\":\"10.0.0.7:7100\"}"));
    }

    @Test
    void isReachable_unparseableInput_defaultsToFalse() {
        assertFalse(LiveNodesFilter.isReachable("not json"));
    }
}
