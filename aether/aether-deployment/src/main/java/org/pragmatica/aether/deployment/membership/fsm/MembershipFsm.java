// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.fsm;

import org.pragmatica.aether.deployment.drain.DrainCoordinator.DrainReason;
import org.pragmatica.aether.deployment.membership.fsm.ClusterMembershipReducer.Outcome;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsmEvent.SlotClaimed;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsmEvent.SwimDeparted;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsmEvent.SwimFaulty;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsmEvent.SwimHealthy;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeLifecycleKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ProvisioningSlotKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProvisioningSlotValue;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.swim.SwimObservation;
import org.pragmatica.swim.SwimObservation.DepartedObserved;
import org.pragmatica.swim.SwimObservation.FaultyObserved;
import org.pragmatica.swim.SwimObservation.HealthyObserved;
import org.pragmatica.swim.SwimObservation.SuspectObserved;
import org.pragmatica.swim.SwimObservation.UnknownObserved;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;


/// Read-only shadow of the per-peer cluster-membership FSM (spec §9, E.3 deliverable).
///
/// On every node (leader and follower), the shadow:
/// - reconstructs per-peer state from `NodeLifecycleKey` + `ProvisioningSlotKey` on `start()`
///   (spec §6.2 steps 1-3, invariant I1: KV-reconstructible);
/// - subscribes to KV notifications on those two key classes so state stays in sync with
///   external writes (e.g., the production `HealthReconciler` continues to write while the
///   shadow runs alongside it);
/// - accepts SWIM observations and operator events through `onSwimObservation` /
///   `enqueueOperatorEvent`; each event is fed into `ClusterMembershipReducer` and the
///   resulting `Outcome` is **logged** but never applied (no KV writes, no timer scheduling,
///   no `DrainCoordinator` invocation — those land in E.4-E.7).
///
/// **Behaviour gate.** Activation is gated by `MembershipFsmConfig.shadowEnabled` (default
/// `false`). With the flag `false`, `start()` returns immediately without subscribing to any
/// KV notifications and without exposing the shadow state map — i.e., zero behaviour change.
/// With the flag `true`, the shadow runs alongside `HealthReconciler` and logs comparisons.
///
/// **Concurrency model.** A single `ReentrantLock` (`fsmLock`) serializes all FSM event
/// delivery. SWIM observations, KV notifications, and operator events all enter through
/// methods that acquire this lock. Rationale: the shadow is observe-only and not perf-critical;
/// a single lock matches the spec §10.2 "strictly single-threaded for transitions" model
/// without introducing a queue/worker pair (those land in E.4 when the shadow becomes the
/// production writer). State is held in a `ConcurrentHashMap` to allow lock-free reads from
/// the public `snapshot()` / `get()` accessors (spec §9 E.3: read API exposed to callers).
public final class MembershipFsm {
    private static final Logger log = LoggerFactory.getLogger(MembershipFsm.class);

    private final NodeId self;

    private final MembershipFsmConfig config;

    private final ClusterMembershipReducer reducer;

    private final LifecycleSnapshotReader lifecycleSnapshotReader;

    private final SlotSnapshotReader slotSnapshotReader;

    private final ReentrantLock fsmLock = new ReentrantLock();

    private final Map<NodeId, MembershipFsmState> fsmStates = new ConcurrentHashMap<>();

    private final Map<String, NodeId> slotIdToPeer = new ConcurrentHashMap<>();

    private final AtomicBoolean started = new AtomicBoolean();

    private MembershipFsm(NodeId self,
                          MembershipFsmConfig config,
                          ClusterMembershipReducer reducer,
                          LifecycleSnapshotReader lifecycleSnapshotReader,
                          SlotSnapshotReader slotSnapshotReader) {
        this.self = self;
        this.config = config;
        this.reducer = reducer;
        this.lifecycleSnapshotReader = lifecycleSnapshotReader;
        this.slotSnapshotReader = slotSnapshotReader;
    }

    public static MembershipFsm membershipFsm(NodeId self,
                                              MembershipFsmConfig config,
                                              LifecycleSnapshotReader lifecycleSnapshotReader,
                                              SlotSnapshotReader slotSnapshotReader) {
        var reducer = ClusterMembershipReducer.clusterMembershipReducer(config);
        return new MembershipFsm(self, config, reducer, lifecycleSnapshotReader, slotSnapshotReader);
    }

    public static MembershipFsm membershipFsm(NodeId self,
                                              MembershipFsmConfig config,
                                              ClusterMembershipReducer reducer,
                                              LifecycleSnapshotReader lifecycleSnapshotReader,
                                              SlotSnapshotReader slotSnapshotReader) {
        return new MembershipFsm(self, config, reducer, lifecycleSnapshotReader, slotSnapshotReader);
    }

    public boolean shadowEnabled() {
        return config.shadowEnabled();
    }

    public Promise<Unit> start() {
        if (!started.compareAndSet(false, true)) {
            return Promise.unitPromise();
        }
        if (!config.shadowEnabled()) {
            log.debug("MembershipFsm shadow disabled for {} — no KV replay, no notifications wired",
                      self.id());
            return Promise.unitPromise();
        }
        replayFromKv();
        log.info("MembershipFsm shadow started for {} (peers reconstructed: {})",
                 self.id(),
                 fsmStates.size());
        return Promise.unitPromise();
    }

    public Promise<Unit> stop() {
        if (!started.compareAndSet(true, false)) {
            return Promise.unitPromise();
        }
        clearState();
        log.info("MembershipFsm shadow stopped for {}", self.id());
        return Promise.unitPromise();
    }

    /// Returns the current per-peer state map. Public read-only API for downstream subsystems
    /// (status routes, routing) per spec §9 E.3 deliverable.
    public Map<NodeId, MembershipFsmState> snapshot() {
        return Map.copyOf(fsmStates);
    }

    /// Returns the current state for `peer` if tracked, or `Option.none()` otherwise.
    public Option<MembershipFsmState> get(NodeId peer) {
        return option(fsmStates.get(peer));
    }

    /// SWIM observation entry point. Translates the observation into a typed FSM event and
    /// runs the reducer under `fsmLock`. Reducer outcome is logged but not applied (E.3).
    @Contract public void onSwimObservation(SwimObservation observation) {
        if (!shouldProcess()) {
            return;
        }
        translate(observation).onPresent(this::processShadowEvent);
    }

    /// Operator event entry point — used by E.4 to feed `OperatorDrain`/`OperatorDecommission`
    /// from REST handlers. Public so the E.4 wiring layer can plug it in without further
    /// changes to this file.
    @Contract public void enqueueOperatorEvent(MembershipFsmEvent event) {
        if (!shouldProcess()) {
            return;
        }
        processShadowEvent(event);
    }

    /// KV-notification handler for `NodeLifecycleKey` puts. Updates the shadow state from the
    /// externally-written lifecycle value WITHOUT emitting any reducer effects (the value
    /// already reflects the production write — the FSM derives its state, it doesn't re-act).
    @Contract public void onNodeLifecyclePut(ValuePut<NodeLifecycleKey, NodeLifecycleValue> put) {
        if (!shouldProcess()) {
            return;
        }
        applyExternalLifecyclePut(put.cause().key().nodeId(), put.cause().value());
    }

    /// KV-notification handler for `NodeLifecycleKey` removes. Returns the peer to `UNTRACKED`
    /// (this fires when `DecommissionedAtomGc` cleans up a fully-decommissioned atom). If the
    /// peer still has a provisioning slot, the shadow falls back to `PROVISIONING` instead.
    @Contract public void onNodeLifecycleRemove(ValueRemove<NodeLifecycleKey, NodeLifecycleValue> remove) {
        if (!shouldProcess()) {
            return;
        }
        applyExternalLifecycleRemove(remove.cause().key().nodeId());
    }

    /// KV-notification handler for `ProvisioningSlotKey` puts. Updates slot-to-peer mapping and,
    /// when the slot is newly claimed (`assignedNodeId.isPresent()`), feeds a `SlotClaimed`
    /// event into the reducer.
    @Contract public void onProvisioningSlotPut(ValuePut<ProvisioningSlotKey, ProvisioningSlotValue> put) {
        if (!shouldProcess()) {
            return;
        }
        applySlotPut(put.cause().key().slotId(), put.cause().value());
    }

    /// KV-notification handler for `ProvisioningSlotKey` removes. Cleans up the slot-to-peer
    /// mapping. Does not feed the reducer (the lifecycle transition `JOINING → ON_DUTY` /
    /// `JOINING → DECOMMISSIONED` already removed the slot association).
    @Contract public void onProvisioningSlotRemove(ValueRemove<ProvisioningSlotKey, ProvisioningSlotValue> remove) {
        if (!shouldProcess()) {
            return;
        }
        applySlotRemove(remove.cause().key().slotId());
    }

    private boolean shouldProcess() {
        return started.get() && config.shadowEnabled();
    }

    private void applyExternalLifecyclePut(NodeId peer, NodeLifecycleValue value) {
        fsmLock.lock();
        try {
            var newState = deriveStateFromLifecycle(peer, value, slotIdForPeer(peer));
            var previous = fsmStates.put(peer, newState);
            logExternalLifecycleChange(peer, previous, newState, value.state());
        } finally {
            fsmLock.unlock();
        }
    }

    private void applyExternalLifecycleRemove(NodeId peer) {
        fsmLock.lock();
        try {
            var slotIdOpt = slotIdForPeer(peer);
            slotIdOpt.apply(() -> applyLifecycleRemoveWithoutSlot(peer),
                            slotId -> applyLifecycleRemoveWithSlot(peer, slotId));
        } finally {
            fsmLock.unlock();
        }
    }

    private void applyLifecycleRemoveWithoutSlot(NodeId peer) {
        var previous = fsmStates.remove(peer);
        log.info("Shadow FSM: lifecycle-removed peer={} previous={} → UNTRACKED",
                 peer.id(),
                 describe(previous));
    }

    private void applyLifecycleRemoveWithSlot(NodeId peer, String slotId) {
        var derived = MembershipFsmState.provisioning(peer, slotId);
        var previous = fsmStates.put(peer, derived);
        log.info("Shadow FSM: lifecycle-removed peer={} retained slot={}, prior={} → PROVISIONING",
                 peer.id(),
                 slotId,
                 describe(previous));
    }

    private void applySlotPut(String slotId, ProvisioningSlotValue value) {
        value.assignedNodeId().apply(() -> applySlotPutUnassigned(slotId),
                                      peer -> applySlotPutAssigned(slotId, peer, value));
    }

    private void applySlotPutUnassigned(String slotId) {
        slotIdToPeer.remove(slotId);
        log.debug("Shadow FSM: slot-put slotId={} unassigned (PROVISIONING placeholder; spec §4.2)",
                  slotId);
    }

    private void applySlotPutAssigned(String slotId, NodeId peer, ProvisioningSlotValue value) {
        slotIdToPeer.put(slotId, peer);
        fsmLock.lock();
        try {
            ensureProvisioningTracked(peer, slotId);
            processShadowEventLocked(new SlotClaimed(peer, slotId, value.spawnedAtMs()));
        } finally {
            fsmLock.unlock();
        }
    }

    private void ensureProvisioningTracked(NodeId peer, String slotId) {
        fsmStates.computeIfAbsent(peer, key -> MembershipFsmState.provisioning(key, slotId));
    }

    private void applySlotRemove(String slotId) {
        var removedPeer = slotIdToPeer.remove(slotId);
        log.debug("Shadow FSM: slot-removed slotId={} peer={}",
                  slotId,
                  removedPeer == null ? "<unassigned>" : removedPeer.id());
    }

    private void processShadowEvent(MembershipFsmEvent event) {
        fsmLock.lock();
        try {
            processShadowEventLocked(event);
        } finally {
            fsmLock.unlock();
        }
    }

    private void processShadowEventLocked(MembershipFsmEvent event) {
        var peer = event.peer();
        var current = fsmStates.getOrDefault(peer, MembershipFsmState.untracked(peer));
        var outcome = reducer.apply(current, event);
        fsmStates.put(peer, outcome.newState());
        logShadowOutcome(event, current, outcome);
    }

    private void replayFromKv() {
        fsmLock.lock();
        try {
            clearState();
            slotSnapshotReader.forEachSlot(this::indexSlotForReplay);
            lifecycleSnapshotReader.forEachLifecycle(this::reconstructPeerFromLifecycle);
            slotSnapshotReader.forEachSlot(this::ensureSlotPeerCovered);
            log.info("Shadow FSM replay: lifecycle peers={}, slot peers={}",
                     fsmStates.size(),
                     slotIdToPeer.size());
        } finally {
            fsmLock.unlock();
        }
    }

    private void indexSlotForReplay(ProvisioningSlotKey slotKey, ProvisioningSlotValue slotValue) {
        slotValue.assignedNodeId().onPresent(peer -> slotIdToPeer.put(slotKey.slotId(), peer));
    }

    private void reconstructPeerFromLifecycle(NodeLifecycleKey lifecycleKey, NodeLifecycleValue value) {
        var peer = lifecycleKey.nodeId();
        var derived = deriveStateFromLifecycle(peer, value, slotIdForPeer(peer));
        fsmStates.put(peer, derived);
    }

    private void ensureSlotPeerCovered(ProvisioningSlotKey slotKey, ProvisioningSlotValue slotValue) {
        slotValue.assignedNodeId().onPresent(peer -> ensureProvisioningTracked(peer, slotKey.slotId()));
    }

    private void clearState() {
        fsmStates.clear();
        slotIdToPeer.clear();
    }

    private Option<String> slotIdForPeer(NodeId peer) {
        for (var entry : slotIdToPeer.entrySet()) {
            if (entry.getValue().equals(peer)) {
                return some(entry.getKey());
            }
        }
        return none();
    }

    private static MembershipFsmState deriveStateFromLifecycle(NodeId peer,
                                                                NodeLifecycleValue value,
                                                                Option<String> slotId) {
        return switch (value.state()) {
            case JOINING -> MembershipFsmState.joining(peer, value.updatedAt(), slotId);
            case ON_DUTY -> MembershipFsmState.onDuty(peer, value.updatedAt());
            case DRAINING -> MembershipFsmState.draining(peer, value.updatedAt(), DrainReason.OPERATOR_DRAIN);
            case DECOMMISSIONED -> MembershipFsmState.decommissioned(peer, value.updatedAt());
            case FAILED_DRAIN -> MembershipFsmState.failedDrain(peer, value.updatedAt());
            case SHUTTING_DOWN -> MembershipFsmState.draining(peer, value.updatedAt(), DrainReason.OPERATOR_DRAIN);
        };
    }

    private static Option<MembershipFsmEvent> translate(SwimObservation observation) {
        var nowMs = System.currentTimeMillis();
        return switch (observation) {
            case HealthyObserved h -> some(new SwimHealthy(h.peer(), h.incarnation(), nowMs));
            case FaultyObserved f -> some(new SwimFaulty(f.peer(), f.incarnation(), nowMs));
            case DepartedObserved d -> some(new SwimDeparted(d.peer(), d.incarnation(), nowMs));
            case SuspectObserved _ -> none();
            case UnknownObserved _ -> none();
        };
    }

    private void logShadowOutcome(MembershipFsmEvent event,
                                  MembershipFsmState priorState,
                                  Outcome outcome) {
        log.info("Shadow FSM: event={} peer={} priorState={} → newState={} would-write={} would-effect={}",
                 event.getClass().getSimpleName(),
                 event.peer().id(),
                 describe(priorState),
                 describe(outcome.newState()),
                 outcome.writes(),
                 outcome.effects());
    }

    private void logExternalLifecycleChange(NodeId peer,
                                            MembershipFsmState previous,
                                            MembershipFsmState newState,
                                            NodeLifecycleState lifecycleState) {
        log.info("Shadow FSM: external KV write peer={} priorState={} newState={} lifecycle={}",
                 peer.id(),
                 describe(previous),
                 describe(newState),
                 lifecycleState);
    }

    private static String describe(MembershipFsmState state) {
        if (state == null) {
            return "<absent/UNTRACKED>";
        }
        return state.getClass().getSimpleName();
    }

    /// Snapshot reader for `NodeLifecycleKey → NodeLifecycleValue` entries. Implemented by
    /// the wiring layer over `KVStore.forEach(...)`. Kept as a `@FunctionalInterface` so the
    /// shadow has no compile-time dependency on `KVStore` and remains testable with a
    /// hand-rolled fake.
    @FunctionalInterface
    public interface LifecycleSnapshotReader {
        @Contract void forEachLifecycle(BiConsumer<NodeLifecycleKey, NodeLifecycleValue> consumer);
    }

    /// Snapshot reader for `ProvisioningSlotKey → ProvisioningSlotValue` entries.
    @FunctionalInterface
    public interface SlotSnapshotReader {
        @Contract void forEachSlot(BiConsumer<ProvisioningSlotKey, ProvisioningSlotValue> consumer);
    }
}
