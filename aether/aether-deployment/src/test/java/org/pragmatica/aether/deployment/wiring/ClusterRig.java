// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.wiring;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.pragmatica.aether.config.TimeoutsConfig.ClusterTimeouts;
import org.pragmatica.aether.config.WorkerConfig.SwimSettings;
import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager.DeploymentAtomicity;
import org.pragmatica.aether.deployment.cluster.fsm2.AbsenceBounds;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterEffect;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterFsm;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterInput;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterState;
import org.pragmatica.aether.deployment.cluster.fsm2.Seams;
import org.pragmatica.aether.deployment.cluster.fsm2.Settings;
import org.pragmatica.aether.deployment.membership.MembershipConfig;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.swim.SwimConfig;

import static org.awaitility.Awaitility.await;
import static org.pragmatica.aether.deployment.wiring.WiringFixtures.*;

/// A cluster FSM interpreter assembled the way PR C will assemble it, from the production pieces: the real `ClusterFsm`, `Submitter`,
/// `KvSnapshotter`, `FsmMailbox`/`FsmTimers`/`ClusterFsmInterpreter` and the real `KVStore` applier (committing at increasing slots), with a manual
/// clock. The KV routes below do what `Fsm2Routes` will do (PR C): stamp each notification with `(revision, replay)` and deliver it.
final class ClusterRig implements AutoCloseable {
    static final NodeId CORE_2 = NodeId.nodeId("core-2").unwrap();
    static final NodeId CORE_3 = NodeId.nodeId("core-3").unwrap();
    static final long START_WALL = 1_760_000_000_000L;
    static final long START_MONO = 5_000_000_000L;

    final ManualTimerService clock = new ManualTimerService();
    final MessageRouter.MutableRouter router = MessageRouter.mutable();
    final KVStore<AetherKey, AetherValue> store = WiringFixtures.store(router);
    final Applier applier = new Applier(store);
    final StubCluster cluster = WiringFixtures.over(applier);
    final List<ClusterEffect> sunk = Collections.synchronizedList(new ArrayList<>());
    final List<String> stepped = Collections.synchronizedList(new ArrayList<>());
    final Set<String> stepThreads = Collections.synchronizedSet(new java.util.HashSet<>());
    final AtomicInteger snapshots = new AtomicInteger();
    final ClusterFsmInterpreter interpreter;
    private final AtomicInteger barrier = new AtomicInteger();
    private volatile java.util.concurrent.CountDownLatch gate = new java.util.concurrent.CountDownLatch(0);
    private volatile String gatedInput = "";
    /// The committed leader changes without the FSM hearing of it yet: the window the pre-submit self-check exists for.
    volatile boolean muteLeaderNotifications;
    /// The seams the next `Rebuild` reads; the default is a three-core roster with no `coreView`.
    volatile Seams supplied;
    /// The seams the model held at the end of its last `Rebuild` step.
    volatile Seams atRebuild;

    ClusterRig() {
        var settings = Settings.settings(SELF, DeploymentAtomicity.ALL_OR_NOTHING, Set.of(), bounds());
        var fsm = ClusterFsm.clusterFsm(settings, () -> START_WALL + clock.nowMs, () -> START_MONO + clock.nowMs * 1_000_000L);
        var submitter = Submitter.submitter(() -> cluster, Submitter.CommittedReader.committedReader(store));

        interpreter = ClusterFsmInterpreter.clusterFsmInterpreter(new ClusterFsmInterpreter.Parts(fsm,
                                                                                                  submitter,
                                                                                                  KvSnapshotter.kvSnapshotter(store),
                                                                                                  this::seams,
                                                                                                  Map::of,
                                                                                                  clock,
                                                                                                  sunk::add,
                                                                                                  this::stepped));
        router.addRoute(KVStoreNotification.ValuePut.class, this::onPut);
        router.addRoute(KVStoreNotification.ValueRemove.class, this::onRemove);
        applier.commitLeader(LEADER);
        interpreter.start();
    }

    static AbsenceBounds bounds() {
        var swim = SwimConfig.DEFAULT;

        return AbsenceBounds.derive(swim.suspectTimeout(),
                                    SwimSettings.swimSettings().suspectTimeout(),
                                    swim.lhmMaxScore(),
                                    SwimConfig.MAX_CLUSTER_SIZE_WINDOW_MULTIPLIER,
                                    MembershipConfig.membershipConfig().splitTimeout(),
                                    TimeSpan.timeSpan(30).seconds(),
                                    ClusterTimeouts.clusterTimeouts().communityAbsence(),
                                    TimeSpan.timeSpan(5).seconds().plus(swim.period()))
                            .unwrap();
    }

    private Seams seams() {
        snapshots.incrementAndGet();

        return supplied != null
               ? supplied
               : Seams.UNWIRED.withCountedCores(Set.of(SELF, CORE_2, CORE_3)).withReady(Set.of(SELF, CORE_2, CORE_3));
    }

    private void stepped(String fsm, String input, long nanos) {
        stepThreads.add(Thread.currentThread().getName());
        stepped.add(input);
        if (input.equals("Rebuild") && interpreter.state() instanceof ClusterState.Active active) {
            atRebuild = active.model().seams();
        }

        if (input.equals(gatedInput)) {
            await().atMost(Duration.ofSeconds(10)).until(() -> gate.getCount() == 0);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void onPut(KVStoreNotification.ValuePut put) {
        var key = put.cause().key();
        var value = put.cause().value();

        if (key instanceof AetherKey aetherKey && value instanceof AetherValue aetherValue) {
            kv(new ClusterInput.KvPut(aetherKey, aetherValue));
        } else if (key instanceof LeaderKey && value instanceof LeaderValue leader && !muteLeaderNotifications) {
            kv(new ClusterInput.LeaderObserved(leader));
        }
    }

    @SuppressWarnings({"rawtypes"})
    private void onRemove(KVStoreNotification.ValueRemove remove) {
        if (remove.cause().key() instanceof AetherKey key) {
            kv(new ClusterInput.KvRemove(key));
        }
    }

    private void kv(ClusterInput input) {
        interpreter.deliverKv(input, store.notificationRevision(), store.isReplaying());
    }

    // -- scenario steps ------------------------------------------------------------------------------------------------
    /// Holds the consumer inside the observer of its next `input` step until [#release()].
    void holdConsumerAt(String input) {
        gate = new java.util.concurrent.CountDownLatch(1);
        gatedInput = input;
    }

    void release() {
        gatedInput = "";
        gate.countDown();
    }

    ClusterRig activate() {
        interpreter.activate();

        return settle();
    }

    /// A target of `instances` for `artifact`: the leader places goals for it.
    ClusterRig target(Artifact artifact, int instances) {
        applier.put(new SliceTargetKey(artifact.base()), SliceTargetValue.sliceTargetValue(artifact.version(), instances, 1));

        return this;
    }

    /// A committed DEPARTED record for `node` naming `process`, written the way the leader writes it: one `LeaderTransaction`.
    ClusterRig departed(NodeId node, AetherValue.ProcessIncarnation process) {
        var key = AetherKey.NodeDepartureKey.nodeDepartureKey(node);
        var value = new AetherValue.NodeDepartureValue(AetherValue.DepartureState.DEPARTED,
                                                       1L,
                                                       process,
                                                       Option.some(AetherValue.DepartureCause.NODE_REMOVED),
                                                       START_WALL);

        applier.commit(List.of(new KVCommand.LeaderTransaction<>(key,
                                                                                                      "departed-" + node.id(),
                                                                                                      LEADER,
                                                                                                      List.of(),
                                                                                                      List.of(new KVCommand.Mutation<>(key,
                                                                                                                                                                              Option.none(),
                                                                                                                                                                              Option.some(value))))));

        return this;
    }

    ClusterRig settle() {
        var mark = barrier.incrementAndGet();
        var before = countOf("SelfShutdown");

        interpreter.deliver(new ClusterInput.SelfShutdown(NodeId.nodeId("barrier-" + mark).unwrap()));
        await().atMost(Duration.ofSeconds(10)).until(() -> countOf("SelfShutdown") > before);

        return this;
    }

    long countOf(String input) {
        synchronized (stepped) {
            return stepped.stream().filter(input::equals).count();
        }
    }

    long metric(String input, String result) {
        synchronized (sunk) {
            return sunk.stream()
                       .filter(ClusterEffect.Metric.class::isInstance)
                       .map(ClusterEffect.Metric.class::cast)
                       .filter(m -> m.name().equals("aether_deployment_fsm_inputs_total"))
                       .filter(m -> input.equals(m.tags().get("input")) && result.equals(m.tags().get("result")))
                       .mapToLong(ClusterEffect.Metric::delta)
                       .sum();
        }
    }

    long routed(Class<?> event) {
        synchronized (sunk) {
            return sunk.stream().filter(ClusterEffect.Route.class::isInstance).map(ClusterEffect.Route.class::cast).filter(r -> event.isInstance(r.event())).count();
        }
    }

    boolean isActive() {
        return interpreter.state() instanceof ClusterState.Active;
    }

    void awaitThat(BooleanSupplier condition) {
        await().atMost(Duration.ofSeconds(10)).until(condition::getAsBoolean);
    }

    @Override
    public void close() {
        interpreter.close();
    }
}
