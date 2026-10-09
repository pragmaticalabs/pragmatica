// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.cluster.NodeReplacementService;
import org.pragmatica.aether.deployment.cluster.NodeReplacementService.Refusal;
import org.pragmatica.aether.deployment.cluster.UpgradeRunIndex;
import org.pragmatica.aether.deployment.cluster.UpgradeRunReconciler.BeginResult;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeReplacementPhase;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeReplacementValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;

/// #1543 F — how the run reads the replacement service's answer when it asks for the next replacement. A refusal that a later tick can
/// get past (another replacement holds the slot, not the leader this tick, the record changed under us, the node is gone) is DEFERRED:
/// the run waits and asks again. A refusal that will not change (unsupported role, fleet full, ...) PAUSES the run for an operator. Mapping
/// a transient refusal to a pause strands the run until someone resumes it; mapping a permanent one to a deferral loops for ever.
class UpgradeRunBeginMappingTest {
    private static final NodeId NODE = new NodeId("a");

    private static BeginResult answerOf(Cause cause) {
        var replacements = new NodeReplacementService() {
            @Override
            public Promise<NodeReplacementValue> begin(NodeId original, String targetVersion) {
                return cause.promise();
            }

            @Override
            public Promise<NodeReplacementValue> beginExternal(NodeId original, NodeId replacement, String targetVersion) {
                return cause.promise();
            }

            @Override
            public Option<NodeReplacementValue> status(NodeId original) {
                return Option.none();
            }

            @Override
            public Map<NodeId, NodeReplacementValue> all() {
                return Map.of();
            }

            @Override
            public Promise<Unit> settle(NodeId original, Settlement settlement) {
                return cause.promise();
            }
        };
        var inputs = new UpgradeRunWiring.Inputs(new NodeId("self"), () -> true, null, null, UpgradeRunIndex.upgradeRunIndex(), () -> null, _ -> "", replacements, () -> 1L);

        return new UpgradeRunWiring.Env(inputs).begin(NODE, "2.0.0").await().unwrap();
    }

    @Test
    void aStartedReplacement_isStarted() {
        var started = new NodeReplacementValue(new NodeId("a2"), "core", NodeReplacementPhase.PROVISIONING, 0L);
        var inputs = new UpgradeRunWiring.Inputs(new NodeId("self"), () -> true, null, null, UpgradeRunIndex.upgradeRunIndex(), () -> null, _ -> "", new NodeReplacementService() {
            @Override
            public Promise<NodeReplacementValue> begin(NodeId original, String targetVersion) {
                return Promise.success(started);
            }

            @Override
            public Promise<NodeReplacementValue> beginExternal(NodeId original, NodeId replacement, String targetVersion) {
                return Promise.success(started);
            }

            @Override
            public Option<NodeReplacementValue> status(NodeId original) {
                return Option.none();
            }

            @Override
            public Map<NodeId, NodeReplacementValue> all() {
                return Map.of();
            }

            @Override
            public Promise<Unit> settle(NodeId original, Settlement settlement) {
                return Promise.unitPromise();
            }
        }, () -> 1L);

        assertThat(new UpgradeRunWiring.Env(inputs).begin(NODE, "2.0.0").await().unwrap()).isInstanceOf(BeginResult.Started.class);
    }

    @Test
    void refusalsALaterTickCanGetPast_areDeferred() {
        assertThat(answerOf(new Refusal.NotLeader())).as("not the leader this tick").isInstanceOf(BeginResult.Deferred.class);
        assertThat(answerOf(new Refusal.AlreadyReplacing(new NodeId("other")))).as("another replacement holds the one slot").isInstanceOf(BeginResult.Deferred.class);
        assertThat(answerOf(new Refusal.Conflict(NODE))).as("the record changed concurrently").isInstanceOf(BeginResult.Deferred.class);
        assertThat(answerOf(new Refusal.UnknownNode(NODE))).as("the node is gone: the planner's vanished-record path decides").isInstanceOf(BeginResult.Deferred.class);
        assertThat(answerOf(Causes.cause("timeout"))).as("anything that is not a refusal").isInstanceOf(BeginResult.Deferred.class);
    }

    @Test
    void refusalsThatWillNotChange_pauseTheRunForAnOperator() {
        assertThat(answerOf(new Refusal.RoleNotSupported(NODE, "spot"))).isInstanceOf(BeginResult.Refused.class);
        assertThat(answerOf(new Refusal.FleetFull(5))).isInstanceOf(BeginResult.Refused.class);
        assertThat(answerOf(new Refusal.Unavailable())).isInstanceOf(BeginResult.Refused.class);
    }
}
