// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.reconciler;

import org.pragmatica.aether.config.ReconcilerConfig;
import org.pragmatica.aether.config.RuleSpec;
import org.pragmatica.aether.deployment.audit.CommandLifecycleEvent;
import org.pragmatica.aether.deployment.audit.CommandLifecycleEvent.CommandReceived;
import org.pragmatica.aether.deployment.cluster.LifecycleWriter;
import org.pragmatica.aether.deployment.membership.fsm.LifecycleCommand;
import org.pragmatica.aether.deployment.membership.fsm.LifecycleCommand.ForceDecommission;
import org.pragmatica.aether.deployment.membership.fsm.LifecycleCommand.ForceDrain;
import org.pragmatica.aether.deployment.membership.fsm.LifecycleCommand.ForceOnDuty;
import org.pragmatica.aether.deployment.membership.fsm.LifecycleCommand.RecordJoining;
import org.pragmatica.aether.deployment.membership.fsm.LifecycleCommand.RequestReJoin;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsmConfig;
import org.pragmatica.aether.deployment.reconciler.LifecycleReconciler.RuleDecision;
import org.pragmatica.aether.deployment.reconciler.LifecycleReconciler.RuleStatus;
import org.pragmatica.aether.deployment.reconciler.rules.DrainTimeout;
import org.pragmatica.aether.deployment.reconciler.rules.GenerationLifecycleGap;
import org.pragmatica.aether.deployment.reconciler.rules.JoiningStuckAlert;
import org.pragmatica.aether.deployment.reconciler.rules.JoiningTimeout;
import org.pragmatica.aether.deployment.reconciler.rules.OnDutyFaulty;
import org.pragmatica.aether.deployment.reconciler.rules.ReconciliationAction;
import org.pragmatica.aether.deployment.reconciler.rules.ReconciliationRule;
import org.pragmatica.aether.deployment.reconciler.rules.ReconciliationSnapshot;
import org.pragmatica.aether.deployment.reconciler.rules.StoppedZombie;
import org.pragmatica.aether.deployment.reconciler.rules.SwimLifecycleGap;
import org.pragmatica.aether.slice.StreamPublisher;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DrainDeadlineKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.JoinDeadlineKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeLifecycleKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterPhase;
import org.pragmatica.aether.slice.kvstore.AetherValue.DrainDeadlineValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.JoinDeadlineValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.MembershipView;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.swim.SwimHealth;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Phase 4 PR-D — leader-only periodic `LifecycleReconciler` implementation
/// (cluster-convergence-reconciler-spec §7).
///
/// Lifecycle binding mirrors `ClusterTopologyManagerRecord`: `activate()` schedules a
/// fixed-rate tick at `config.tickInterval()`; `deactivate()` cancels the tick. Both
/// methods are idempotent — `activate()` while already active is a no-op; same for
/// `deactivate()`.
///
/// Each tick:
///   1. Phase gate — if `phaseSupplier.get() != NORMAL`, log at debug and return.
///   2. Build a `ReconciliationSnapshot` from KV (lifecycle / deadline atoms), SWIM
///      (`peerObservations`), generation snapshot, and `activeSyncHolds`.
///   3. For each rule in the rules registry (insertion order):
///      a. Invoke `rule.evaluate(snapshot)`.
///      b. For each emitted `ReconciliationAction`:
///         - If the rule's `enforce` toggle is true: dispatch via
///           `lifecycleWriter.applyCommand(action.command(), SOURCE_RECONCILER)`.
///         - Else: publish an audit-only `CommandReceived(accepted=false,
///           source=RECONCILER)` so operators can observe the would-have-fired set.
///         - Record the decision in the per-rule ring buffer (`recentDecisions`).
///   4. Update `lastTickAt` and (if any action fired) `lastActionAt`.
///
/// **SWIM "since" tracking** is maintained inside this record — the snapshot supplied
/// to rules carries `swimSinceMs` derived by diffing the previous tick's
/// `swimHealthSnapshot` against the current one. Peers whose health is unchanged across
/// consecutive ticks retain their original "since" timestamp; peers whose health
/// changes get a fresh "since" timestamp equal to the current `nowMs`.
///
/// Read-only status accessors back the `GET /api/nodes/lifecycle/reconciler` endpoint.
record LifecycleReconcilerRecord(Supplier<ClusterPhase> phaseSupplier,
                                 KVStore<AetherKey, AetherValue> kvStore,
                                 Supplier<Option<MembershipView>> generationSnapshot,
                                 Supplier<Map<NodeId, SwimHealth>> swimHealthSnapshot,
                                 Supplier<Set<NodeId>> activeSyncHolds,
                                 LifecycleWriter lifecycleWriter,
                                 StreamPublisher<CommandLifecycleEvent> auditPublisher,
                                 MembershipFsmConfig fsmConfig,
                                 ReconcilerConfig config,
                                 LongSupplier clock,
                                 AtomicBoolean activeRef,
                                 AtomicReference<ScheduledFuture<?>> scheduledTickRef,
                                 AtomicLong lastTickAtMs,
                                 AtomicLong lastActionAtMs,
                                 AtomicLong normalEnteredAtMs,
                                 AtomicReference<ClusterPhase> lastObservedPhase,
                                 List<ReconciliationRule> rules,
                                 ConcurrentHashMap<String, AtomicLong> ruleLastFiredAtMs,
                                 ConcurrentHashMap<String, AtomicLong> ruleFireCountMs,
                                 Deque<RuleDecision> recentDecisionsBuffer,
                                 Object decisionsLock,
                                 ConcurrentHashMap<NodeId, SwimSinceEntry> swimSinceTracker) implements LifecycleReconciler {
    private static final Logger log = LoggerFactory.getLogger(LifecycleReconcilerRecord.class);
    private static final long UNINITIALIZED = -1L;

    /// Build the record-shaped reconciler. The seven rules are wired in fixed insertion
    /// order; the `activate()` lifecycle, in-flight tick scheduling, and decision buffer
    /// are initialised from the supplied `config`.
    static LifecycleReconcilerRecord lifecycleReconcilerRecord(Supplier<ClusterPhase> phaseSupplier,
                                                               KVStore<AetherKey, AetherValue> kvStore,
                                                               Supplier<Option<MembershipView>> generationSnapshot,
                                                               Supplier<Map<NodeId, SwimHealth>> swimHealthSnapshot,
                                                               Supplier<Set<NodeId>> activeSyncHolds,
                                                               LifecycleWriter lifecycleWriter,
                                                               StreamPublisher<CommandLifecycleEvent> auditPublisher,
                                                               MembershipFsmConfig fsmConfig,
                                                               ReconcilerConfig config,
                                                               LongSupplier clock) {
        var rules = List.<ReconciliationRule>of(JoiningTimeout.joiningTimeout(),
                                                JoiningStuckAlert.joiningStuckAlert(),
                                                OnDutyFaulty.onDutyFaulty(),
                                                DrainTimeout.drainTimeout(),
                                                GenerationLifecycleGap.generationLifecycleGap(),
                                                SwimLifecycleGap.swimLifecycleGap(),
                                                StoppedZombie.stoppedZombie());
        return new LifecycleReconcilerRecord(phaseSupplier,
                                             kvStore,
                                             generationSnapshot,
                                             swimHealthSnapshot,
                                             activeSyncHolds,
                                             lifecycleWriter,
                                             auditPublisher,
                                             fsmConfig,
                                             config,
                                             clock,
                                             new AtomicBoolean(false),
                                             new AtomicReference<>(null),
                                             new AtomicLong(UNINITIALIZED),
                                             new AtomicLong(UNINITIALIZED),
                                             new AtomicLong(UNINITIALIZED),
                                             new AtomicReference<>(ClusterPhase.COLD_BOOT),
                                             rules,
                                             initRuleCounters(rules),
                                             initRuleCounters(rules),
                                             new ArrayDeque<>(),
                                             new Object(),
                                             new ConcurrentHashMap<>());
    }

    private static ConcurrentHashMap<String, AtomicLong> initRuleCounters(List<ReconciliationRule> rules) {
        var map = new ConcurrentHashMap<String, AtomicLong>();
        rules.forEach(rule -> map.put(rule.name(), new AtomicLong(0L)));
        return map;
    }

    @Contract @Override public void activate() {
        if (!activeRef.compareAndSet(false, true)) {return;}
        if (!config.enabled()) {
            log.info("Reconciler: activate skipped — disabled in config");
            return;
        }
        var future = SharedScheduler.scheduleAtFixedRate(this::reconcile, config.tickInterval());
        scheduledTickRef.set(future);
        log.info("Reconciler: activated (tickInterval={})", config.tickInterval());
    }

    @Contract @Override public void deactivate() {
        if (!activeRef.compareAndSet(true, false)) {return;}
        cancelScheduledTick();
        log.info("Reconciler: deactivated");
    }

    @Override public boolean active() {
        return activeRef.get();
    }

    @Override public ClusterPhase observedPhase() {
        return lastObservedPhase.get();
    }

    @Override public Option<Long> lastTickAt() {
        return optionFromTimestamp(lastTickAtMs.get());
    }

    @Override public Option<Long> lastActionAt() {
        return optionFromTimestamp(lastActionAtMs.get());
    }

    @Override public List<RuleStatus> ruleStatuses() {
        var statuses = new ArrayList<RuleStatus>();
        rules.forEach(rule -> statuses.add(buildRuleStatus(rule)));
        return List.copyOf(statuses);
    }

    @Override public List<RuleDecision> recentDecisions() {
        synchronized (decisionsLock) {
            return List.copyOf(recentDecisionsBuffer);
        }
    }

    @Contract private void reconcile() {
        try {
            doReconcile();
        } catch (Throwable t) {
            log.warn("Reconciler: tick failed: {}", t.toString(), t);
        }
    }

    @Contract private void doReconcile() {
        if (!activeRef.get()) {return;}
        var phase = phaseSupplier.get();
        var priorPhase = lastObservedPhase.getAndSet(phase);
        var nowMs = clock.getAsLong();

        if (phase != ClusterPhase.NORMAL) {
            if (priorPhase == ClusterPhase.NORMAL) {
                // NORMAL → not-NORMAL: clear warmup + SWIM-since so the next NORMAL entry
                // gets a fresh observation window (stale Faulty-since stamps from the prior
                // NORMAL window must not fire OnDutyFaulty / JoiningTimeout on re-entry).
                normalEnteredAtMs.set(UNINITIALIZED);
                swimSinceTracker.clear();
                log.info("Reconciler: phase {} -> {} — cleared warmup + SWIM-since tracker",
                         priorPhase,
                         phase);
            }
            log.debug("Reconciler: tick skipped — phase={}", phase);
            return;
        }

        if (priorPhase != ClusterPhase.NORMAL) {
            // not-NORMAL → NORMAL transition. Stamp the entry time and start warmup.
            normalEnteredAtMs.set(nowMs);
            swimSinceTracker.clear();
            log.info("Reconciler: phase {} -> NORMAL — warmup={} starts at nowMs={}",
                     priorPhase,
                     config.normalPhaseWarmup(),
                     nowMs);
        }

        var snapshot = buildSnapshot();
        lastTickAtMs.set(snapshot.nowMs());

        var normalEntered = normalEnteredAtMs.get();
        var warmupRemaining = (normalEntered == UNINITIALIZED)
                              ? 0L
                              : (normalEntered + config.normalPhaseWarmup().millis() - snapshot.nowMs());
        if (warmupRemaining > 0L) {
            log.debug("Reconciler: NORMAL-phase warmup active ({}ms remaining) — rules skipped this tick",
                      warmupRemaining);
            return;
        }

        var fired = false;
        for (var rule : rules) {
            fired |= evaluateRule(rule, snapshot);
        }
        if (fired) {lastActionAtMs.set(snapshot.nowMs());}
    }

    private boolean evaluateRule(ReconciliationRule rule, ReconciliationSnapshot snapshot) {
        var result = rule.evaluate(snapshot);
        if (result.isFailure()) {
            log.warn("Reconciler: rule {} failed: {}",
                     rule.name(),
                     result.fold(Cause::message, _ -> ""));
            return false;
        }
        var actions = result.fold(_ -> List.<ReconciliationAction>of(), a -> a);
        if (actions.isEmpty()) {return false;}

        var ruleSpec = ruleSpecFor(rule);
        actions.forEach(action -> dispatchAction(rule, ruleSpec, action, snapshot.nowMs()));
        return true;
    }

    private RuleSpec ruleSpecFor(ReconciliationRule rule) {
        return switch (rule.name()) {
            case JoiningTimeout.NAME -> config.rules().joiningTimeout();
            case JoiningStuckAlert.NAME -> config.rules().joiningStuckAlert();
            case OnDutyFaulty.NAME -> config.rules().onDutyFaulty();
            case DrainTimeout.NAME -> config.rules().drainTimeout();
            case GenerationLifecycleGap.NAME -> config.rules().generationLifecycleGap();
            case SwimLifecycleGap.NAME -> config.rules().swimLifecycleGap();
            case StoppedZombie.NAME -> config.rules().stoppedZombie();
            default -> RuleSpec.disabled();
        };
    }

    @Contract private void dispatchAction(ReconciliationRule rule,
                                          RuleSpec ruleSpec,
                                          ReconciliationAction action,
                                          long nowMs) {
        recordDecision(rule, ruleSpec.enforce(), action, nowMs);
        if (ruleSpec.enforce()) {
            applyEnforcing(action);
        } else {
            publishAuditOnly(action);
        }
    }

    @Contract private void applyEnforcing(ReconciliationAction action) {
        lifecycleWriter.applyCommand(action.command(), CommandLifecycleEvent.SOURCE_RECONCILER)
                       .onFailure(cause -> log.warn("Reconciler: enforce failed for {} on {}: {}",
                                                    commandType(action.command()),
                                                    action.peer().id(),
                                                    cause.message()));
    }

    @Contract private void publishAuditOnly(ReconciliationAction action) {
        var event = new CommandReceived(commandType(action.command()),
                                        action.peer().id(),
                                        reasonTag(action.command()),
                                        action.justification().message(),
                                        CommandLifecycleEvent.SOURCE_RECONCILER,
                                        clock.getAsLong());
        auditPublisher.publish(event)
                      .onFailure(cause -> log.debug("Reconciler: audit publish failed (dry-run): {}",
                                                     cause.message()));
    }

    @Contract private void recordDecision(ReconciliationRule rule,
                                          boolean enforced,
                                          ReconciliationAction action,
                                          long nowMs) {
        ruleLastFiredAtMs.get(rule.name()).set(nowMs);
        ruleFireCountMs.get(rule.name()).incrementAndGet();
        var decision = new RuleDecision(rule.name(),
                                        action.peer().id(),
                                        commandType(action.command()),
                                        reasonTag(action.command()),
                                        action.justification().message(),
                                        enforced,
                                        nowMs);
        synchronized (decisionsLock) {
            if (recentDecisionsBuffer.size() >= config.recentDecisionsCapacity()) {
                recentDecisionsBuffer.pollFirst();
            }
            recentDecisionsBuffer.addLast(decision);
        }
    }

    private RuleStatus buildRuleStatus(ReconciliationRule rule) {
        var ruleSpec = ruleSpecFor(rule);
        var firedAt = ruleLastFiredAtMs.get(rule.name()).get();
        var fireCount = ruleFireCountMs.get(rule.name()).get();
        return new RuleStatus(rule.name(),
                              ruleSpec.enabled(),
                              ruleSpec.enforce(),
                              optionFromTimestamp(firedAt),
                              fireCount);
    }

    private ReconciliationSnapshot buildSnapshot() {
        var nowMs = clock.getAsLong();
        var lifecycleEntries = new HashMap<NodeId, NodeLifecycleValue>();
        kvStore.forEach(NodeLifecycleKey.class,
                        NodeLifecycleValue.class,
                        (key, value) -> lifecycleEntries.put(key.nodeId(), value));
        var joinDeadlines = new HashMap<NodeId, JoinDeadlineValue>();
        kvStore.forEach(JoinDeadlineKey.class,
                        JoinDeadlineValue.class,
                        (key, value) -> joinDeadlines.put(key.nodeId(), value));
        var drainDeadlines = new HashMap<NodeId, DrainDeadlineValue>();
        kvStore.forEach(DrainDeadlineKey.class,
                        DrainDeadlineValue.class,
                        (key, value) -> drainDeadlines.put(key.nodeId(), value));
        var generationMembers = generationSnapshot.get()
                                                  .map(MembershipView::coreMemberIds)
                                                  .or(Set.of());
        var swimHealth = swimHealthSnapshot.get();
        var swimSince = updateSwimSinceTracker(swimHealth, nowMs);
        var holds = activeSyncHolds.get();
        return new ReconciliationSnapshot(lifecycleEntries,
                                          swimHealth,
                                          swimSince,
                                          generationMembers,
                                          joinDeadlines,
                                          drainDeadlines,
                                          holds,
                                          nowMs,
                                          fsmConfig,
                                          config.rules());
    }

    /// Update the SWIM "since" tracker with the current health snapshot and return a
    /// map of `peer → first-observed-at-this-state-ms` for the rules. Peers whose
    /// health state is unchanged keep their original "since" stamp; peers whose health
    /// state has flipped (or which appeared this tick) are stamped with `nowMs`. Peers
    /// that have disappeared from the SWIM view are evicted.
    private Map<NodeId, Long> updateSwimSinceTracker(Map<NodeId, SwimHealth> swimHealth, long nowMs) {
        swimSinceTracker.keySet().retainAll(swimHealth.keySet());
        var result = new HashMap<NodeId, Long>();
        swimHealth.forEach((peer, health) -> recordSwimSince(peer, health, nowMs, result));
        return result;
    }

    @Contract private void recordSwimSince(NodeId peer,
                                           SwimHealth health,
                                           long nowMs,
                                           Map<NodeId, Long> sink) {
        var prior = swimSinceTracker.get(peer);
        if (prior == null || prior.health() != health) {
            swimSinceTracker.put(peer, new SwimSinceEntry(health, nowMs));
            sink.put(peer, nowMs);
        } else {
            sink.put(peer, prior.sinceMs());
        }
    }

    @Contract private void cancelScheduledTick() {
        var prior = scheduledTickRef.getAndSet(null);
        if (prior != null) {prior.cancel(false);}
    }

    private static Option<Long> optionFromTimestamp(long timestampMs) {
        return timestampMs == UNINITIALIZED ? Option.none() : Option.some(timestampMs);
    }

    private static String commandType(LifecycleCommand command) {
        return command.getClass().getSimpleName();
    }

    private static String reasonTag(LifecycleCommand command) {
        return switch (command) {
            case ForceDecommission cmd -> cmd.reason().name();
            case ForceDrain cmd -> cmd.reason().name();
            case ForceOnDuty _, RecordJoining _, RequestReJoin _ -> "";
        };
    }

    /// SWIM-since tracker entry. Records the SWIM `health` value last observed for a
    /// peer and the wall-clock `sinceMs` at which that state was first seen during the
    /// reconciler's tick history.
    record SwimSinceEntry(SwimHealth health, long sinceMs) {}
}
