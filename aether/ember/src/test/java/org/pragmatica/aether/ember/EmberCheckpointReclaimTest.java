// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.node.StorageFactory;
import org.pragmatica.aether.node.StreamEntityLogSubstrate;
import org.pragmatica.aether.resource.entity.EntityLogSubstrate;
import org.pragmatica.aether.slice.kvstore.AetherKey.EntityCheckpointKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.EntityFoldCheckpointValue;
import org.pragmatica.aether.stream.EvictionListener;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.segment.SegmentIndex;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.dht.DHTMessage;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Retry;
import org.pragmatica.lang.utils.Retry.BackoffStrategy;
import org.pragmatica.storage.BlockId;
import org.pragmatica.storage.StorageInstance;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;
import static org.pragmatica.aether.node.StreamEntityLogSubstrate.CheckpointReclaim.checkpointReclaim;

/// NT-63 (streams PR-3c, spec 6.4 item 1): checkpoint reclaim on the DHT tier, not a refcount. Three nodes on the production node
/// construction, the DHT at replication 3, write quorum 2 (not `FULL`, so anti-entropy runs). Each writer is a
/// [StreamEntityLogSubstrate] over its node's own `streams` storage instance, KV store and consensus applier.
///
/// Node A writes block `B0`; node B, the successor, writes `B1` and deletes `B0` while the third replica C is cut off (alive, its DHT
/// engine intact, its channels open). `B0` must leave the DHT engines B's remove reached, C must still hold its stale copy before the
/// anti-entropy round, and after C reconnects and anti-entropy has run no engine holds a live `B0` and neither B's nor C's storage reads it
/// (no resurrection). A's own memory and disk copy is node-private, which B's delete cannot reach (the measured leak, L-12): it is printed,
/// not asserted away. The identity clause: byte-identical snapshots in two partitions, with and without a lineage, are different blocks.
///
/// Arm b: C stays cut off until the survivors declare it Dead, then returns and becomes a member again, its process and its DHT engine
/// (the stale `B0` in it) intact. Its stale copy gives way to the tombstone and nothing brings `B0` back. Not covered: a return after
/// `tombstone_retention` (1 h by default), when the tombstone is gone (a DHT-level limit); `FULL` replication; node-private copies.
@PortBudget
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EmberCheckpointReclaimTest {
    private static final int CLUSTER_SIZE = 3;
    private static final int SLOTS = 2 * CLUSTER_SIZE;
    private static final int MGMT_OFFSET = 40;
    private static final int APP_HTTP_OFFSET = 80;
    private static final EmberTestPorts.Block PORTS = new EmberTestPorts.Block(EmberTestPorts.POOL_FIRST,
                                                                               EmberTestPorts.POOL_LAST,
                                                                               EmberTestPorts.POOL_STEP,
                                                                               SLOTS,
                                                                               MGMT_OFFSET,
                                                                               APP_HTTP_OFFSET);
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(180).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(60).seconds();
    private static final TimeSpan SAVE_BOUND = TimeSpan.timeSpan(60).seconds();
    private static final long LEADER_BUDGET_MS = 90_000L;
    private static final long REPLICATION_BUDGET_MS = 30_000L;
    private static final long DELETE_BUDGET_MS = 30_000L;
    /// Three anti-entropy intervals (30 s each) and margin.
    private static final long ANTI_ENTROPY_BUDGET_MS = 150_000L;
    /// The survivors declared a silenced replica Dead after about 26 s in the v-2516 probe; the budget is generous.
    private static final long DEAD_BUDGET_MS = 300_000L;
    /// Silence kept past the Dead verdict, so the survivors act on it before the replica returns (the v-2516 probe's margin).
    private static final long DEAD_MARGIN_MS = 20_000L;
    /// The returned replica was a member again 40 s after it returned in the v-2516 probe; the budget is generous.
    private static final long REJOIN_BUDGET_MS = 240_000L;
    /// How long the returned replica must stay a member with no replacement core joining: past the leader's deficit debounce (15 s after
    /// the Dead verdict), by which a replacement would have been provisioned, and the excess scale-down that would follow.
    private static final long STAY_MS = 30_000L;
    private static final String DEAD = "Dead";
    private static final String MEMBER = "Member";
    private static final String KEYSPACE = "reclaim";
    private static final String DHT_PREFIX = "stream-segments/";
    private static final StreamEntityLogSubstrate.CheckpointReclaim RECLAIM = checkpointReclaim(TimeSpan.timeSpan(1).seconds(),
                                                                                                Retry.retry()
                                                                                                     .attempts(5)
                                                                                                     .strategy(BackoffStrategy.fixed()
                                                                                                                              .interval(TimeSpan.timeSpan(1)
                                                                                                                                                .seconds())));

    @TempDir
    Path dataDir;

    private EmberCluster cluster;
    private int generation;

    /// One cluster for both tests, started inside the first test that runs: the port budget is per test ([PortBudget]).
    private EmberCluster cluster() {
        if (cluster == null) {
            cluster = EmberTestPorts.startedCluster(PORTS, this::threeNodes, START_BOUND);
            awaitCondition("a leader is elected", LEADER_BUDGET_MS, () -> cluster.currentLeader().isPresent());
        }

        return cluster;
    }

    @AfterAll
    void stopCluster() {
        if (cluster != null) {
            assertThat(cluster.stop().await(STOP_BOUND).fold(Cause::message, _ -> "stopped")).isEqualTo("stopped");
        }
    }

    @Test
    @Order(1)
    @Timeout(300)
    void identicalSnapshots_inTwoPartitions_areDifferentBlocks() {
        var writer = cluster().allNodes().getFirst();
        var substrate = substrateOf(writer);
        var bytes = bytes("the same snapshot");
        var lineage = bytes("the same lineage");

        save(substrate, 10, 4L, bytes, Option.none());
        save(substrate, 11, 4L, bytes, Option.none());
        save(substrate, 12, 4L, bytes, Option.some(lineage));
        save(substrate, 13, 4L, bytes, Option.some(lineage));

        assertThat(pointed(writer, 10)).as("an empty-lineage frame: two partitions, two blocks").isNotEqualTo(pointed(writer, 11));
        assertThat(pointed(writer, 12)).as("a lineage frame: two partitions, two blocks").isNotEqualTo(pointed(writer, 13));
    }

    @Test
    @Order(2)
    @Timeout(600)
    void theSuccessorDeletesTheDisplacedBlock_fromTheDht_andAReconnectedReplicaDoesNotResurrectIt() {
        var leader = cluster().currentLeader().or("none");
        var nodes = cluster().allNodes();
        var cutOff = nodes.stream().filter(node -> !node.self().id().equals(leader)).findFirst().orElseThrow();
        var writers = nodes.stream().filter(node -> node != cutOff).toList();
        var nodeA = writers.get(0);
        var nodeB = writers.get(1);

        save(substrateOf(nodeA), 0, 10L, bytes("written by A at 10"), Option.none());
        var b0 = pointed(nodeA, 0);
        var key = dhtKey(b0);

        awaitCondition("B's KV names B0", REPLICATION_BUDGET_MS, () -> pointedIfAny(nodeB, 0).filter(b0::equals).isPresent());
        awaitCondition("control: B0 is live on every replica's DHT engine", REPLICATION_BUDGET_MS, () -> nodes.stream().allMatch(node -> holdsLive(node, key)));

        var cutAt = System.currentTimeMillis();

        cutOff.blackhole(true);
        try {
            save(substrateOf(nodeB), 0, 20L, bytes("written by B at 20"), Option.none());
            var b1 = pointed(nodeB, 0);

            assertThat(b1).as("control: B's pointer names a new block").isNotEqualTo(b0);
            awaitCondition("B0 leaves the DHT engines B's remove reached (A and B)", DELETE_BUDGET_MS, () -> writers.stream().noneMatch(node -> holdsLive(node, key)));
            assertThat(holdsLive(cutOff, key)).as("control: the cut-off replica still holds the stale B0 before the anti-entropy round").isTrue();
        } finally {
            cutOff.blackhole(false);
        }
        System.out.printf("NT-63 replica %s cut off for %d ms%n", cutOff.self().id(), System.currentTimeMillis() - cutAt);

        awaitCondition("after the reconnect and anti-entropy, no engine holds a live B0", ANTI_ENTROPY_BUDGET_MS, () -> nodes.stream().noneMatch(node -> holdsLive(node, key)));
        System.out.printf("NT-63 the cut-off replica's engine entry for B0 after anti-entropy: %s%n",
                          entry(cutOff, key).map(found -> found.tombstone() ? "tombstone" : "live").or("absent"));

        assertThat(readable(nodeB, b0)).as("B's storage does not read B0").isFalse();
        assertThat(readable(cutOff, b0)).as("the reconnected replica's storage does not read B0 (no resurrection)").isFalse();
        System.out.printf("NT-63 L-12: A's node-private copy of B0 readable through A's storage: %s%n", readable(nodeA, b0));
        assertThat(nodes).as("control: the block the pointer names is readable through every node").allSatisfy(node -> assertThat(readable(node, pointed(nodeB, 0))).isTrue());
    }

    @Test
    @Order(3)
    @Timeout(900)
    void aReplicaDeclaredDead_thatReturns_takesTheTombstone_andNothingResurrectsTheDeletedBlock() {
        startAFreshCluster();
        var leader = cluster().currentLeader().or("none");
        var nodes = cluster().allNodes();

        disableAutoHeal(nodes, leader);
        var cutOff = nodes.stream().filter(node -> !node.self().id().equals(leader)).findFirst().orElseThrow();
        var writers = nodes.stream().filter(node -> node != cutOff).toList();
        var nodeA = writers.get(0);
        var nodeB = writers.get(1);

        save(substrateOf(nodeA), 1, 10L, bytes("written by A at 10"), Option.none());
        var b0 = pointed(nodeA, 1);
        var key = dhtKey(b0);

        awaitCondition("B's KV names B0", REPLICATION_BUDGET_MS, () -> pointedIfAny(nodeB, 1).filter(b0::equals).isPresent());
        awaitCondition("control: B0 is live on every replica's DHT engine", REPLICATION_BUDGET_MS, () -> nodes.stream().allMatch(node -> holdsLive(node, key)));

        var cutAt = System.currentTimeMillis();

        cutOff.blackhole(true);
        try {
            save(substrateOf(nodeB), 1, 20L, bytes("written by B at 20"), Option.none());

            assertThat(pointed(nodeB, 1)).as("control: B's pointer names a new block").isNotEqualTo(b0);
            awaitCondition("B0 leaves the DHT engines B's remove reached (A and B)", DELETE_BUDGET_MS, () -> writers.stream().noneMatch(node -> holdsLive(node, key)));
            awaitCondition("the survivors declare the cut-off replica Dead", DEAD_BUDGET_MS, () -> stateOf(nodeA, cutOff).equals(DEAD));
            System.out.printf("NT-63 arm b: replica %s declared Dead after %d ms%n", cutOff.self().id(), System.currentTimeMillis() - cutAt);
            assertThat(holdsLive(cutOff, key)).as("control: the replica declared Dead still holds the stale B0").isTrue();
            pause(DEAD_MARGIN_MS);
        } finally {
            cutOff.blackhole(false);
        }

        var resurrected = new ArrayList<String>();
        var replacements = new LinkedHashSet<String>();
        var seen = new ArrayList<String>();

        awaitCondition("the returned replica is a member again and no engine holds a live B0", REJOIN_BUDGET_MS, () -> {
            writers.stream().filter(node -> holdsLive(node, key)).forEach(node -> resurrected.add(node.self().id()));
            replacements.addAll(newcomers(nodes));
            noteChange(seen, stateOf(nodeA, cutOff) + ", live B0 on " + nodes.stream().filter(node -> holdsLive(node, key)).map(node -> node.self().id()).toList(), cutAt);

            return stateOf(nodeA, cutOff).equals(MEMBER) && nodes.stream().noneMatch(node -> holdsLive(node, key));
        });
        assertThat(replacements).as("control: no replacement core was provisioned while the replica returned").isEmpty();
        holdsFor("the returned replica stays a member and no replacement core joins", STAY_MS, () -> stateOf(nodeA, cutOff).equals(MEMBER) && newcomers(nodes).isEmpty());
        System.out.printf("NT-63 arm b: replica %s a member again %d ms after the cut; its engine entry for B0: %s%n",
                          cutOff.self().id(),
                          System.currentTimeMillis() - cutAt,
                          entry(cutOff, key).map(found -> found.tombstone() ? "tombstone" : "live").or("absent"));

        assertThat(resurrected).as("no writer's engine held a live B0 again while the replica returned").isEmpty();
        assertThat(readable(nodeB, b0)).as("B's storage does not read B0").isFalse();
        assertThat(readable(cutOff, b0)).as("the returned replica's storage does not read B0 (no resurrection)").isFalse();
        System.out.printf("NT-63 arm b L-12: A's node-private copy of B0 readable through A's storage: %s%n", readable(nodeA, b0));
        assertThat(nodes).as("control: the block the pointer names is readable through every node").allSatisfy(node -> assertThat(readable(node, pointed(nodeB, 1))).isTrue());
    }

    /// Arm b declares a replica Dead (the survivors then provision a replacement), which the arms before it would leave behind: it starts
    /// on a cluster of its own, as its v-2516 probe did. Run 1 on the shared cluster saw the replica declared Dead in 6 s and never a member
    /// again.
    private void startAFreshCluster() {
        stopCluster();
        cluster = null;
        generation++;
    }

    /// With auto-heal on, the leader provisions a replacement core once the Dead replica's absence outlasts its deficit debounce, and then
    /// scales the returned replica away as the excess, so it is never a member again (CI ember-shard-2 at 3f6814b5d3, and run 1 on the
    /// shared cluster: a race between that debounce and the return). Arm b is about the DHT tombstone, not auto-heal: its cluster runs
    /// with auto-heal off, through the consensus-backed switch, and every node must read it off before the cut.
    private static void disableAutoHeal(List<AetherNode> nodes, String leader) {
        var leaderNode = nodes.stream().filter(node -> node.self().id().equals(leader)).findFirst().orElseThrow();

        assertThat(leaderNode.clusterTopologyManager()
                             .map(ctm -> ctm.setAutoHealEnabled(false, "NT-63 arm b").await(SAVE_BOUND).isSuccess())
                             .or(false)).as("auto-heal is disabled for arm b's cluster")
                                        .isTrue();
        awaitCondition("every node reads auto-heal disabled", REPLICATION_BUDGET_MS, () -> nodes.stream().allMatch(EmberCheckpointReclaimTest::autoHealDisabled));
    }

    /// Nodes in the cluster now that are not among `original`: a replacement core the leader provisioned.
    private List<String> newcomers(List<AetherNode> original) {
        var ids = original.stream().map(node -> node.self().id()).toList();

        return cluster().allNodes().stream().map(node -> node.self().id()).filter(id -> !ids.contains(id)).toList();
    }

    private static boolean autoHealDisabled(AetherNode node) {
        return node.clusterTopologyManager()
                   .map(ctm -> !ctm.isAutoHealEnabled())
                   .or(false);
    }

    /// Prints the returning replica's state each time it changes, so a red run shows where the return stopped.
    private static void noteChange(List<String> seen, String state, long cutAt) {
        if (seen.isEmpty() || !seen.getLast().equals(state)) {
            seen.add(state);
            System.out.printf("NT-63 arm b: +%d ms replica %s%n", System.currentTimeMillis() - cutAt, state);
        }
    }

    private EmberCluster threeNodes(int basePort) {
        var built = emberCluster(CLUSTER_SIZE, basePort, basePort + MGMT_OFFSET, basePort + APP_HTTP_OFFSET, "ckptreclaim");

        built.withDataBaseDir(dataDir.resolve("cluster-" + generation));
        built.withDhtReplication(DHTConfig.DEFAULT);

        return built;
    }

    private static EntityLogSubstrate substrateOf(AetherNode node) {
        return StreamEntityLogSubstrate.streamEntityLogSubstrate(node.streamPartitionManager(),
                                                                 (_, _) -> new StreamPartitionManager.ReplicaCatchupSource.CatchupView(0, true),
                                                                 null,
                                                                 new SegmentIndex(),
                                                                 EvictionListener.NOOP,
                                                                 streams(node),
                                                                 node.kvStore(),
                                                                 commands -> node.<Object>apply(commands),
                                                                 RECLAIM);
    }

    private static StorageInstance streams(AetherNode node) {
        return node.storageSetups().get(StorageFactory.STREAMS_NAME).instance();
    }

    private static void save(EntityLogSubstrate substrate, int partition, long through, byte[] snapshot, Option<byte[]> lineage) {
        assertThat(substrate.saveCheckpoint(KEYSPACE, partition, through, snapshot, lineage).await(SAVE_BOUND).fold(Cause::message, _ -> "saved"))
            .as("the save of partition " + partition + " at " + through + " resolves")
            .isEqualTo("saved");
    }

    private static BlockId pointed(AetherNode node, int partition) {
        return pointedIfAny(node, partition).or(() -> {
            throw new AssertionError("no checkpoint pointer for partition " + partition + " on " + node.self().id());
        });
    }

    private static Option<BlockId> pointedIfAny(AetherNode node, int partition) {
        return node.kvStore()
                   .getTyped(EntityCheckpointKey.entityCheckpointKey(KEYSPACE, partition), EntityFoldCheckpointValue.class)
                   .flatMap(pointer -> BlockId.fromHex(pointer.blockIdHex()).option());
    }

    private static boolean readable(AetherNode node, BlockId id) {
        return streams(node).get(id).await(SAVE_BOUND).fold(_ -> false, Option::isPresent);
    }

    private static byte[] dhtKey(BlockId id) {
        return (DHT_PREFIX + id.hexString()).getBytes(StandardCharsets.UTF_8);
    }

    private static Option<DHTMessage.KeyValue> entry(AetherNode node, byte[] key) {
        return node.dhtNode()
                   .flatMap(dht -> dht.storage().getEntry(key).await().or(Option.none()));
    }

    private static boolean holdsLive(AetherNode node, byte[] key) {
        return entry(node, key).filter(found -> !found.tombstone()).isPresent();
    }

    private static String stateOf(AetherNode viewer, AetherNode node) {
        return viewer.membershipFsm()
                     .memberStates()
                     .entrySet()
                     .stream()
                     .filter(entry -> entry.getKey().id().equals(node.self().id()))
                     .map(Map.Entry::getValue)
                     .findFirst()
                     .orElse("absent");
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @SuppressWarnings("JBCT-EX-01")
    private static void awaitCondition(String what, long budgetMs, BooleanSupplier condition) {
        var deadline = System.currentTimeMillis() + budgetMs;

        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }

            try {
                Thread.sleep(250L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted waiting for: " + what, e);
            }
        }

        throw new AssertionError("not within " + budgetMs + "ms: " + what);
    }

    @SuppressWarnings("JBCT-EX-01")
    private static void holdsFor(String what, long budgetMs, BooleanSupplier condition) {
        var deadline = System.currentTimeMillis() + budgetMs;

        while (System.currentTimeMillis() < deadline) {
            if (!condition.getAsBoolean()) {
                throw new AssertionError("did not hold for " + budgetMs + "ms: " + what);
            }

            pause(250L);
        }
    }

    @SuppressWarnings("JBCT-EX-01")
    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted", e);
        }
    }
}
