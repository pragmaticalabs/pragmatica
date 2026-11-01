// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.assembly;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.pragmatica.aether.node.assembly.ReplicaClientAssembly.DirectoryInstall;
import org.pragmatica.aether.node.assembly.ReplicaClientAssembly.DirectoryInstaller;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.cluster.metrics.ClusterDirectory;
import org.pragmatica.cluster.metrics.ClusterDirectory.DhtReplication;
import org.pragmatica.cluster.metrics.DirectoryVersion;
import org.pragmatica.cluster.node.rabia.RabiaNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.rabia.VoterConfiguration;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;

/// The DARK client's glue: the directory installer (readiness f), the one publisher of what readiness drives (I-W6) and the uplink order.
/// Each test names the mutation that turns it red.
class ReplicaClientAssemblyTest {
    private static final DhtReplication DHT = new DhtReplication(3, 2, 3, 2, 7, 0, 0, 0, true);

    private final AtomicReference<Option<ClusterDirectory>> held = new AtomicReference<>(Option.none());
    private final List<List<NodeInfo>> peersInstalled = new ArrayList<>();
    private final List<List<NodeInfo>> endpointsInstalled = new ArrayList<>();
    private final AtomicInteger dhtInstalled = new AtomicInteger();
    private final List<VoterConfiguration> votersInstalled = new ArrayList<>();
    private final AtomicReference<Result<Unit>> peersResult = new AtomicReference<>(Result.success(Unit.unit()));
    private final DirectoryInstaller installer = DirectoryInstaller.directoryInstaller(held::get,
                                                                                      new DirectoryInstall(peers -> record(peers),
                                                                                                           endpointsInstalled::add,
                                                                                                           _ -> dhtInstalled.incrementAndGet(),
                                                                                                           votersInstalled::add));

    private Result<Unit> record(List<NodeInfo> peers) {
        peersInstalled.add(peers);

        return peersResult.get();
    }

    private static ClusterDirectory directory(long incarnation, long term, long counter, long generation) {
        return ClusterDirectory.clusterDirectory(DirectoryVersion.directoryVersion(incarnation, term, counter, generation), List.of(), DHT, Option.none());
    }

    @Test
    void tick_installsANewerDirectoryIntoTheThreeConsumers_once() {
        held.set(Option.some(directory(1, 1, 1, 1)));

        installer.tick();
        installer.tick();

        assertThat(peersInstalled).hasSize(1);
        assertThat(endpointsInstalled).hasSize(1);
        assertThat(dhtInstalled.get()).isEqualTo(1);
        assertThat(installer.isInstalled()).isTrue();
    }

    private static VoterConfiguration voters(long epoch) {
        return VoterConfiguration.voterConfiguration(epoch, List.of(NodeId.nodeId("core-1").unwrap(), NodeId.nodeId("core-2").unwrap())).unwrap();
    }

    /// #2543. The leader's electorate reaches the engine door with the rest of the directory. Mutation: drop the `voters` line of `installRest`.
    @Test
    void tick_installsTheDirectoryVoters_whenItCarriesThem() {
        var carried = voters(3);

        held.set(Option.some(new ClusterDirectory(1L, 1L, 1L, 1L, List.of(), DHT, Option.some(carried))));
        installer.tick();

        assertThat(votersInstalled).containsExactly(carried);
    }

    /// A governor's relay is installed by the same installer, so a strictly newer DIRECTORY can carry an OLDER electorate. The installer
    /// filters nothing by voter epoch: it hands every carried electorate to the engine door, whose own epoch guard (RabiaEnginePassiveVotersTest
    /// `_olderConfigInsideANewerRelayedDirectory_ignored`) decides. Mutation: compare voter epochs here instead (the guard would then live in two places).
    @Test
    void tick_aNewerDirectoryCarryingAnOlderElectorate_stillReachesTheEngineDoor() {
        held.set(Option.some(new ClusterDirectory(1L, 1L, 1L, 2L, List.of(), DHT, Option.some(voters(3)))));
        installer.tick();
        held.set(Option.some(new ClusterDirectory(1L, 1L, 1L, 3L, List.of(), DHT, Option.some(voters(1)))));
        installer.tick();

        assertThat(votersInstalled).extracting(VoterConfiguration::epoch).containsExactly(3L, 1L);
    }

    /// A directory from a leader with no installed electorate installs none: the worker keeps the one it holds.
    @Test
    void tick_aDirectoryWithoutVoters_installsNoVoters() {
        held.set(Option.some(directory(1, 1, 1, 1)));
        installer.tick();

        assertThat(votersInstalled).isEmpty();
        assertThat(installer.isInstalled()).isTrue();
    }

    /// Mutation: install on `!equals` instead of `isStrictlyAfter` (an older directory would replace a newer one).
    @Test
    void tick_neverInstallsAnOlderOrEqualDirectory() {
        held.set(Option.some(directory(1, 2, 5, 3)));
        installer.tick();
        held.set(Option.some(directory(1, 1, 9, 9)));
        installer.tick();

        assertThat(peersInstalled).as("the older epoch's higher generation is refused").hasSize(1);

        held.set(Option.some(directory(1, 3, 1, 1)));
        installer.tick();

        assertThat(peersInstalled).as("a newer epoch with a lower generation installs").hasSize(2);
    }

    /// Mutation: drop the `generation > 0` test in `isInstalled`.
    @Test
    void isInstalled_isFalse_untilADirectoryWithAGenerationIsIn() {
        assertThat(installer.isInstalled()).isFalse();

        held.set(Option.some(directory(1, 1, 1, 0)));
        installer.tick();

        assertThat(installer.isInstalled()).as("generation 0 is the empty directory").isFalse();
    }

    /// Mutation: install the endpoints and DHT replication before the peers succeed (the order of `installNow`).
    @Test
    void tick_aFailedPeerInstall_installsNothingElse_andIsRetried() {
        peersResult.set(Causes.cause("no scope").result());
        held.set(Option.some(directory(1, 1, 1, 1)));
        installer.tick();

        assertThat(endpointsInstalled).isEmpty();
        assertThat(dhtInstalled.get()).isZero();
        assertThat(installer.isInstalled()).isFalse();

        peersResult.set(Result.success(Unit.unit()));
        installer.tick();

        assertThat(installer.isInstalled()).isTrue();
        assertThat(peersInstalled).as("the failed attempt was retried").hasSize(2);
    }

    /// I-W6. Mutation: `setPassiveClientReady` only on the rising edge (today's latch), or run `workerReady` on the falling edge.
    @Test
    @SuppressWarnings("unchecked")
    void publishReadiness_setsAndClearsTheEngineFlag_andRunsWorkerReadyOnlyWhenReady() {
        RabiaNode<KVCommand<AetherKey>> cluster = Mockito.mock(RabiaNode.class);
        var ran = new AtomicInteger();

        ReplicaClientAssembly.publishReadiness(cluster, ran::incrementAndGet, true);
        ReplicaClientAssembly.publishReadiness(cluster, ran::incrementAndGet, false);

        var order = Mockito.inOrder(cluster);

        order.verify(cluster).setPassiveClientReady(true);
        order.verify(cluster).setPassiveClientReady(false);
        assertThat(ran.get()).isEqualTo(1);
    }

    /// Mutation: drop the `worker` test in `replicaClient` (a core would build a client).
    @Test
    void replicaClient_onANodeThatIsNotAWorker_buildsNothing() {
        assertThat(ReplicaClientAssembly.replicaClient(false, null).isPresent()).isFalse();
    }

    /// On a worker: the freshness gate is replaced by the readiness (not ready yet), the four messages a worker receives are routed and the two
    /// ticks (client, directory install) are scheduled, and the serve gate is closed while the node is not a governor. Mutations: drop
    /// `workerProjectionFreshRef.set(readiness::ready)`; drop a route; drop either `defer.accept`; build the gate over `() -> true`.
    @Test
    @SuppressWarnings("unchecked")
    void replicaClient_onAWorker_replacesTheFreshnessGate_routesFourMessages_andSchedulesTwoTicks() {
        RabiaNode<KVCommand<AetherKey>> cluster = Mockito.mock(RabiaNode.class);
        var network = Mockito.mock(org.pragmatica.consensus.net.ClusterNetwork.class);

        Mockito.when(cluster.network()).thenReturn(network);

        java.util.function.BooleanSupplier original = () -> true;
        var freshness = new AtomicReference<java.util.function.BooleanSupplier>(original);
        var routes = new ArrayList<org.pragmatica.messaging.MessageRouter.Entry<?>>();
        var deferred = new AtomicInteger();
        var inputs = new ReplicaClientAssembly.Inputs(NodeId.nodeId("worker-9").unwrap(),
                                                      new org.pragmatica.cluster.state.kvstore.KVStore<AetherKey, org.pragmatica.aether.slice.kvstore.AetherValue>(org.pragmatica.messaging.MessageRouter.mutable(),
                                                                                                                                                        null,
                                                                                                                                                        null),
                                                      cluster,
                                                      Option::none,
                                                      Set::of,
                                                      new org.pragmatica.cluster.replica.ReplicaFeedClient.GovernorArm(() -> false, Option::none),
                                                      new DirectoryInstall(_ -> Result.success(Unit.unit()), _ -> {}, _ -> {}, _ -> {}),
                                                      () -> {},
                                                      freshness,
                                                      routes,
                                                      _ -> deferred.incrementAndGet());

        var started = ReplicaClientAssembly.replicaClient(true, inputs);

        assertThat(started.isPresent()).isTrue();
        assertThat(started.unwrap().serveGate().mayServeMembers()).as("not a governor: members are not served").isFalse();

        assertThat(freshness.get()).isNotSameAs(original);
        assertThat(freshness.get().getAsBoolean()).as("a fresh replica is not ready").isFalse();
        assertThat(routes).hasSize(4);
        assertThat(deferred.get()).isEqualTo(2);
    }

    /// Readiness (d), against a real store. Mutation: `assignedWorker` always true, or always false.
    @Test
    void assignedWorker_isTrueOnlyForAWorkerDirectiveOfThisNode() {
        var self = NodeId.nodeId("worker-9").unwrap();
        var store = new org.pragmatica.cluster.state.kvstore.KVStore<AetherKey, org.pragmatica.aether.slice.kvstore.AetherValue>(org.pragmatica.messaging.MessageRouter.mutable(), null, null);

        assertThat(ReplicaClientAssembly.assignedWorker(store, self)).as("no directive").isFalse();

        store.applyEffects(directive(self, 0, org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue.core()));
        assertThat(ReplicaClientAssembly.assignedWorker(store, self)).as("CORE directive").isFalse();

        store.applyEffects(directive(self, 1, org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue.worker("c-0", "")));
        assertThat(ReplicaClientAssembly.assignedWorker(store, self)).as("WORKER directive").isTrue();
    }

    private static org.pragmatica.cluster.state.kvstore.ReplicaEntry directive(NodeId self, long slot, org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue value) {
        return new org.pragmatica.cluster.state.kvstore.ReplicaEntry(org.pragmatica.cluster.state.kvstore.IncarnationTag.NONE,
                                                                     slot,
                                                                     slot,
                                                                     "b" + slot,
                                                                     List.of(new KVCommand.Put<>(new AetherKey.ActivationDirectiveKey(self), value)));
    }

    /// Mutation: take the uplinks unsorted (set order is not stable across runs).
    @Test
    void uplinks_areTwoCores_inAFixedOrder() {
        var self = NodeId.nodeId("worker-7").unwrap();
        var cores = Set.of(NodeId.nodeId("core-1").unwrap(),
                           NodeId.nodeId("core-2").unwrap(),
                           NodeId.nodeId("core-3").unwrap(),
                           NodeId.nodeId("core-4").unwrap(),
                           NodeId.nodeId("core-5").unwrap());
        var uplinks = ReplicaClientAssembly.uplinks(self, cores);

        assertThat(uplinks).hasSize(2).isSorted().isSubsetOf(cores);
        assertThat(ReplicaClientAssembly.uplinks(self, cores)).isEqualTo(uplinks);
    }

    private static final org.pragmatica.cluster.state.kvstore.IncarnationTag TAG = org.pragmatica.cluster.state.kvstore.IncarnationTag.incarnationTag(1, "A");

    /// A replica that is current against its source (readiness `servable`), or one that never heard a heartbeat.
    private static org.pragmatica.cluster.replica.ReplicaReadiness readiness(boolean current) {
        var position = org.pragmatica.cluster.replica.ReplicaPosition.replicaPosition(TAG,
                                                                                       10,
                                                                                       List.of(new org.pragmatica.cluster.state.kvstore.AnchorPair(9, "x")));
        var readiness = org.pragmatica.cluster.replica.ReplicaReadiness.replicaReadiness(System::nanoTime,
                                                                                         () -> position,
                                                                                         () -> true,
                                                                                         () -> true,
                                                                                         _ -> {},
                                                                                         org.pragmatica.cluster.replica.ReplicaClientLimits.DEFAULT);

        if (current) {
            readiness.caughtUp(true);
            readiness.onHeartbeat(TAG, 10, true);
        }

        return readiness;
    }

    /// A client that has chosen a core uplink (`followsCore` true), or one that has chosen nothing.
    private static org.pragmatica.cluster.replica.ReplicaFeedClient<AetherKey, org.pragmatica.aether.slice.kvstore.AetherValue> client(boolean followingACore) {
        var store = new org.pragmatica.cluster.state.kvstore.KVStore<AetherKey, org.pragmatica.aether.slice.kvstore.AetherValue>(org.pragmatica.messaging.MessageRouter.mutable(),
                                                                                                                                  null,
                                                                                                                                  null);
        var limits = org.pragmatica.cluster.replica.ReplicaClientLimits.DEFAULT;
        var core = NodeId.nodeId("core-1").unwrap();
        var client = org.pragmatica.cluster.replica.ReplicaFeedClient.replicaFeedClient(NodeId.nodeId("worker-1").unwrap(),
                                                                                         org.pragmatica.cluster.replica.ReplicaApplier.replicaApplier(store, limits.bufferBytes()),
                                                                                         readiness(true),
                                                                                         (_, _) -> {},
                                                                                         () -> followingACore
                                                                                               ? List.of(core)
                                                                                               : List.<NodeId> of(),
                                                                                         Runnable::run,
                                                                                         System::nanoTime,
                                                                                         limits,
                                                                                         () -> {},
                                                                                         new org.pragmatica.cluster.replica.ReplicaFeedClient.GovernorArm(() -> false, Option::none));

        client.tick();

        return client;
    }

    /// I-W5 wiring: each of the three terms of the serve gate comes from its own object; with the other two held true, one false closes the gate.
    /// Mutations: replace the governor term, the `followsCore` term or the `servable` term in `serveGate` by `() -> true`.
    @Test
    void serveGate_needsEachOfItsThreeTerms_wiredFromTheirOwnObjects() {
        var governor = new java.util.concurrent.atomic.AtomicBoolean(true);
        var arm = new org.pragmatica.cluster.replica.ReplicaFeedClient.GovernorArm(governor::get, Option::none);
        var following = client(true);
        var idle = client(false);

        assertThat(ReplicaClientAssembly.serveGate(arm, following, readiness(true)).mayServeMembers()).as("control: all three hold").isTrue();
        assertThat(ReplicaClientAssembly.serveGate(arm, idle, readiness(true)).mayServeMembers()).as("its upstream is not a core").isFalse();
        assertThat(ReplicaClientAssembly.serveGate(arm, following, readiness(false)).mayServeMembers()).as("its replica is not current").isFalse();
        governor.set(false);
        assertThat(ReplicaClientAssembly.serveGate(arm, following, readiness(true)).mayServeMembers()).as("it is not the governor").isFalse();
    }
}
