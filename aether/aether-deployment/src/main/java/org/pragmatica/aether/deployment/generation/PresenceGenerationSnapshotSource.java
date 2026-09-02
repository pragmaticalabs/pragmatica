// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.generation;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.GenerationSnapshotSource;
import org.pragmatica.consensus.topology.MembershipView;
import org.pragmatica.lang.Option;


/// Presence-derived `GenerationSnapshotSource` that feeds `TopologyObserver`'s quorum
/// evaluation (and the `MembershipDeltaProjector`'s `observedRabiaTerm` log-index stamp,
/// Wave 4) from the authoritative membership FSM's core-scoped OBSERVED-REACHABILITY
/// projection (`MembershipFsm.coreObservedMembers(self)`, wired as `memberSupplier` in
/// `AetherNode.presenceMemberSupplier`) instead of the committed `GenerationSnapshot`.
///
/// **The supplier is `coreObservedMembers`, NOT `coreCountedMembers` (#557).** This docstring
/// claimed the latter for some time after the rewire, and that stale text is not harmless: #557's
/// own diagnosis comment concluded this path used "health assumed, not observed" — reasoning from
/// the description rather than the wiring. The distinction is the whole point of the fix.
/// `coreCountedMembers` reports every CONFIGURED core the moment `seed()` runs, so feeding it here
/// would make boot quorum a statement about configuration and let a node broadcast its
/// `SyncRequest` into a network it has never contacted. `coreObservedMembers` admits a peer only
/// on `PeerConnected` or `SwimHealthy` (the `everReachable` latch), so quorum is a reachability
/// claim. Placement / heal-deficit / role-assignment consumers keep reading `coreCountedMembers` —
/// a readiness count is not a quorum count.
///
/// The composition is pinned by `PresenceGenerationSnapshotSourceQuorumCompositionTest`, which
/// fails if the supplier is swapped back.
///
/// This decouples `NodeRemoved`/`NodeJoined` and quorum from consensus commits
/// (`aether/docs/specs/membership-placement-split-spec.md` §4.1–4.2, step 2 of §8).
///
/// Cold-start gating uses a **one-way quorum latch** mirroring `TopologyObserver`'s own
/// BOOTING→NORMAL latch. Until the FSM projection first converges to quorum-many members the
/// cluster is
/// still cold-starting and `currentMembershipView()` returns `Option.none()`, letting
/// `TopologyObserver` bootstrap quorum from its legacy in-memory `nodeStatesById`
/// peer-count path (CRITICAL for cold-start formation — must not be removed). Once the FSM view
/// has shown quorum the latch flips permanently: the FSM view then governs membership
/// deltas AND steady-state quorum forever, so a later sub-quorum drop is reported as a low
/// member count (which drives the dissolve path), NOT a flip back to the legacy fallback.
///
/// The FSM carries neither `desiredCoreSize` nor the CTM-provisioned set, so those are
/// supplied separately (`desiredCoreSize` from `ClusterConfigKey.CURRENT` / topology,
/// `ctmProvisioned` from the FSM descriptor source labels).
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
