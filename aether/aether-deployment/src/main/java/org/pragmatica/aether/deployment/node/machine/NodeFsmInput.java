// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node.machine;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceDesiredValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;


/// What can reach the node FSM (spec 1.2, NI-1..NI-8). Every input is handled by one consumer from one mailbox; none is
/// handled on the delivering thread (R-3.1).
public sealed interface NodeFsmInput {
    /// NI-1: a quorum was established. Starts an Active incarnation from `snapshot` (R-3.4.1). Ignored while Active.
    record QuorumEstablished(RebuildSnapshot snapshot) implements NodeFsmInput {}

    /// NI-1: a genuine quorum loss. A demotion is not one (`9454522a0`); the adapter does not deliver it.
    record QuorumDisappeared() implements NodeFsmInput {}

    /// NI-6: the periodic level-heal of a dropped one-shot edge (B-114). In Dormant it acts as `QuorumEstablished`;
    /// in Active it does nothing, so self-ready is not signalled twice.
    record ReconcileActivation(RebuildSnapshot snapshot) implements NodeFsmInput {}

    /// NI-2: own `SliceDesiredKey` put.
    record DesiredPut(Artifact artifact, SliceDesiredValue value) implements NodeFsmInput {}

    /// NI-2: own `SliceDesiredKey` removed.
    record DesiredRemove(Artifact artifact) implements NodeFsmInput {}

    /// NI-3: a `SliceObservedKey` put. Own keys confirm writes; others' keys feed the replica index.
    /// `knownToken` is the boot token the delivering registry knew for `node` (0 = unknown).
    record ObservedPut(NodeId node, Artifact artifact, SliceObservedValue value, long knownToken) implements NodeFsmInput {}

    /// NI-3: a `SliceObservedKey` removed.
    record ObservedRemove(NodeId node, Artifact artifact) implements NodeFsmInput {}

    /// NI-4: a committed `SliceTargetKey` put.
    record TargetPut(ArtifactBase base, Version version) implements NodeFsmInput {}

    /// NI-4: a committed `SliceTargetKey` removed.
    record TargetRemove(ArtifactBase base) implements NodeFsmInput {}

    /// NI-4: a committed `VersionRoutingKey` put.
    record RoutingPut(ArtifactBase base, Version oldVersion, Version newVersion) implements NodeFsmInput {}

    /// NI-4: a committed `VersionRoutingKey` removed.
    record RoutingRemove(ArtifactBase base) implements NodeFsmInput {}

    /// NI-5: a config key changed.
    record ConfigChanged(String key) implements NodeFsmInput {}

    /// NI-7: a timer the FSM scheduled fired.
    record TimerFired(TimerId timer) implements NodeFsmInput {}

    /// NI-8: a submitted batch was settled by consensus.
    record WriteSettled(long incarnation, Artifact artifact, long correlation, WriteOutcome outcome) implements NodeFsmInput {}

    /// NI-8: a local step ended.
    record LocalOpSettled(long incarnation, Artifact artifact, long token, LocalOutcome outcome) implements NodeFsmInput {}

    /// How a submission ended. `Accepted` says consensus took it, not that the fence applied it: a refused fenced Put
    /// is silent (spec 5.0), so confirmation always comes from the node's own notification (R-3.4.9 (3)).
    sealed interface WriteOutcome {
        record Accepted() implements WriteOutcome {}

        record Failed(Cause cause) implements WriteOutcome {}

        static WriteOutcome accepted() {
            return new Accepted();
        }

        static WriteOutcome failed(Cause cause) {
            return new Failed(cause);
        }
    }
}
