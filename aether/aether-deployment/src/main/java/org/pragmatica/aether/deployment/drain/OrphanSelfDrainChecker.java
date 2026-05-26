// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.drain;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;

import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Core-only periodic orphan self-drain checker (slot-based-core-membership-redesign §5).
///
/// A core node removes *itself* when it is a genuine slot orphan — there is no leader-side
/// surplus reaper (§6). This checker reads the durable slot bindings AND the connected-members
/// view from the local node and fires `SelfDrainCoordinator.onOrphanDetected(..)` only when
/// **every** conjunct of the §5 predicate holds, evaluated atomically on a single tick:
///
/// ```text
///   liveFilled = count of slots whose occupant is in the CONNECTED-members set
///   core                            // strict role gate — workers MUST NOT evaluate (Scope)
///   && rabia.isActive()             // sync complete; engine Idle/InPhase, NOT Syncing/Paused/Stopped
///   && inQuorum()                   // connected to a true majority → synced == converged
///   && liveFilled == configured     // every slot live-filled ⇒ converged, no room; while < cfg WAIT
///   && !boundToConnectedSlot(self)  // self is not the occupant of any LIVE-FILLED slot → orphan
///   => onOrphanDetected("no live slot binding")
/// ```
///
/// **Dynamic, state-based — NOT a fixed-time grace.** "Filled" means the slot's `assignedNodeId`
/// is in the node's **connected-members view**, NOT merely that the slot atom carries an
/// `assignedNodeId`. A slot bound to a dead/disconnected occupant is therefore NOT live-filled, so
/// `liveFilled < configured` and the node WAITS (it could be rebound into that slot once §3/
/// freeDeadSlots clears it) instead of falsely concluding "all full → I'm surplus." This both
/// closes the dead-occupant false-drain AND removes the need for the fixed-grace timer: the node
/// defers as long as there is any slot it could still be placed in, and the converging-vs-converged
/// signal (`liveFilled == configured`) replaces the dwell. It also subsumes the binding-sanity
/// backstop — if slots were bound to nodes that aren't even connected they read as not-live-filled,
/// so the node waits rather than drains.
///
/// **Why the gate is the converged-read.** Rabia sync transfers a whole-state snapshot from a
/// responding quorum (highest `lastCommittedPhase`); by quorum intersection a synced node holds
/// the latest committed state, so **synced == converged**. `isActive()` is false during the
/// `Syncing`/`Paused`/`Stopped` buffering windows where the local view is stale, so gating on it
/// excludes those. The one edge — `syncQuorumSize` is computed off `connected`, not `clusterSize`,
/// so a degraded-connectivity sync could return a slightly stale snapshot — is closed by the
/// `inQuorum()` conjunct: a node that cannot reach a true majority fails `inQuorum()` and defers to
/// the `SelfDrainCoordinator` quorum-loss trigger instead of making an orphan decision. The orphan
/// and quorum-loss triggers are therefore mutually exclusive: orphan requires `inQuorum()`,
/// quorum-loss requires `!inQuorum`.
///
/// **Quorum-safe by construction.** If every slot is always bound (§1, §4) then a node with no
/// LIVE binding while every slot is live-filled by *other connected* members is genuinely beyond
/// the `configured` slots — draining it can never remove a quorum member.
///
/// **Re-confirmation before the irreversible drain.** The connected-members view and the slot map
/// are both re-read at the top of `check()` and the predicate is evaluated in full on the same
/// tick, with `onOrphanDetected(..)` invoked synchronously from that evaluation — so the read that
/// decides to drain is the read immediately preceding the drain.
///
/// **This component reads KV + the connected view; the coordinator does not.** Per the design
/// constraint the `SelfDrainCoordinator` stays KV/consensus-free (a partition victim must drain
/// without consensus). Both KV-derived dependencies — the slot map (`slotOccupants`) and the
/// connected-members view (`connectedMembers`) — are isolated here. The production suppliers are
/// adapted in `AetherNode` from the same local-KV slot reader the CTM uses and the same
/// `TopologyObserver` connected view the CTM's universal fill uses; role/active/quorum come from
/// `RabiaNode`/`TopologyObserver`. The checker takes everything as suppliers so unit tests fake
/// them directly.
public final class OrphanSelfDrainChecker {
    private static final Logger log = LoggerFactory.getLogger(OrphanSelfDrainChecker.class);

    private final NodeId self;
    private final BooleanSupplier coreRole;
    private final BooleanSupplier rabiaActive;
    private final BooleanSupplier inQuorum;
    private final Supplier<Set<NodeId>> slotOccupants;
    private final Supplier<Set<NodeId>> connectedMembers;
    private final IntSupplier configuredSize;
    private final OrphanDrainTrigger trigger;

    private OrphanSelfDrainChecker(NodeId self,
                                   BooleanSupplier coreRole,
                                   BooleanSupplier rabiaActive,
                                   BooleanSupplier inQuorum,
                                   Supplier<Set<NodeId>> slotOccupants,
                                   Supplier<Set<NodeId>> connectedMembers,
                                   IntSupplier configuredSize,
                                   OrphanDrainTrigger trigger) {
        this.self = self;
        this.coreRole = coreRole;
        this.rabiaActive = rabiaActive;
        this.inQuorum = inQuorum;
        this.slotOccupants = slotOccupants;
        this.connectedMembers = connectedMembers;
        this.configuredSize = configuredSize;
        this.trigger = trigger;
    }

    /// Trigger sink invoked when the §5 orphan predicate holds. Production wires this to
    /// `SelfDrainCoordinator::onOrphanDetected`; tests pass a recording fake.
    @FunctionalInterface
    public interface OrphanDrainTrigger {
        @Contract
        void onOrphanDetected(String reason);
    }

    /// Canonical factory. `slotOccupants` yields the `assignedNodeId`s across the durable slot
    /// atoms; `connectedMembers` yields the SWIM/transport-derived connected core node ids (the
    /// SAME signal the CTM universal fill uses); `configuredSize` is the durable slot count.
    public static OrphanSelfDrainChecker orphanSelfDrainChecker(NodeId self,
                                                                BooleanSupplier coreRole,
                                                                BooleanSupplier rabiaActive,
                                                                BooleanSupplier inQuorum,
                                                                Supplier<Set<NodeId>> slotOccupants,
                                                                Supplier<Set<NodeId>> connectedMembers,
                                                                IntSupplier configuredSize,
                                                                OrphanDrainTrigger trigger) {
        return new OrphanSelfDrainChecker(self, coreRole, rabiaActive, inQuorum,
                                          slotOccupants, connectedMembers, configuredSize, trigger);
    }

    /// Periodic tick. Caller schedules this (e.g. `SharedScheduler.scheduleAtFixedRate`). The
    /// strict core-role gate short-circuits first so a worker never touches the slot-binding
    /// logic (Scope). When every conjunct holds, fires `onOrphanDetected` synchronously.
    @Contract
    public void check() {
        if (!coreRole.getAsBoolean()) {return;}
        if (!rabiaActive.getAsBoolean()) {return;}
        if (!inQuorum.getAsBoolean()) {return;}

        var connected = connectedMembers.get();
        var occupants = slotOccupants.get();
        var configured = configuredSize.getAsInt();

        if (!isOrphan(occupants, connected, configured)) {return;}

        log.warn("Orphan self-drain: {} holds no live slot binding (liveFilled={} == configured={}, self not a live occupant) — triggering self-drain",
                 self.id(),
                 liveFilled(occupants, connected),
                 configured);
        trigger.onOrphanDetected("no live slot binding");
    }

    /// Orphan iff every slot is LIVE-FILLED (its occupant is in the connected-members set,
    /// `liveFilled == configured`) AND this node is not one of those live occupants. A
    /// dead/disconnected occupant drops `liveFilled` below `configured` → not an orphan → WAIT.
    private boolean isOrphan(Set<NodeId> occupants, Set<NodeId> connected, int configured) {
        return configured > 0
               && liveFilled(occupants, connected) == configured
               && !boundToConnectedSlot(occupants, connected);
    }

    /// Count of slots whose occupant is currently connected. Slots are identified by their
    /// distinct connected occupant ids — a dead/disconnected occupant contributes nothing.
    private int liveFilled(Set<NodeId> occupants, Set<NodeId> connected) {
        return (int) occupants.stream()
                              .filter(connected::contains)
                              .count();
    }

    /// True iff self is the occupant of a slot AND self is connected (i.e. self is among the
    /// live-filled occupants). When false while `liveFilled == configured`, self is genuinely
    /// surplus to the durable membership.
    private boolean boundToConnectedSlot(Set<NodeId> occupants, Set<NodeId> connected) {
        return occupants.contains(self) && connected.contains(self);
    }
}
