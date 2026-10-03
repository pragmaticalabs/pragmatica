// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.forge;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// #1339 — after a partition's owner dies, a WRITE to that partition succeeds again. The owner FIELD re-resolving
/// is not that: ownership is a committed record, written only by the leader, and the cloud run that filed the
/// ticket (rc4 cluster B, SIGKILL of the owner of partition 0) refused writes with "committed owner is
/// hetzner-eu-core-1" for at least 35 s while `Failover completed` — which gates on the owner field — passed.
///
/// The kill target is the cluster LEADER when it owns a partition, because that is the shape where the removal
/// decision's reconcile pass runs with no live leader and writes nothing (the ownership writer is leader-only), and
/// only a leadership change can run the pass again. When the leader owns none of the four partitions the target is
/// partition 0's owner, which is the ticket's own scenario; the test logs which of the two ran, so a green run can
/// be read for what it examined.
///
/// The assertion is per partition, from a read-back: every partition carries an event published AFTER the kill
/// and acked. A partition whose owner is dead and never replaced refuses every publish routed to it, so its
/// post-kill suffix stays empty and the bound expires.
///
/// Publishing is serial, paced and bounded by [#PUBLISH_TIMEOUT], with one connection in flight: a stall costs
/// requests proportional to the bound, not an unbounded number of sockets (#1553 is why the sustained-publish
/// variant is disabled).
///
/// Ember equivalence: the kill is [org.pragmatica.aether.ember.EmberCluster#killNode] (`node.stop()`, a SWIM
/// leave), not a SIGKILL — it exercises the leaderless window and the committed-record rewrite, not failure-detection
/// latency.
@Tag("Heavy")
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StreamOwnerKillWritesResumeTest extends AbstractMultiPartitionStream {
    private static final Duration PUBLISH_TIMEOUT = Duration.ofSeconds(5);
    private static final long PUBLISH_PACE_NANOS = Duration.ofMillis(250).toNanos();
    private static final int PRE_KILL_EVENTS = 8;
    private static final long FIRST_POST_KILL_SEQ = 1_000_000L;

    @Override
    int basePort() {
        return 7700;
    }

    @Override
    int baseMgmtPort() {
        return 7740;
    }

    @Override
    int baseAppHttpPort() {
        return 7770;
    }

    @Override
    String nodePrefix() {
        return "okw";
    }

    @Override
    String blueprintId() {
        return "forge.test:stream-owner-kill-writes:1.0.0";
    }

    @Test
    void ownerKilled_everyPartitionAcceptsAWriteAgain() {
        await().atMost(PLACEMENT_TIMEOUT).pollInterval(POLL_INTERVAL).until(this::allPartitionsPlaced);

        var leader = cluster.currentLeader().or("");
        var ownedByLeader = IntStream.range(0, PARTITIONS).filter(partition -> ownerId(partition).equals(leader)).boxed().toList();
        var target = ownedByLeader.isEmpty() ? ownerId(0) : leader;
        var survivorPort = portOtherThan(target);

        assertThat(target).describedAs("a kill target identified before the kill").isNotBlank();
        System.getLogger(getClass().getName())
              .log(System.Logger.Level.INFO,
                   "#1339 kill target {0}: leader={1}, partitions owned by the leader={2} ({3})",
                   target,
                   leader,
                   ownedByLeader,
                   ownedByLeader.isEmpty() ? "leader owns none: partition 0's owner is killed instead"
                                           : "leader owns a partition: the leaderless-removal shape");

        // Control, taken before the kill: every partition accepts and serves a write, so a partition empty of
        // post-kill events afterwards is the kill's doing and not an unwritable fixture.
        for (var seq = 0L; seq < PRE_KILL_EVENTS; seq++) {
            assertThat(publish(survivorPort, seq, PUBLISH_TIMEOUT)).describedAs("pre-kill publish %d acked", seq).isTrue();
        }
        assertThat(partitionsCarryingSeqAtOrAbove(survivorPort, 0L)).describedAs("control: every partition holds a pre-kill event")
                                                                    .hasSize(PARTITIONS);

        LifecycleAwait.nodeBestEffort("kill node " + target + " in ownerKilled_everyPartitionAcceptsAWriteAgain()",
                                      cluster,
                                      cluster.killNode(target, false));

        var seq = FIRST_POST_KILL_SEQ;
        var deadline = deadline(FAILOVER_TIMEOUT);
        var served = new HashSet<Integer>();

        while (System.nanoTime() < deadline && served.size() < PARTITIONS) {
            for (var i = 0; i < PARTITIONS; i++) {
                publish(survivorPort, seq++, PUBLISH_TIMEOUT);
                LockSupport.parkNanos(PUBLISH_PACE_NANOS);
            }
            served.clear();
            served.addAll(partitionsCarryingSeqAtOrAbove(survivorPort, FIRST_POST_KILL_SEQ));
        }

        assertThat(served).describedAs("#1339: every partition accepted a write after the owner of %s was killed (owners now: %s)",
                                       target,
                                       owners())
                          .hasSize(PARTITIONS);
    }

    private List<String> owners() {
        var owners = new ArrayList<String>();

        for (var partition = 0; partition < PARTITIONS; partition++) {
            owners.add(partition + "=" + ownerId(partition));
        }

        return owners;
    }

    /// Partitions whose read-back holds an event with `seq >= from`.
    private Set<Integer> partitionsCarryingSeqAtOrAbove(int port, long from) {
        var partitions = new HashSet<Integer>();

        for (var partition = 0; partition < PARTITIONS; partition++) {
            if (drainPartition(port, partition).stream().anyMatch(event -> event.seq() >= from)) {
                partitions.add(partition);
            }
        }

        return partitions;
    }

    private int portOtherThan(String nodeId) {
        var excluded = appPortFor(nodeId);

        return cluster.getAvailableAppHttpPorts()
                      .stream()
                      .filter(port -> port != excluded)
                      .findFirst()
                      .orElseThrow(() -> new AssertionError("no ready app-http port other than node " + nodeId));
    }
}
