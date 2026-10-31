// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Asynchronous.NewBatch;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.Message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// Qsync-scenario liveness probe, Ember arm (Q6 brief section 6.2): can five real cores, on real QUIC and Rabia, stall
/// completely under a partial or asymmetric cut while the voters hold different pending queues?
///
/// Each submitting core writes from its OWN node (`AetherNode.apply` -> `RabiaNode.apply` -> `RabiaEngine.apply`
/// -> `registerBatch` -> `mergePending`: the batch enters that node's own pending queue; nothing is forwarded to a
/// leader), about 20 writes per second each. A write is acknowledged when it commits.
///
/// - **E1, consensus-only cut:** the inbound fault filter drops Rabia protocol messages across the cut pairs and every
///   `NewBatch` between voters, so a batch spreads only through the proposals themselves (`learnProposedBatch`); SWIM
///   and everything else stay up, so membership does not react.
/// - **E2, realistic cut:** `partitionFrom` on both sides of each cut pair (QUIC and SWIM). Membership reacts; the
///   assertion is about consensus progress whatever it decides.
///
/// Stall: no write acknowledged anywhere for [#STALL_MS] during the cut. The bound is below the membership death
/// window (about 15 s), so E1 measures consensus, not a reconfiguration. The controls are an unfiltered run and a
/// NewBatch-dropped run with no cut, interleaved with the cut cells, and a per-cut-pair dropped-message counter.
@PortBudget
class EmberRabiaPartialPartitionTest {
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
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(120).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(60).seconds();
    private static final long LEADER_BOUND_MS = 90_000L;
    private static final long POLL_MS = 250L;
    /// No acknowledged write anywhere for this long, while a connected majority exists, is a stall.
    private static final long STALL_MS = 10_000L;
    private static final long WRITE_PERIOD_MS = 50L;
    private static final long DRAIN_BOUND_MS = 45_000L;
    private static final long PRE_MS = 5_000L;
    private static final long CUT_MS = 24_000L;
    private static final long POST_MS = 6_000L;
    private static final long CONTROL_CUT_MS = 10_000L;
    private static final long CONTROL_POST_MS = 2_000L;

    private EmberCluster cluster;

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            healAll();
            cluster.stop().await(STOP_BOUND);
        }
    }

    @Test
    @Timeout(1500)
    void partialAndAsymmetricCuts_consensusKeepsCommittingWithDivergentPendingQueues() {
        cluster = EmberTestPorts.startedCluster(PORTS,
                                                basePort -> emberCluster(CLUSTER_SIZE,
                                                                         basePort,
                                                                         basePort + MGMT_OFFSET,
                                                                         basePort + APP_HTTP_OFFSET,
                                                                         "qsync"),
                                                START_BOUND);
        System.out.println("EMBER-LEADER " + awaitAgreedLeader());
        var nodes = cluster.allNodes().stream().sorted(Comparator.comparing(node -> node.self().id())).toList();
        var failures = new ArrayList<String>();

        // C-controls: an unfiltered run, then NewBatch dropped with no cut (the E1 baseline), before any cut.
        record(failures, runCell("E00-clean", List.of(nodes.get(0), nodes.get(2)), () -> {}, 0, CONTROL_CUT_MS, CONTROL_POST_MS, new Drops()));
        record(failures, runConsensusOnly("E0-newbatch-dropped", nodes, List.of(0, 2), Set.of(), CONTROL_CUT_MS, CONTROL_POST_MS));
        // E1: Q1's shape on cores 0-2 (cut 0<->2), then Q3 on all five (cut {0,1}<->{3,4}).
        record(failures, runConsensusOnly("E1-Q1", nodes, List.of(0, 2), edges(nodes, List.of(0), List.of(2), true), CUT_MS, POST_MS));
        record(failures, runConsensusOnly("E1-Q3", nodes, List.of(0, 4), edges(nodes, List.of(0, 1), List.of(3, 4), true), CUT_MS, POST_MS));
        // Interleaved no-cut control between the arms.
        record(failures, runConsensusOnly("E0-newbatch-dropped-again", nodes, List.of(0, 2), Set.of(), CONTROL_CUT_MS, CONTROL_POST_MS));
        // E2: partitionFrom on both sides (membership reacts), Q1's and Q3's pairs.
        record(failures, runCell("E2-Q1", List.of(nodes.get(0), nodes.get(2)), () -> partition(nodes, List.of(0), List.of(2)), PRE_MS, CUT_MS, POST_MS, new Drops(ids(nodes, 0, 2))));
        record(failures, runCell("E2-Q3", List.of(nodes.get(0), nodes.get(4)), () -> partition(nodes, List.of(0, 1), List.of(3, 4)), PRE_MS, CUT_MS, POST_MS, new Drops(ids(nodes, 0, 1, 3, 4))));

        assertThat(failures).as("every cell commits (no stall of %d ms, no failed or unanswered write)", STALL_MS).isEmpty();
    }

    private static Set<NodeId> ids(List<AetherNode> nodes, int... indices) {
        return java.util.Arrays.stream(indices).mapToObj(index -> nodes.get(index).self()).collect(java.util.stream.Collectors.toSet());
    }

    private static void record(List<String> failures, CellResult result) {
        System.out.println(result.line());
        failures.addAll(result.problems());
    }

    // ------------------------------------------------------------------ cells

    private CellResult runConsensusOnly(String cell, List<AetherNode> nodes, List<Integer> submitters, Set<Edge> cutEdges, long cutMs, long postMs) {
        var drops = new Drops(cutEdges.stream().map(Edge::to).collect(java.util.stream.Collectors.toSet()));

        return runCell(cell, submitters.stream().map(nodes::get).toList(), () -> installConsensusFilter(nodes, cutEdges, drops), PRE_MS, cutMs, postMs, drops, cutEdges);
    }

    private CellResult runCell(String cell, List<AetherNode> submitters, Runnable cut, long preMs, long cutMs, long postMs, Drops drops) {
        return runCell(cell, submitters, cut, preMs, cutMs, postMs, drops, Set.of());
    }

    private CellResult runCell(String cell, List<AetherNode> submitters, Runnable cut, long preMs, long cutMs, long postMs, Drops drops, Set<Edge> cutEdges) {
        healAll();
        var workload = new Workload(cell, submitters);
        var started = System.nanoTime();

        workload.start();
        sleep(Math.max(preMs, 1_000L));
        var cutStart = System.nanoTime();

        cut.run();
        var filterDropsBefore = filterDrops();

        sleep(cutMs);
        var cutEnd = System.nanoTime();
        var filterDropsAfter = filterDrops();
        var connected = cluster.allNodes().stream().map(node -> node.self().id() + "=" + node.connectedNodeCount()).toList();

        healAll();
        sleep(postMs);
        workload.stop();
        workload.awaitDrained();

        return workload.summarize(started, cutStart, cutEnd, drops, cutEdges, connected, deltas(filterDropsBefore, filterDropsAfter));
    }

    /// E1 filter on every node: all NewBatch dropped; Rabia protocol messages dropped across the given directed edges.
    private void installConsensusFilter(List<AetherNode> nodes, Set<Edge> cutEdges, Drops drops) {
        for (var node : nodes) {
            var self = node.self();
            var blockedSources = new HashSet<NodeId>();

            cutEdges.stream().filter(edge -> edge.to().equals(self)).forEach(edge -> blockedSources.add(edge.from()));
            node.setInboundFaultFilter((peer, message) -> admit(drops, peer, self, blockedSources, message));
        }
    }

    private static boolean admit(Drops drops, NodeId peer, NodeId self, Set<NodeId> blockedSources, Message.Wired message) {
        if (message instanceof NewBatch<?>) {
            drops.newBatch().incrementAndGet();
            return false;
        }
        if (blockedSources.contains(peer) && message instanceof RabiaProtocolMessage) {
            drops.perEdge().computeIfAbsent(new Edge(peer, self), _ -> new AtomicLong()).incrementAndGet();
            return false;
        }
        return true;
    }

    /// Both directions of every pair across the two groups.
    private static Set<Edge> edges(List<AetherNode> nodes, List<Integer> left, List<Integer> right, boolean mutual) {
        var edges = new HashSet<Edge>();

        left.forEach(l -> right.forEach(r -> {
            edges.add(new Edge(nodes.get(l).self(), nodes.get(r).self()));
            if (mutual) {
                edges.add(new Edge(nodes.get(r).self(), nodes.get(l).self()));
            }
        }));
        return edges;
    }

    /// E2: `partitionFrom` on both sides of every pair.
    private static void partition(List<AetherNode> nodes, List<Integer> left, List<Integer> right) {
        left.forEach(l -> nodes.get(l).partitionFrom(right.stream().map(r -> nodes.get(r).self()).collect(java.util.stream.Collectors.toSet())));
        right.forEach(r -> nodes.get(r).partitionFrom(left.stream().map(l -> nodes.get(l).self()).collect(java.util.stream.Collectors.toSet())));
    }

    /// The transport's own count of inbound messages dropped by the fault filter, per node: the E2 witness that the cut is real.
    private Map<NodeId, Long> filterDrops() {
        var counts = new ConcurrentHashMap<NodeId, Long>();

        cluster.allNodes().forEach(node -> counts.put(node.self(), node.transportMetrics().getOrDefault("quic_inbound_fault_filter_drops_total", 0L).longValue()));
        return counts;
    }

    private static Map<NodeId, Long> deltas(Map<NodeId, Long> before, Map<NodeId, Long> after) {
        var delta = new ConcurrentHashMap<NodeId, Long>();

        after.forEach((node, count) -> delta.put(node, count - before.getOrDefault(node, 0L)));
        return delta;
    }

    private void healAll() {
        cluster.allNodes().forEach(node -> {
            node.partitionFrom(Set.of());
            node.setInboundFaultFilter((_, _) -> true);
        });
    }

    private String awaitAgreedLeader() {
        var deadline = System.currentTimeMillis() + LEADER_BOUND_MS;

        while (System.currentTimeMillis() < deadline) {
            var leader = cluster.currentLeader();

            if (leader.isPresent() && cluster.allNodes().stream().allMatch(node -> node.leader().map(NodeId::id).or("none").equals(leader.or("none")))) {
                return leader.or("none");
            }
            sleep(POLL_MS);
        }
        throw new AssertionError("no leader every node agrees on within " + LEADER_BOUND_MS + " ms");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted", e);
        }
    }

    // ------------------------------------------------------------------ measurement

    private record Edge(NodeId from, NodeId to) {}

    /// Filter-side counters plus the nodes that must show transport-level fault-filter drops during the cut.
    private record Drops(AtomicLong newBatch, Map<Edge, AtomicLong> perEdge, Set<NodeId> mustDrop) {
        Drops() {
            this(Set.of());
        }

        Drops(Set<NodeId> mustDrop) {
            this(new AtomicLong(), new ConcurrentHashMap<>(), mustDrop);
        }
    }

    private record Ack(long atNanos, long latencyNanos, int submitter) {}

    private record CellResult(String line, List<String> problems) {}

    /// Writes from each submitter's own node at about 20 per second, one unique no-op key per write.
    private static final class Workload {
        private final String cell;
        private final List<AetherNode> submitters;
        private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(2);
        private final ConcurrentLinkedQueue<Ack> acks = new ConcurrentLinkedQueue<>();
        private final ConcurrentLinkedQueue<String> failed = new ConcurrentLinkedQueue<>();
        private final AtomicInteger submitted = new AtomicInteger();
        private final AtomicInteger[] sequence;

        Workload(String cell, List<AetherNode> submitters) {
            this.cell = cell;
            this.submitters = submitters;
            this.sequence = submitters.stream().map(_ -> new AtomicInteger()).toArray(AtomicInteger[]::new);
        }

        void start() {
            for (int index = 0; index < submitters.size(); index++) {
                var submitter = index;

                executor.scheduleAtFixedRate(() -> write(submitter), 0, WRITE_PERIOD_MS, TimeUnit.MILLISECONDS);
            }
        }

        void stop() {
            executor.shutdownNow();
        }

        private void write(int submitter) {
            var sent = System.nanoTime();
            var key = AetherKey.StreamPartitionOwnershipKey.streamPartitionOwnershipKey("qs-" + cell + "-" + submitter, sequence[submitter].getAndIncrement());

            submitted.incrementAndGet();
            submitters.get(submitter)
                      .<Object> apply(List.of(new KVCommand.Noop<AetherKey>(key)))
                      .onSuccess(_ -> acks.add(new Ack(System.nanoTime(), System.nanoTime() - sent, submitter)))
                      .onFailure(cause -> failed.add("submitter " + submitter + ": " + cause.message()));
        }

        void awaitDrained() {
            var deadline = System.currentTimeMillis() + DRAIN_BOUND_MS;

            while (System.currentTimeMillis() < deadline && acks.size() + failed.size() < submitted.get()) {
                sleep(POLL_MS);
            }
        }

        CellResult summarize(long started, long cutStart, long cutEnd, Drops drops, Set<Edge> cutEdges, List<String> connected, Map<NodeId, Long> filterDelta) {
            var all = new ArrayList<>(acks);
            var gap = maxGapMs(all, cutStart, cutEnd);
            var perSubmitterGap = new ArrayList<String>();

            for (int index = 0; index < submitters.size(); index++) {
                var own = index;

                perSubmitterGap.add("voter%d=%dms".formatted(own, maxGapMs(all.stream().filter(ack -> ack.submitter() == own).toList(), cutStart, cutEnd)));
            }
            var problems = new ArrayList<String>();
            var unanswered = submitted.get() - all.size() - failed.size();

            if (gap >= STALL_MS) {
                problems.add("STALL cell=%s: no write acknowledged for %d ms during the cut (bound %d ms)".formatted(cell, gap, STALL_MS));
            }
            if (!failed.isEmpty()) {
                problems.add("cell=%s: %d write(s) failed, first: %s".formatted(cell, failed.size(), failed.peek()));
            }
            if (unanswered > 0) {
                problems.add("cell=%s: %d write(s) still unanswered %d ms after the workload stopped".formatted(cell, unanswered, DRAIN_BOUND_MS));
            }
            cutEdges.forEach(edge -> {
                if (drops.perEdge().getOrDefault(edge, new AtomicLong()).get() <= 0) {
                    problems.add("C1 instrument: cell=%s cut edge %s->%s dropped no Rabia message".formatted(cell, edge.from().id(), edge.to().id()));
                }
            });
            drops.mustDrop().forEach(node -> {
                if (filterDelta.getOrDefault(node, 0L) <= 0) {
                    problems.add("C1 instrument: cell=%s node %s showed no transport fault-filter drop during the cut".formatted(cell, node.id()));
                }
            });
            var line = ("EMBER-CELL cell=%s submitters=%s submitted=%d acked=%d failed=%d unanswered=%d rate(before=%.1f during=%.1f after=%.1f)/s "
                        + "maxGapMs(union)=%d perSubmitter=%s medianLatencyMs=%d newBatchDropped=%d cutEdgeDrops=%s connectedPeers=%s transportFilterDropsDuringCut=%s")
                .formatted(cell, submitters.stream().map(node -> node.self().id()).toList(), submitted.get(), all.size(), failed.size(), unanswered,
                           rate(all, started, cutStart), rate(all, cutStart, cutEnd), rate(all, cutEnd, System.nanoTime()), gap, perSubmitterGap,
                           medianLatencyMs(all), drops.newBatch().get(), drops.perEdge().entrySet().stream().map(e -> e.getKey().from().id() + "->" + e.getKey().to().id() + "=" + e.getValue().get()).sorted().toList(),
                           connected, new java.util.TreeMap<>(filterDelta.entrySet().stream().collect(java.util.stream.Collectors.toMap(e -> e.getKey().id(), Map.Entry::getValue))));
            return new CellResult(line, problems);
        }

        private static double rate(List<Ack> acks, long fromNanos, long toNanos) {
            var seconds = Math.max(1e-3, (toNanos - fromNanos) / 1e9);

            return acks.stream().filter(ack -> ack.atNanos() >= fromNanos && ack.atNanos() < toNanos).count() / seconds;
        }

        private static long medianLatencyMs(List<Ack> acks) {
            var sorted = acks.stream().map(Ack::latencyNanos).sorted().toList();

            return sorted.isEmpty() ? -1 : sorted.get(sorted.size() / 2) / 1_000_000L;
        }

        /// Longest span inside [from, to] with no acknowledged write, including the spans at both ends.
        private static long maxGapMs(List<Ack> acks, long fromNanos, long toNanos) {
            var times = acks.stream().map(Ack::atNanos).filter(at -> at >= fromNanos && at <= toNanos).sorted().toList();
            var previous = fromNanos;
            var max = 0L;

            for (var at : times) {
                max = Math.max(max, at - previous);
                previous = at;
            }
            return Math.max(max, toNanos - previous) / 1_000_000L;
        }
    }
}
