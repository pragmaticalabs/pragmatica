// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.generation;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.GenerationSnapshotSource;
import org.pragmatica.consensus.topology.MembershipView;
import org.pragmatica.lang.Option;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntSupplier;
import java.util.function.Supplier;


/// Presence-derived `GenerationSnapshotSource` that feeds `TopologyObserver`'s membership
/// (`publishMembershipDeltas`) and quorum evaluation from local presence sampler presence
/// (`PresenceSampler.currentMembers()`) instead of the committed `GenerationSnapshot`.
/// This decouples `NodeRemoved`/`NodeJoined` and quorum from consensus commits
/// (`aether/docs/specs/membership-placement-split-spec.md` §4.1–4.2, step 2 of §8).
///
/// Cold-start gating uses a **one-way quorum latch** mirroring `TopologyObserver`'s own
/// BOOTING→NORMAL latch. `PresenceSampler.currentMembers()` is never empty — it is
/// seeded with `{self}` at construction — so an "empty-members" check can never trigger
/// the fallback. Instead, until presence sampler first converges to quorum-many members the cluster is
/// still cold-starting and `currentMembershipView()` returns `Option.none()`, letting
/// `TopologyObserver` bootstrap quorum from its legacy in-memory `nodeStatesById`
/// peer-count path (CRITICAL for cold-start formation — must not be removed). Once presence sampler
/// has shown quorum the latch flips permanently: the presence view then governs membership
/// deltas AND steady-state quorum forever, so a later sub-quorum drop is reported as a low
/// member count (which drives the dissolve path), NOT a flip back to the legacy fallback.
///
/// presence sampler carries neither `desiredCoreSize` nor the CTM-provisioned set, so those are
/// supplied separately (`desiredCoreSize` from `ClusterConfigKey.CURRENT` / topology,
/// `ctmProvisioned` delegated to the retained KV-backed source).
public final class PresenceGenerationSnapshotSource implements GenerationSnapshotSource {
    private final Supplier<Set<NodeId>> memberSupplier;
    private final IntSupplier desiredCoreSizeSupplier;
    private final Supplier<Set<NodeId>> ctmProvisionedSupplier;
    private final Supplier<Long> rabiaTermSupplier;
    private final AtomicBoolean reachedQuorum = new AtomicBoolean(false);

    private PresenceGenerationSnapshotSource(Supplier<Set<NodeId>> memberSupplier,
                                             IntSupplier desiredCoreSizeSupplier,
                                             Supplier<Set<NodeId>> ctmProvisionedSupplier,
                                             Supplier<Long> rabiaTermSupplier) {
        this.memberSupplier = memberSupplier;
        this.desiredCoreSizeSupplier = desiredCoreSizeSupplier;
        this.ctmProvisionedSupplier = ctmProvisionedSupplier;
        this.rabiaTermSupplier = rabiaTermSupplier;
    }

    public static PresenceGenerationSnapshotSource presenceGenerationSnapshotSource(Supplier<Set<NodeId>> memberSupplier,
                                                                                    IntSupplier desiredCoreSizeSupplier,
                                                                                    Supplier<Set<NodeId>> ctmProvisionedSupplier,
                                                                                    Supplier<Long> rabiaTermSupplier) {
        return new PresenceGenerationSnapshotSource(memberSupplier,
                                                    desiredCoreSizeSupplier,
                                                    ctmProvisionedSupplier,
                                                    rabiaTermSupplier);
    }

    /// Cold-start gate via a one-way quorum latch. Before the latch flips, a sub-quorum
    /// member count yields `none()` so the legacy peer-count fallback bootstraps quorum;
    /// reaching quorum (or having reached it earlier) yields the presence view, which then
    /// owns quorum permanently — a subsequent sub-quorum count is reported as-is to drive
    /// dissolve, never reverting to the fallback. `desiredCoreSize` is read lazily each call
    /// because its KV source may populate just after boot; `max(1, …)` guards a not-yet-seeded 0.
    @Override
    public Option<MembershipView> currentMembershipView() {
        var members = memberSupplier.get();
        var quorum = Math.max(1, desiredCoreSizeSupplier.getAsInt() / 2 + 1);

        if (!reachedQuorum.get() && members.size() < quorum) {
            return Option.none();
        }

        reachedQuorum.set(true);
        return Option.some(PresenceMembershipView.from(memberSupplier, desiredCoreSizeSupplier, ctmProvisionedSupplier));
    }

    @Override
    public long observedRabiaTerm() {
        return rabiaTermSupplier.get();
    }
}
