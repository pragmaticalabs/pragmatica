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
import org.pragmatica.consensus.topology.TopologyObserver.TopologyMode;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Verifies the explicit `BOOTING` / `NORMAL` `TopologyMode` semantics after the
/// 2026-05-18 nodeStatesById-fallback removal: `mode` is purely informational and
/// no longer gates read sources. The snapshot-backed `MembershipView` is the SOLE
/// source for `healthyActiveNodeCount`, `readyNodeCount`, and `coreNodes`. When the
/// snapshot is absent — even during BOOTING — reads return conservative zeros
/// rather than the constructor-seeded in-memory map.
class TopologyObserverModeTest {
    private static final NodeId SELF = nodeId("node-self").unwrap();
    private static final NodeId PEER_A = nodeId("node-a").unwrap();
    private static final NodeId PEER_B = nodeId("node-b").unwrap();
    private static final NodeId PEER_C = nodeId("node-c").unwrap();
    private static final NodeId PEER_D = nodeId("node-d").unwrap();

    private static final NodeInfo INFO_SELF = NodeInfo.nodeInfo(SELF, NodeAddress.nodeAddress("localhost", 5000).unwrap());
    private static final NodeInfo INFO_A = NodeInfo.nodeInfo(PEER_A, NodeAddress.nodeAddress("localhost", 5001).unwrap());
    private static final NodeInfo INFO_B = NodeInfo.nodeInfo(PEER_B, NodeAddress.nodeAddress("localhost", 5002).unwrap());
    private static final NodeInfo INFO_C = NodeInfo.nodeInfo(PEER_C, NodeAddress.nodeAddress("localhost", 5003).unwrap());
    private static final NodeInfo INFO_D = NodeInfo.nodeInfo(PEER_D, NodeAddress.nodeAddress("localhost", 5004).unwrap());

    /// Cluster-of-5 baseline: quorum threshold is 3.
    private static TopologyConfig clusterOf5() {
        return new TopologyConfig(SELF,
                                  5,
                                  timeSpan(60).seconds(),
                                  timeSpan(1).seconds(),
                                  List.of(INFO_SELF, INFO_A, INFO_B, INFO_C, INFO_D));
    }

    private static TopologyObserver observerWith(TopologyConfig config, GenerationSnapshotSource source) {
        return TopologyObserver.topologyObserver(config, MessageRouter.mutable(), source)
                               .unwrap();
    }

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
    class InitialMode {
        @Test
        void initialMode_isBooting() {
            var observer = observerWith(clusterOf5(), GenerationSnapshotSource.noop());

            assertThat(observer.topologyMode()).isEqualTo(TopologyMode.BOOTING);
        }
    }

    @Nested
    class BootingFallback {
        @Test
        void bootingMode_noSnapshot_healthyActiveNodeCount_returnsZero() {
            // 2026-05-18: nodeStatesById is no longer a read source. Even though the
            // constructor seeds 5 non-passive members from coreNodes, BOOTING with an
            // absent snapshot must report 0 — the per-reader-variance bug class that
            // chaos suites surfaced is exactly the dual read path this assertion
            // pins down.
            var observer = observerWith(clusterOf5(), GenerationSnapshotSource.noop());

            assertThat(observer.topologyMode()).isEqualTo(TopologyMode.BOOTING);
            assertThat(observer.healthyActiveNodeCount()).isZero();
        }

        @Test
        void bootingMode_noSnapshot_readyNodeCount_returnsZero() {
            var observer = observerWith(clusterOf5(), GenerationSnapshotSource.noop());

            assertThat(observer.topologyMode()).isEqualTo(TopologyMode.BOOTING);
            assertThat(observer.readyNodeCount()).isZero();
        }

        @Test
        void bootingMode_noSnapshot_coreNodes_returnsEmpty() {
            var observer = observerWith(clusterOf5(), GenerationSnapshotSource.noop());

            assertThat(observer.topologyMode()).isEqualTo(TopologyMode.BOOTING);
            assertThat(observer.coreNodes()).isEmpty();
        }
    }

    @Nested
    class TransitionBootingToNormal {
        @Test
        void firstQuorumSnapshot_flipsToNormal() {
            var source = new StubSource();
            var observer = observerWith(clusterOf5(), source);

            assertThat(observer.topologyMode()).isEqualTo(TopologyMode.BOOTING);

            // Quorum for cluster-of-5 = 3. Publish a snapshot whose coreMemberIds reaches it.
            source.set(new StubView(Set.of(SELF, PEER_A, PEER_B), Set.of(SELF, PEER_A, PEER_B), 3, 5));

            // Trigger an evaluation by calling a public read path (which evaluates the
            // transition on snapshot presence).
            assertThat(observer.healthyActiveNodeCount()).isEqualTo(3);
            assertThat(observer.topologyMode()).isEqualTo(TopologyMode.NORMAL);
        }

        @Test
        void belowQuorumSnapshot_doesNotFlip() {
            var source = new StubSource();
            var observer = observerWith(clusterOf5(), source);

            // Below-quorum snapshot: cluster-of-5 needs 3 core ids; only 2 here.
            source.set(new StubView(Set.of(SELF, PEER_A), Set.of(SELF, PEER_A), 2, 5));

            // Read still returns the snapshot's value (2) but mode stays BOOTING.
            assertThat(observer.healthyActiveNodeCount()).isEqualTo(2);
            assertThat(observer.topologyMode()).isEqualTo(TopologyMode.BOOTING);
        }
    }

    @Nested
    class NormalIsSnapshotOnly {
        @Test
        void normalMode_returnsSnapshotCount() {
            var source = new StubSource();
            var observer = observerWith(clusterOf5(), source);

            // Trip into NORMAL.
            source.set(new StubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                    Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                    5, 5));
            assertThat(observer.healthyActiveNodeCount()).isEqualTo(5);
            assertThat(observer.topologyMode()).isEqualTo(TopologyMode.NORMAL);

            // A subsequent shrunken snapshot is the source of truth; legacy is ignored.
            source.set(new StubView(Set.of(SELF, PEER_A, PEER_B, PEER_C),
                                    Set.of(SELF, PEER_A, PEER_B, PEER_C),
                                    4, 5));
            assertThat(observer.healthyActiveNodeCount()).isEqualTo(4);
        }

        @Test
        void normalMode_emptySnapshotReturnsZero() {
            var source = new StubSource();
            var observer = observerWith(clusterOf5(), source);

            // Trip into NORMAL.
            source.set(new StubView(Set.of(SELF, PEER_A, PEER_B), Set.of(SELF, PEER_A, PEER_B), 3, 5));
            assertThat(observer.healthyActiveNodeCount()).isEqualTo(3);
            assertThat(observer.topologyMode()).isEqualTo(TopologyMode.NORMAL);

            // Now CLEAR the snapshot — in NORMAL we must NOT silently fall back to legacy.
            source.clear();
            assertThat(observer.topologyMode()).isEqualTo(TopologyMode.NORMAL);
            assertThat(observer.healthyActiveNodeCount()).isZero();
            assertThat(observer.readyNodeCount()).isZero();
        }
    }

    @Nested
    class OneWayTransition {
        @Test
        void smallerSubsequentSnapshot_doesNotRevertToBooting() {
            var source = new StubSource();
            var observer = observerWith(clusterOf5(), source);

            // Trip into NORMAL with a quorum-reaching snapshot.
            source.set(new StubView(Set.of(SELF, PEER_A, PEER_B), Set.of(SELF, PEER_A, PEER_B), 3, 5));
            assertThat(observer.healthyActiveNodeCount()).isEqualTo(3);
            assertThat(observer.topologyMode()).isEqualTo(TopologyMode.NORMAL);

            // Subsequent below-quorum snapshot must NOT flip the mode back to BOOTING.
            source.set(new StubView(Set.of(SELF), Set.of(SELF), 1, 5));
            assertThat(observer.healthyActiveNodeCount()).isEqualTo(1);
            assertThat(observer.topologyMode()).isEqualTo(TopologyMode.NORMAL);
        }
    }
}
