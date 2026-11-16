// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.api.ManagementApiResponses.WarningEntry;
import org.pragmatica.aether.api.ManagementApiResponses.WarningsResponse;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.FsmWarningKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.FsmWarningValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProcessIncarnation;
import org.pragmatica.cluster.state.kvstore.KVStore.RevisionedSnapshot;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.http.routing.JsonCodecAdapter;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import static org.assertj.core.api.Assertions.assertThat;


/// `GET /api/v1/warnings` decision table (#3274), driven through [WarningsRoutes#read] with the node reduced to its three observations.
///
/// Rows of the design's per-state table NOT reachable here: observer-by-role, core-count-unknown (threshold 0) and detector-not-wired are
/// all answers of [StatusRoutes#quorumStatus] (the one source `/api/status`, `/api/health` and `/health/ready` share); at this seam they
/// arrive as `quorumHeld` true or false, which the quorum tests below pin. The quorate and minority rows are the two values.
class WarningsRoutesTest {
    private static final FsmWarningKey STRAY_B = FsmWarningKey.fsmWarningKey("STRAY", "b");
    private static final FsmWarningKey STRAY_A = FsmWarningKey.fsmWarningKey("STRAY", "a");
    private static final FsmWarningKey HOLD = FsmWarningKey.fsmWarningKey("HOLD", "art");

    private static RevisionedSnapshot<AetherKey, AetherValue> snapshot(long revision,
                                                                       boolean exact,
                                                                       Map<AetherKey, AetherValue> rows) {
        return RevisionedSnapshot.revisionedSnapshot(revision, exact, rows);
    }

    private static Cause refusal(Result<WarningsResponse> result) {
        return result.fold(cause -> cause, _ -> null);
    }

    private static WarningsResponse served(Result<WarningsResponse> result) {
        return result.fold(_ -> null, response -> response);
    }

    @Test
    void read_worker_refusesAndNeverReadsQuorumOrStore() {
        var touched = new AtomicBoolean();
        var result = WarningsRoutes.read(true,
                                         () -> {
                                             touched.set(true);
                                             return true;
                                         },
                                         () -> {
                                             touched.set(true);
                                             return snapshot(5, true, Map.of());
                                         });

        assertThat(refusal(result)).isSameAs(WarningsRoutes.WORKER);
        assertThat(touched).isFalse();
    }

    @Test
    void read_coreWithoutQuorum_refusesBeforeTheStoreIsRead() {
        var touched = new AtomicBoolean();
        var result = WarningsRoutes.read(false,
                                         () -> false,
                                         () -> {
                                             touched.set(true);
                                             return snapshot(5, true, Map.of());
                                         });

        assertThat(refusal(result)).isSameAs(WarningsRoutes.NO_QUORUM);
        assertThat(touched).isFalse();
    }

    @Test
    void read_revisionZero_refuses() {
        var result = WarningsRoutes.read(false, () -> true, () -> snapshot(0, true, Map.of()));

        assertThat(refusal(result)).isSameAs(WarningsRoutes.NO_REVISION);
    }

    @Test
    void read_labelNotExact_refuses() {
        var result = WarningsRoutes.read(false, () -> true, () -> snapshot(9, false, Map.of()));

        assertThat(refusal(result)).isSameAs(WarningsRoutes.NOT_EXACT);
    }

    @Test
    void read_quorateExactCore_servesEmptyWarningsWithTheRevision() {
        var response = served(WarningsRoutes.read(false, () -> true, () -> snapshot(7, true, Map.of())));

        assertThat(response.revision()).isEqualTo(7L);
        assertThat(response.warnings()).isEmpty();
    }

    @Test
    void read_openRows_areServedSortedByCodeThenSubject_withMembersAndMessage() {
        var rows = Map.<AetherKey, AetherValue> of(STRAY_B,
                                                   row(Map.of("n2", "x"), true, "stray b"),
                                                   STRAY_A,
                                                   row(Map.of(), true, ""),
                                                   HOLD,
                                                   row(Map.of("n1", "sig"), true, "held"));
        var response = served(WarningsRoutes.read(false, () -> true, () -> snapshot(12, true, rows)));

        assertThat(response.warnings()).containsExactly(new WarningEntry("HOLD", "art", Map.of("n1", "sig"), "held"),
                                                        new WarningEntry("STRAY", "a", Map.of(), ""),
                                                        new WarningEntry("STRAY", "b", Map.of("n2", "x"), "stray b"));
    }

    @Test
    void read_closedRow_isNotAnOpenWarning() {
        var rows = Map.<AetherKey, AetherValue> of(STRAY_A,
                                                   row(Map.of(), false, "recovered"));
        var response = served(WarningsRoutes.read(false, () -> true, () -> snapshot(3, true, rows)));

        assertThat(response.warnings()).isEmpty();
    }

    /// The wire form, not the record: the management codec writes with `NON_EMPTY`, which dropped `members` (and an empty `warnings`).
    @Test
    void wireJson_entryWithMembers_carriesCodeSubjectMembersAndMessage() {
        var json = wire(new WarningsResponse(12, List.of(new WarningEntry("HOLD", "art", Map.of("n1", "sig"), "held"))));

        assertThat(json).isEqualTo("{\"revision\":12,\"warnings\":[{\"code\":\"HOLD\",\"subject\":\"art\",\"members\":{\"n1\":\"sig\"},\"message\":\"held\"}]}");
    }

    @Test
    void wireJson_entryWithoutMembers_carriesAnEmptyMembersObject() {
        var json = wire(new WarningsResponse(12, List.of(new WarningEntry("STRAY", "a", Map.of(), ""))));

        assertThat(json).isEqualTo("{\"revision\":12,\"warnings\":[{\"code\":\"STRAY\",\"subject\":\"a\",\"members\":{},\"message\":\"\"}]}");
    }

    @Test
    void wireJson_noWarnings_carriesAnEmptyWarningsArray() {
        assertThat(wire(new WarningsResponse(7, List.of()))).isEqualTo("{\"revision\":7,\"warnings\":[]}");
    }

    private static String wire(WarningsResponse response) {
        return JsonCodecAdapter.defaultCodec()
                               .serialize(response)
                               .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                               .unwrap();
    }

    private static FsmWarningValue row(Map<String, String> members, boolean open, String message) {
        return FsmWarningValue.fsmWarningValue(members, open, message, 1L, NodeId.nodeId("core-1").unwrap(), new ProcessIncarnation(1L, 1L), 1L);
    }
}
