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
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.ReadPreference;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.aether.stream.OffHeapRingBuffer.RawEvent;
import org.pragmatica.aether.stream.forward.StreamForwardMessage;
import org.pragmatica.aether.stream.replication.ReplicationMessage;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// T-07 (stream-storage design §9.1, S0, #3333): tripwire on §8 row 2 -- a replica never serves a record above the visible
/// offset it learned from the owner. The gate under test is ST-RP-2 facts (4)-(6): a non-owner GOVERNOR read is forwarded to
/// the owner while this copy is behind the announced mark (`behindCommittedOwner`), a replica's `visible` never exceeds
/// `min(durable, announced mark)` (`exposeReplicaCommitted`), and an unverified tail is refused `ReplicaNotVerified`.
///
/// Three real nodes (Ember refuses fewer, #3302), one partition at the default replication (RF 3, CF 2): owner O, replica R
/// (the one under test) and replica F. Every owner announcement (`ReplicationMessage.CommitAdvance`) reaching R passes an
/// inbound filter that records the highest mark R LEARNED and drops the message while the stall is on. Replication itself
/// (`Replicate` and its acks) is never touched, so R keeps receiving and holding records it may not serve.
///
/// Counting rule (ST-RP-2 facts (4)-(6)): only a record SERVED by R's own copy above R's learned visible offset is a breach.
/// A read R forwards to another node and a `ReplicaNotVerified` refusal are "not served above visible", whatever the target
/// returns for the forward. A router read counts as forwarded when a `StreamForwardMessage.ReadForward` from R for this stream
/// arrived at another node during the read (counted by the same inbound filters, so the classification does not rest on the
/// router's own choice: `GOVERNOR` forwards while R is behind, `NEAREST` while R is not caught up, `ANY_REPLICA` prefers a
/// remote copy, ForwardingReadRouter.java:32-53).
///
/// Phases:
/// 1. Stall on from before the stream exists: R hears no announcement in its life. Records are acknowledged. R holds them,
///    serves none of them from its copy, and its router reads are forwards (the counting rule's control: a forward returns
///    offsets above R's learned visible, and is not a breach).
/// 2. Stall off: R learns the owner's mark and serves the acknowledged records. Since at CF >= 2 an owner announces only once
///    its ownership has ACTIVATED (`announceMark` -> `ownerRoleHolds`, SPM:2913-2919, 3211-3225), a mark learned here is the
///    evidence that the stall of phase 3 is applied after the owner's activation (ST-RP-2 fact (1)).
/// 3. Stall on again, more records acknowledged: R's copy holds more than its learned visible offset, and no read path of R
///    (local read, the read served to a forwarded consumer, the router's GOVERNOR / NEAREST / ANY_REPLICA reads, the visible
///    bounds) serves an offset above it.
///
/// Mutation that turns it red (ST L825): serve replica reads to the replica's WAL end (R's `visible` follows its durable
/// offset instead of `min(durable, announced mark)`). Failure message: "T-07: replica served above the visible offset it learned".
@PortBudget
class EmberReplicaReadVisibleTripwireTest {
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
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final long LEADER_BUDGET_MS = 90_000L;
    private static final long ISR_BUDGET_MS = 60_000L;
    private static final long REPLICA_BUDGET_MS = 30_000L;
    private static final long REPAIR_WINDOW_MS = 2_500L;
    private static final int FIRST = 3;
    private static final int SECOND = 3;
    private static final int PARTITION = 0;
    private static final int CONFIRMATION_FACTOR = 2;
    private static final long NOTHING_LEARNED = -1L;
    private static final String BREACH = "T-07: replica served above the visible offset it learned";
    private static final String NAMESPACE = "ember";
    private static final String NAME = "replicavisible";
    private static final String VERSION = "1.0.0";
    private static final String ENGINE_KEY = NAMESPACE + ":" + NAME + ":" + VERSION;

    @TempDir
    Path dataDir;

    private EmberCluster cluster;
    private final AtomicReference<String> replicaId = new AtomicReference<>("");
    private final AtomicLong forwardsFromReplica = new AtomicLong();

    private record Response(int status, String body) {
        boolean acked() {
            return status >= 200 && status < 300;
        }
    }

    /// One read on R: what it returned, and whether R forwarded it to another node or refused it instead of serving its own copy.
    private record Observed(String path, List<Long> offsets, boolean forwarded, boolean refused) {
        long highest() {
            return offsets.stream().mapToLong(Long::longValue).max().orElse(NOTHING_LEARNED);
        }

        /// The counting rule: a forward and a refusal are "not served above visible"; only R's own served records count.
        boolean servedAbove(long learned) {
            return !forwarded && !refused && highest() > learned;
        }
    }

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            cluster.allNodes().forEach(node -> node.partitionFrom(Set.of()));
            assertThat(cluster.stop().await(STOP_BOUND).fold(Cause::message, _ -> "stopped")).isEqualTo("stopped");
        }
    }

    @Test
    @Timeout(600)
    void replicaReadNeverAboveVisible() {
        cluster = EmberTestPorts.startedCluster(PORTS, this::threeNodes, START_BOUND);
        awaitLeader();

        // The stall is installed on every node before the stream exists, so whichever node becomes R has heard no
        // announcement for it in its life; the filter acts only on the node chosen as R below.
        var stalled = new AtomicBoolean(true);
        var learned = new AtomicLong(NOTHING_LEARNED);

        cluster.allNodes()
               .forEach(node -> node.setInboundFaultFilter((_, message) -> countForward(message) && passes(node, message, replicaId, stalled, learned)));

        var created = post(leaderPort(), "/api/v1/streams/" + NAMESPACE + "/" + NAME + "/" + VERSION, "{\"partitions\":1}");

        assertThat(created.acked()).as("create: %s", created.body()).isTrue();

        var record = awaitFullIsr();
        var owner = cluster.getNode(record.owner().id()).unwrap();
        var replicas = record.isr().stream().filter(member -> !member.equals(record.owner())).toList();
        var replica = cluster.getNode(replicas.getFirst().id()).unwrap();

        replicaId.set(replica.self().id());
        // Each node adopts the committed stream config from its own KV notification, so the owner's and R's local views
        // lag the committed ISR on a loaded runner (0 = "stream unknown here"): wait for both, then judge.
        awaitUntil(() -> owner.streamPartitionManager().confirmationFactorFor(ENGINE_KEY) == CONFIRMATION_FACTOR,
                   REPLICA_BUDGET_MS,
                   "CF of the stream under test on the owner " + owner.self().id());
        awaitUntil(() -> replica.streamPartitionManager().confirmationFactorFor(ENGINE_KEY) == CONFIRMATION_FACTOR,
                   REPLICA_BUDGET_MS,
                   "CF of the stream under test on R " + replica.self().id());
        assertThat(owner.streamPartitionManager().confirmationFactorFor(ENGINE_KEY)).as("CF of the stream under test")
                                                                                   .isEqualTo(CONFIRMATION_FACTOR);

        // Phase 1: R has learned nothing; records are acknowledged without its visible offset moving.
        publishAll(owner, "first-", FIRST);
        awaitHolds(replica, FIRST);
        sleepQuietly(REPAIR_WINDOW_MS);
        assertThat(learned.get()).as("phase 1: R learned no mark while stalled from the start").isEqualTo(NOTHING_LEARNED);

        var phaseOne = observeAll(replica);

        assertNoneServedAbove(phaseOne, learned.get(), "phase 1");
        assertThat(phaseOne).as("control (counting rule): a read R forwards returns records above R's learned visible, which is"
                                + " not a breach; reads: %s", phaseOne)
                            .anyMatch(read -> read.forwarded() && read.highest() > NOTHING_LEARNED);

        // Phase 2: R learns the owner's mark; an activated owner is the only sender (fact (1)/(2)).
        stalled.set(false);
        awaitUntil(() -> learned.get() >= FIRST - 1, REPLICA_BUDGET_MS, "R learns the owner's mark " + (FIRST - 1));
        awaitUntil(() -> offsets(replica).size() >= FIRST, REPLICA_BUDGET_MS, "R serves the acknowledged records");
        assertThat(offsets(replica)).as("control: R serves records once it has learned the mark, so the instrument sees records")
                                    .containsExactly(0L, 1L, 2L);

        // Phase 3: the stall after the owner's activation; R's copy grows above what it learned.
        stalled.set(true);
        var learnedAtStall = learned.get();

        publishAll(owner, "second-", SECOND);
        awaitHolds(replica, FIRST + SECOND);
        sleepQuietly(REPAIR_WINDOW_MS);
        assertThat(learned.get()).as("no mark reached R during the stall").isEqualTo(learnedAtStall);
        assertThat(replica.streamPartitionManager().partitionBuffer(ENGINE_KEY, PARTITION).map(ring -> ring.headOffset()).or(-1L))
            .as("control: R's copy holds more than its learned visible offset %d", learnedAtStall)
            .isEqualTo((long) (FIRST + SECOND - 1))
            .isGreaterThan(learnedAtStall);
        awaitUntil(() -> owner.streamPartitionManager().visibleBounds(ENGINE_KEY, PARTITION).map(bounds -> bounds.visibleHead()).or(-2L)
                         == FIRST + SECOND - 1,
                   REPLICA_BUDGET_MS,
                   "the owner makes the second batch visible");
        assertThat(owner.streamPartitionManager().visibleBounds(ENGINE_KEY, PARTITION).map(bounds -> bounds.visibleHead()).or(-2L))
            .as("control: the owner made the second batch visible, so R's stalled mark is genuinely behind")
            .isEqualTo((long) (FIRST + SECOND - 1));

        var phaseThree = observeAll(replica);

        assertNoneServedAbove(phaseThree, learnedAtStall, "phase 3");
        assertThat(replica.streamPartitionManager().visibleBounds(ENGINE_KEY, PARTITION).map(bounds -> bounds.visibleHead()).or(-2L))
            .as("%s: R's visible bounds (a cursor is built from them) stay at its learned visible offset", BREACH)
            .isLessThanOrEqualTo(learnedAtStall);
    }

    /// Delivers every message except an owner announcement of the stream under test to R while the stall is on (to every node
    /// until R is known, so R has heard none in its life); records the highest mark R learned from the announcements it receives.
    private static boolean passes(AetherNode node,
                                  Object message,
                                  AtomicReference<String> replicaId,
                                  AtomicBoolean stalled,
                                  AtomicLong learned) {
        if (!(message instanceof ReplicationMessage.CommitAdvance advance) || !ENGINE_KEY.equals(advance.streamName())
            || advance.partition() != PARTITION) {
            return true;
        }
        var unknown = replicaId.get().isEmpty();
        var isReplica = node.self().id().equals(replicaId.get());

        if (stalled.get() && (unknown || isReplica)) {
            return false;
        }
        if (isReplica) {
            learned.accumulateAndGet(advance.committedThrough(), Math::max);
        }
        return true;
    }

    /// Counts every read forward R sends for the stream under test, as it arrives at another node; never drops a message.
    private boolean countForward(Object message) {
        if (message instanceof StreamForwardMessage.ReadForward forward && ENGINE_KEY.equals(forward.streamName())
            && forward.sender().id().equals(replicaId.get())) {
            forwardsFromReplica.incrementAndGet();
        }
        return true;
    }

    private static void assertNoneServedAbove(List<Observed> reads, long learned, String phase) {
        assertThat(reads).as("%s: %s %d; reads: %s", BREACH, phase, learned, reads)
                         .noneMatch(read -> read.servedAbove(learned));
    }

    /// Every read path R offers: its own copy through the local and the served read, and the router under each preference.
    private List<Observed> observeAll(AetherNode replica) {
        var manager = replica.streamPartitionManager();
        var reads = new ArrayList<Observed>();

        reads.add(local("local read", () -> manager.readLocal(ENGINE_KEY, PARTITION, 0L, 100)));
        reads.add(local("read served to a forwarded consumer", () -> manager.readServing(ENGINE_KEY, PARTITION, 0L, 100)));
        for (var preference : List.of(ReadPreference.GOVERNOR, ReadPreference.NEAREST, ReadPreference.ANY_REPLICA)) {
            reads.add(routed(replica, preference));
        }
        return reads;
    }

    private static Observed local(String path, Supplier<Result<List<RawEvent>>> read) {
        return read.get().fold(cause -> new Observed(path + " refused: " + cause.message(), List.of(), false, true),
                               events -> new Observed(path, offsetsOf(events), false, false));
    }

    private Observed routed(AetherNode replica, ReadPreference preference) {
        var before = forwardsFromReplica.get();
        var result = replica.streamReadRouter()
                            .read(ENGINE_KEY, PARTITION, 0L, 100, preference)
                            .await(TimeSpan.timeSpan(15).seconds());
        var forwarded = forwardsFromReplica.get() > before;

        return result.fold(cause -> new Observed("router " + preference + " refused: " + cause.message(), List.of(), forwarded, true),
                           events -> new Observed("router " + preference, offsetsOf(events), forwarded, false));
    }

    private static List<Long> offsetsOf(List<RawEvent> events) {
        return events.stream().map(RawEvent::offset).toList();
    }

    private List<Long> offsets(AetherNode node) {
        return node.streamPartitionManager()
                   .readLocal(ENGINE_KEY, PARTITION, 0L, 100)
                   .map(EmberReplicaReadVisibleTripwireTest::offsetsOf)
                   .or(List.of());
    }

    private void publishAll(AetherNode owner, String prefix, int count) {
        for (var i = 0; i < count; i++) {
            var response = publishAcked(owner, prefix + i);

            assertThat(response.acked()).as("publish %s%d acknowledged: %s", prefix, i, response.body()).isTrue();
        }
    }

    private Response publishAcked(AetherNode owner, String payload) {
        var deadline = System.currentTimeMillis() + ISR_BUDGET_MS;
        var appendedAt = new AtomicLong(-1);
        var last = publishOnce(owner, payload, appendedAt);

        while (!last.acked() && System.currentTimeMillis() < deadline) {
            sleepQuietly(500L);
            last = publishOnce(owner, payload, appendedAt);
        }

        return last;
    }

    /// The owner's own local publish path (what `StreamWriteRouter` runs for a self-owned partition): the owner-admitted append
    /// at the replica floor, then the confirmation barrier.
    private Response publishOnce(AetherNode node, String payload, AtomicLong appendedAt) {
        var manager = node.streamPartitionManager();
        var confirmations = manager.confirmationFactorFor(ENGINE_KEY) - 1;
        var appended = appendedAt.get() >= 0
                       ? Promise.success(appendedAt.get())
                       : manager.publishLocalAtFloor(ENGINE_KEY, PARTITION, payload.getBytes(StandardCharsets.UTF_8), System.currentTimeMillis(), confirmations)
                                .async()
                                .onSuccess(appendedAt::set);

        return appended.flatMap(offset -> manager.awaitReplication(ENGINE_KEY, PARTITION, offset, confirmations).map(_ -> offset))
                       .await(TimeSpan.timeSpan(30).seconds())
                       .fold(cause -> new Response(503, cause.message()), offset -> new Response(200, "offset " + offset));
    }

    private void awaitHolds(AetherNode node, int count) {
        awaitUntil(() -> node.streamPartitionManager().partitionBuffer(ENGINE_KEY, PARTITION).map(ring -> ring.headOffset() + 1).or(0L) >= count,
                   REPLICA_BUDGET_MS,
                   node.self().id() + " holds " + count + " records");
    }

    private static void awaitUntil(BooleanSupplier condition, long budgetMs, String what) {
        var deadline = System.currentTimeMillis() + budgetMs;

        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            sleepQuietly(200L);
        }
        assertThat(condition.getAsBoolean()).as("within %dms: %s", budgetMs, what).isTrue();
    }

    /// The committed record once its ISR holds every node (owner + both replicas).
    private StreamPartitionOwnershipValue awaitFullIsr() {
        var deadline = System.currentTimeMillis() + ISR_BUDGET_MS;

        while (System.currentTimeMillis() < deadline) {
            var record = committedRecord().filter(value -> value.isrVersion() > 0 && value.isr().size() == CLUSTER_SIZE);

            if (record.isPresent()) {
                return record.unwrap();
            }

            sleepQuietly(250L);
        }

        throw new AssertionError("no committed ISR of three for " + ENGINE_KEY + " within " + ISR_BUDGET_MS + "ms; last: " + committedRecord());
    }

    private Option<StreamPartitionOwnershipValue> committedRecord() {
        var seen = new HashSet<StreamPartitionOwnershipValue>();

        cluster.allNodes()
               .forEach(node -> node.kvStore()
                                    .getTyped(AetherKey.StreamPartitionOwnershipKey.streamPartitionOwnershipKey(ENGINE_KEY, PARTITION),
                                              StreamPartitionOwnershipValue.class)
                                    .onPresent(seen::add));

        return seen.size() == 1
               ? Option.some(seen.iterator().next())
               : Option.none();
    }

    private EmberCluster threeNodes(int basePort) {
        var built = emberCluster(CLUSTER_SIZE, basePort, basePort + MGMT_OFFSET, basePort + APP_HTTP_OFFSET, NAME);

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
    private static Response post(int mgmtPort, String path, String json) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://127.0.0.1:" + mgmtPort + path))
                                 .timeout(REQUEST_TIMEOUT)
                                 .header("Content-Type", "application/json")
                                 .POST(HttpRequest.BodyPublishers.ofString(json))
                                 .build();

        try (var client = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build()) {
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());

            return new Response(response.statusCode(), response.body());
        } catch (Exception e) {
            return new Response(-1, e.toString());
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
