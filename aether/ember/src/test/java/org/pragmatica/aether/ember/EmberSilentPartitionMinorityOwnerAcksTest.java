// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.config.cluster.ReplicationDefaultsParser;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// #1730, the SILENT-partition half: a stream owner cut off into a minority, with a producer that keeps publishing to it, acknowledges
/// nothing once its node has lost quorum; the only acknowledgements the minority side gives are the ones inside the detection
/// window, and that window is measured here, not assumed.
///
/// Five real nodes, no drain, no kill: the owner's network is black-holed ([AetherNode#blackhole], the tool of #1560's fence test), so
/// the only thing that tells it the quorum is gone is the failure detector. The black hole starts only after the cold-boot
/// convergence window, because until then SWIM deliberately reports UNKNOWN instead of FAULTY and quorum loss is deferred. (The
/// per-peer [AetherNode#partitionFrom] seam is not used: it drops inbound messages but leaves the QUIC connections up, and SWIM
/// refuses to declare a peer FAULTY against a live transport connection, so it never reaches PASSIVE.) A producer thread per
/// stream calls the owner's local write path (what `StreamWriteRouter` runs for a self-owned partition: the owner admission, the
/// replica floor, then the confirmation barrier) back to back from the cut on. A poller reads `mayServeAsOwner`, the owner serve gate
/// that quorum loss (PASSIVE) clears, once a millisecond; the first refusal after the cut is when PASSIVE took effect.
///
/// Two streams share the cut owner: one at the default `confirmation_factor` 2 (an ack needs the whole ISR, which the cut makes
/// unreachable at once) and one at `confirmation_factor` 1 (the ack is owner-local, so the gate is the ONLY thing between the
/// minority and an acknowledgement; this is the documented residual window of `OwnerActivation`).
///
/// What it pins: at CF 1, no write STARTED after the gate refused is acknowledged, and the window from the cut to the refusal is
/// bounded and printed; at CF 2, no write started after the cut is acknowledged at all. Removing `ownerServeGate.admit` from the
/// owner write admission turns the CF 1 assertion red (the writes keep being acknowledged until the node fences itself).
@PortBudget
class EmberSilentPartitionMinorityOwnerAcksTest {
    private static final int CLUSTER_SIZE = 5;
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
    private static final TimeSpan REQUEST = TimeSpan.timeSpan(30).seconds();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final long LEADER_BUDGET_MS = 90_000L;
    /// Past `AetherNode.COLD_BOOT_CONVERGENCE_WINDOW_MS` (75 s), measured from cluster start, as in `EmberPartitionedCoreSelfFenceTest`.
    private static final long COLD_BOOT_CLEARANCE_MS = 85_000L;
    private static final long OWNERSHIP_BUDGET_MS = 60_000L;
    /// One attempt waits at most this for the confirmation barrier, so a CF 2 attempt never outlives the observation by much.
    private static final TimeSpan ATTEMPT_BOUND = TimeSpan.timeSpan(2).seconds();
    /// How long the producers and the poller keep going after the gate first refuses.
    private static final long AFTER_PASSIVE_MS = 5_000L;
    /// The whole observation, from the cut. Past the quorum-loss detection (about 18 s measured for #1560 at the node level) but
    /// short enough that the cut node has not yet fenced itself away.
    private static final long OBSERVE_BOUND_MS = 45_000L;
    /// The bound the detection window is held to; a measured value far above it would mean PASSIVE is not firing under a silent cut.
    private static final long PASSIVE_BOUND_MS = 40_000L;
    private static final int STREAMS_PER_FACTOR = 6;
    private static final String NAMESPACE = "ember";
    private static final String VERSION = "1.0.0";

    @TempDir
    Path dataDir;

    private EmberCluster cluster;

    private record Attempt(long startedAtNs, long endedAtNs, boolean acked, String detail) {}

    private record Chosen(String name, String engineKey, StreamPartitionOwnershipValue record) {}

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            cluster.allNodes().forEach(node -> node.blackhole(false));
            assertThat(cluster.stop().await(STOP_BOUND).fold(Cause::message, _ -> "stopped")).isEqualTo("stopped");
        }
    }

    @Test
    @Timeout(900)
    void silentlyPartitionedOwner_acknowledgesNothingAfterItsNodeLostQuorum_andTheWindowIsMeasured() {
        cluster = EmberTestPorts.startedCluster(PORTS, this::fiveNodes, START_BOUND);
        var startedAtNs = System.nanoTime();

        awaitLeader();

        var leaderId = cluster.currentLeader().unwrap();
        var cf2Names = names("silentcf2-", STREAMS_PER_FACTOR);

        cf2Names.forEach(this::createStream);
        commitReplication(3, 1);

        var cf1Names = names("silentcf1-", STREAMS_PER_FACTOR);

        cf1Names.forEach(this::createStream);

        var cf2 = cf2Names.stream().map(this::awaitOwnership).toList();
        var cf1 = cf1Names.stream().map(this::awaitOwnership).toList();
        var pair = pickSharedOwner(cf2, cf1, leaderId);
        var cf2Stream = pair.get(0);
        var cf1Stream = pair.get(1);
        var ownerId = cf2Stream.record().owner();
        var owner = cluster.getNode(ownerId.id()).unwrap();

        assertThat(owner.streamPartitionManager().confirmationFactorFor(cf2Stream.engineKey())).as("control: the CF 2 stream's factor").isEqualTo(2);
        assertThat(owner.streamPartitionManager().confirmationFactorFor(cf1Stream.engineKey())).as("control: the CF 1 stream's factor").isEqualTo(1);

        var control2 = acknowledgedWithin(owner, cf2Stream, "before-cut", OWNERSHIP_BUDGET_MS);
        var control1 = acknowledgedWithin(owner, cf1Stream, "before-cut", OWNERSHIP_BUDGET_MS);

        assertThat(control2.acked()).as("control: the owner acknowledges the CF 2 stream before the cut: %s", control2.detail()).isTrue();
        assertThat(control1.acked()).as("control: the owner acknowledges the CF 1 stream before the cut: %s", control1.detail()).isTrue();
        assertThat(owner.streamPartitionManager().mayServeAsOwner(cf1Stream.engineKey(), 0)).as("control: the gate admits before the cut").isTrue();

        var clearanceLeftMs = COLD_BOOT_CLEARANCE_MS - (System.nanoTime() - startedAtNs) / 1_000_000L;

        if (clearanceLeftMs > 0) {
            sleepQuietly(clearanceLeftMs);
        }

        var attempts1 = new CopyOnWriteArrayList<Attempt>();
        var attempts2 = new CopyOnWriteArrayList<Attempt>();
        var running = new AtomicBoolean(true);
        var passiveAtNs = new AtomicLong(0L);
        var cutAtNs = System.nanoTime();

        owner.blackhole(true);

        var producers = List.of(producer(owner, cf1Stream, attempts1, running), producer(owner, cf2Stream, attempts2, running));

        producers.forEach(Thread::start);

        var deadline = cutAtNs + OBSERVE_BOUND_MS * 1_000_000L;

        while (System.nanoTime() < deadline) {
            if (passiveAtNs.get() == 0L && !owner.streamPartitionManager().mayServeAsOwner(cf1Stream.engineKey(), 0)) {
                passiveAtNs.set(System.nanoTime());
                deadline = Math.min(deadline, passiveAtNs.get() + AFTER_PASSIVE_MS * 1_000_000L);
            }

            sleepQuietly(1L);
        }

        running.set(false);
        producers.forEach(thread -> join(thread));

        var passiveAt = passiveAtNs.get();
        var windowMs = passiveAt == 0L ? -1L : (passiveAt - cutAtNs) / 1_000_000L;
        var cf1Window = attempts1.stream().filter(a -> a.acked() && a.startedAtNs() >= cutAtNs && (passiveAt == 0L || a.startedAtNs() <= passiveAt)).toList();
        var cf1After = attempts1.stream().filter(a -> a.acked() && passiveAt != 0L && a.startedAtNs() > passiveAt).toList();
        var cf1InFlight = attempts1.stream().filter(a -> a.acked() && passiveAt != 0L && a.startedAtNs() <= passiveAt && a.endedAtNs() > passiveAt).toList();
        var cf2AfterCut = attempts2.stream().filter(a -> a.acked() && a.startedAtNs() > cutAtNs).toList();
        var cf1RefusalKinds = attempts1.stream()
                                       .filter(a -> !a.acked() && passiveAt != 0L && a.startedAtNs() > passiveAt)
                                       .collect(Collectors.groupingBy(a -> a.detail().length() > 40 ? a.detail().substring(0, 40) : a.detail(), java.util.TreeMap::new, Collectors.counting()));
        var firstCf1RefusalMs = attempts1.stream().filter(a -> !a.acked()).findFirst().map(a -> (a.startedAtNs() - cutAtNs) / 1_000_000L).orElse(-1L);
        var lastCf1Ack = cf1Window.isEmpty() ? -1L : (cf1Window.getLast().endedAtNs() - cutAtNs) / 1_000_000L;

        System.out.printf("SILENTCUT owner=%s cutToGateRefusalMs=%d cf1AcksInWindow=%d cf1LastAckAtMs=%d cf1AcksStartedAfterRefusal=%d cf1AcksInFlightAcrossRefusal=%d"
                          + " cf1Attempts=%d cf2AcksAfterCut=%d cf2Attempts=%d firstCf1RefusalAtMs=%d cf1RefusalsAfterGate=%s%n",
                          ownerId.id(),
                          windowMs,
                          cf1Window.size(),
                          lastCf1Ack,
                          cf1After.size(),
                          cf1InFlight.size(),
                          attempts1.size(),
                          cf2AfterCut.size(),
                          attempts2.size(),
                          firstCf1RefusalMs,
                          cf1RefusalKinds);

        assertThat(passiveAt).as("the owner's serve gate refused within %d ms of the silent cut (PASSIVE fired); attempts: %d", OBSERVE_BOUND_MS, attempts1.size()).isNotZero();
        assertThat(windowMs).as("detection window from the cut to the gate's refusal, ms").isLessThanOrEqualTo(PASSIVE_BOUND_MS);
        assertThat(cf1After).as("CF 1: no write started after the owner gate refused may be acknowledged (window %d ms)", windowMs).isEmpty();
        assertThat(cf2AfterCut).as("CF 2: no write started after the cut may be acknowledged: the ISR is unreachable").isEmpty();
    }

    private Thread producer(AetherNode owner, Chosen stream, List<Attempt> sink, AtomicBoolean running) {
        return Thread.ofPlatform().name("silentcut-producer-" + stream.name()).unstarted(() -> {
            var manager = owner.streamPartitionManager();
            var confirmations = manager.confirmationFactorFor(stream.engineKey()) - 1;
            var sequence = 0;

            while (running.get()) {
                var payload = ("after-cut-" + sequence++).getBytes(StandardCharsets.UTF_8);
                var started = System.nanoTime();
                var outcome = manager.publishLocalAtFloor(stream.engineKey(), 0, payload, System.currentTimeMillis(), confirmations)
                                     .async()
                                     .flatMap(offset -> manager.awaitReplication(stream.engineKey(), 0, offset, confirmations).map(_ -> offset))
                                     .await(ATTEMPT_BOUND)
                                     .fold(cause -> new Attempt(started, System.nanoTime(), false, cause.message()),
                                           offset -> new Attempt(started, System.nanoTime(), true, "offset " + offset));

                sink.add(outcome);
                sleepQuietly(2L);
            }
        });
    }

    /// A cf2 and a cf1 stream whose committed owner is the same node and is not the leader (a leader cut would take the consensus
    /// leadership into the question; this test is about an owner).
    private List<Chosen> pickSharedOwner(List<Chosen> cf2, List<Chosen> cf1, String leaderId) {
        for (var first : cf2) {
            for (var second : cf1) {
                if (first.record().owner().equals(second.record().owner()) && !first.record().owner().id().equals(leaderId)) {
                    return List.of(first, second);
                }
            }
        }

        throw new AssertionError("no cf2 and cf1 stream share a non-leader owner; cf2 owners " + cf2.stream().map(c -> c.record().owner().id()).toList()
                                 + ", cf1 owners " + cf1.stream().map(c -> c.record().owner().id()).toList() + ", leader " + leaderId);
    }

    private record Response(boolean acked, String detail) {}

    private Response acknowledgedWithin(AetherNode owner, Chosen stream, String payload, long budgetMs) {
        var manager = owner.streamPartitionManager();
        var confirmations = manager.confirmationFactorFor(stream.engineKey()) - 1;
        var deadline = System.currentTimeMillis() + budgetMs;
        var last = new Response(false, "not attempted");

        while (System.currentTimeMillis() < deadline) {
            last = manager.publishLocalAtFloor(stream.engineKey(), 0, payload.getBytes(StandardCharsets.UTF_8), System.currentTimeMillis(), confirmations)
                          .async()
                          .flatMap(offset -> manager.awaitReplication(stream.engineKey(), 0, offset, confirmations).map(_ -> offset))
                          .await(TimeSpan.timeSpan(10).seconds())
                          .fold(cause -> new Response(false, cause.message()), offset -> new Response(true, "offset " + offset));

            if (last.acked()) {
                return last;
            }

            sleepQuietly(500L);
        }

        return last;
    }

    private static List<String> names(String prefix, int count) {
        return java.util.stream.IntStream.range(0, count).mapToObj(i -> prefix + i).toList();
    }

    private void createStream(String name) {
        var status = post(leaderPort(), "/api/v1/streams/" + NAMESPACE + "/" + name + "/" + VERSION, "{\"partitions\":1}");

        assertThat(status).as("create %s", name).startsWith("2");
    }

    private Chosen awaitOwnership(String name) {
        var key = NAMESPACE + ":" + name + ":" + VERSION;
        var deadline = System.currentTimeMillis() + OWNERSHIP_BUDGET_MS;

        while (System.currentTimeMillis() < deadline) {
            var record = committedRecord(key);

            if (record.isPresent()) {
                return new Chosen(name, key, record.unwrap());
            }

            sleepQuietly(250L);
        }

        throw new AssertionError("no committed ownership record for " + key + " within " + OWNERSHIP_BUDGET_MS + " ms");
    }

    private Option<StreamPartitionOwnershipValue> committedRecord(String engineKey) {
        var seen = new HashSet<StreamPartitionOwnershipValue>();

        cluster.allNodes()
               .forEach(node -> node.kvStore()
                                    .getTyped(AetherKey.StreamPartitionOwnershipKey.streamPartitionOwnershipKey(engineKey, 0),
                                              StreamPartitionOwnershipValue.class)
                                    .onPresent(seen::add));

        return seen.size() == 1
               ? Option.some(seen.iterator().next())
               : Option.none();
    }

    /// Commits `[replication]` with the given factors into the RUNNING cluster through the leader (the committed default a stream
    /// created afterwards takes), as `EmberWorkerDhtReplicationTest` does.
    private void commitReplication(int replicationFactor, int confirmationFactor) {
        var leader = cluster.currentLeader().flatMap(cluster::getNode).unwrap();
        var before = leader.kvStore().getTyped(ClusterConfigKey.CURRENT, ClusterConfigValue.class).unwrap();
        var toml = withoutReplication(before.tomlContent().or("")) + "\n[replication]\nreplication_factor = %d\nconfirmation_factor = %d\n".formatted(replicationFactor,
                                                                                                                       confirmationFactor);

        assertThat(ReplicationDefaultsParser.fromClusterToml(Option.some(toml)).map(defaults -> defaults.confirmationFactor()).or(0))
            .as("arming: the document carries the confirmation factor")
            .isEqualTo(confirmationFactor);
        var value = new ClusterConfigValue(Option.some(toml),
                                           before.clusterName(),
                                           before.version(),
                                           before.desiredTopology(),
                                           before.coreMin(),
                                           before.coreMax(),
                                           before.deploymentType(),
                                           before.configVersion() + 1,
                                           System.currentTimeMillis());
        var id = UUID.randomUUID().toString();
        var authority = leader.kvStore().getTyped(LeaderKey.INSTANCE, LeaderValue.class).unwrap();
        var transaction = new KVCommand.LeaderTransaction<AetherKey, AetherValue>(ClusterConfigKey.CURRENT,
                                                                                  id,
                                                                                  authority,
                                                                                  List.of(),
                                                                                  List.of(new KVCommand.Mutation<>(ClusterConfigKey.CURRENT,
                                                                                                                   Option.some(before),
                                                                                                                   Option.some(value))));

        assertThat(leader.<Object>apply(List.of(transaction)).await(REQUEST).unwrap())
            .anyMatch(outcome -> outcome instanceof KVCommand.TransactionResult accepted && accepted.transactionId().equals(id) && accepted.accepted());
        awaitCommitted(value.configVersion());
    }

    private void awaitCommitted(long configVersion) {
        var deadline = System.currentTimeMillis() + OWNERSHIP_BUDGET_MS;

        while (System.currentTimeMillis() < deadline) {
            var everywhere = cluster.allNodes()
                                    .stream()
                                    .allMatch(node -> node.kvStore()
                                                          .getTyped(ClusterConfigKey.CURRENT, ClusterConfigValue.class)
                                                          .map(config -> config.configVersion() >= configVersion)
                                                          .or(false));

            if (everywhere) {
                return;
            }

            sleepQuietly(250L);
        }

        throw new AssertionError("the committed replication config did not reach every node within " + OWNERSHIP_BUDGET_MS + " ms");
    }

    private static String withoutReplication(String toml) {
        var start = toml.indexOf("\n[replication]");

        if (start < 0) {
            return toml;
        }

        var next = toml.indexOf("\n[", start + 1);

        return next < 0
               ? toml.substring(0, start)
               : toml.substring(0, start) + toml.substring(next);
    }

    private EmberCluster fiveNodes(int basePort) {
        var built = emberCluster(CLUSTER_SIZE, basePort, basePort + MGMT_OFFSET, basePort + APP_HTTP_OFFSET, "silentcut");

        built.withDataBaseDir(dataDir);

        return built;
    }

    private int leaderPort() {
        return cluster.getLeaderManagementPort().or(-1);
    }

    private void awaitLeader() {
        var deadline = System.currentTimeMillis() + LEADER_BUDGET_MS;

        while (System.currentTimeMillis() < deadline && cluster.getLeaderManagementPort().isEmpty()) {
            sleepQuietly(500L);
        }

        assertThat(cluster.getLeaderManagementPort().isPresent()).as("a leader is elected").isTrue();
    }

    @SuppressWarnings("JBCT-EX-01")
    private static String post(int mgmtPort, String path, String json) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://127.0.0.1:" + mgmtPort + path))
                                 .timeout(REQUEST_TIMEOUT)
                                 .header("Content-Type", "application/json")
                                 .POST(HttpRequest.BodyPublishers.ofString(json))
                                 .build();

        try (var client = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build()) {
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());

            return response.statusCode() + " " + response.body();
        } catch (Exception e) {
            return "-1 " + e;
        }
    }

    @SuppressWarnings("JBCT-EX-01")
    private static void join(Thread thread) {
        try {
            thread.join(30_000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @SuppressWarnings("JBCT-EX-01")
    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
