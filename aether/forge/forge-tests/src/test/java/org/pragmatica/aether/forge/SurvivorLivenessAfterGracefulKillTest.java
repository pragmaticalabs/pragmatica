// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.aether.ember.EmberCluster.emberCluster;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.awaitility.Awaitility.await;


/// #498 — the membership-layer guarantee a single graceful departure must not break: after ONE node
/// of five leaves cleanly, the four LIVE survivors stay mutually reachable and counted for the whole
/// post-departure window. The ticket's evidence (gate-run-1, 2026-07-19) was the opposite: the
/// transient QuorumLost→PASSIVE window evicted quiet survivor↔survivor links, SWIM held a live
/// survivor DEAD-stuck (`swimDeadStuck=[sof-1]`), the dial layer withheld the re-dial for up to the
/// 60s higher-id grace, and with auto-heal OFF the false removals cascaded into a quorum-loss
/// self-fence. This test reproduces exactly that configuration — DEFAULT SWIM/hello/split timeouts,
/// auto-heal OFF on every node (the ticket's "worse, not better" case), graceful `killNode` of a
/// NON-leader — and samples every survivor once a second across the whole grace window.
///
/// What is asserted is the guarantee, not the mechanism: (1) the victim leaves counted membership;
/// (2) from that moment on, no survivor ever counts fewer than the four live nodes (a dip is a false
/// removal of a live peer); (3) no survivor's transport view lacks another survivor for longer than
/// one liveness TTL (a longer gap is the withheld re-dial the ticket describes); (4) every survivor is
/// still running at the end (no self-fence). The window is sized past the 60s force-dial grace so a
/// removal that only heals when that grace elapses is still observed.
@Tag("Heavy")
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SurvivorLivenessAfterGracefulKillTest {
    private static final Logger log = LoggerFactory.getLogger(SurvivorLivenessAfterGracefulKillTest.class);
    private static final int SIZE = 5;
    private static final int BASE_PORT = 24500;
    private static final int BASE_MGMT_PORT = 24600;
    private static final int BASE_APP_HTTP_PORT = 24700;

    private static final String PIN_REASON = "#498 probe: auto-heal off so a false removal cannot be masked by a replacement";

    private static final Duration FORM_TIMEOUT = Duration.ofSeconds(240);
    private static final Duration SETTLE = Duration.ofSeconds(20);
    private static final Duration POLL = Duration.ofMillis(500);
    /// SWIM suspicion (10s) + NTT departure (15s), tripled for CI load — same derivation as
    /// `MembershipChaosCycleTest`. A graceful leave should be far faster; the budget is a ceiling.
    private static final Duration DEPARTURE_BUDGET = Duration.ofSeconds(75);
    /// Past `QuicClusterNetwork.RECONCILE_BACKOFF_CAP_MS` (60s) plus a reconcile tick and a margin:
    /// a survivor link that heals only via the higher-id force-dial still shows up as a gap here.
    private static final Duration OBSERVE_WINDOW = Duration.ofSeconds(90);
    private static final Duration SAMPLE_GAP = Duration.ofSeconds(1);
    /// `pingInterval * LIVENESS_TTL_PING_INTERVAL_FACTOR` (5s * 8) — one transport liveness TTL. A
    /// link gap shorter than this is a legitimate evict+re-dial blip; longer is a withheld dial.
    private static final int MAX_LINK_GAP_SAMPLES = 40;

    private EmberCluster cluster;

    @BeforeAll
    void setUp() {
        cluster = emberCluster(SIZE, BASE_PORT, BASE_MGMT_PORT, BASE_APP_HTTP_PORT, "sgk");
        LifecycleAwait.settled("cluster start in setUp()", cluster, cluster.start());
        await().atMost(FORM_TIMEOUT).pollInterval(POLL).until(() -> cluster.currentLeader()
                                                                           .isPresent());
        await().atMost(FORM_TIMEOUT).pollInterval(POLL).until(() -> minCountedAcrossNodes() == SIZE);
        log.info("SGK: {}-node cluster formed, leader={}",
                 SIZE,
                 cluster.currentLeader().or("none"));
    }

    @AfterAll
    void tearDown() {
        Option.option(cluster).onPresent(c -> LifecycleAwait.bestEffort("cluster stop in tearDown()", c, c.stop()));
    }

    @Test
    void gracefulNonLeaderDeparture_keepsEveryLiveSurvivorCountedAndConnected() {
        await().pollDelay(SETTLE).timeout(SETTLE.plusSeconds(10)).until(() -> true);
        pinAutoHealOff();
        var victim = nonLeaderNode();
        var survivors = cluster.allNodes()
                               .stream()
                               .map(AetherNode::self)
                               .filter(id -> !id.id()
                                                .equals(victim))
                               .collect(Collectors.toSet());

        log.info("SGK: leader={} victim={} (graceful stop) survivors={}",
                 cluster.currentLeader().or("none"),
                 victim,
                 ids(survivors));
        var t0 = System.nanoTime();

        LifecycleAwait.nodeSettled("graceful kill of " + victim, cluster, cluster.killNode(victim, true));
        await().atMost(DEPARTURE_BUDGET)
             .pollInterval(POLL)
             .until(() -> survivorsAgree(node -> !node.membershipFsm()
                                                      .coreCountedMembers()
                                                      .contains(new NodeId(victim))));
        log.info("SGK: victim {} left counted membership on every survivor at t+{}ms", victim, elapsedMs(t0));
        var samples = observe(survivors);
        // Logged BEFORE the assertions so a red run carries its own timeline.
        log.info("SGK RESULT: samples={} minCounted={} maxLinkGapSamples={} survivorsNotMemberSamples={} gaps={}",
                 samples.count,
                 samples.minCounted,
                 samples.maxLinkGap,
                 samples.notMemberSamples,
                 samples.gapsByEdge);
        assertThat(samples.minCounted).as("#498: after the graceful departure every survivor must keep counting all %d live survivors "
                                         + "for the whole %ds window — a lower count is a live peer falsely removed",
                                          SIZE - 1,
                                          OBSERVE_WINDOW.toSeconds())
                  .isEqualTo(SIZE - 1);
        assertThat(samples.notMemberSamples).as("#498: a live survivor must never be projected as anything but Member by another survivor "
                                               + "(Suspect/Dead/Departing on a live node is the false-removal signature)")
                  .isZero();
        assertThat(samples.maxLinkGap).as("#498: no survivor may lack a transport link to another survivor for more than one liveness "
                                         + "TTL (%d samples) — a longer gap is the withheld re-dial",
                                          MAX_LINK_GAP_SAMPLES)
                  .isLessThanOrEqualTo(MAX_LINK_GAP_SAMPLES);
        assertThat(cluster.allNodes().stream().map(AetherNode::self).collect(Collectors.toSet())).as("#498: every survivor must still be running (a missing one self-fenced on quorum loss)")
                  .containsExactlyInAnyOrderElementsOf(survivors);
    }

    private record Samples(int count,
                           int minCounted,
                           int maxLinkGap,
                           int notMemberSamples,
                           Map<String, Integer> gapsByEdge) {}

    /// Samples every survivor once per [#SAMPLE_GAP] for [#OBSERVE_WINDOW]: counted-core size,
    /// per-peer projected state, and the transport link set. A link gap is counted per directed edge
    /// as consecutive samples in which the source's `connectedPeerIds()` lacks the target.
    private Samples observe(Set<NodeId> survivors) {
        var deadline = System.nanoTime() + OBSERVE_WINDOW.toNanos();
        var count = 0;
        var minCounted = Integer.MAX_VALUE;
        var notMember = 0;
        var maxGap = 0;
        var running = new HashMap<String, Integer>();
        var worst = new HashMap<String, Integer>();

        while (System.nanoTime() < deadline) {
            count++;
            for (var node : cluster.allNodes()) {
                var counted = node.membershipFsm().coreCountedMembers();
                var states = node.membershipFsm().memberStates();
                var links = node.connectedPeerIds();

                minCounted = Math.min(minCounted, counted.size());
                for (var peer : survivors) {
                    if (peer.equals(node.self())) {
                        continue;
                    }

                    if (!"Member".equals(states.get(peer))) {
                        notMember++;
                        log.warn("SGK: {} projects live survivor {} as {} (sample {})",
                                 node.self().id(),
                                 peer.id(),
                                 states.get(peer),
                                 count);
                    }

                    var edge = node.self().id() + "->" + peer.id();

                    if (links.contains(peer)) {
                        running.put(edge, 0);
                    } else {
                        var gap = running.merge(edge, 1, Integer::sum);

                        worst.merge(edge, gap, Math::max);
                        maxGap = Math.max(maxGap, gap);
                        log.warn("SGK: link {} absent for {} consecutive sample(s)", edge, gap);
                    }
                }
            }

            if (count % 10 == 0) {
                log.info("SGK: sample {} minCounted={} notMember={} maxGap={} counted={}",
                         count,
                         minCounted,
                         notMember,
                         maxGap,
                         cluster.allNodes()
                                .stream()
                                .map(n -> n.self()
                                           .id() + "=" + n.membershipFsm()
                                                          .coreCountedMembers()
                                                          .size())
                                .toList());
            }

            LockSupport.parkNanos(SAMPLE_GAP.toNanos());
        }

        return new Samples(count,
                           minCounted == Integer.MAX_VALUE
                           ? -1
                           : minCounted,
                           maxGap,
                           notMember,
                           worst);
    }

    private void pinAutoHealOff() {
        cluster.allNodes()
               .forEach(node -> node.clusterTopologyManager()
                                    .onPresent(ctm -> ctm.setAutoHealEnabled(false, PIN_REASON)
                                                         .await(TimeSpan.timeSpan(30).seconds())
                                                         .onFailure(cause -> fail("auto-heal pin failed: " + cause.message()))));
    }

    private boolean survivorsAgree(Predicate<AetherNode> condition) {
        return cluster.allNodes()
                      .stream()
                      .allMatch(condition);
    }

    private int minCountedAcrossNodes() {
        return cluster.allNodes()
                      .stream()
                      .mapToInt(node -> node.membershipFsm()
                                            .coreCountedMembers()
                                            .size())
                      .min()
                      .orElse(0);
    }

    private String nonLeaderNode() {
        var leader = cluster.currentLeader().or("");
        var victim = cluster.status()
                            .nodes()
                            .stream()
                            .map(EmberCluster.NodeStatus::id)
                            .filter(id -> !id.equals(leader))
                            .findFirst()
                            .orElse("");

        assertThat(victim).as("a non-leader node to kill").isNotBlank();

        return victim;
    }

    private static long elapsedMs(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }

    private static List<String> ids(Set<NodeId> ids) {
        return ids.stream()
                  .map(NodeId::id)
                  .sorted()
                  .toList();
    }
}
