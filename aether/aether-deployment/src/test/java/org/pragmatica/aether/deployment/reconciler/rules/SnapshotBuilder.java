// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.reconciler.rules;

import org.pragmatica.aether.config.ReconcilerRulesConfig;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsmConfig;
import org.pragmatica.aether.slice.kvstore.AetherValue.DrainDeadlineValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.JoinDeadlineValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.swim.SwimHealth;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;


/// Test fixture builder for `ReconciliationSnapshot` — composable, mutable until
/// `build()` is called. Each rule unit test composes the minimal snapshot it needs
/// without copy-pasting the verbose record constructor.
final class SnapshotBuilder {
    /// Sentinel HLC stamp used when a test does not care about the `at` field on
    /// emitted commands. Tests that assert HLC propagation should pass an explicit
    /// `.at(...)` value.
    private static final HlcTimestamp DEFAULT_AT =
        new HlcTimestamp(0L, NodeId.nodeId("test").unwrap());

    private final Map<NodeId, NodeLifecycleValue> lifecycleEntries = new HashMap<>();
    private final Map<NodeId, SwimHealth> swimHealth = new HashMap<>();
    private final Map<NodeId, Long> swimSinceMs = new HashMap<>();
    private final Set<NodeId> generationMembers = new HashSet<>();
    private final Map<NodeId, JoinDeadlineValue> joinDeadlines = new HashMap<>();
    private final Map<NodeId, DrainDeadlineValue> drainDeadlines = new HashMap<>();
    private final Set<NodeId> activeSyncHolds = new HashSet<>();
    private long nowMs = 0L;
    private HlcTimestamp at = DEFAULT_AT;
    private MembershipFsmConfig fsmConfig = MembershipFsmConfig.defaultMembershipFsmConfig();
    private ReconcilerRulesConfig rulesConfig = ReconcilerRulesConfig.dryRunDefaults();

    static SnapshotBuilder snapshot() {
        return new SnapshotBuilder();
    }

    SnapshotBuilder nowMs(long nowMs) {
        this.nowMs = nowMs;
        return this;
    }

    SnapshotBuilder at(HlcTimestamp at) {
        this.at = at;
        return this;
    }

    SnapshotBuilder lifecycle(NodeId peer, NodeLifecycleValue value) {
        lifecycleEntries.put(peer, value);
        return this;
    }

    SnapshotBuilder swim(NodeId peer, SwimHealth health, long sinceMs) {
        swimHealth.put(peer, health);
        swimSinceMs.put(peer, sinceMs);
        return this;
    }

    SnapshotBuilder generationMember(NodeId peer) {
        generationMembers.add(peer);
        return this;
    }

    SnapshotBuilder joinDeadline(NodeId peer, JoinDeadlineValue value) {
        joinDeadlines.put(peer, value);
        return this;
    }

    SnapshotBuilder drainDeadline(NodeId peer, DrainDeadlineValue value) {
        drainDeadlines.put(peer, value);
        return this;
    }

    SnapshotBuilder syncHold(NodeId peer) {
        activeSyncHolds.add(peer);
        return this;
    }

    SnapshotBuilder fsmConfig(MembershipFsmConfig fsmConfig) {
        this.fsmConfig = fsmConfig;
        return this;
    }

    SnapshotBuilder rulesConfig(ReconcilerRulesConfig rulesConfig) {
        this.rulesConfig = rulesConfig;
        return this;
    }

    ReconciliationSnapshot build() {
        return new ReconciliationSnapshot(lifecycleEntries,
                                          swimHealth,
                                          swimSinceMs,
                                          generationMembers,
                                          joinDeadlines,
                                          drainDeadlines,
                                          activeSyncHolds,
                                          nowMs,
                                          at,
                                          fsmConfig,
                                          rulesConfig);
    }

    static NodeId peer(String id) {
        return NodeId.nodeId(id).unwrap();
    }
}
