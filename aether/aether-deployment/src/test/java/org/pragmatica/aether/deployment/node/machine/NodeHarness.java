// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node.machine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import io.netty.buffer.ByteBuf;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceObservedKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.Generation;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProcessIncarnation;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceDesiredValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.StructuredKey;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.BootTokens;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

/// Test interpreter for the node FSM: a mailbox, a virtual clock and scheduler, a scriptable local runtime, and the REAL
/// `KVStore` applier behind the node's observed key (spec 8.3: "run against the real KVStore applier"). The FSM itself is
/// the production [NodeFsm]; this class only plays the roles the spec gives the interpreter.
final class NodeHarness {
    static final NodeId SELF = NodeId.nodeId("worker-1").unwrap();
    static final NodeId PEER = NodeId.nodeId("worker-2").unwrap();
    static final NodeId PEER_B = NodeId.nodeId("worker-3").unwrap();
    static final long SELF_TOKEN = 0x5E1FL;
    static final long PEER_TOKEN = 0xBEEFL;
    static final long OTHER_PROCESS_TOKEN = 0x0BADL;
    static final long BOOT_INCARNATION = 1_760_000_000_000L;
    static final Artifact ARTIFACT = Artifact.artifact("org.example:orders:1.0.0").unwrap();
    static final Artifact ARTIFACT_B = Artifact.artifact("org.example:billing:2.0.0").unwrap();
    static final Artifact ARTIFACT_C = Artifact.artifact("org.example:audit:3.0.0").unwrap();
    static final Generation G1 = new Generation(5L, 1L);
    static final Generation G2 = new Generation(5L, 2L);
    static final Generation G3 = new Generation(5L, 3L);

    /// How the harness treats a `Submit`.
    enum WriteMode {
        /// Apply to the real store, settle Accepted, deliver the notification.
        APPLY,
        /// Apply to the real store and notify, but settle nothing.
        APPLY_SILENT,
        /// Apply to the real store, settle Accepted, deliver no notification.
        APPLY_NO_NOTIFY,
        /// The submission is lost: nothing applied, nothing settled.
        LOST,
        /// Consensus reports a failure at once.
        FAIL
    }

    final BootTokens tokens;
    final ProcessIncarnation process;
    final NodeFsmConfig config;
    final NodeFsmTimings timings;
    final KVStore<StructuredKey, Object> store = newStore();
    final List<NodeFsmEffect> effects = new ArrayList<>();
    final List<Submitted> submitted = new ArrayList<>();
    final List<Applied> history = new ArrayList<>();
    /// The wall clock at which each submission was handed to the interpreter, by correlation.
    final Map<Long, Long> submitWall = new LinkedHashMap<>();
    final Map<Long, Pending> pendingLocal = new LinkedHashMap<>();
    final Map<TimerId, Long> timers = new LinkedHashMap<>();
    final Map<Artifact, SliceDesiredValue> desired = new LinkedHashMap<>();
    final java.util.Set<Artifact> slicesInStore = new java.util.HashSet<>();
    final Map<LocalStep, LocalOutcome> script = new HashMap<>();
    /// A step whose outcome is computed when it runs, from the real store (a builder that reads the mirror).
    final Map<LocalStep, java.util.function.Supplier<LocalOutcome>> computed = new HashMap<>();
    final Map<LocalStep, Boolean> holdSteps = new HashMap<>();
    final List<LocalRun> localRuns = new ArrayList<>();
    final ArrayDeque<NodeFsmInput> mailbox = new ArrayDeque<>();
    long wall = 1_760_000_100_000L;
    WriteMode writeMode = WriteMode.APPLY;
    Function<Submitted, WriteMode> writeScript = _ -> null;
    final Map<ArtifactBase, Version> targets = new LinkedHashMap<>();
    final java.util.Set<Artifact> rowArtifacts = new java.util.HashSet<>();
    final NodeFsm fsm;
    NodeFsmModel model = NodeFsmModel.nodeFsmModel();

    NodeHarness() {
        this(SELF_TOKEN);
    }

    /// `selfToken` 0 is a node assembled without a boot token (L-17).
    NodeHarness(long selfToken) {
        this(selfToken, NodeFsmTimings.nodeFsmTimings());
    }

    NodeHarness(long selfToken, NodeFsmTimings custom) {
        tokens = BootTokens.bootTokens(selfToken);
        process = new ProcessIncarnation(BOOT_INCARNATION, tokens.self());
        config = NodeFsmConfig.nodeFsmConfig(SELF, process).withTimings(custom);
        timings = config.timings();
        fsm = NodeFsm.nodeFsm(config, () -> wall);
    }

    record Submitted(NodeFsmEffect.Submit submit, WriteMode mode, boolean applied) {}

    /// A change the real applier made to the node's own observed key, in order.
    record Applied(Artifact artifact, Option<SliceObservedValue> before, Option<SliceObservedValue> after) {}

    record Pending(NodeFsmEffect.LocalOp op) {}

    record LocalRun(long incarnation, long token, LocalStep step, Artifact artifact) {}

    private static KVStore<StructuredKey, Object> newStore() {
        return new KVStore<>(MessageRouter.mutable(), new Serializer() {
            @Override
            public <T> void write(ByteBuf buffer, T value) {}
        }, new Deserializer() {
            @Override
            public <T> T read(ByteBuf buffer) {
                return null;
            }
        });
    }

    // ------------------------------------------------------------------ driving

    /// Delivers one input and everything it causes, until the mailbox is empty.
    NodeHarness feed(NodeFsmInput input) {
        mailbox.add(input);

        return drain();
    }

    /// Delivers whatever is queued, and everything it causes.
    NodeHarness drain() {
        while (!mailbox.isEmpty()) {
            run(mailbox.poll());
        }

        return this;
    }

    private void run(NodeFsmInput input) {
        var transition = fsm.step(model, input);

        model = transition.model();
        transition.effects().forEach(this::interpret);
    }

    private void interpret(NodeFsmEffect effect) {
        effects.add(effect);

        switch (effect) {
            case NodeFsmEffect.Submit submit -> submit(submit);
            case NodeFsmEffect.LocalOp op -> local(op);
            case NodeFsmEffect.Schedule schedule -> timers.put(schedule.timer(), wall + schedule.delayMs());
            case NodeFsmEffect.Cancel cancel -> timers.remove(cancel.timer());
            default -> {}
        }
    }

    // ------------------------------------------------------------------ the real applier

    private void submit(NodeFsmEffect.Submit submit) {
        submitWall.put(submit.correlation(), wall);

        var mode = Option.option(writeScript.apply(new Submitted(submit, writeMode, false))).or(writeMode);

        if (mode == WriteMode.LOST) {
            submitted.add(new Submitted(submit, mode, false));
            return;
        }

        if (mode == WriteMode.FAIL) {
            submitted.add(new Submitted(submit, mode, false));
            mailbox.add(new NodeFsmInput.WriteSettled(submit.incarnation(), submit.artifact(), submit.correlation(), NodeFsmInput.WriteOutcome.failed(Causes.cause("consensus unavailable"))));
            return;
        }

        var before = observed(submit.artifact());

        applyBatch(submit.commands());
        submitted.add(new Submitted(submit, mode, true));

        var after = observed(submit.artifact());

        if (!before.equals(after)) {
            history.add(new Applied(submit.artifact(), before, after));
        }

        if (mode != WriteMode.APPLY_SILENT) {
            mailbox.add(new NodeFsmInput.WriteSettled(submit.incarnation(), submit.artifact(), submit.correlation(), NodeFsmInput.WriteOutcome.accepted()));
        }

        if (mode != WriteMode.APPLY_NO_NOTIFY) {
            notifyChange(submit.artifact(), before, after);
        }
    }

    /// A submission the harness held back reaches the applier now: the batch is applied against the real store, the own
    /// notification is delivered if the row changed, and the settlement arrives (a late original, spec NT-134 (b)).
    NodeHarness applyLate(NodeFsmEffect.Submit submit) {
        var before = observed(submit.artifact());

        applyBatch(submit.commands());

        var after = observed(submit.artifact());

        if (!before.equals(after)) {
            history.add(new Applied(submit.artifact(), before, after));
        }

        mailbox.add(new NodeFsmInput.WriteSettled(submit.incarnation(), submit.artifact(), submit.correlation(), NodeFsmInput.WriteOutcome.accepted()));
        notifyChange(submit.artifact(), before, after);

        return drain();
    }

    @SuppressWarnings("unchecked")
    void applyBatch(List<? extends KVCommand<AetherKey>> commands) {
        var batch = new ArrayList<KVCommand<StructuredKey>>();

        commands.forEach(command -> batch.add((KVCommand<StructuredKey>) (KVCommand<?>) command));
        store.process(store.createBatch(batch));
    }

    Option<SliceObservedState> state(Artifact artifact) {
        return observed(artifact).map(SliceObservedValue::state);
    }

    Option<SliceObservedValue> observed(Artifact artifact) {
        return store.get(SliceObservedKey.sliceObservedKey(SELF, artifact))
                    .filter(SliceObservedValue.class::isInstance)
                    .map(SliceObservedValue.class::cast);
    }

    private void notifyChange(Artifact artifact, Option<SliceObservedValue> before, Option<SliceObservedValue> after) {
        if (before.equals(after)) {
            return;
        }

        after.fold(() -> mailbox.add(new NodeFsmInput.ObservedRemove(SELF, artifact)),
                   value -> mailbox.add(new NodeFsmInput.ObservedPut(SELF, artifact, value, tokens.self())));
    }

    /// Delivers the notification of the committed own row, as the node's KV subscription would.
    NodeHarness deliverCommitted(Artifact artifact) {
        observed(artifact).fold(() -> feed(new NodeFsmInput.ObservedRemove(SELF, artifact)),
                                value -> feed(new NodeFsmInput.ObservedPut(SELF, artifact, value, tokens.self())));

        return this;
    }

    // ------------------------------------------------------------------ the local runtime

    private void local(NodeFsmEffect.LocalOp op) {
        op.artifact().onPresent(artifact -> localRuns.add(new LocalRun(op.incarnation(), op.token(), op.step(), artifact)));
        op.artifact().onPresent(artifact -> effectOnStore(op.step(), artifact));

        if (op.token() == 0L) {
            return;
        }

        if (holdSteps.getOrDefault(op.step(), false)) {
            pendingLocal.put(op.token(), new Pending(op));
            return;
        }

        mailbox.add(new NodeFsmInput.LocalOpSettled(op.incarnation(),
                                                    op.artifact().or(ARTIFACT),
                                                    op.token(),
                                                    outcomeOf(op.step())));
    }

    private LocalOutcome outcomeOf(LocalStep step) {
        return Option.option(computed.get(step))
                     .map(java.util.function.Supplier::get)
                     .or(() -> script.getOrDefault(step, LocalOutcome.done()));
    }

    /// `step`'s outcome is computed by `outcome` each time it runs.
    NodeHarness scriptWith(LocalStep step, java.util.function.Supplier<LocalOutcome> outcome) {
        computed.put(step, outcome);

        return this;
    }

    private void effectOnStore(LocalStep step, Artifact artifact) {
        switch (step) {
            case LOAD -> slicesInStore.add(artifact);
            case UNLOAD, FORCED_CLEANUP, EVICT -> slicesInStore.remove(artifact);
            default -> {}
        }
    }

    /// Completes a held local step.
    NodeHarness settle(LocalStep step, LocalOutcome outcome) {
        var held = pendingLocal.values().stream().filter(p -> p.op().step() == step).findFirst().orElseThrow();

        pendingLocal.remove(held.op().token());

        return feed(new NodeFsmInput.LocalOpSettled(held.op().incarnation(), held.op().artifact().or(ARTIFACT), held.op().token(), outcome));
    }

    NodeHarness hold(LocalStep step) {
        holdSteps.put(step, true);

        return this;
    }

    NodeHarness script(LocalStep step, LocalOutcome outcome) {
        script.put(step, outcome);

        return this;
    }

    // ------------------------------------------------------------------ the scheduler

    /// Moves the clock and fires every timer that falls due, in due order.
    NodeHarness advance(long ms) {
        var target = wall + ms;

        while (true) {
            var next = timers.entrySet().stream().filter(entry -> entry.getValue() <= target).min(Map.Entry.comparingByValue());

            if (next.isEmpty()) {
                break;
            }

            wall = Math.max(wall, next.get().getValue());
            timers.remove(next.get().getKey());
            feed(new NodeFsmInput.TimerFired(next.get().getKey()));
        }

        wall = target;

        return this;
    }

    /// Fires the ACTIVATING check armed for the committed row at `version`.
    NodeHarness fireCheck(long version) {
        return feed(new NodeFsmInput.TimerFired(TimerId.timerId(TimerId.TimerKind.ACTIVATING_CHECK, ARTIFACT, model.incarnation(), version)));
    }

    NodeHarness fire(TimerId timer) {
        timers.remove(timer);

        return feed(new NodeFsmInput.TimerFired(timer));
    }

    List<TimerId> timersOf(TimerId.TimerKind kind) {
        return timers.keySet().stream().filter(timer -> timer.kind() == kind).toList();
    }

    // ------------------------------------------------------------------ scenario helpers

    NodeHarness establish() {
        return feed(new NodeFsmInput.QuorumEstablished(snapshot()));
    }

    RebuildSnapshot snapshot() {
        var observedRows = new HashMap<Artifact, SliceObservedValue>();

        desired.keySet().forEach(artifact -> observed(artifact).onPresent(value -> observedRows.put(artifact, value)));
        slicesInStore.forEach(artifact -> observed(artifact).onPresent(value -> observedRows.put(artifact, value)));
        rowArtifacts.forEach(artifact -> observed(artifact).onPresent(value -> observedRows.put(artifact, value)));

        return RebuildSnapshot.rebuildSnapshot()
                              .withDesired(desired)
                              .withTargets(targets)
                              .withObserved(observedRows)
                              .withLoaded(slicesInStore);
    }

    /// The leader's goal arrives (a LeaderTransaction on the real cluster; here only its notification matters).
    NodeHarness goal(Artifact artifact, SliceGoal goal, Generation generation) {
        var value = new SliceDesiredValue(goal, generation, wall);

        desired.put(artifact, value);

        return feed(new NodeFsmInput.DesiredPut(artifact, value));
    }

    NodeHarness permit(Artifact artifact) {
        targets.put(artifact.base(), artifact.version());

        return feed(new NodeFsmInput.TargetPut(artifact.base(), artifact.version()));
    }

    NodeHarness removeGoal(Artifact artifact) {
        desired.remove(artifact);

        return feed(new NodeFsmInput.DesiredRemove(artifact));
    }

    /// A row of `node` for `artifact` as the replica index sees it.
    NodeHarness peerRow(NodeId node, Artifact artifact, SliceObservedState state, long token, long knownToken) {
        var row = new SliceObservedValue(state,
                                         1L,
                                         G1,
                                         new ProcessIncarnation(BOOT_INCARNATION, token),
                                         Option.none(),
                                         false,
                                         0,
                                         List.of(),
                                         wall);

        return feed(new NodeFsmInput.ObservedPut(node, artifact, row, knownToken));
    }

    /// Writes a row chain straight into the real store, as an earlier process (or an earlier incarnation) left it: versions
    /// 1..`version`, each a LOADING row but the last, which carries `state`.
    NodeHarness seedRow(Artifact artifact, SliceObservedState state, long token, long version, Generation generation) {
        rowArtifacts.add(artifact);

        for (var v = 1L; v <= version; v++) {
            var row = new SliceObservedValue(v == version ? state : SliceObservedState.LOADING,
                                             v,
                                             generation,
                                             new ProcessIncarnation(BOOT_INCARNATION - 1L, token),
                                             Option.none(),
                                             false,
                                             0,
                                             List.of(),
                                             wall);

            applyBatch(List.of(new KVCommand.Put<AetherKey, SliceObservedValue>(SliceObservedKey.sliceObservedKey(SELF, artifact), row)));
        }

        return this;
    }

    // ------------------------------------------------------------------ reading what happened

    <T extends NodeFsmEffect> List<T> effectsOf(Class<T> type) {
        return effects.stream().filter(type::isInstance).map(type::cast).toList();
    }

    List<NodeFsmEffect.LocalOp> localOps() {
        return effectsOf(NodeFsmEffect.LocalOp.class);
    }

    List<LocalStep> localSteps() {
        return localOps().stream().map(NodeFsmEffect.LocalOp::step).toList();
    }

    /// Every observed Put the FSM submitted, in order.
    List<SliceObservedValue> observedPuts() {
        return effectsOf(NodeFsmEffect.Submit.class).stream()
                                                    .filter(submit -> submit.attempt() == 1 && !submit.isProbe())
                                                    .flatMap(submit -> submit.commands().stream())
                                                    .filter(KVCommand.Put.class::isInstance)
                                                    .map(command -> ((KVCommand.Put<?, ?>) command).value())
                                                    .filter(SliceObservedValue.class::isInstance)
                                                    .map(SliceObservedValue.class::cast)
                                                    .toList();
    }

    List<SliceObservedState> writtenStates() {
        return effectsOf(NodeFsmEffect.Submit.class).stream()
                                                    .filter(submit -> submit.attempt() == 1 && !submit.isProbe())
                                                    .flatMap(submit -> submit.commands().stream())
                                                    .filter(KVCommand.Put.class::isInstance)
                                                    .map(command -> ((KVCommand.Put<?, ?>) command).value())
                                                    .filter(SliceObservedValue.class::isInstance)
                                                    .map(value -> ((SliceObservedValue) value).state())
                                                    .toList();
    }

    /// The observed Put in a submission, if it carries one.
    static Option<SliceObservedValue> putOf(NodeFsmEffect.Submit submit) {
        return Option.option(submit.commands()
                                   .stream()
                                   .filter(KVCommand.Put.class::isInstance)
                                   .map(command -> ((KVCommand.Put<?, ?>) command).value())
                                   .filter(SliceObservedValue.class::isInstance)
                                   .map(SliceObservedValue.class::cast)
                                   .findFirst()
                                   .orElse(null));
    }

    /// Submissions that carry an observed Put of `state`, first attempts only.
    List<NodeFsmEffect.Submit> submitsOf(SliceObservedState state) {
        return effectsOf(NodeFsmEffect.Submit.class).stream()
                                                    .filter(submit -> submit.attempt() == 1 && !submit.isProbe())
                                                    .filter(submit -> putOf(submit).map(row -> row.state() == state).or(false))
                                                    .toList();
    }

    /// Submissions of `state` are lost; everything else is applied.
    NodeHarness loseWritesOf(SliceObservedState... states) {
        var lost = java.util.Set.of(states);

        writeScript = submitted -> putOf(submitted.submit()).map(row -> lost.contains(row.state()) ? WriteMode.LOST : WriteMode.APPLY)
                                                            .or(WriteMode.APPLY);

        return this;
    }

    /// A command a leg could contribute: only its identity matters to the FSM.
    static LegCommand leg(LegCommand.LegKind kind, Artifact tag) {
        return LegCommand.legCommand(kind, new KVCommand.Remove<AetherKey>(AetherKey.NodeRoutesKey.nodeRoutesKey(SELF, tag)));
    }

    NodeHarness clearEffects() {
        effects.clear();
        submitted.clear();
        localRuns.clear();

        return this;
    }

    InstanceRecord record(Artifact artifact) {
        return model.instance(artifact);
    }

    static Comparator<Artifact> byName() {
        return Comparator.comparing(Artifact::asString);
    }
}
