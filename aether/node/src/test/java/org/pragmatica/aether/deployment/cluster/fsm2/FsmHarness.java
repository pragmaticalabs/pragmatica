// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Predicate;

import io.netty.buffer.ByteBuf;
import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager.DeploymentAtomicity;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterInput.Settlement;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification;
import org.pragmatica.cluster.state.kvstore.LeaderAuthorized;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.cluster.state.kvstore.StructuredKey;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

/// The interpreter of the cluster FSM for tests: an injected wall and monotonic clock, an injected scheduler (a due-time
/// queue), a mailbox, and the **real `KVStore` applier** that receives every `Submit` and notifies the FSM back.
///
/// Nothing here is a model of the applier: transactions are applied by `KVStore.process`, and the FSM sees only what the
/// applier notifies. A `SettlePolicy` decides what happens to a submission, so lost, late, refused and failed writes can be
/// driven (#2143).
final class FsmHarness {
    static final NodeId SELF = NodeId.nodeId("leader-1").unwrap();
    static final long START_WALL = 1_760_000_000_000L;
    static final long START_MONO = 5_000_000_000L;

    enum SettlePolicy {
        /// Apply the transaction and report the applier's verdict.
        APPLY,
        /// The submission is lost: no application, no settlement.
        LOSE,
        /// The submission is held: no application and no settlement, until `applyHeld` / `settleHeld`.
        HOLD,
        /// The settlement is a failure; the transaction is held and may commit later (`applyHeld`).
        FAIL_AND_HOLD
    }

    record Due(long atNs, long order, TimerId timer, long incarnation) {}

    final ClusterFsm fsm;
    final Settings settings;
    final KVStore<AetherKey, AetherValue> store;
    final MessageRouter.MutableRouter router = MessageRouter.mutable();
    final ArrayDeque<ClusterInput> mailbox = new ArrayDeque<>();
    final PriorityQueue<Due> timers = new PriorityQueue<>(Comparator.comparingLong(Due::atNs).thenComparingLong(Due::order));
    final List<ClusterEffect> log = new ArrayList<>();
    final List<List<ClusterEffect>> steps = new ArrayList<>();
    final Map<String, ClusterEffect.Submit> held = new LinkedHashMap<>();
    final Map<NodeId, Admission> admissions = new HashMap<>();
    long wallMs = START_WALL;
    long monoNs = START_MONO;
    long order;
    int inputs;
    Seams seams = Seams.UNWIRED;
    SettlePolicy policy = SettlePolicy.APPLY;
    ClusterState state = ClusterState.initial();
    boolean notifying = true;

    FsmHarness(Settings settings) {
        this.settings = settings;
        this.fsm = ClusterFsm.clusterFsm(settings, () -> wallMs, () -> monoNs);
        this.store = new KVStore<>(router, new Serializer() {
            @Override
            public <T> void write(ByteBuf buffer, T value) {}
        }, new Deserializer() {
            @Override
            public <T> T read(ByteBuf buffer) {
                return null;
            }
        });
        router.addRoute(KVStoreNotification.ValuePut.class, this::onPut);
        router.addRoute(KVStoreNotification.ValueRemove.class, this::onRemove);
    }

    static FsmHarness harness() {
        return new FsmHarness(Settings.settings(SELF, DeploymentAtomicity.ALL_OR_NOTHING, Set.of(), Fixtures.bounds()));
    }

    // -- the applier ---------------------------------------------------------------------------------------------

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void onPut(KVStoreNotification.ValuePut put) {
        if (!notifying) {
            return;
        }

        var key = put.cause().key();
        var value = put.cause().value();

        if (key instanceof AetherKey aetherKey && value instanceof AetherValue aetherValue) {
            mailbox.addLast(new ClusterInput.KvPut(aetherKey, aetherValue));
        } else if (key instanceof LeaderKey && value instanceof LeaderValue leader) {
            mailbox.addLast(new ClusterInput.LeaderObserved(leader));
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void onRemove(KVStoreNotification.ValueRemove remove) {
        if (notifying && remove.cause().key() instanceof AetherKey key) {
            mailbox.addLast(new ClusterInput.KvRemove(key));
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    Object applyCommand(KVCommand command) {
        return ((KVStore) store).process(((KVStore) store).createBatch(List.of(command))).getFirst();
    }

    /// Commit the leader value (the applier fences a `LeaderKey` write by `viewSequence`).
    void commitLeader(NodeId leader, long viewSequence) {
        applyCommand(new KVCommand.Put<StructuredKey, Object>(LeaderKey.INSTANCE, new LeaderValue(leader, viewSequence)));
    }

    Option<LeaderValue> committedLeader() {
        return ((KVStore<StructuredKey, Object>) (KVStore) store).get(LeaderKey.INSTANCE)
                                                                  .filter(LeaderValue.class::isInstance)
                                                                  .map(LeaderValue.class::cast);
    }

    /// Write a value as a test fixture, as the right writer would: a bare Put for an unmarked value, a one-mutation
    /// `LeaderTransaction` for a `LeaderAuthorized` one. Notifications are delivered to the FSM like any other.
    @SuppressWarnings({"unchecked", "rawtypes"})
    void seed(AetherKey key, AetherValue value) {
        if (value instanceof LeaderAuthorized) {
            var leader = committedLeader().or(new LeaderValue(SELF, 1L));
            var expected = store.get(key);
            var mutation = new KVCommand.Mutation<AetherKey, AetherValue>(key, expected, Option.some(value));

            applyCommand(new KVCommand.LeaderTransaction<AetherKey, AetherValue>(key, "seed", leader, List.of(), List.of(mutation)));
        } else {
            applyCommand(new KVCommand.Put<AetherKey, AetherValue>(key, value));
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    void seedRemove(AetherKey key) {
        var current = store.get(key);

        if (current.isPresent() && current.or((AetherValue) null) instanceof LeaderAuthorized) {
            var leader = committedLeader().or(new LeaderValue(SELF, 1L));
            var mutation = new KVCommand.Mutation<AetherKey, AetherValue>(key, current, Option.none());

            applyCommand(new KVCommand.LeaderTransaction<AetherKey, AetherValue>(key, "seed-remove", leader, List.of(), List.of(mutation)));
        } else {
            applyCommand(new KVCommand.Remove<AetherKey>(key, current.map(v -> (Object) v)));
        }
    }

    /// Seed without telling the FSM (state that already existed before it was activated).
    void seedQuietly(AetherKey key, AetherValue value) {
        notifying = false;
        seed(key, value);
        notifying = true;
    }

    // -- the mailbox ---------------------------------------------------------------------------------------------

    ClusterInput.Snapshot snapshot() {
        var entries = new ArrayList<ClusterInput.KvPut>();

        ((KVStore<StructuredKey, Object>) (KVStore) store).snapshot()
                                                          .forEach((key, value) -> {
                                                              if (key instanceof AetherKey aetherKey && value instanceof AetherValue aetherValue) {
                                                                  entries.add(new ClusterInput.KvPut(aetherKey, aetherValue));
                                                              }
                                                          });
        return ClusterInput.Snapshot.snapshot(entries, seams, admissions, committedLeader());
    }

    /// Deliver one input and run the mailbox until it is empty.
    FsmHarness deliver(ClusterInput input) {
        mailbox.addLast(input);
        return drain();
    }

    FsmHarness drain() {
        var guard = 0;

        while (!mailbox.isEmpty()) {
            if (++guard > 20_000) {
                throw new AssertionError("the mailbox did not drain: the FSM keeps queueing work for itself");
            }

            step(mailbox.pollFirst());
        }
        return this;
    }

    /// Deliver `inputs` in one drain: they are all queued before any is handled, so the end-of-drain flush sees them all.
    FsmHarness deliverTogether(ClusterInput... inputs) {
        for (var input : inputs) {
            mailbox.addLast(input);
        }
        return drain();
    }

    void step(ClusterInput input) {
        inputs++;
        var result = fsm.step(state, input);

        state = result.state();
        steps.add(result.effects());
        result.effects().forEach(this::perform);
    }

    private void perform(ClusterEffect effect) {
        log.add(effect);
        switch (effect) {
            case ClusterEffect.Enqueue enqueue -> mailbox.addLast(enqueue.input());
            case ClusterEffect.RequestRebuild _ -> mailbox.addFirst(new ClusterInput.Rebuild(snapshot()));
            case ClusterEffect.Schedule schedule -> timers.add(new Due(monoNs + schedule.delayMs() * 1_000_000L, order++, schedule.timer(), schedule.incarnation()));
            case ClusterEffect.Cancel cancel -> timers.removeIf(due -> due.timer().equals(cancel.timer()));
            case ClusterEffect.Submit submit -> submitted(submit);
            default -> {}
        }
    }

    private void submitted(ClusterEffect.Submit submit) {
        switch (policy) {
            case APPLY -> settle(submit, applyTransaction(submit));
            case LOSE -> {}
            case HOLD -> held.put(submit.correlationId(), submit);
            case FAIL_AND_HOLD -> {
                held.put(submit.correlationId(), submit);
                mailbox.addLast(new ClusterInput.WriteSettled(submit.correlationId(), submit.incarnation(), new Settlement.Failed("injected")));
            }
        }
    }

    /// Apply the transaction with the real applier; the verdict is the applier's.
    Settlement applyTransaction(ClusterEffect.Submit submit) {
        var result = (KVCommand.TransactionResult) applyCommand(submit.transaction());

        return result.accepted()
               ? new Settlement.Accepted()
               : refused(submit);
    }

    Settlement refused(ClusterEffect.Submit submit) {
        var values = new HashMap<AetherKey, Option<AetherValue>>();

        submit.transaction().mutations().forEach(mutation -> values.put(mutation.key(), store.get(mutation.key())));
        submit.transaction().guards().forEach(guard -> values.put(guard.key(), store.get(guard.key())));
        return new Settlement.Refused(committedLeader(), values);
    }

    void settle(ClusterEffect.Submit submit, Settlement settlement) {
        mailbox.addLast(new ClusterInput.WriteSettled(submit.correlationId(), submit.incarnation(), settlement));
    }

    /// A held transaction commits now (a late commit), without any settlement.
    Settlement applyHeld(String correlation) {
        var submit = held.remove(correlation);

        return applyTransaction(submit);
    }

    /// Report a held transaction as settled with `settlement`.
    FsmHarness settleHeld(ClusterEffect.Submit submit, Settlement settlement) {
        settle(submit, settlement);
        return drain();
    }

    // -- time ----------------------------------------------------------------------------------------------------

    /// Advance both clocks and fire the timers that fall due, in order, each followed by a full drain.
    FsmHarness advance(long ms) {
        var target = monoNs + ms * 1_000_000L;

        while (!timers.isEmpty() && timers.peek().atNs() <= target) {
            var due = timers.poll();

            wallMs += (due.atNs() - monoNs) / 1_000_000L;
            monoNs = due.atNs();
            deliver(new ClusterInput.Timer(due.timer(), due.incarnation()));
        }

        wallMs += (target - monoNs) / 1_000_000L;
        monoNs = target;
        return this;
    }

    /// Advance one second at a time (lets time-driven judgments see each boundary).
    FsmHarness advanceBySeconds(long seconds) {
        for (var i = 0L; i < seconds; i++) {
            advance(1_000L);
        }
        return this;
    }

    // -- lifecycle -----------------------------------------------------------------------------------------------

    FsmHarness activate() {
        return deliver(new ClusterInput.Activate());
    }

    ClusterModel model() {
        return ((ClusterState.Active) state).model();
    }

    boolean isActive() {
        return state instanceof ClusterState.Active;
    }

    // -- observation ---------------------------------------------------------------------------------------------

    <T extends ClusterEffect> List<T> effects(Class<T> type) {
        return log.stream().filter(type::isInstance).map(type::cast).toList();
    }

    List<ClusterEffect.Submit> submits() {
        return effects(ClusterEffect.Submit.class);
    }

    List<ClusterEffect.Submit> submits(ClusterModel.SubmitKind kind) {
        return submits().stream().filter(submit -> submit.kind() == kind).toList();
    }

    List<ClusterEffect.Warn> warns(String code) {
        return effects(ClusterEffect.Warn.class).stream().filter(warn -> warn.code().equals(code)).toList();
    }

    long metricTotal(String name, Predicate<Map<String, String>> tags) {
        return effects(ClusterEffect.Metric.class).stream()
                                                  .filter(metric -> metric.name().equals(name) && tags.test(metric.tags()))
                                                  .mapToLong(ClusterEffect.Metric::delta)
                                                  .sum();
    }

    List<KVCommand.Mutation<AetherKey, AetherValue>> mutations(Class<? extends AetherKey> keyType) {
        return submits().stream()
                        .flatMap(submit -> submit.transaction().mutations().stream())
                        .filter(mutation -> keyType.isInstance(mutation.key()))
                        .toList();
    }

    /// Forget everything logged so far (to assert on what happens next).
    FsmHarness clearLog() {
        log.clear();
        steps.clear();
        return this;
    }
}
