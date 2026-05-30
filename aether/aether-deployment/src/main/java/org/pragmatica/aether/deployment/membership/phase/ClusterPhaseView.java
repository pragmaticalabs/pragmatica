// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.phase;

import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterPhase;
import org.pragmatica.lang.Contract;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;


/// Derives the KV-facing [`ClusterPhase`] from two live signals: in-quorum and have-leader.
///
/// The swim membership tracker's own `phase()` signal is gone; this view computes the phase
/// directly. It latches `everQuorate` the first time `inQuorum` reads true and never resets
/// (a cluster that has reached quorum once has left `COLD_BOOT` forever):
///
/// - Before the first quorate observation → `COLD_BOOT` (initial formation, no quorum yet).
/// - After `everQuorate`, `inQuorum && haveLeader` → `NORMAL`.
/// - After `everQuorate`, otherwise (lost quorum or no leader) → `RECOVERING`.
///
/// Leader awareness is the extra refinement quorum alone cannot express: a quorate set with
/// no elected leader is `RECOVERING`, never `NORMAL`.
public final class ClusterPhaseView {
    private final BooleanSupplier inQuorumReader;
    private final BooleanSupplier haveLeaderReader;
    private final AtomicBoolean everQuorate = new AtomicBoolean(false);

    private ClusterPhaseView(BooleanSupplier inQuorumReader, BooleanSupplier haveLeaderReader) {
        this.inQuorumReader = inQuorumReader;
        this.haveLeaderReader = haveLeaderReader;
    }

    public static ClusterPhaseView clusterPhaseView(BooleanSupplier inQuorumReader,
                                                    BooleanSupplier haveLeaderReader) {
        return new ClusterPhaseView(inQuorumReader, haveLeaderReader);
    }

    public ClusterPhase compute() {
        var inQuorum = inQuorumReader.getAsBoolean();

        latchQuorate(inQuorum);

        return resolve(inQuorum, haveLeaderReader.getAsBoolean());
    }

    @Contract
    private void latchQuorate(boolean inQuorum) {
        if (inQuorum) {
            everQuorate.set(true);
        }
    }

    private ClusterPhase resolve(boolean inQuorum, boolean haveLeader) {
        if (!everQuorate.get()) {
            return ClusterPhase.COLD_BOOT;
        }
        return inQuorum && haveLeader
               ? ClusterPhase.NORMAL
               : ClusterPhase.RECOVERING;
    }
}
