// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.wiring;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.node.machine.NodeFsm;
import org.pragmatica.aether.deployment.node.machine.NodeFsmConfig;
import org.pragmatica.aether.deployment.node.machine.NodeFsmEffect;
import org.pragmatica.aether.deployment.node.machine.NodeFsmInput;
import org.pragmatica.aether.deployment.node.machine.NodeFsmTimings;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceDesiredKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceObservedKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceDesiredValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification;
import org.pragmatica.consensus.net.BootTokens;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;

import static org.awaitility.Awaitility.await;
import static org.pragmatica.aether.deployment.wiring.WiringFixtures.*;

/// A node FSM interpreter assembled the way PR C will assemble it, from the production pieces: the real `NodeFsm`, `Submitter`,
/// `KvSnapshotter`, `FsmMailbox`/`FsmTimers`/`NodeFsmInterpreter` and the real `KVStore` applier (committing at increasing slots), with a
/// manual clock and a scriptable local runtime. The KV routes below do what `Fsm2Routes` will do (PR C): stamp the notification with
/// `(revision, replay)` and deliver it to the interpreter, own-node filter included.
final class NodeRig implements AutoCloseable {
    final ManualTimerService clock = new ManualTimerService();
    final MessageRouter.MutableRouter router = MessageRouter.mutable();
    final KVStore<AetherKey, AetherValue> store = WiringFixtures.store(router);
    final Applier applier = new Applier(store);
    final StubCluster cluster = WiringFixtures.over(applier);
    final BootTokens tokens = BootTokens.bootTokens(SELF_TOKEN);
    final LocalOpsDouble localOps = new LocalOpsDouble();
    final List<NodeFsmEffect> sunk = Collections.synchronizedList(new ArrayList<>());
    final List<String> stepped = Collections.synchronizedList(new ArrayList<>());
    final Set<String> stepThreads = Collections.synchronizedSet(new java.util.HashSet<>());
    final AtomicInteger selfReady = new AtomicInteger();
    final AtomicInteger snapshots = new AtomicInteger();
    final NodeFsmTimings timings;
    final NodeFsmInterpreter interpreter;
    private final AtomicInteger barrier = new AtomicInteger();
    volatile long wall = 1_760_000_100_000L;

    NodeRig() {
        this(NodeFsmTimings.nodeFsmTimings());
    }

    NodeRig(NodeFsmTimings timings) {
        this(timings, true);
    }

    /// `started` false leaves the consumer unstarted (a node closed before it ran).
    NodeRig(boolean started) {
        this(NodeFsmTimings.nodeFsmTimings(), started);
    }

    NodeRig(NodeFsmTimings timings, boolean started) {
        this.timings = timings;
        var config = NodeFsmConfig.nodeFsmConfig(SELF, PROCESS).withTimings(timings);
        var fsm = NodeFsm.nodeFsm(config, () -> wall);
        var submitter = Submitter.submitter(() -> cluster, Submitter.CommittedReader.committedReader(store));

        interpreter = NodeFsmInterpreter.nodeFsmInterpreter(new NodeFsmInterpreter.Parts(fsm,
                                                                                         submitter,
                                                                                         KvSnapshotter.kvSnapshotter(store),
                                                                                         () -> {
                                                                                             snapshots.incrementAndGet();
                                                                                             return Set.of();
                                                                                         },
                                                                                         tokens::tokenOf,
                                                                                         localOps,
                                                                                         clock,
                                                                                         sunk::add,
                                                                                         selfReady::incrementAndGet,
                                                                                         (_, input, _) -> {
                                                                                             stepThreads.add(Thread.currentThread().getName());
                                                                                             stepped.add(input);
                                                                                         }));
        routes();
        applier.commitLeader(LEADER);

        if (started) {
            interpreter.start();
        }
    }

    private void routes() {
        router.addRoute(KVStoreNotification.ValuePut.class, this::onPut);
        router.addRoute(KVStoreNotification.ValueRemove.class, this::onRemove);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void onPut(KVStoreNotification.ValuePut put) {
        var key = put.cause().key();
        var value = put.cause().value();

        input(key, value).onPresent(this::kv);
    }

    @SuppressWarnings({"rawtypes"})
    private void onRemove(KVStoreNotification.ValueRemove remove) {
        removal(remove.cause().key()).onPresent(this::kv);
    }

    private void kv(NodeFsmInput input) {
        interpreter.deliverKv(input, store.notificationRevision(), store.isReplaying());
    }

    private Option<NodeFsmInput> input(Object key, Object value) {
        return switch (key) {
            case SliceDesiredKey k when k.nodeId().equals(SELF) && value instanceof SliceDesiredValue v -> Option.some(new NodeFsmInput.DesiredPut(k.artifact(), v));
            case SliceObservedKey k when value instanceof SliceObservedValue v ->
                Option.some(new NodeFsmInput.ObservedPut(k.nodeId(), k.artifact(), v, tokens.tokenOf(k.nodeId())));
            case SliceTargetKey k when value instanceof SliceTargetValue v -> Option.some(new NodeFsmInput.TargetPut(k.artifactBase(), v.currentVersion()));
            default -> Option.none();
        };
    }

    private Option<NodeFsmInput> removal(Object key) {
        return switch (key) {
            case SliceDesiredKey k when k.nodeId().equals(SELF) -> Option.some(new NodeFsmInput.DesiredRemove(k.artifact()));
            case SliceObservedKey k -> Option.some(new NodeFsmInput.ObservedRemove(k.nodeId(), k.artifact()));
            case SliceTargetKey k -> Option.some(new NodeFsmInput.TargetRemove(k.artifactBase()));
            default -> Option.none();
        };
    }

    // -- scenario steps ------------------------------------------------------------------------------------------------
    NodeRig permit(Artifact artifact) {
        applier.put(new SliceTargetKey(artifact.base()), SliceTargetValue.sliceTargetValue(artifact.version(), 1, 1));

        return this;
    }

    /// The leader's goal for `artifact` on this node: a `LeaderAuthorized` value, so it is committed by a `LeaderTransaction`.
    NodeRig goal(Artifact artifact, SliceGoal goal) {
        applier.commit(List.of(WiringFixtures.put(store, "goal-" + applier.slot(), LEADER, artifact, goal)));

        return this;
    }

    NodeRig enter() {
        interpreter.requestEntry(false);

        return settle();
    }

    /// Waits until the consumer has handled everything enqueued before this call.
    NodeRig settle() {
        var mark = barrier.incrementAndGet();
        var before = countOf("ConfigChanged");

        interpreter.deliver(new NodeFsmInput.ConfigChanged("barrier-" + mark));
        await().atMost(Duration.ofSeconds(10)).until(() -> countOf("ConfigChanged") > before);

        return this;
    }

    long countOf(String input) {
        synchronized (stepped) {
            return stepped.stream().filter(input::equals).count();
        }
    }

    /// The rounds counted at submission (`submissions_total{fsm=node, kind, outcome=submitted}`), one per Submit that left for the engine.
    long submitted(String kind) {
        synchronized (sunk) {
            return sunk.stream()
                       .filter(NodeFsmEffect.Metric.class::isInstance)
                       .map(NodeFsmEffect.Metric.class::cast)
                       .filter(m -> m.name().equals("aether_deployment_fsm_submissions_total"))
                       .filter(m -> kind.equals(m.labels().get("kind")) && "submitted".equals(m.labels().get("outcome")))
                       .mapToLong(NodeFsmEffect.Metric::delta)
                       .sum();
        }
    }

    long metric(String input, String result) {
        synchronized (sunk) {
            return sunk.stream()
                       .filter(NodeFsmEffect.Metric.class::isInstance)
                       .map(NodeFsmEffect.Metric.class::cast)
                       .filter(m -> m.name().equals("aether_deployment_fsm_inputs_total"))
                       .filter(m -> input.equals(m.labels().get("input")) && result.equals(m.labels().get("result")))
                       .mapToLong(NodeFsmEffect.Metric::delta)
                       .sum();
        }
    }

    Option<SliceObservedState> stateOf(Artifact artifact) {
        return Option.option(store.get(SliceObservedKey.sliceObservedKey(SELF, artifact)).or((AetherValue) null))
                     .filter(SliceObservedValue.class::isInstance)
                     .map(SliceObservedValue.class::cast)
                     .map(SliceObservedValue::state);
    }

    void awaitState(Artifact artifact, SliceObservedState state) {
        await().atMost(Duration.ofSeconds(10)).until(() -> stateOf(artifact).map(state::equals).or(false));
    }

    void awaitThat(BooleanSupplier condition) {
        await().atMost(Duration.ofSeconds(10)).until(condition::getAsBoolean);
    }

    @Override
    public void close() {
        interpreter.close();
    }
}
