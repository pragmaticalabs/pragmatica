// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.drain;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.io.TimeSpan;

import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Core-only periodic orphan self-drain checker (slot-based-core-membership-redesign §5).
///
/// A core node removes *itself* when it is a genuine slot orphan — there is no leader-side
/// surplus reaper (§6). This checker reads the durable slot bindings from the local KV view
/// and fires `SelfDrainCoordinator.onOrphanDetected(..)` only when **every** conjunct of the
/// §5 predicate holds, evaluated atomically on a single tick:
///
/// ```text
///   core                            // strict role gate — workers MUST NOT evaluate (Scope)
///   && rabia.isActive()             // sync complete; engine Idle/InPhase, NOT Syncing/Paused/Stopped
///   && inQuorum()                   // connected to a true majority → synced == converged
///   && graceElapsed                 // dwell since activation/join (configurable; default 30s)
///   && boundSet.size() == configured // never act on a partial slot set
///   && !boundSet.contains(self)     // I hold no slot binding → orphan
///   => onOrphanDetected("no slot binding")
/// ```
///
/// **Why this is the converged read.** Rabia sync transfers a whole-state snapshot from a
/// responding quorum (highest `lastCommittedPhase`); by quorum intersection a synced node
/// holds the latest committed state, so **synced == converged**. `isActive()` is false during
/// the `Syncing`/`Paused`/`Stopped` buffering windows where the local view is stale, so gating
/// on it excludes those. The one edge — `syncQuorumSize` is computed off `connected`, not
/// `clusterSize`, so a degraded-connectivity sync could return a slightly stale snapshot — is
/// closed by the `inQuorum()` conjunct: a node that cannot reach a true majority fails
/// `inQuorum()` and defers to the `SelfDrainCoordinator` quorum-loss trigger instead of making
/// an orphan decision. The orphan and quorum-loss triggers are therefore mutually exclusive:
/// orphan requires `inQuorum()`, quorum-loss requires `!inQuorum`.
///
/// **Quorum-safe by construction.** If every slot is always bound (§1, §4) then a node with no
/// binding is genuinely beyond the `configured` slots — draining it can never remove a quorum
/// member. `boundSet.size() == configured` (the slot set is full of distinct bound occupants)
/// AND `!boundSet.contains(self)` together prove this node is surplus to the durable membership.
///
/// **Re-confirmation before the irreversible drain.** The predicate is evaluated in full on
/// every tick and `onOrphanDetected(..)` is invoked synchronously from the same evaluation, so
/// the read that decides to drain is the read immediately preceding the drain — there is no
/// time-of-check/time-of-use gap to re-confirm across. `SelfDrainCoordinator.initiateDrain`'s
/// CAS makes a redundant tick a no-op.
///
/// **This component reads KV; the coordinator does not.** Per the design constraint, the
/// `SelfDrainCoordinator` stays KV/consensus-free (a partition victim must be able to drain
/// without consensus). The orphan predicate's KV dependency is isolated here: the production
/// `boundSet`/`configured`/`slotCount` suppliers are adapted in `AetherNode` from the same
/// local-KV slot reader the CTM uses; the role/active/quorum signals come from `RabiaNode`
/// and `TopologyObserver`. The checker takes everything as suppliers so unit tests fake them
/// directly.
public final class OrphanSelfDrainChecker {
    private static final Logger log = LoggerFactory.getLogger(OrphanSelfDrainChecker.class);

    /// Default grace dwell after activation/join before an orphan decision is permitted.
    /// Generous on purpose: a late-joining bootstrap node must have time to bind into an
    /// empty slot (§3) and a freshly-reactivated leader must have time to converge its KV
    /// view before any node concludes it is orphaned.
    public static final TimeSpan DEFAULT_GRACE = TimeSpan.timeSpan(30).seconds();

    private final NodeId self;
    private final BooleanSupplier coreRole;
    private final BooleanSupplier rabiaActive;
    private final BooleanSupplier inQuorum;
    private final Supplier<Set<NodeId>> boundSet;
    private final IntSupplier configuredSize;
    private final TimeSpan grace;
    private final LongSupplier clock;
    private final OrphanDrainTrigger trigger;
    private final long anchorMs;

    private OrphanSelfDrainChecker(NodeId self,
                                   BooleanSupplier coreRole,
                                   BooleanSupplier rabiaActive,
                                   BooleanSupplier inQuorum,
                                   Supplier<Set<NodeId>> boundSet,
                                   IntSupplier configuredSize,
                                   TimeSpan grace,
                                   LongSupplier clock,
                                   OrphanDrainTrigger trigger) {
        this.self = self;
        this.coreRole = coreRole;
        this.rabiaActive = rabiaActive;
        this.inQuorum = inQuorum;
        this.boundSet = boundSet;
        this.configuredSize = configuredSize;
        this.grace = grace;
        this.clock = clock;
        this.trigger = trigger;
        this.anchorMs = clock.getAsLong();
    }

    /// Trigger sink invoked when the §5 orphan predicate holds. Production wires this to
    /// `SelfDrainCoordinator::onOrphanDetected`; tests pass a recording fake.
    @FunctionalInterface
    public interface OrphanDrainTrigger {
        @Contract
        void onOrphanDetected(String reason);
    }

    /// Canonical factory. `clock` anchors the grace dwell at construction (taken as the
    /// activation/join instant); `grace` is the configurable dwell (default `DEFAULT_GRACE`).
    public static OrphanSelfDrainChecker orphanSelfDrainChecker(NodeId self,
                                                                BooleanSupplier coreRole,
                                                                BooleanSupplier rabiaActive,
                                                                BooleanSupplier inQuorum,
                                                                Supplier<Set<NodeId>> boundSet,
                                                                IntSupplier configuredSize,
                                                                TimeSpan grace,
                                                                LongSupplier clock,
                                                                OrphanDrainTrigger trigger) {
        return new OrphanSelfDrainChecker(self, coreRole, rabiaActive, inQuorum, boundSet,
                                          configuredSize, grace, clock, trigger);
    }

    /// Periodic tick. Caller schedules this (e.g. `SharedScheduler.scheduleAtFixedRate`). The
    /// strict core-role gate short-circuits first so a worker never touches the slot-binding
    /// logic (Scope). When every conjunct holds, fires `onOrphanDetected` synchronously.
    @Contract
    public void check() {
        if (!coreRole.getAsBoolean()) {return;}
        if (!rabiaActive.getAsBoolean()) {return;}
        if (!inQuorum.getAsBoolean()) {return;}
        if (!graceElapsed()) {return;}

        var bound = boundSet.get();
        var configured = configuredSize.getAsInt();
        if (!isOrphan(bound, configured)) {return;}

        log.warn("Orphan self-drain: {} holds no slot binding (boundSet.size={} == configured={}, self absent) — triggering self-drain",
                 self.id(),
                 bound.size(),
                 configured);
        trigger.onOrphanDetected("no slot binding");
    }

    /// Orphan iff the slot set is full of distinct bound occupants (`boundSet.size ==
    /// configured`, never a partial set) AND this node is not among them. A transient empty
    /// slot during refill drops `boundSet.size` below `configured`, suppressing the decision.
    private boolean isOrphan(Set<NodeId> bound, int configured) {
        return configured > 0
               && bound.size() == configured
               && !bound.contains(self);
    }

    private boolean graceElapsed() {
        return clock.getAsLong() - anchorMs >= grace.millis();
    }
}
