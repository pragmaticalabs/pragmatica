// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

import io.netty.handler.codec.quic.QuicStreamChannel;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ConfigValue;
import org.pragmatica.cluster.node.rabia.RabiaNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.quic.PeerState;
import org.pragmatica.consensus.net.quic.QuicClusterNetwork;
import org.pragmatica.consensus.net.quic.QuicTransportMetrics.DropSite;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.StreamType;
import org.pragmatica.testlogging.LogCapture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// #3194, the missing instrument: what happens to a KV write committed WHILE one live peer's inbound CONSENSUS lane is
/// held unread, once the hold is released. Three real nodes (real QUIC, real Rabia, real KV store). One node, the held
/// peer, stops reading its inbound CONSENSUS stream from both others (`setAutoRead(false)`); the other two keep a quorum
/// and keep committing. Large puts submitted to each of them exhaust the stream's flow-control window, so the sender's
/// lane to the held peer turns unwritable for real (no mock, no filter): the backpressure episode opens and the later
/// frames are held in the lane backlog.
///
/// Pinned:
///  1. the hold is covered by unwritability: at least one `cleared` summary per held lane closes after the markers, and
///     their durations (each capped at the marker-to-release span) add up to the hold on every lane. A lane that turns
///     writable for a moment mid-hold splits its episode in two and still passes; the lane flaps while the window fills,
///     so shorter episodes also appear. The log line does not name the sender, so per-lane attribution is by count and
///     summed duration, not by identity. Every `entered` has its `cleared`, read from the same snapshot;
///  2. a frame held longer than the 5 s frame budget is given up and counted (`RETRY_GAVE_UP`), and once the hold is released
///     no backlog and no retry stays behind;
///  3. THE #3194 ASSERTION: a write committed during the hold reaches the held peer within [#CATCH_UP_BOUND_MS] of the release,
///     so a frame the hold made the transport give up is recovered by a layer above the lane. Measured (mutation probes):
///     the lane's held-frame backlog re-send is NOT what recovers it (discarding the backlog on release left this green);
///     neither are the `SyncRequest`, `RoundRequest` or `KVSyncRequest` handlers (ignored on the senders, alone or together
///     with the discard, still green). What does: the senders' past-slot Decision replay (`RabiaEngine.repairPastSlot`),
///     triggered by the held peer's own later propose/vote; with it also disabled the write never arrives. Which layer
///     recovers is not asserted, only that one does.
///
/// Not pinned, and part of the cost being measured: recovery may depend on the traffic submitted after the release (the
/// `kick-` puts), and each sender writes up to [#FILL_PUTS_MAX] puts of [#FILL_VALUE_CHARS] chars (about 120 MB) before
/// the lane is unwritable, so the held peer's catch-up includes that volume.
///
/// Hold length: the frame budget is 5 s, so the marker writes, submitted 1 s into a [#HOLD_AFTER_MARKERS] hold, are older
/// than the budget (and given up) several times over by the release; a longer hold adds CI time and no coverage.
@PortBudget
class EmberConsensusLaneHoldTest {
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
    private static final TimeSpan APPLY_BOUND = TimeSpan.timeSpan(60).seconds();
    private static final long LEADER_BUDGET_MS = 90_000L;
    /// The stream's flow-control window is 32 MB: a sender that has written more than that into a stream nobody reads is
    /// blocked.
    private static final int FILL_VALUE_CHARS = 2_000_000;
    private static final int FILL_PUTS_MAX = 60;
    private static final long HOLD_AFTER_MARKERS_MS = 12_000L;
    private static final long CATCH_UP_BOUND_MS = 90_000L;
    private static final long SLACK_MS = 1_500L;
    private static final String LOGGER = "org.pragmatica.consensus.net.quic.QuicClusterNetwork";
    private static final Pattern CLEARED = Pattern.compile("peer (\\S+) lane CONSENSUS cleared: (\\d+) write\\(s\\) found it unwritable over (\\d+) ms");

    @TempDir
    Path dataDir;

    private EmberCluster cluster;

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            assertThat(cluster.stop().await(STOP_BOUND).fold(Cause::message, _ -> "stopped")).isEqualTo("stopped");
        }
    }

    private record Arrival(long nanos, String message) {}

    @Test
    @Timeout(600)
    void aWriteCommittedWhileOnePeerHoldsItsConsensusLane_reachesThatPeerAfterTheRelease() {
        cluster = EmberTestPorts.startedCluster(PORTS, this::threeNodes, START_BOUND);
        awaitLeader();
        var nodes = cluster.allNodes();
        var held = nodes.stream().filter(node -> !node.isLeader()).findFirst().orElseThrow();
        var heldId = held.self();
        var senders = nodes.stream().filter(node -> node != held).toList();
        var arrivals = new CopyOnWriteArrayList<Arrival>();

        try (var capture = LogCapture.capture(LOGGER, Level.INFO, true, event -> arrivals.add(new Arrival(System.nanoTime(),
                                                                                                          event.getMessage().getFormattedMessage())))) {
            var inboundLanes = senders.stream().map(sender -> laneOf(held, sender)).toList();

            inboundLanes.forEach(lane -> lane.config().setAutoRead(false));
            var heldSince = System.nanoTime();

            // The lane of each sender to the held peer, until it is really unwritable (bounded: a regression cannot spin).
            for (var sender : senders) {
                fillUntilUnwritable(sender, heldId);
            }
            for (var sender : senders) {
                assertThat(laneOf(sender, heldId).isWritable()).as("precondition: %s -> %s CONSENSUS is unwritable", sender.self(), heldId).isFalse();
            }
            // The writes under test: committed (quorum of the two senders) while the held peer reads nothing.
            for (var sender : senders) {
                put(sender, marker(sender), "during-hold");
            }
            var markersAt = System.nanoTime();
            // The episode opens on the first write that finds the lane unwritable: the markers' broadcast is that write.
            awaitTrue("an entered WARN per held lane", () -> enteredFor(arrivals, heldId).size() >= senders.size());
            var gaveUpBefore = senders.stream().mapToLong(sender -> gaveUp(sender)).sum();

            pause(HOLD_AFTER_MARKERS_MS);
            var gaveUp = senders.stream().mapToLong(sender -> gaveUp(sender)).sum() - gaveUpBefore;
            var releasedAt = System.nanoTime();

            inboundLanes.forEach(lane -> lane.config().setAutoRead(true));
            // Traffic after the release closes the episodes (the summary is written by the first write that finds the lane
            // writable) and gives the held peer something to react to.
            for (var sender : senders) {
                put(sender, "kick-" + sender.self().id(), "after-release");
            }
            var releasedAtMs = (releasedAt - heldSince) / 1_000_000L;

            // The lane flaps while the flow-control window fills (many short episodes); the hold episodes are the ones that
            // span the marker phase. Their summary is written by the first write after the release.
            var holdMs = (releasedAt - markersAt) / 1_000_000L - SLACK_MS;

            var spanMs = (releasedAt - markersAt) / 1_000_000L;

            awaitTrue("a cleared summary covering the hold per held lane",
                      () -> coversHold(holdEpisodes(List.copyOf(arrivals), heldId, markersAt), senders.size(), spanMs, holdMs));

            // (1) every held lane has an episode that closed after the markers, together covering the hold (a split episode
            // passes), and every episode that opened also closed. One snapshot: a flap between two reads cannot red it.
            var snapshot = List.copyOf(arrivals);
            var episodes = holdEpisodes(snapshot, heldId, markersAt);
            var entered = enteredFor(snapshot, heldId);
            var cleared = clearedFor(snapshot, heldId);

            System.out.printf("LANE-HOLD heldFor=%d ms (from hold to release) holdEpisodes=%d entered=%d cleared=%d gaveUpDuringHold=%d%n",
                              releasedAtMs, episodes.size(), entered.size(), cleared.size(), gaveUp);
            assertThat(coversHold(episodes, senders.size(), spanMs, holdMs)).as("every held lane (%d senders) has an episode closed after the markers, together covering the %d ms hold: %s",
                                                                              senders.size(),
                                                                              holdMs,
                                                                              episodes).isTrue();
            // (2) frames older than the budget were given up and counted; nothing stays behind after the release.
            assertThat(gaveUp).as("held frames older than the frame budget are counted as RETRY_GAVE_UP").isPositive();
            awaitTrue("no backlog and no retry in flight after the release",
                      () -> senders.stream().allMatch(sender -> backlogCount(sender) == 0 && network(sender).consensusRetriesInFlight() == 0));
            // An episode opened by a post-release flap is closed by the next write that finds the lane writable, which the
            // catch-up traffic supplies: wait for the books to balance, each evaluation reading ONE snapshot.
            awaitTrue("every episode that entered also cleared (none lost)", () -> balanced(List.copyOf(arrivals), heldId));
            // (3) THE #3194 ASSERTION.
            var releaseNanos = releasedAt;

            awaitTrue("writes committed during the hold reach the held peer (recovered above the lane: Decision replay, not the backlog re-send), within " + CATCH_UP_BOUND_MS + " ms of the release",
                      CATCH_UP_BOUND_MS,
                      () -> senders.stream().allMatch(sender -> holds(held, marker(sender))));
            System.out.printf("LANE-HOLD caughtUpMs=%d markersAgeAtRelease=%d%n",
                              (System.nanoTime() - releaseNanos) / 1_000_000L,
                              (releasedAt - markersAt) / 1_000_000L);
        }
    }

    private static String marker(AetherNode sender) {
        return "mark-" + sender.self().id();
    }

    private static boolean holds(AetherNode node, String key) {
        return node.kvStore().get(ConfigKey.forKey(key)).isPresent();
    }

    private void fillUntilUnwritable(AetherNode sender, NodeId heldId) {
        var random = new java.util.Random(sender.self().id().hashCode());
        var puts = 0;

        // Incompressible, distinct per put: a repeated character would shrink on the wire and never fill the window.
        for (var n = 0; n < FILL_PUTS_MAX && (n == 0 || laneOf(sender, heldId).isWritable()); n++) {
            var chars = new char[FILL_VALUE_CHARS];

            for (var i = 0; i < chars.length; i++) {
                chars[i] = (char) ('a' + random.nextInt(26));
            }
            put(sender, "fill-" + sender.self().id() + "-" + n, new String(chars));
            puts++;
            // Unwritable right after a put can be the write still draining into the flow-control window: only a lane that is
            // still unwritable after the event loop caught up counts.
            pause(300L);
        }
        System.out.printf("LANE-HOLD fill %s -> %s puts=%d writable=%s%n", sender.self().id(), heldId.id(), puts, laneOf(sender, heldId).isWritable());
        // Let the stalled write settle: unwritable now and still unwritable after the event loop caught up.
        pause(500L);
    }

    private void put(AetherNode node, String key, String value) {
        List<KVCommand<AetherKey>> commands = List.of(new KVCommand.Put<>(ConfigKey.forKey(key), ConfigValue.configValue(key, value)));
        var failure = node.<Object> apply(commands).await(APPLY_BOUND).fold(cause -> cause, _ -> null);

        assertThat(failure).as("write %s submitted to %s commits", key, node.self()).isNull();
    }

    private static List<Arrival> enteredFor(List<Arrival> arrivals, NodeId peer) {
        return arrivals.stream()
                       .filter(a -> a.message().startsWith("Backpressure on peer " + peer + " lane CONSENSUS") && a.message().contains("entered"))
                       .toList();
    }

    /// The cleared summaries written after `markersAt`: the episodes that were open while the hold ran.
    private static List<Arrival> holdEpisodes(List<Arrival> arrivals, NodeId peer, long markersAt) {
        return clearedFor(arrivals, peer).stream().filter(a -> a.nanos() >= markersAt).toList();
    }

    private static boolean balanced(List<Arrival> snapshot, NodeId peer) {
        return enteredFor(snapshot, peer).size() == clearedFor(snapshot, peer).size();
    }

    /// At least one episode per sender and, summing each episode capped at the marker-to-release span, the hold on every sender.
    private static boolean coversHold(List<Arrival> episodes, int senders, long spanMs, long holdMs) {
        var covered = episodes.stream().mapToLong(a -> Math.min(spanMs, durationMs(a))).sum();

        return episodes.size() >= senders && covered >= senders * holdMs;
    }

    private static long durationMs(Arrival episode) {
        var matcher = CLEARED.matcher(episode.message());

        return matcher.find() ? Long.parseLong(matcher.group(3)) : 0L;
    }

    private static List<Arrival> clearedFor(List<Arrival> arrivals, NodeId peer) {
        return arrivals.stream()
                       .filter(a -> a.message().contains("Backpressure on peer " + peer + " lane CONSENSUS cleared"))
                       .map(a -> new Arrival(a.nanos(), a.message().substring(a.message().indexOf("peer "))))
                       .toList();
    }

    private static QuicClusterNetwork network(AetherNode node) {
        return (QuicClusterNetwork) StreamTripwireSupport.component(node, "clusterNode", RabiaNode.class).network();
    }

    /// `owner`'s CONSENSUS stream with `peer`: for the held node, its inbound lane from `peer`; for a sender, its outbound
    /// lane to the held peer.
    @SuppressWarnings({"unchecked", "JBCT-EX-01"})
    private static QuicStreamChannel laneOf(AetherNode owner, AetherNode peer) {
        return laneOf(owner, peer.self());
    }

    @SuppressWarnings({"unchecked", "JBCT-EX-01"})
    private static QuicStreamChannel laneOf(AetherNode owner, NodeId peer) {
        try {
            var peers = (Map<NodeId, PeerState>) field(QuicClusterNetwork.class, "peers").get(network(owner));

            return peers.get(peer).activeConnection().flatMap(connection -> connection.stream(StreamType.CONSENSUS)).fold(
                () -> {
                    throw new AssertionError(owner.self() + " has no CONSENSUS stream with " + peer);
                },
                stream -> stream);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    @SuppressWarnings({"unchecked", "JBCT-EX-01"})
    private static int backlogCount(AetherNode node) {
        try {
            return ((ConcurrentHashMap<?, ?>) field(QuicClusterNetwork.class, "laneBacklogs").get(network(node))).size();
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    private static long gaveUp(AetherNode node) {
        return network(node).quicMetrics().droppedAfterSentCount(StreamType.CONSENSUS, DropSite.RETRY_GAVE_UP);
    }

    @SuppressWarnings("JBCT-EX-01")
    private static Field field(Class<?> type, String name) {
        try {
            var field = type.getDeclaredField(name);

            field.setAccessible(true);

            return field;
        } catch (NoSuchFieldException e) {
            throw new AssertionError("QuicClusterNetwork seam moved: no field '" + name + "'", e);
        }
    }

    private EmberCluster threeNodes(int basePort) {
        var built = emberCluster(CLUSTER_SIZE, basePort, basePort + MGMT_OFFSET, basePort + APP_HTTP_OFFSET, "lanehold");

        built.withDataBaseDir(dataDir);

        return built;
    }

    private void awaitLeader() {
        var deadline = System.currentTimeMillis() + LEADER_BUDGET_MS;

        while (System.currentTimeMillis() < deadline && cluster.currentLeader().isEmpty()) {
            pause(500L);
        }

        assertThat(cluster.currentLeader().isPresent()).as("a leader is elected").isTrue();
    }

    private static void awaitTrue(String what, BooleanSupplier condition) {
        awaitTrue(what, 60_000L, condition);
    }

    private static void awaitTrue(String what, long budgetMs, BooleanSupplier condition) {
        var deadline = System.currentTimeMillis() + budgetMs;

        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out waiting: " + what);
            }
            pause(250L);
        }
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
