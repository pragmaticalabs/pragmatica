/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.pragmatica.consensus.topology;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Verifies `TopologyObserver`'s dual-mode read paths switch between the snapshot-backed
/// `MembershipView` and the legacy in-memory `nodeStatesById` map based on whether the
/// injected `GenerationSnapshotSource` currently exposes a view.
class TopologyObserverSnapshotDualModeTest {
    private static final NodeId SELF = nodeId("node-self").unwrap();
    private static final NodeId PEER_A = nodeId("node-a").unwrap();
    private static final NodeId PEER_B = nodeId("node-b").unwrap();
    private static final NodeId PEER_C = nodeId("node-c").unwrap();

    private static final NodeInfo INFO_SELF = NodeInfo.nodeInfo(SELF, NodeAddress.nodeAddress("localhost", 5000).unwrap());
    private static final NodeInfo INFO_A = NodeInfo.nodeInfo(PEER_A, NodeAddress.nodeAddress("localhost", 5001).unwrap());
    private static final NodeInfo INFO_B = NodeInfo.nodeInfo(PEER_B, NodeAddress.nodeAddress("localhost", 5002).unwrap());

    private static TopologyConfig baseConfig() {
        return new TopologyConfig(SELF,
                                  3,
                                  timeSpan(60).seconds(),
                                  timeSpan(1).seconds(),
                                  List.of(INFO_SELF, INFO_A, INFO_B));
    }

    private static TopologyObserver observerWith(GenerationSnapshotSource source) {
        return TopologyObserver.topologyObserver(baseConfig(), MessageRouter.mutable(), source)
                               .unwrap();
    }

    /// Minimal test-only source whose view is toggled via the outer `AtomicReference`.
    private static final class StubSource implements GenerationSnapshotSource {
        private final AtomicReference<Option<MembershipView>> view = new AtomicReference<>(Option.none());

        void set(MembershipView v) {
            view.set(Option.some(v));
        }

        void clear() {
            view.set(Option.none());
        }

        @Override public Option<MembershipView> currentMembershipView() {
            return view.get();
        }

        @Override public long observedRabiaTerm() {
            return 0L;
        }
    }

    private record StubView(Set<NodeId> coreMemberIds,
                            Set<NodeId> onDutyMemberIds,
                            int healthyOnDutyCount,
                            int desiredCoreSize) implements MembershipView {}

    @Nested
    class HealthyActiveCount {
        @Test
        void healthyActiveNodeCount_noSnapshot_usesLegacy() {
            var observer = observerWith(GenerationSnapshotSource.noop());

            // constructor adds all three core nodes as HEALTHY -> count = 3
            assertThat(observer.healthyActiveNodeCount()).isEqualTo(3);
        }

        @Test
        void healthyActiveNodeCount_withSnapshot_readsFromSnapshot() {
            var source = new StubSource();
            source.set(new StubView(Set.of(SELF, PEER_A, PEER_B, PEER_C),
                                    Set.of(SELF, PEER_A, PEER_B, PEER_C),
                                    4,
                                    4));
            var observer = observerWith(source);

            assertThat(observer.healthyActiveNodeCount()).isEqualTo(4);
        }

        @Test
        void healthyActiveNodeCount_snapshotAndLegacyDisagree_snapshotWins() {
            // Legacy in-memory would report 3 healthy (all coreNodes added in ctor).
            // Snapshot says 1 -> snapshot wins.
            var source = new StubSource();
            source.set(new StubView(Set.of(SELF), Set.of(SELF), 1, 3));
            var observer = observerWith(source);

            assertThat(observer.healthyActiveNodeCount()).isEqualTo(1);
        }
    }

    @Nested
    class CoreNodes {
        @Test
        void coreNodeIds_noSnapshot_usesLegacy() {
            var observer = observerWith(GenerationSnapshotSource.noop());

            assertThat(observer.coreNodes()).containsExactlyInAnyOrder(SELF, PEER_A, PEER_B);
        }

        @Test
        void coreNodeIds_withSnapshot_readsFromSnapshot() {
            var source = new StubSource();
            source.set(new StubView(Set.of(PEER_A, PEER_C), Set.of(PEER_A), 1, 3));
            var observer = observerWith(source);

            // Snapshot disagrees with the static config — observer should return the
            // snapshot-projected set, not the constructor-seeded core map.
            assertThat(observer.coreNodes()).containsExactlyInAnyOrder(PEER_A, PEER_C);
        }
    }

    @Nested
    class ReadyNodes {
        @Test
        void readyNodes_withSnapshot_filtersByOnDuty() {
            var source = new StubSource();
            source.set(new StubView(Set.of(SELF, PEER_A, PEER_B, PEER_C),
                                    Set.of(PEER_A, PEER_B),
                                    2,
                                    4));
            var observer = observerWith(source);

            // onDuty set size (2) is the source of truth while the snapshot is live.
            assertThat(observer.readyNodeCount()).isEqualTo(2);
        }

        @Test
        void readyNodes_noSnapshot_isZero_R5() {
            // R5: markReady has been removed entirely. With no snapshot live,
            // readyNodeCount falls back to the (now permanently empty) legacy
            // `readyNodes` set — KV NodeLifecycleKey writes (HealthReconciler) are
            // the sole source of ON_DUTY truth. The count is zero by construction.
            var source = new StubSource(); // empty view
            var observer = observerWith(source);

            assertThat(observer.readyNodeCount())
                .as("R5: with no snapshot, readyNodeCount is zero — the fallback set is unwritable")
                .isZero();
        }
    }

    @Nested
    class EffectiveMembershipAttribution {
        @Test
        void effectiveMembershipSource_reflectsActivePath() {
            var source = new StubSource();
            var observer = observerWith(source);

            // No snapshot present -> LEGACY.
            assertThat(observer.effectiveMembership().source()).isEqualTo(EffectiveMembership.Source.LEGACY);
            assertThat(observer.effectiveMembership().coreMemberIds())
                .containsExactlyInAnyOrder(SELF, PEER_A, PEER_B);

            source.set(new StubView(Set.of(PEER_A, PEER_C), Set.of(PEER_A, PEER_C), 2, 3));

            // Snapshot now live -> SNAPSHOT, membership matches the view.
            assertThat(observer.effectiveMembership().source()).isEqualTo(EffectiveMembership.Source.SNAPSHOT);
            assertThat(observer.effectiveMembership().coreMemberIds())
                .containsExactlyInAnyOrder(PEER_A, PEER_C);

            source.clear();

            // Snapshot cleared -> back to LEGACY (dual-mode fallback).
            assertThat(observer.effectiveMembership().source()).isEqualTo(EffectiveMembership.Source.LEGACY);
        }
    }

}
