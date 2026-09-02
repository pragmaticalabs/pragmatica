// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamCompression;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamConfigValue;
import org.pragmatica.aether.stream.StreamPartitionManager.Exhaustion;
import org.pragmatica.aether.stream.StreamPartitionManager.HydrationSnapshot;
import org.pragmatica.aether.stream.StreamPartitionManager.ReplicaCatchupSource.CatchupView;
import org.pragmatica.aether.stream.replication.ReplicaSetController.Role;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.lang.Option;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// #265 increment 5 — reshuffle ring lifecycle: catch-up-gated release, flap debounce, `reshuffle_concurrency`
/// pacing, and the `system:*` budget exemption. Every proof is deterministic and in-JVM: the reconcile tick
/// ({@link StreamPartitionManager#reconcileReshuffle}) is called directly to advance the release state machine
/// and drain the materialization queue, and the placement / catch-up / owner / cluster-size seams are stubs
/// the test controls. Separate file from `StreamHydrationSnapshotTest` per the harness convention (keeps that
/// suite's line baseline stable).
class StreamReshuffleLifecycleTest {

    private static final long HEADER = 64L;
    private static final long INDEX_ENTRY = 24L;
    private static final long SEGMENT = 256 * 1024L;
    private static final int SMALL_COUNT = 100;
    private static final long SMALL_BYTES = 64 * 1024L;
    /// Per-partition floor for the small retention used throughout: 64 + 24×100 + min(256KiB, 64KiB) = 68000.
    private static final long FLOOR = HEADER + INDEX_ENTRY * SMALL_COUNT + Math.min(SEGMENT, SMALL_BYTES);

    private static StreamConfig cfg(String name, int partitions, int replicas) {
        var retention = RetentionPolicy.retentionPolicy(SMALL_COUNT, SMALL_BYTES, 3_600_000L);

        return StreamConfig.streamConfig(name,
                                         partitions,
                                         retention,
                                         "latest",
                                         1_048_576L,
                                         ConsistencyMode.EVENTUAL,
                                         replicas,
                                         0,
                                         StreamCompression.NONE,
                                         Option.none());
    }

    private static ValuePut<StreamConfigKey, StreamConfigValue> configPut(StreamConfig config) {
        var key = StreamConfigKey.streamConfigKey(config.name());
        var value = StreamConfigValue.streamConfigValue(config);

        return new ValuePut<>(new KVCommand.Put<>(key, value), Option.none());
    }

    private static boolean materialized(StreamPartitionManager manager, String stream, int partition) {
        return manager.partitionBuffer(stream, partition).isPresent();
    }

    private static long queueDepth(StreamPartitionManager manager) {
        return manager.hydrationSnapshot().materializeQueueDepth();
    }

    /// Tests 1-5: the release state machine (candidate → debounce → catch-up + owner gate → release). All use a
    /// single-partition stream created as OWNER (so the ring is materialized), the role then flipped to NONE.
    @Nested
    class ReleaseLifecycle {

        @Test
        void roleNone_afterDebounce_releasesWhenCaughtUp_freesBytesAndSnapshot() {
            var manager = streamPartitionManager(64 * 1024 * 1024L);
            var role = new AtomicReference<>(Role.OWNER);
            try {
                manager.placementRoleSupplier((_, _) -> role.get());
                manager.clusterSizeSupplier(() -> 3);
                manager.replicaCatchupSource((_, _) -> new CatchupView(3, true));
                manager.ownerReleaseGuard((_, _) -> true);
                manager.createStream(cfg("s", 1, 1)).onFailure(_ -> fail("create should succeed"));
                assertThat(manager.totalAllocatedBytes()).isEqualTo(FLOOR);

                role.set(Role.NONE);

                manager.reconcileReshuffle();   // tick 1: role NONE observed → candidate, debounce starts
                assertThat(manager.hydrationSnapshot().releaseCandidates()).isEqualTo(1L);
                assertThat(manager.totalAllocatedBytes()).isEqualTo(FLOOR);

                manager.reconcileReshuffle();   // tick 2: still within the 2-tick grace window
                assertThat(manager.totalAllocatedBytes()).isEqualTo(FLOOR);

                manager.reconcileReshuffle();   // tick 3: debounce elapsed + gates pass → release

                var snapshot = manager.hydrationSnapshot();
                assertThat(manager.totalAllocatedBytes()).isEqualTo(0L);
                assertThat(snapshot.releaseCandidates()).isEqualTo(0L);
                assertThat(snapshot.releasedPartitionsSinceBoot()).isEqualTo(1L);
                assertThat(materialized(manager, "s", 0)).isFalse();
            } finally {
                manager.close();
            }
        }

        @Test
        void roleRegainedWithinWindow_cancelsCandidacy_noReleaseNoRematerialize() {
            var manager = streamPartitionManager(64 * 1024 * 1024L);
            var role = new AtomicReference<>(Role.OWNER);
            try {
                manager.placementRoleSupplier((_, _) -> role.get());
                manager.clusterSizeSupplier(() -> 3);
                manager.replicaCatchupSource((_, _) -> new CatchupView(3, true));
                manager.ownerReleaseGuard((_, _) -> true);
                manager.createStream(cfg("s", 1, 1)).onFailure(_ -> fail("create should succeed"));

                role.set(Role.NONE);
                manager.reconcileReshuffle();   // tick 1: candidate
                manager.reconcileReshuffle();   // tick 2: debouncing
                assertThat(manager.hydrationSnapshot().releaseCandidates()).isEqualTo(1L);

                role.set(Role.REPLICA);         // flap back before release eligibility
                manager.reconcileReshuffle();   // tick 3: role regained → candidacy cancelled

                var snapshot = manager.hydrationSnapshot();
                assertThat(manager.totalAllocatedBytes()).isEqualTo(FLOOR);
                assertThat(snapshot.releaseCandidates()).isEqualTo(0L);
                assertThat(snapshot.releasedPartitionsSinceBoot()).isEqualTo(0L);
                assertThat(materialized(manager, "s", 0)).isTrue();
            } finally {
                manager.close();
            }
        }

        @Test
        void catchupGateBlocks_untilRegistryShowsEnoughCaughtUp() {
            var manager = streamPartitionManager(64 * 1024 * 1024L);
            var role = new AtomicReference<>(Role.OWNER);
            var caughtUp = new AtomicReference<>(new CatchupView(2, true));
            try {
                manager.placementRoleSupplier((_, _) -> role.get());
                manager.clusterSizeSupplier(() -> 5);             // effective RF = clamp(3, 1, 5) = 3
                manager.replicaCatchupSource((_, _) -> caughtUp.get());
                manager.ownerReleaseGuard((_, _) -> true);
                manager.createStream(cfg("s", 1, 3)).onFailure(_ -> fail("create should succeed"));

                role.set(Role.NONE);
                manager.reconcileReshuffle();
                manager.reconcileReshuffle();
                manager.reconcileReshuffle();   // past debounce, but only 2 of 3 caught up → HELD
                assertThat(manager.totalAllocatedBytes()).isEqualTo(FLOOR);
                assertThat(manager.hydrationSnapshot().releaseCandidates()).isEqualTo(1L);

                caughtUp.set(new CatchupView(3, true));            // third replica now caught up (>= RF)
                manager.reconcileReshuffle();                     // gate passes → release

                assertThat(manager.totalAllocatedBytes()).isEqualTo(0L);
                assertThat(manager.hydrationSnapshot().releasedPartitionsSinceBoot()).isEqualTo(1L);
            } finally {
                manager.close();
            }
        }

        @Test
        void effectiveRfShrink_usesClampedThreshold_notDeclared() {
            var manager = streamPartitionManager(64 * 1024 * 1024L);
            var role = new AtomicReference<>(Role.OWNER);
            var caughtUp = new AtomicReference<>(new CatchupView(2, true));
            try {
                manager.placementRoleSupplier((_, _) -> role.get());
                // Declared replicas 5, but the cluster shrank to 3 → effective (clamped) RF = 3, NOT 5.
                manager.clusterSizeSupplier(() -> 3);
                manager.replicaCatchupSource((_, _) -> caughtUp.get());
                manager.ownerReleaseGuard((_, _) -> true);
                manager.createStream(cfg("s", 1, 5)).onFailure(_ -> fail("create should succeed"));

                role.set(Role.NONE);
                manager.reconcileReshuffle();
                manager.reconcileReshuffle();
                manager.reconcileReshuffle();   // 2 caught up < clamped RF 3 → HELD (clamping keeps copies safe)
                assertThat(manager.totalAllocatedBytes()).isEqualTo(FLOOR);

                // 3 caught up == clamped RF 3 but < declared 5. Release PROVES the threshold is the clamped 3
                // (declared 5 would still block), so a shrink does not demand copies the cluster cannot host.
                caughtUp.set(new CatchupView(3, true));
                manager.reconcileReshuffle();

                assertThat(manager.totalAllocatedBytes()).isEqualTo(0L);
                assertThat(manager.hydrationSnapshot().releasedPartitionsSinceBoot()).isEqualTo(1L);
            } finally {
                manager.close();
            }
        }

        @Test
        void ownerRule_holdsUntilCommittedOwnershipMoves() {
            var manager = streamPartitionManager(64 * 1024 * 1024L);
            var role = new AtomicReference<>(Role.OWNER);
            var committedElsewhere = new AtomicReference<>(Boolean.FALSE);   // committed owner still self
            try {
                manager.placementRoleSupplier((_, _) -> role.get());
                manager.clusterSizeSupplier(() -> 3);
                manager.replicaCatchupSource((_, _) -> new CatchupView(3, true));   // catch-up gate passes
                manager.ownerReleaseGuard((_, _) -> committedElsewhere.get());
                manager.createStream(cfg("s", 1, 1)).onFailure(_ -> fail("create should succeed"));

                role.set(Role.NONE);
                manager.reconcileReshuffle();
                manager.reconcileReshuffle();
                manager.reconcileReshuffle();   // past debounce + catch-up passes, but owner still self → HELD
                assertThat(manager.totalAllocatedBytes()).isEqualTo(FLOOR);
                assertThat(manager.hydrationSnapshot().releaseCandidates()).isEqualTo(1L);

                committedElsewhere.set(Boolean.TRUE);   // committed ownership record moves to another node
                manager.reconcileReshuffle();

                assertThat(manager.totalAllocatedBytes()).isEqualTo(0L);
                assertThat(manager.hydrationSnapshot().releasedPartitionsSinceBoot()).isEqualTo(1L);
            } finally {
                manager.close();
            }
        }
    }

    /// Test 6: reshuffle_concurrency = 2. At most two REPLICA partitions materialize concurrently; the rest
    /// queue (system first), and a queued app partition needs a slot AND budget headroom (budget-AND).
    @Nested
    class ReshuffleConcurrency {

        @Test
        void atMostTwoInFlight_systemDrainsFirst_budgetAndRespected() {
            // Budget fits exactly 3 rings. Slots = 2. Roles resolve only AFTER a metadata-only hydrate, so the
            // rings materialize through the slot-gated lazy path (not the create-time bulk path).
            var manager = streamPartitionManager(3 * FLOOR);
            var roles = new ConcurrentHashMap<String, Role>();
            var caughtUp = ConcurrentHashMap.<String>newKeySet();
            try {
                manager.placementRoleSupplier((stream, partition) -> roles.getOrDefault(stream + "#" + partition, Role.NONE));
                manager.replicaCatchupSource((stream, partition) -> new CatchupView(Integer.MAX_VALUE,
                                                                                    caughtUp.contains(stream + "#" + partition)));

                manager.onStreamConfigPut(configPut(cfg("app", 8, 1)));
                manager.onStreamConfigPut(configPut(cfg("system:sys", 8, 1)));
                assertThat(manager.totalAllocatedBytes()).isEqualTo(0L);   // both hydrated metadata-only

                roles.put("app#0", Role.REPLICA);
                roles.put("app#1", Role.REPLICA);
                roles.put("app#2", Role.REPLICA);
                roles.put("system:sys#0", Role.REPLICA);

                // Two REPLICA materializations fill both slots.
                manager.materializePartition("app", 0).onFailure(_ -> fail("first slot should materialize"));
                manager.materializePartition("app", 1).onFailure(_ -> fail("second slot should materialize"));
                assertThat(materialized(manager, "app", 0)).isTrue();
                assertThat(materialized(manager, "app", 1)).isTrue();

                // Slots exhausted → app#2 then system:sys#0 both queue (system#0 enqueued LAST).
                manager.materializePartition("app", 2)
                       .onSuccess(_ -> fail("app#2 must be paced, not materialized"))
                       .onFailure(cause -> assertThat(cause).isInstanceOf(StreamError.ReshufflePaced.class));
                manager.materializePartition("system:sys", 0)
                       .onSuccess(_ -> fail("system:sys#0 must be paced, not materialized"))
                       .onFailure(cause -> assertThat(cause).isInstanceOf(StreamError.ReshufflePaced.class));
                assertThat(queueDepth(manager)).isEqualTo(2L);
                assertThat(materialized(manager, "app", 2)).isFalse();
                assertThat(materialized(manager, "system:sys", 0)).isFalse();

                // Free ONE slot (app#0 finished backfill). Drain must pick the SYSTEM partition first, even
                // though app#2 was enqueued earlier.
                caughtUp.add("app#0");
                manager.reconcileReshuffle();
                assertThat(materialized(manager, "system:sys", 0)).isTrue();
                assertThat(materialized(manager, "app", 2)).isFalse();
                assertThat(queueDepth(manager)).isEqualTo(1L);
                assertThat(manager.totalAllocatedBytes()).isEqualTo(3 * FLOOR);   // budget now full

                // Free the second slot (app#1). A slot is now free but the pool is exhausted → app#2 is
                // head-of-line budget-blocked (budget-AND): a slot alone is not enough.
                caughtUp.add("app#1");
                manager.reconcileReshuffle();
                assertThat(materialized(manager, "app", 2)).isFalse();
                assertThat(queueDepth(manager)).isEqualTo(1L);
                assertThat(manager.totalAllocatedBytes()).isEqualTo(3 * FLOOR);
            } finally {
                manager.close();
            }
        }
    }

    /// Test 7: the `system:*` budget exemption and the release-frees-budget → drain-admits trigger.
    @Nested
    class SystemExemptionAndAdmission {

        @Test
        void systemStreamOversubscribes_whileAppDefers() {
            var manager = streamPartitionManager(FLOOR);   // budget = exactly one ring
            var events = new CopyOnWriteArrayList<Exhaustion>();
            var roles = new ConcurrentHashMap<String, Role>();
            try {
                manager.exhaustionSink(events::add);
                manager.placementRoleSupplier((stream, partition) -> roles.getOrDefault(stream + "#" + partition, Role.NONE));

                // An app stream fills the whole budget as OWNER.
                roles.put("occupier#0", Role.OWNER);
                manager.onStreamConfigPut(configPut(cfg("occupier", 1, 1)));
                assertThat(manager.totalAllocatedBytes()).isEqualTo(FLOOR);

                // A system stream over budget MATERIALIZES anyway (oversubscribes) + emits SYSTEM_OVERSUBSCRIBE.
                roles.put("system:crit#0", Role.OWNER);
                manager.onStreamConfigPut(configPut(cfg("system:crit", 1, 1)));
                assertThat(materialized(manager, "system:crit", 0)).isTrue();
                assertThat(manager.totalAllocatedBytes()).isEqualTo(2 * FLOOR);
                assertThat(manager.hydrationSnapshot().overBudget()).isTrue();
                assertThat(events).anyMatch(e -> e.phase() == Exhaustion.Phase.SYSTEM_OVERSUBSCRIBE
                                              && e.streamName().equals("system:crit"));

                // An app stream over budget DEFERS (metadata-only) — the exemption is system-only.
                roles.put("appdefer#0", Role.OWNER);
                manager.onStreamConfigPut(configPut(cfg("appdefer", 1, 1)));
                assertThat(materialized(manager, "appdefer", 0)).isFalse();
                var view = manager.hydrationSnapshot()
                                  .streams()
                                  .stream()
                                  .filter(s -> s.name().equals("appdefer"))
                                  .findFirst()
                                  .orElseThrow();
                assertThat(view.partitionsDeferred()).isEqualTo(1);
                assertThat(view.ringsMaterialized()).isEqualTo(0);
            } finally {
                manager.close();
            }
        }

        @Test
        void releaseFreesBudget_sameTickDrainAdmitsQueuedApp() {
            // Budget fits 3 rings; slots = 2. p0,p1 fill the slots; p3 (OWNER, un-paced) consumes the last
            // floor; p2 queues (a slot was unavailable) and is budget-blocked at drain until p0 releases.
            var manager = streamPartitionManager(3 * FLOOR);
            var roles = new ConcurrentHashMap<String, Role>();
            try {
                manager.placementRoleSupplier((stream, partition) -> roles.getOrDefault(stream + "#" + partition, Role.NONE));
                manager.clusterSizeSupplier(() -> 3);
                manager.replicaCatchupSource((_, _) -> new CatchupView(3, true));   // catch-up gate passes
                manager.ownerReleaseGuard((_, _) -> true);                          // owner rule passes

                manager.onStreamConfigPut(configPut(cfg("app", 8, 1)));

                roles.put("app#0", Role.REPLICA);
                roles.put("app#1", Role.REPLICA);
                roles.put("app#2", Role.REPLICA);
                roles.put("app#3", Role.OWNER);

                manager.materializePartition("app", 0).onFailure(_ -> fail("slot 1"));
                manager.materializePartition("app", 1).onFailure(_ -> fail("slot 2"));
                // p2 has budget headroom (1 floor) but no slot → QUEUED.
                manager.materializePartition("app", 2)
                       .onSuccess(_ -> fail("app#2 must queue"))
                       .onFailure(cause -> assertThat(cause).isInstanceOf(StreamError.ReshufflePaced.class));
                // p3 is OWNER → un-paced (no slot), consumes the last floor → pool now full.
                manager.materializePartition("app", 3).onFailure(_ -> fail("owner materialize"));
                assertThat(manager.totalAllocatedBytes()).isEqualTo(3 * FLOOR);
                assertThat(queueDepth(manager)).isEqualTo(1L);

                // p0 loses its role → release candidate. While it debounces, p2 stays queued (budget full even
                // though p0's slot freed on the first tick — the drain is head-of-line budget-blocked).
                roles.put("app#0", Role.NONE);
                manager.reconcileReshuffle();   // tick 1: p0 candidate; p2 blocked (pool full)
                manager.reconcileReshuffle();   // tick 2: debouncing; p2 still blocked
                assertThat(materialized(manager, "app", 2)).isFalse();
                assertThat(manager.totalAllocatedBytes()).isEqualTo(3 * FLOOR);

                manager.reconcileReshuffle();   // tick 3: p0 releases → frees a floor → same-tick drain admits p2

                assertThat(materialized(manager, "app", 0)).isFalse();   // released
                assertThat(materialized(manager, "app", 2)).isTrue();    // admitted from the queue
                assertThat(queueDepth(manager)).isEqualTo(0L);
                assertThat(manager.hydrationSnapshot().releasedPartitionsSinceBoot()).isEqualTo(1L);
                assertThat(manager.totalAllocatedBytes()).isEqualTo(3 * FLOOR);   // p0 out, p2 in
            } finally {
                manager.close();
            }
        }
    }

    /// Starvation preemption. A slot used to be held for as long as a partition stayed a not-caught-up
    /// REPLICA, with no upper bound, and `PartitionBackfill` retries forever once its bounded wait elapses
    /// with a committed owner present — so the release condition was exactly the condition that would never
    /// become true. Live cost (02y-stream-crash, 2026-08-16): two `entity:orders` partitions held both slots
    /// for 4m55s with ZERO releases, the two `multipart-events` partitions this node was the designated
    /// replica for sat queued behind them, never became in-sync, and were lost outright when their owner was
    /// SIGKILLed. Budget is deliberately ample here so SLOTS are the only constraint under test.
    @Nested
    class StalledSlotPreemption {

        private static StreamPartitionManager pacedManager(ConcurrentHashMap<String, Role> roles,
                                                           java.util.Set<String> caughtUp) {
            var manager = streamPartitionManager(64 * 1024 * 1024L);

            manager.placementRoleSupplier((stream, partition) -> roles.getOrDefault(stream + "#" + partition, Role.NONE));
            manager.replicaCatchupSource((stream, partition) -> new CatchupView(Integer.MAX_VALUE,
                                                                                caughtUp.contains(stream + "#" + partition)));
            manager.onStreamConfigPut(configPut(cfg("app", 8, 1)));

            return manager;
        }

        @Test
        void stalledReplicas_afterTenureBound_arePreempted_soAQueuedPartitionProceeds() {
            var roles = new ConcurrentHashMap<String, Role>();
            var caughtUp = ConcurrentHashMap.<String>newKeySet();
            var manager = pacedManager(roles, caughtUp);
            try {
                roles.put("app#0", Role.REPLICA);
                roles.put("app#1", Role.REPLICA);
                roles.put("app#2", Role.REPLICA);

                manager.materializePartition("app", 0).onFailure(_ -> fail("first slot should materialize"));
                manager.materializePartition("app", 1).onFailure(_ -> fail("second slot should materialize"));
                manager.materializePartition("app", 2)
                       .onSuccess(_ -> fail("app#2 must be paced — both slots are taken"))
                       .onFailure(cause -> assertThat(cause).isInstanceOf(StreamError.ReshufflePaced.class));
                assertThat(queueDepth(manager)).isEqualTo(1L);

                // Neither holder EVER becomes caught up — the stall this fix exists for.
                for (var i = 0; i < StreamPartitionManager.RESHUFFLE_SLOT_MAX_TICKS - 1; i++) {
                    manager.reconcileReshuffle();
                }
                assertThat(materialized(manager, "app", 2))
                    .as("a slot must not be preempted before its tenure bound — that would defeat the pacing")
                    .isFalse();

                manager.reconcileReshuffle();

                assertThat(materialized(manager, "app", 2))
                    .as("at the tenure bound a stalled slot is preempted and the starving partition proceeds")
                    .isTrue();
                assertThat(queueDepth(manager)).isEqualTo(0L);
            } finally {
                manager.close();
            }
        }

        @Test
        void emptyQueue_neverPreempts_soThePacingBoundIsPreserved() {
            var roles = new ConcurrentHashMap<String, Role>();
            var caughtUp = ConcurrentHashMap.<String>newKeySet();
            var manager = pacedManager(roles, caughtUp);
            try {
                roles.put("app#0", Role.REPLICA);
                roles.put("app#1", Role.REPLICA);

                manager.materializePartition("app", 0).onFailure(_ -> fail("first slot should materialize"));
                manager.materializePartition("app", 1).onFailure(_ -> fail("second slot should materialize"));

                // Nothing is waiting, so tenure is irrelevant however long it runs.
                for (var i = 0; i < StreamPartitionManager.RESHUFFLE_SLOT_MAX_TICKS * 2; i++) {
                    manager.reconcileReshuffle();
                }

                roles.put("app#2", Role.REPLICA);
                manager.materializePartition("app", 2)
                       .onSuccess(_ -> fail("no queue means no preemption — both slots must still be held"))
                       .onFailure(cause -> assertThat(cause).isInstanceOf(StreamError.ReshufflePaced.class));
            } finally {
                manager.close();
            }
        }

        /// PERMIT ACCOUNTING. Preemption releases the permit while the backfill keeps running, so the ref is
        /// tracked as preempted rather than simply dropped: it must not release a SECOND permit when it later
        /// completes, and must not take a second one if it re-enters the acquire path. A double release would
        /// silently raise effective concurrency above the bound — invisible except as a flood under failover.
        @Test
        void preemptedSlotCompletingLater_doesNotReleaseASecondPermit() {
            var roles = new ConcurrentHashMap<String, Role>();
            var caughtUp = ConcurrentHashMap.<String>newKeySet();
            var manager = pacedManager(roles, caughtUp);
            try {
                roles.put("app#0", Role.REPLICA);
                roles.put("app#1", Role.REPLICA);
                roles.put("app#2", Role.REPLICA);

                manager.materializePartition("app", 0).onFailure(_ -> fail("first slot should materialize"));
                manager.materializePartition("app", 1).onFailure(_ -> fail("second slot should materialize"));
                manager.materializePartition("app", 2).onSuccess(_ -> fail("app#2 must be paced"));

                // Both stalled holders are preempted (2 permits released); the drain admits app#2 (1 taken).
                for (var i = 0; i < StreamPartitionManager.RESHUFFLE_SLOT_MAX_TICKS; i++) {
                    manager.reconcileReshuffle();
                }
                assertThat(materialized(manager, "app", 2)).isTrue();

                // A preempted partition finally finishes. Its permit was already returned at preemption.
                caughtUp.add("app#0");
                manager.reconcileReshuffle();

                roles.put("app#3", Role.REPLICA);
                roles.put("app#4", Role.REPLICA);
                manager.materializePartition("app", 3).onFailure(_ -> fail("exactly one permit should remain free"));
                manager.materializePartition("app", 4)
                       .onSuccess(_ -> fail("a completing preempted slot must NOT return a second permit — concurrency would exceed the bound"))
                       .onFailure(cause -> assertThat(cause).isInstanceOf(StreamError.ReshufflePaced.class));
            } finally {
                manager.close();
            }
        }
    }
}
