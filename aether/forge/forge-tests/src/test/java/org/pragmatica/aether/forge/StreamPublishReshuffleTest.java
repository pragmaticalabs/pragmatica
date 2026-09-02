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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// #430 — publish-under-ownership-reshuffle stream chaos test (builds on the #429 `test-stream-multipart`
/// fixture). A background publisher streams events at a sustained rate through a stable node while the
/// partition-0 HRW OWNER is killed mid-stream, forcing an ownership reshuffle. After the reshuffle the
/// whole log is drained and reconciled against the set of ACKED publishes.
///
/// ## Precise guarantee tested
/// **Every publish ACKED under `min-sync-replicas=2` remains readable, uniquely offset, and
/// per-partition-ordered across a single owner-kill reshuffle.** Concretely, on the final drain:
///
///   1. **Acked-write durability.** Every seq whose publish returned an ACK (min-sync-2: the owner
///      plus one in-sync replica confirmed, so the write was on >=2 nodes) is present in the read-back.
///      Killing ONE node leaves >=1 copy of every acked write, so none is lost.
///   2. **No duplication.** No seq appears twice across the whole log; within each partition offsets
///      are a contiguous run (no duplicate or skipped offset).
///   3. **Per-partition order preserved.** Within each partition the embedded seqs strictly increase
///      with offset — the reshuffle does not reorder a partition's committed suffix.
///
/// UNACKED in-flight publishes MAY be lost — a min-sync-2 write to a partition that momentarily lost a
/// replica-set member cannot be acked until RF is restored, so those publishes fail fast (short client
/// timeout) and are NOT asserted to survive. The publisher is serial (one in-flight publish), so a
/// partition's ACKED events are appended strictly in publish order.
///
/// ## Reshuffle trigger: kill, not add (documented decision)
/// The reshuffle is driven by an owner KILL (in-sync replica promotion) — the lossless-read-failover
/// mechanism [AbstractStreamOwnerFailover] proves HARD for a single partition. An add-node-driven
/// ownership MIGRATION to a fresh empty owner is the separate RF-restoration / backfill path, gated by
/// [StreamOwnerFailoverPinnedTest] (#491); under DEFAULT membership it can stall on the still-open #498
/// (SWIM false-removal-under-churn), so exercising it here would couple this durability assertion to an
/// unrelated open residual. The kill path exercises a real ownership reshuffle while keeping the acked
/// -durability guarantee decidable under default membership.
@Tag("Heavy")
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StreamPublishReshuffleTest extends AbstractMultiPartitionStream {
    private static final int KILL_PARTITION = 0;
    private static final int PRE_KILL_ACKS = 20;
    private static final int POST_KILL_ACKS = 16;
    private static final Duration PUBLISH_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration ACK_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration POST_KILL_TIMEOUT = Duration.ofSeconds(180);
    private static final long PUBLISH_PACE_NANOS = Duration.ofMillis(1).toNanos();

    @Override
    int basePort() {
        return 17000;
    }

    @Override
    int baseMgmtPort() {
        return 17100;
    }

    @Override
    int baseAppHttpPort() {
        return 17200;
    }

    @Override
    String nodePrefix() {
        return "mpr";
    }

    @Override
    String blueprintId() {
        return "forge.test:stream-reshuffle:1.0.0";
    }

    @Test
    void sustainedPublish_duringOwnerKillReshuffle_everyAckedOffsetSurvivesUniqueAndOrdered() {
        await().atMost(PLACEMENT_TIMEOUT).pollInterval(POLL_INTERVAL).until(this::allPartitionsPlaced);

        var killTarget = ownerId(KILL_PARTITION);
        assertThat(killTarget)
            .describedAs("partition %d owner identified before the kill", KILL_PARTITION)
            .isNotBlank();

        // Publish through a node that is NOT the kill target, so the ingress + forwarding path stays up
        // across the reshuffle (only the target partition's write path is disrupted).
        var publishPort = appPortForNodeOtherThan(killTarget);
        var acked = ConcurrentHashMap.<Long>newKeySet();
        var stop = new AtomicBoolean(false);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var publisher = CompletableFuture.runAsync(() -> publishLoop(publishPort, acked, stop), executor);

            // Pre-kill: let a healthy batch of acked writes accumulate across all four partitions.
            await().atMost(ACK_TIMEOUT).pollInterval(POLL_INTERVAL).until(() -> acked.size() >= PRE_KILL_ACKS);
            var preKillAcked = acked.size();

            // Kill the partition-0 owner MID-STREAM and wait for the partition to re-resolve to a new owner.
            cluster.killNode(killTarget).await();
            await().atMost(FAILOVER_TIMEOUT).pollInterval(POLL_INTERVAL).until(() -> ownerReResolved(KILL_PARTITION, killTarget));

            // Keep publishing across/after the reshuffle until a further batch of writes is acked.
            await().atMost(POST_KILL_TIMEOUT).pollInterval(POLL_INTERVAL).until(() -> acked.size() >= preKillAcked + POST_KILL_ACKS);

            stop.set(true);
            publisher.join();
        }

        var ackedSnapshot = List.copyOf(acked);
        // The promoted owner (and unaffected owners) must serve every acked write; poll until the
        // read-back covers the full acked set before the final reconciliation.
        await().atMost(FAILOVER_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> readbackSeqs(publishPort).containsAll(ackedSnapshot));

        var partitionEvents = drainAllPartitions(publishPort);
        assertAckedSurviveUniqueAndOrdered(partitionEvents, ackedSnapshot);
    }

    // --- publisher ----------------------------------------------------------

    /// Serial sustained publisher: publish monotonically increasing seqs (short client timeout so a
    /// wedged partition's write fails fast instead of stalling the loop), recording every ACKED seq,
    /// until `stop` is set. One in-flight publish at a time keeps each partition's acked suffix in
    /// publish order.
    private void publishLoop(int port, Set<Long> acked, AtomicBoolean stop) {
        var seq = 0L;

        while (!stop.get()) {
            if (publish(port, seq, PUBLISH_TIMEOUT)) {
                acked.add(seq);
            }

            seq++;
            LockSupport.parkNanos(PUBLISH_PACE_NANOS);
        }
    }

    // --- reshuffle helpers --------------------------------------------------

    private int appPortForNodeOtherThan(String excludedNodeId) {
        var excludedPort = appPortFor(excludedNodeId);

        return cluster.getAvailableAppHttpPorts()
                      .stream()
                      .filter(port -> port != excludedPort)
                      .findFirst()
                      .orElseThrow(() -> new AssertionError("no ready app-http port other than node " + excludedNodeId));
    }

    private boolean ownerReResolved(int partition, String oldOwner) {
        var owner = ownerId(partition);

        return !owner.isBlank() && !owner.equals(oldOwner);
    }

    // --- reconciliation -----------------------------------------------------

    private Set<Long> readbackSeqs(int port) {
        var seqs = new HashSet<Long>();

        drainAllPartitions(port).forEach(events -> events.forEach(event -> seqs.add(event.seq())));

        return seqs;
    }

    private void assertAckedSurviveUniqueAndOrdered(List<List<Event>> partitionEvents, List<Long> acked) {
        for (int partition = 0; partition < PARTITIONS; partition++) {
            assertPerPartitionOrdered(partitionEvents.get(partition), partition);
        }

        var allSeqs = new ArrayList<Long>();

        partitionEvents.forEach(events -> events.forEach(event -> allSeqs.add(event.seq())));

        var uniqueSeqs = new HashSet<>(allSeqs);

        assertThat(uniqueSeqs)
            .describedAs("no event is duplicated across the log (each seq appears at most once)")
            .hasSize(allSeqs.size());
        assertThat(uniqueSeqs)
            .describedAs("every ACKED publish (min-sync-2, %d acked) survives the owner-kill reshuffle", acked.size())
            .containsAll(acked);
        assertThat(acked)
            .describedAs("acked writes accumulated both before and after the kill")
            .hasSizeGreaterThanOrEqualTo(PRE_KILL_ACKS + POST_KILL_ACKS);
    }
}
