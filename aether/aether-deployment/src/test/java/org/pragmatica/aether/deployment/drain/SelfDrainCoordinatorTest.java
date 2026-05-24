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
///   * Threshold IS the authoritative `TopologyManager.quorumSize()` supplied directly —
///     NOT a recomputed `(N/2)+1` over the raw (inflatable) topology list.
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
            IntSupplier quorum = () -> 3;
            var coord = SelfDrainCoordinator.selfDrainCoordinator(SELF, peers, quorum, tracker, fastConfig(),
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
            IntSupplier quorum = () -> 3;
            var coord = SelfDrainCoordinator.selfDrainCoordinator(SELF, peersRef::get, quorum, tracker,
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
                                                                  () -> 3,
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
                                                                  () -> 3,
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
                                                                  () -> 3,
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
                                                                  () -> 3,
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
            var coord = SelfDrainCoordinator.selfDrainCoordinator(SELF, () -> Set.of(), () -> 3,
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
            var coord = SelfDrainCoordinator.selfDrainCoordinator(SELF, () -> Set.of(), () -> 3,
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
            var coord = SelfDrainCoordinator.selfDrainCoordinator(SELF, () -> Set.of(), () -> 3,
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

    @Nested class QuorumThreshold {
        // The supplier now provides the authoritative quorum directly (TopologyManager.quorumSize());
        // the coordinator no longer recomputes (N/2)+1. visible = connectedPeers + 1 (self).
        @ParameterizedTest(name = "quorum={0}, visible=quorum-1 → drains")
        @CsvSource({"2", "3", "4", "5", "6"})
        void belowQuorum_trips_whenVisibleOneShortOfQuorum(int quorum) {
            var exits = exitCounter();
            var tracker = InFlightRequestTracker.inFlightRequestTracker();
            // visible = quorum - 1 → exactly one short of quorum → must drain.
            // connectedPeers = visible - 1 (self counts) = quorum - 2.
            var peers = peerSetOfSize(quorum - 2);
            var coord = SelfDrainCoordinator.selfDrainCoordinator(SELF, () -> peers, () -> quorum,
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
            // authoritative quorum=3, visible=self+2 peers=3 → at quorum, must NOT trip.
            var coord = SelfDrainCoordinator.selfDrainCoordinator(SELF,
                                                                  () -> Set.of(PEER_A, PEER_B),
                                                                  () -> 3,
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

        /// REGRESSION (inflated-topology collapse): a 5-node cluster's authoritative quorum is 3.
        /// A survivor connected to 3 live peers sees visible=4 ≥ quorum=3 → must NOT drain — even
        /// though the raw topology (inflated to ~9 by dead + CTM-replacement nodes) would have
        /// pushed a recomputed (9/2)+1=5 threshold above 4 and collapsed the cluster on first loss.
        @Test
        void onConnectivityChange_fourVisibleWithQuorumThree_doesNotDrain() {
            var exits = exitCounter();
            var tracker = InFlightRequestTracker.inFlightRequestTracker();
            // connectedPeers = 3 → visible = 4; authoritative quorum = 3.
            var coord = SelfDrainCoordinator.selfDrainCoordinator(SELF,
                                                                  () -> Set.of(PEER_A, PEER_B, PEER_C),
                                                                  () -> 3,
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

        /// REGRESSION (minority partition): authoritative quorum=3, survivor sees only 1 live peer
        /// → visible=2 < 3 → drains after the debounce window. Correct minority-partition detection.
        @Test
        void onConnectivityChange_twoVisibleWithQuorumThree_drainsAfterDebounce() {
            var exits = exitCounter();
            var tracker = InFlightRequestTracker.inFlightRequestTracker();
            // connectedPeers = 1 → visible = 2; authoritative quorum = 3.
            var coord = SelfDrainCoordinator.selfDrainCoordinator(SELF,
                                                                  () -> Set.of(PEER_A),
                                                                  () -> 3,
                                                                  tracker,
                                                                  fastConfig(),
                                                                  exits::incrementAndGet,
                                                                  SelfDrainEventPublisher.NO_OP);

            coord.onConnectivityChange();
            await().atMost(2, TimeUnit.SECONDS)
                   .pollInterval(50, TimeUnit.MILLISECONDS)
                   .untilAsserted(() -> {
                       coord.onConnectivityChange();
                       assertThat(exits.get()).isEqualTo(1);
                   });
            assertThat(coord.phase()).isEqualTo(SelfDrainCoordinator.Phase.EXITED);
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
            var coord = SelfDrainCoordinator.selfDrainCoordinator(SELF, () -> Set.of(), () -> 3,
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
            var coord = SelfDrainCoordinator.selfDrainCoordinator(SELF, () -> Set.of(), () -> 3,
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
            var coord = SelfDrainCoordinator.selfDrainCoordinator(SELF, () -> Set.of(), () -> 3,
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
