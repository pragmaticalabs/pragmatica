// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.drain;

import org.pragmatica.consensus.NodeId;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

import java.util.concurrent.TimeUnit;


/// Unit tests for the node-side self-drain coordinator.
///
/// Scenario coverage (membership-architecture-spec.md §16.1):
///
///   * Periodic trigger trip after `triggerThreshold` sustained below-quorum (S19).
///   * Recovery before `triggerThreshold` does NOT trip.
///   * Uninterruptible once drain begins (quorum restoration mid-drain is ignored).
///   * `jvmExit` invoked exactly once (CAS guard on `DRAINING → EXITED`).
///   * `setAcceptingNewWork(false)` rejects new requests via the tracker gate.
///   * `inflightGrace` expiry exits the process even if tracker never drains.
///   * `onQuorumDisappeared` and `onRabiaPaused` are immediate (no debounce).
///   * NO consensus/KV imports in `SelfDrainCoordinator.java` (static audit).
///   * Threshold formula `(N/2) + 1` matches the aggregator quorum.
class SelfDrainCoordinatorTest {
    private static final NodeId SELF = nodeId("self-node").unwrap();
    private static final NodeId PEER_A = nodeId("peer-a").unwrap();
    private static final NodeId PEER_B = nodeId("peer-b").unwrap();
    private static final NodeId PEER_C = nodeId("peer-c").unwrap();
    private static final NodeId PEER_D = nodeId("peer-d").unwrap();

    private static SelfDrainConfig fastConfig() {
        return new SelfDrainConfig(timeSpan(200).millis(), timeSpan(1).seconds());
    }

    private static SelfDrainConfig tightGrace() {
        return new SelfDrainConfig(timeSpan(200).millis(), timeSpan(200).millis());
    }

    private static AtomicInteger exitCounter() {
        return new AtomicInteger(0);
    }

    @Nested class Triggers {
        @Test
        void onConnectivityChange_trips_afterSustainedBelowQuorum() {
            var exits = exitCounter();
            var tracker = InFlightRequestTracker.inFlightRequestTracker();
            Supplier<Set<NodeId>> peers = () -> Set.of(PEER_A);
            IntSupplier size = () -> 5;
            var coord = SelfDrainCoordinator.selfDrainCoordinator(SELF, peers, size, tracker, fastConfig(),
                                                                  exits::incrementAndGet, SelfDrainEventPublisher.NO_OP);

            coord.onConnectivityChange();
            await().atMost(2, TimeUnit.SECONDS)
                   .pollInterval(50, TimeUnit.MILLISECONDS)
                   .untilAsserted(() -> {
                       coord.onConnectivityChange();
                       assertThat(exits.get()).isEqualTo(1);
                   });
            assertThat(coord.phase()).isEqualTo(SelfDrainCoordinator.Phase.EXITED);
        }

        @Test
        void onConnectivityChange_doesNotTrip_whenRecoveryBeforeThreshold() {
            var exits = exitCounter();
            var tracker = InFlightRequestTracker.inFlightRequestTracker();
            var peersRef = new AtomicReference<Set<NodeId>>(Set.of(PEER_A));
            IntSupplier size = () -> 5;
            var coord = SelfDrainCoordinator.selfDrainCoordinator(SELF, peersRef::get, size, tracker,
                                                                  new SelfDrainConfig(timeSpan(400).millis(), timeSpan(1).seconds()),
                                                                  exits::incrementAndGet, SelfDrainEventPublisher.NO_OP);

            coord.onConnectivityChange();
            sleep(100);
            peersRef.set(Set.of(PEER_A, PEER_B, PEER_C, PEER_D));
            coord.onConnectivityChange();
            sleep(500);
            coord.onConnectivityChange();

            assertThat(exits.get()).isZero();
            assertThat(coord.phase()).isEqualTo(SelfDrainCoordinator.Phase.ACTIVE);
        }

        @Test
        void onQuorumDisappeared_isImmediate() {
            var exits = exitCounter();
            var tracker = InFlightRequestTracker.inFlightRequestTracker();
            var coord = SelfDrainCoordinator.selfDrainCoordinator(SELF,
                                                                  () -> Set.of(PEER_A, PEER_B, PEER_C, PEER_D),
                                                                  () -> 5,
                                                                  tracker,
                                                                  fastConfig(),
                                                                  exits::incrementAndGet,
                                                                  SelfDrainEventPublisher.NO_OP);

            coord.onQuorumDisappeared();
            await().atMost(1, TimeUnit.SECONDS).untilAsserted(() -> assertThat(exits.get()).isEqualTo(1));
            assertThat(coord.phase()).isEqualTo(SelfDrainCoordinator.Phase.EXITED);
        }

        @Test
        void onRabiaPaused_isImmediate() {
            var exits = exitCounter();
            var tracker = InFlightRequestTracker.inFlightRequestTracker();
            var coord = SelfDrainCoordinator.selfDrainCoordinator(SELF,
                                                                  () -> Set.of(PEER_A, PEER_B, PEER_C, PEER_D),
                                                                  () -> 5,
                                                                  tracker,
                                                                  fastConfig(),
                                                                  exits::incrementAndGet,
                                                                  SelfDrainEventPublisher.NO_OP);

            coord.onRabiaPaused();
            await().atMost(1, TimeUnit.SECONDS).untilAsserted(() -> assertThat(exits.get()).isEqualTo(1));
            assertThat(coord.phase()).isEqualTo(SelfDrainCoordinator.Phase.EXITED);
        }
    }

    @Nested class Uninterruptible {
        @Test
        void connectivityRestoration_doesNotAbortDrain() {
            var exits = exitCounter();
            var tracker = InFlightRequestTracker.inFlightRequestTracker();
            var coord = SelfDrainCoordinator.selfDrainCoordinator(SELF,
                                                                  () -> Set.of(PEER_A, PEER_B, PEER_C, PEER_D),
                                                                  () -> 5,
                                                                  tracker,
                                                                  tightGrace(),
                                                                  exits::incrementAndGet,
                                                                  SelfDrainEventPublisher.NO_OP);

            coord.onQuorumDisappeared();
            // Full quorum is now visible — must NOT abort drain.
            coord.onConnectivityChange();
            coord.onConnectivityChange();
            coord.onConnectivityChange();

            await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> assertThat(exits.get()).isEqualTo(1));
            assertThat(coord.phase()).isEqualTo(SelfDrainCoordinator.Phase.EXITED);
        }

        @Test
        void jvmExit_invokedExactlyOnce_underDoubleTrigger() {
            var exits = exitCounter();
            var tracker = InFlightRequestTracker.inFlightRequestTracker();
            var coord = SelfDrainCoordinator.selfDrainCoordinator(SELF,
                                                                  () -> Set.of(),
                                                                  () -> 5,
                                                                  tracker,
                                                                  tightGrace(),
                                                                  exits::incrementAndGet,
                                                                  SelfDrainEventPublisher.NO_OP);

            coord.initiateDrain("test-1");
            coord.initiateDrain("test-2");
            coord.onQuorumDisappeared();
            coord.onRabiaPaused();

            await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> assertThat(exits.get()).isEqualTo(1));
            assertThat(coord.phase()).isEqualTo(SelfDrainCoordinator.Phase.EXITED);
        }
    }

    @Nested class TrackerGate {
        @Test
        void initiateDrain_closesTrackerGate() {
            var exits = exitCounter();
            var tracker = InFlightRequestTracker.inFlightRequestTracker();
            var coord = SelfDrainCoordinator.selfDrainCoordinator(SELF, () -> Set.of(), () -> 5,
                                                                  tracker, fastConfig(), exits::incrementAndGet,
                                                                  SelfDrainEventPublisher.NO_OP);

            assertThat(tracker.isAcceptingNewWork()).isTrue();
            coord.onQuorumDisappeared();
            assertThat(tracker.isAcceptingNewWork()).isFalse();
            assertThat(tracker.tryEnter()).isFalse();
        }

        @Test
        void trackerDrains_firesExit_beforeGrace() {
            var exits = exitCounter();
            var tracker = InFlightRequestTracker.inFlightRequestTracker();
            tracker.tryEnter();
            tracker.tryEnter();
            var coord = SelfDrainCoordinator.selfDrainCoordinator(SELF, () -> Set.of(), () -> 5,
                                                                  tracker,
                                                                  new SelfDrainConfig(timeSpan(200).millis(), timeSpan(10).seconds()),
                                                                  exits::incrementAndGet,
                                                                  SelfDrainEventPublisher.NO_OP);

            coord.onQuorumDisappeared();
            sleep(50);
            tracker.exit();
            tracker.exit();

            await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> assertThat(exits.get()).isEqualTo(1));
        }
    }

    @Nested class GraceTimeout {
        @Test
        void graceExpires_exitFires_evenIfTrackerNeverDrains() {
            var exits = exitCounter();
            var tracker = InFlightRequestTracker.inFlightRequestTracker();
            tracker.tryEnter();
            assertThat(tracker.count()).isEqualTo(1);
            var coord = SelfDrainCoordinator.selfDrainCoordinator(SELF, () -> Set.of(), () -> 5,
                                                                  tracker,
                                                                  new SelfDrainConfig(timeSpan(50).millis(), timeSpan(200).millis()),
                                                                  exits::incrementAndGet,
                                                                  SelfDrainEventPublisher.NO_OP);

            coord.onQuorumDisappeared();

            await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> assertThat(exits.get()).isEqualTo(1));
            assertThat(tracker.count()).isEqualTo(1);
            assertThat(coord.phase()).isEqualTo(SelfDrainCoordinator.Phase.EXITED);
        }
    }

    @Nested class ThresholdFormula {
        @ParameterizedTest(name = "topologySize={0} → threshold={1}")
        @CsvSource({"1, 1", "3, 2", "5, 3", "7, 4", "9, 5", "10, 6"})
        void thresholdEquals_halfPlusOne(int topologySize, int expectedThreshold) {
            var exits = exitCounter();
            var tracker = InFlightRequestTracker.inFlightRequestTracker();
            // visibleMinusSelf = expectedThreshold - 2 forces "below quorum" by exactly one slot.
            // If expectedThreshold = 1, no value can put us below — skip that row.
            if (expectedThreshold <= 1) {return;}
            var peers = peerSetOfSize(expectedThreshold - 2);
            var coord = SelfDrainCoordinator.selfDrainCoordinator(SELF, () -> peers, () -> topologySize,
                                                                  tracker, fastConfig(), exits::incrementAndGet,
                                                                  SelfDrainEventPublisher.NO_OP);

            coord.onConnectivityChange();
            await().atMost(2, TimeUnit.SECONDS)
                   .pollInterval(50, TimeUnit.MILLISECONDS)
                   .untilAsserted(() -> {
                       coord.onConnectivityChange();
                       assertThat(exits.get()).isEqualTo(1);
                   });
        }

        @Test
        void atOrAboveQuorum_doesNotTrip() {
            var exits = exitCounter();
            var tracker = InFlightRequestTracker.inFlightRequestTracker();
            // 5-node topology, threshold=3, visible=self+2 peers=3 → at quorum, must NOT trip.
            var coord = SelfDrainCoordinator.selfDrainCoordinator(SELF,
                                                                  () -> Set.of(PEER_A, PEER_B),
                                                                  () -> 5,
                                                                  tracker,
                                                                  fastConfig(),
                                                                  exits::incrementAndGet,
                                                                  SelfDrainEventPublisher.NO_OP);

            for (int i = 0; i < 20; i++) {
                coord.onConnectivityChange();
                sleep(20);
            }
            assertThat(exits.get()).isZero();
            assertThat(coord.phase()).isEqualTo(SelfDrainCoordinator.Phase.ACTIVE);
        }
    }

    @Nested class EventEmission {
        @Test
        void selfDrainInitiated_eventPublished_onActiveToDrainingTransition() {
            var exits = exitCounter();
            var tracker = InFlightRequestTracker.inFlightRequestTracker();
            var captured = new java.util.concurrent.atomic.AtomicReference<org.pragmatica.aether.slice.kvstore.AetherValue.ClusterEventValue.EventType>();
            var capturedSeverity = new java.util.concurrent.atomic.AtomicReference<org.pragmatica.aether.slice.kvstore.AetherValue.ClusterEventValue.Severity>();
            var capturedDetails = new java.util.concurrent.atomic.AtomicReference<java.util.Map<String, String>>();
            var publishCount = new AtomicInteger(0);
            SelfDrainEventPublisher publisher = (type, severity, message, details) -> {
                captured.set(type);
                capturedSeverity.set(severity);
                capturedDetails.set(details);
                publishCount.incrementAndGet();
            };
            var coord = SelfDrainCoordinator.selfDrainCoordinator(SELF, () -> Set.of(), () -> 5,
                                                                  tracker,
                                                                  new SelfDrainConfig(timeSpan(50).millis(), timeSpan(200).millis()),
                                                                  exits::incrementAndGet, publisher);

            coord.onQuorumDisappeared();

            await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> assertThat(publishCount.get()).isEqualTo(1));
            assertThat(captured.get()).isEqualTo(org.pragmatica.aether.slice.kvstore.AetherValue.ClusterEventValue.EventType.SELF_DRAIN_INITIATED);
            assertThat(capturedSeverity.get()).isEqualTo(org.pragmatica.aether.slice.kvstore.AetherValue.ClusterEventValue.Severity.WARNING);
            assertThat(capturedDetails.get()).containsEntry("nodeId", SELF.id());
            assertThat(capturedDetails.get()).containsEntry("reason", "quorum-disappeared");
            assertThat(capturedDetails.get()).containsKey("graceMs");
        }

        @Test
        void publisherThrows_drainStillProceeds() {
            var exits = exitCounter();
            var tracker = InFlightRequestTracker.inFlightRequestTracker();
            SelfDrainEventPublisher throwingPublisher = (type, severity, message, details) -> {
                throw new RuntimeException("publisher unavailable");
            };
            var coord = SelfDrainCoordinator.selfDrainCoordinator(SELF, () -> Set.of(), () -> 5,
                                                                  tracker,
                                                                  new SelfDrainConfig(timeSpan(50).millis(), timeSpan(200).millis()),
                                                                  exits::incrementAndGet, throwingPublisher);

            coord.onQuorumDisappeared();

            await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> assertThat(exits.get()).isEqualTo(1));
            assertThat(coord.phase()).isEqualTo(SelfDrainCoordinator.Phase.EXITED);
        }

        @Test
        void selfDrainInitiated_publishedExactlyOnce_onDoubleTrigger() {
            var exits = exitCounter();
            var tracker = InFlightRequestTracker.inFlightRequestTracker();
            var publishCount = new AtomicInteger(0);
            SelfDrainEventPublisher publisher = (type, severity, message, details) -> publishCount.incrementAndGet();
            var coord = SelfDrainCoordinator.selfDrainCoordinator(SELF, () -> Set.of(), () -> 5,
                                                                  tracker, tightGrace(),
                                                                  exits::incrementAndGet, publisher);

            coord.onQuorumDisappeared();
            coord.onRabiaPaused();
            coord.initiateDrain("redundant");

            await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> assertThat(exits.get()).isEqualTo(1));
            assertThat(publishCount.get()).isEqualTo(1);
        }
    }

    @Nested class StaticImportAudit {
        @Test
        void coordinatorSource_hasNoConsensusOrKvImports() throws Exception {
            var source = Files.readString(Path.of(
                    "src/main/java/org/pragmatica/aether/deployment/drain/SelfDrainCoordinator.java"));
            var forbidden = new String[]{
                    "import org.pragmatica.consensus.kvstore",
                    "import org.pragmatica.kvstore",
                    "import org.pragmatica.consensus.rabia"
            };
            for (var pattern : forbidden) {
                assertThat(source)
                        .as("SelfDrainCoordinator.java must not import %s — a partition victim cannot use KV/consensus during self-drain", pattern)
                        .doesNotContain(pattern);
            }
        }
    }

    private static Set<NodeId> peerSetOfSize(int n) {
        var pool = new NodeId[]{PEER_A, PEER_B, PEER_C, PEER_D,
                                nodeId("peer-e").unwrap(),
                                nodeId("peer-f").unwrap(),
                                nodeId("peer-g").unwrap(),
                                nodeId("peer-h").unwrap()};
        if (n <= 0) {return Set.of();}
        return Set.copyOf(Arrays.asList(pool).subList(0, Math.min(n, pool.length)));
    }

    private static void sleep(long ms) {
        try {Thread.sleep(ms);} catch (InterruptedException e) {Thread.currentThread().interrupt();}
    }
}
