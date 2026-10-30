// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.metrics.ClusterDirectory;
import org.pragmatica.cluster.metrics.ClusterSyncMessage.ClusterSyncPong;
import org.pragmatica.cluster.replica.ReplicaFeedClient;
import org.pragmatica.cluster.replica.ReplicaFeedMessage.ReplicaSnapshotChunk;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// W-OBS W4 exit, on real nodes: 5 cores and 25 workers in two communities follow their governors, a plain member installs the leader's cluster directory
/// from its governor's pong alone (NT-O44 over a governor), a governor killed under write load costs its members neither readiness nor data, and a core
/// killed mid-snapshot costs a joining worker one restart on the other core.
///
/// One cluster, five phases, each asserting its own property (a start is half a minute; five clusters would be most of an hour of CI).
@PortBudget
class EmberReplicaRelayTest {
    private static final int CORES = 5;
    private static final int EAST = 13;
    private static final int WEST = 12;
    private static final int EXTRA_SLOTS = 32;
    private static final int SLOTS = 2 * CORES + EXTRA_SLOTS + 2;
    private static final int MGMT_OFFSET = 64;
    private static final int APP_OFFSET = 128;
    private static final EmberTestPorts.Block PORTS = new EmberTestPorts.Block(EmberTestPorts.POOL_FIRST,
                                                                                EmberTestPorts.POOL_LAST,
                                                                                EmberTestPorts.POOL_STEP,
                                                                                SLOTS,
                                                                                MGMT_OFFSET,
                                                                                APP_OFFSET);
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(180).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(90).seconds();
    private static final TimeSpan APPLY_BOUND = TimeSpan.timeSpan(10).seconds();
    private static final long BOUND_MS = 180_000L;
    private static final int LOAD_KEYS = 40;

    private EmberCluster cluster;
    private final List<String> workers = new ArrayList<>();
    private final List<String> cores = new ArrayList<>();
    private final Map<String, String> communityOf = new HashMap<>();

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            cluster.allNodes().forEach(node -> node.setInboundFaultFilter((_, _) -> true));
            cluster.stop().await(STOP_BOUND);
        }
    }

    @Test
    @Timeout(1800)
    void workersFollowTheirGovernors_aMemberHearsTheDirectoryFromItsGovernor_aGovernorAndACoreMayDie() {
        cluster = EmberTestPorts.startedCluster(PORTS,
                                                base -> started(emberCluster(CORES, base, base + MGMT_OFFSET, base + APP_OFFSET, "rel")),
                                                START_BOUND);
        await("a leader is elected", () -> cluster.currentLeader().isPresent());
        cluster.allNodes().forEach(node -> cores.add(node.self().id()));

        addWorkers("east", EAST);
        addWorkers("west", WEST);
        await("every worker is ready", () -> workers.stream().allMatch(this::ready));
        assertThat(workers.stream().map(id -> communityOf.get(id)).distinct()).as("two communities").hasSize(2);

        phaseOneEveryMemberFollowsItsGovernor();
        phaseTwoAMemberHearsTheDirectoryFromItsGovernorAlone();
        phaseThreeAGovernorDiesUnderLoad();
        phaseFourACoreDiesMidSnapshot();
    }

    private static EmberCluster started(EmberCluster created) {
        created.withAdditionalNodeSlots(EXTRA_SLOTS).unwrap();

        return created;
    }

    // ---- phase 1: the relay carries the steady state ----
    private void phaseOneEveryMemberFollowsItsGovernor() {
        await("each community announces a governor", () -> communities().stream().allMatch(community -> governorOf(community).isPresent()));
        await("every member of both communities follows its governor, none a core",
              () -> workers.stream().allMatch(id -> isGovernor(id) || sourceOf(id).equals(governorOf(communityOf.get(id)).map(NodeId::id))));
        assertThat(workers).allSatisfy(id -> assertThat(ready(id)).as(id).isTrue());
        assertThat(workers.stream().mapToLong(id -> client(id).counters().get("replica_silence_switch_total")).sum())
            .as("no member waited out a silent governor while joining: a governor is followed only over a link that is up")
            .isZero();
    }

    // ---- phase 2: NT-O44 over a governor ----
    /// A plain member drops every `ClusterSyncPong` that carries a directory but its governor's. With every directory dropped (the control) it holds the
    /// directory it had while the cores move on; with only its governor's allowed it catches up to the cores' directory. The window is kept short (well
    /// under the 10 s core-absence timeout): a member whose directory is stale gets a directory on every core pong, so dropping them also drops its
    /// core contact, and the core-absence fence drains it. A leader ping never reaches a member, so the update came through
    /// the governor's pong.
    private void phaseTwoAMemberHearsTheDirectoryFromItsGovernorAlone() {
        var member = workers.stream().filter(id -> !isGovernor(id)).findFirst().orElseThrow();
        var governor = governorOf(communityOf.get(member)).unwrap();
        var allowed = new AtomicReference<Predicate<NodeId>>(_ -> false);

        node(member).setInboundFaultFilter((sender, message) -> !(message instanceof ClusterSyncPong pong && pong.directory().isPresent()) || allowed.get().test(sender));
        var held = directory(member).unwrap().version();

        var extra = startWorker("east");

        await("a core's directory moves past the member's", () -> directory(anyFollowerCore()).filter(d -> d.version().isStrictlyAfter(held)).isPresent());
        sleep(3_000);
        assertThat(directory(member).unwrap().version()).as("control: with every directory-bearing pong dropped the member's directory does not move").isEqualTo(held);

        allowed.set(sender -> sender.equals(governor));
        await("the member catches up to the cores' directory through its governor's pong alone",
              () -> directory(member).map(d -> d.version()).equals(directory(anyFollowerCore()).map(d -> d.version())));
        assertThat(directory(member).unwrap().version().isStrictlyAfter(held)).isTrue();
        node(member).setInboundFaultFilter((_, _) -> true);
        register(extra);
    }

    // ---- phase 3: kill a governor under load ----
    private void phaseThreeAGovernorDiesUnderLoad() {
        var community = communities().getFirst();
        var killed = governorOf(community).unwrap().id();
        var members = workers.stream().filter(id -> communityOf.get(id).equals(community) && !id.equals(killed)).toList();
        var load = new Load();

        load.start();
        await("the load is flowing", () -> load.applied.get() >= 5);
        cluster.killNode(killed, false).await(STOP_BOUND);
        workers.remove(killed);

        var notReady = new AtomicInteger();
        var until = System.currentTimeMillis() + 30_000L;

        while (System.currentTimeMillis() < until) {
            alive(members).stream().filter(id -> !ready(id)).forEach(_ -> notReady.incrementAndGet());
            sleep(100);
        }

        assertThat(notReady.get()).as("a member of the killed governor's community was never seen not ready").isZero();
        await("no member follows the dead governor", () -> alive(members).stream().noneMatch(id -> sourceOf(id).equals(Option.some(killed))));
        await("the community announces another governor", () -> governorOf(community).filter(id -> !id.id().equals(killed)).isPresent());
        var successor = governorOf(community).unwrap().id();

        await("the members follow the new governor", () -> alive(members).stream().allMatch(id -> id.equals(successor) || sourceOf(id).equals(Option.some(successor))));
        load.stop();
        await("every surviving worker holds exactly what the leader holds for the load keys", () -> alive(workers).stream().allMatch(this::equalsTheLeader));
        assertThat(load.applied.get()).as("the load kept being applied").isGreaterThan(20);
    }

    // ---- phase 4: kill a core mid-snapshot ----
    /// A worker of a fresh community has no governor, so it joins from a core. Its snapshot chunks are dropped (the transfer is under way and stalled);
    /// the core it asked is killed; with the chunks flowing again the worker restarts on the other core and ends equal to the leader.
    private void phaseFourACoreDiesMidSnapshot() {
        var stalled = new AtomicBoolean(true);
        var joiner = addWorkers("north", 1).getFirst();

        node(joiner).setInboundFaultFilter((_, message) -> !(message instanceof ReplicaSnapshotChunk) || !stalled.get());
        await("the joiner has asked a core for its snapshot",
              () -> client(joiner).source().filter(source -> !workers.contains(source.id())).isPresent());
        var core = client(joiner).source().unwrap().id();

        assertThat(ready(joiner)).as("precondition: the snapshot is still under way").isFalse();
        cluster.killNode(core, false).await(STOP_BOUND);
        stalled.set(false);

        await("the joiner restarts on the other core and becomes ready", () -> ready(joiner));
        assertThat(client(joiner).source().unwrap().id()).isNotEqualTo(core);
        await("it equals the leader", () -> equalsTheLeader(joiner));
    }

    // ---- helpers ----
    private final class Load {
        final AtomicInteger applied = new AtomicInteger();
        private final AtomicBoolean running = new AtomicBoolean(true);
        private Thread thread;

        void start() {
            thread = Thread.ofPlatform().daemon().name("relay-load").unstarted(this::run);
            thread.start();
        }

        void stop() {
            running.set(false);
            try {
                thread.join(30_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private void run() {
            for (var i = 0; running.get(); i++) {
                var key = AetherKey.LogLevelKey.forLogger("relay.load." + (i % LOAD_KEYS));
                var value = AetherValue.LogLevelValue.logLevelValue(key.loggerName(), i % 2 == 0 ? "INFO" : "DEBUG");

                try {
                    leader().<Object> apply(List.of(new KVCommand.Put<AetherKey, AetherValue>(key, value))).await(APPLY_BOUND);
                    applied.incrementAndGet();
                } catch (RuntimeException e) {
                    // an election in flight: the next write retries
                }
                sleep(100);
            }
        }
    }

    private List<String> addWorkers(String source, int count) {
        var added = new ArrayList<String>();

        for (var i = 0; i < count; i++) {
            added.add(register(startWorker(source)));
        }

        return added;
    }

    private String startWorker(String source) {
        return cluster.addNode(Map.of(NodeInfo.LABEL_ROLE, "worker", NodeInfo.LABEL_SOURCE, source)).await(START_BOUND).unwrap().id();
    }

    private String register(String id) {
        workers.add(id);
        await("a directive for " + id, () -> directiveOf(id).isPresent());
        communityOf.put(id, directiveOf(id).unwrap().communityId());

        return id;
    }

    private Option<AetherValue.ActivationDirectiveValue> directiveOf(String id) {
        return leader().kvStore().getTyped(new AetherKey.ActivationDirectiveKey(new NodeId(id)), AetherValue.ActivationDirectiveValue.class);
    }

    private List<String> communities() {
        return communityOf.values().stream().distinct().sorted().toList();
    }

    private Option<NodeId> governorOf(String community) {
        return leader().kvStore()
                       .getTyped(AetherKey.GovernorAnnouncementKey.forCommunity(community), AetherValue.GovernorAnnouncementValue.class)
                       .filter(announcement -> !announcement.dissolved())
                       .map(AetherValue.GovernorAnnouncementValue::governorId);
    }

    private boolean isGovernor(String id) {
        return governorOf(communityOf.get(id)).filter(governor -> governor.id().equals(id)).isPresent();
    }

    private Option<String> sourceOf(String id) {
        return client(id).source().map(NodeId::id);
    }

    @SuppressWarnings("unchecked")
    private ReplicaFeedClient<?, ?> client(String id) {
        try {
            var method = node(id).getClass().getDeclaredMethod("replicaClient");

            method.setAccessible(true);

            return ((Option<ReplicaFeedClient<?, ?>>) method.invoke(node(id))).unwrap();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /// The nodes still in the cluster: a worker may leave on its own (it drains itself when it loses every core link), which is not what this phase measures.
    private List<String> alive(List<String> ids) {
        return ids.stream().filter(id -> cluster.getNode(id).isPresent()).toList();
    }

    private boolean ready(String id) {
        return cluster.getNode(id).map(AetherNode::isReady).or(false);
    }

    private AetherNode node(String id) {
        return cluster.getNode(id).unwrap();
    }

    private Option<ClusterDirectory> directory(String id) {
        return node(id).metricsCollector().heldDirectory();
    }

    private String anyFollowerCore() {
        var leaderId = cluster.currentLeader().or("");

        return cores.stream().filter(id -> !id.equals(leaderId) && cluster.getNode(id).isPresent()).findFirst().orElseThrow();
    }

    private AetherNode leader() {
        return cluster.currentLeader().flatMap(cluster::getNode).unwrap();
    }

    private boolean equalsTheLeader(String id) {
        return cluster.currentLeader().map(leader -> sameLoadKeys(id, leader)).or(false);
    }

    private boolean sameLoadKeys(String id, String reference) {
        return cluster.getNode(id)
                      .flatMap(node -> cluster.getNode(reference).map(other -> sameLoadKeys(node, other)))
                      .or(false);
    }

    private static boolean sameLoadKeys(AetherNode node, AetherNode reference) {
        for (var i = 0; i < LOAD_KEYS; i++) {
            var key = AetherKey.LogLevelKey.forLogger("relay.load." + i);

            if (!node.kvStore().get(key).equals(reference.kvStore().get(key))) {
                return false;
            }
        }

        return true;
    }

    private static void await(String what, BooleanSupplier condition) {
        var deadline = System.currentTimeMillis() + BOUND_MS;

        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out waiting: " + what);
            }
            sleep(250);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
