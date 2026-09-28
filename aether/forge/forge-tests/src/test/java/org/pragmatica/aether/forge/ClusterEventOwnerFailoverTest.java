// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.pragmatica.aether.api.ClusterEvent;
import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.lang.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// #1640 live path: cluster events raised while the cluster-events partition's owner dies are not lost.
///
/// Two surviving non-owner nodes raise uniquely tagged events every 50 ms. Mid-stream the partition-0 owner is
/// killed (phase 1), and then the leader (phase 2, the ticket's scenario, whether or not it owns the partition).
/// Afterwards every tag must be in the event log exactly once, or be counted as dropped by the node that raised
/// it: sent = landed + counted drops, and nothing landed twice.
///
/// It also measures the two things the #1640 design could not settle by reading: which failure a publish to a
/// dead owner surfaces as, and whether an unknown outcome that landed anyway is ever re-sent into a duplicate.
/// Both are logged as numbers (`FAILOVER-PROBE`).
///
/// Five cores, so two kills keep a quorum of three.
@Execution(ExecutionMode.SAME_THREAD)
class ClusterEventOwnerFailoverTest {
    private static final Logger log = LoggerFactory.getLogger(ClusterEventOwnerFailoverTest.class);
    private static final String TAG = "failover-probe-";
    private static final Duration SETTLE = Duration.ofSeconds(120);
    private static final Duration LANDING = Duration.ofSeconds(90);
    private static final Duration COLD_BOOT_MARGIN = Duration.ofSeconds(45);

    /// Registered in `TEST_PORT_ALLOCATION.md`: cluster 24210-24214, management 24230-24234, app HTTP
    /// 24250-24254, SWIM UDP 24310-24314.
    private final EmberCluster cluster = EmberCluster.emberCluster(5, 24210, 24230, 24250, "cevent");
    private final AtomicInteger sequence = new AtomicInteger();

    @AfterEach
    void stop() {
        LifecycleAwait.bestEffort("stop cluster-event failover cluster", cluster, cluster.stop());
    }

    @Test
    void eventsRaisedAcrossOwnerAndLeaderDeaths_eachLandOnceOrAreCountedDropped() {
        LifecycleAwait.settled("start cluster-event failover cluster", cluster, cluster.start());
        await().atMost(SETTLE).until(() -> owner().isPresent() && cluster.currentLeader().isPresent());
        await().atMost(SETTLE).until(this::clusterEventsReadable);
        // Past SWIM's cold-boot phase, in which a never-HEALTHY peer's death is reported as UNKNOWN, not FAULTY.
        await().pollDelay(COLD_BOOT_MARGIN).atMost(COLD_BOOT_MARGIN.plusSeconds(5)).until(() -> true);

        var ownerId = owner().unwrap();
        var phase1 = raiseWhileKilling(ownerId, "phase1");

        await().atMost(SETTLE).until(() -> owner().isPresent() && cluster.currentLeader()
                                                                         .filter(id -> !id.equals(ownerId))
                                                                         .isPresent());

        var leaderId = cluster.currentLeader()
                              .unwrap();

        var phase2 = raiseWhileKilling(leaderId, "phase2");
        var sent = new ArrayList<String>(phase1.sent());

        sent.addAll(phase2.sent());

        var producers = Set.copyOf(concat(phase1.producers(), phase2.producers()));

        await().atMost(LANDING)
               .pollInterval(Duration.ofSeconds(2))
               .until(() -> allWaitingDrained(producers));

        var landed = landedTags();
        var dropped = producers.stream()
                               .mapToLong(id -> droppedOn(id))
                               .sum();

        report(ownerId, leaderId, producers, sent, landed, dropped);

        assertThat(landed.values()).as("no tag landed twice as read").allMatch(count -> count == 1L);
        assertThat(landed.keySet()).as("every landed tag was sent").isSubsetOf(Set.copyOf(sent));
        assertThat((long) landed.size() + dropped).as("sent = landed + counted drops (sent %d, landed %d, dropped %d)",
                                                      sent.size(),
                                                      landed.size(),
                                                      dropped)
                                                  .isEqualTo(sent.size());
    }

    private record Phase(List<String> sent, List<String> producers) {}

    /// Two nodes that are neither the victim nor the leader raise tagged events every 50 ms for 12 s; the victim
    /// is killed 3 s in.
    private Phase raiseWhileKilling(String victim, String phase) {
        var leader = cluster.currentLeader()
                            .or("");
        var producers = cluster.allNodes()
                               .stream()
                               .map(node -> node.self()
                                                .id())
                               .filter(id -> !id.equals(victim) && !id.equals(leader))
                               .limit(2)
                               .toList();
        var clocks = producers.stream()
                              .collect(Collectors.toMap(Function.identity(),
                                                        id -> HlcClock.hlcClock(new NodeId("probe-" + phase + "-" + id))));
        var sent = new ArrayList<String>();
        var start = System.nanoTime();
        var killed = false;

        log.info("FAILOVER-PROBE {}: victim={} leader={} owner={} producers={}", phase, victim, leader, owner().or("?"), producers);
        while (System.nanoTime() - start < Duration.ofSeconds(12).toNanos()) {
            if (!killed && System.nanoTime() - start >= Duration.ofSeconds(3).toNanos()) {
                LifecycleAwait.nodeSettled("kill " + victim, cluster, cluster.killNode(victim));
                killed = true;
            }
            for (var producer : producers) {
                var tag = TAG + phase + "-" + sequence.incrementAndGet();

                cluster.getNode(producer)
                       .onPresent(node -> node.eventAggregator()
                                              .emitLocal(new ClusterEvent.AlertInjected(clocks.get(producer)
                                                                                              .now(),
                                                                                        ClusterEvent.Severity.INFO,
                                                                                        tag,
                                                                                        Map.of("probe", tag))));
                sent.add(tag);
            }
            sleep(50);
        }

        return new Phase(sent, producers);
    }

    private Option<String> owner() {
        return Option.from(cluster.allNodes()
                                                      .stream()
                                                      .filter(node -> node.eventAggregator()
                                                                          .isClusterEventsOwner())
                                                      .map(node -> node.self()
                                                                       .id())
                                                      .findFirst());
    }

    private boolean clusterEventsReadable() {
        return cluster.allNodes()
                      .stream()
                      .findFirst()
                      .map(node -> node.eventAggregator()
                                       .events()
                                       .await()
                                       .isSuccess())
                      .orElse(false);
    }

    private boolean allWaitingDrained(Set<String> producers) {
        return producers.stream()
                        .allMatch(id -> cluster.getNode(id)
                                               .map(node -> node.eventAggregator()
                                                                .redeliveryWaiting() == 0)
                                               .or(true));
    }

    private long droppedOn(String id) {
        return cluster.getNode(id)
                      .map(node -> node.eventAggregator()
                                       .redeliveryDropped()
                                       .values()
                                       .stream()
                                       .mapToLong(Long::longValue)
                                       .sum())
                      .or(0L);
    }

    /// Tag → times it appears in the log as read (after `events()` removes duplicates by `at`).
    private Map<String, Long> landedTags() {
        return cluster.allNodes()
                      .getFirst()
                      .eventAggregator()
                      .events()
                      .await()
                      .map(events -> events.stream()
                                           .filter(event -> event.summary()
                                                                 .startsWith(TAG))
                                           .collect(Collectors.groupingBy(ClusterEvent::summary, Collectors.counting())))
                      .or(Map.of());
    }

    private void report(String ownerId,
                        String leaderId,
                        Set<String> producers,
                        List<String> sent,
                        Map<String, Long> landed,
                        long dropped) {
        var reader = cluster.allNodes()
                            .getFirst()
                            .eventAggregator();

        log.info("FAILOVER-PROBE RESULT: killedOwner={} killedLeader={} sent={} landed={} dropped={} "
                 + "duplicatesRemovedOnRead={}",
                 ownerId,
                 leaderId,
                 sent.size(),
                 landed.size(),
                 dropped,
                 reader.lastReadDuplicates());
        producers.forEach(id -> cluster.getNode(id)
                                       .map(AetherNode::eventAggregator)
                                       .onPresent(aggregator -> log.info("FAILOVER-PROBE producer {}: counters={} failuresByCause={} dropped={}",
                                                                         id,
                                                                         aggregator.redeliveryCounters(),
                                                                         aggregator.redeliveryFailuresByCause(),
                                                                         aggregator.redeliveryDropped())));
    }

    private static List<String> concat(List<String> first, List<String> second) {
        var all = new ArrayList<>(first);

        all.addAll(second);

        return all;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
