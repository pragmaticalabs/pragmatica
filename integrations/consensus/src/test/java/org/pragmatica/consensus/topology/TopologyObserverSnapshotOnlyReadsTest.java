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

/// Regression suite for the 2026-05-18 nodeStatesById-fallback removal.
///
/// Contract pinned here:
/// 1. `MembershipView` from `snapshotSource.currentMembershipView()` is the SOLE
///    source for `coreNodes`, `readyNodeCount`, `healthyActiveNodeCount`, and the
///    private quorum-eval `healthyActivePeerCount`.
/// 2. When the snapshot is absent, reads return conservative zeros / empty —
///    NOT the constructor-seeded in-memory `nodeStatesById` count.
/// 3. The `mode` field (`BOOTING` / `NORMAL`) is informational only; it never
///    gates read sources. The one-way `BOOTING -> NORMAL` transition is preserved
///    purely for diagnostics.
/// 4. In `NORMAL` with the snapshot subsequently cleared, reads still return
///    zero / empty — there is no quiet fallback.
class TopologyObserverSnapshotOnlyReadsTest {
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

    private static TopologyConfig clusterOf5() {
        return new TopologyConfig(SELF,
                                  5,
                                  timeSpan(60).seconds(),
                                  timeSpan(1).seconds(),
                                  List.of(INFO_SELF, INFO_A, INFO_B, INFO_C, INFO_D));
    }

    private static TopologyObserver observerWith(GenerationSnapshotSource source) {
        return TopologyObserver.topologyObserver(clusterOf5(), MessageRouter.mutable(), source)
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
    class BootingAbsentSnapshotReturnsZero {
        @Test
        void healthyActiveNodeCount_zeroWhenSnapshotAbsentAndBooting() {
            // Constructor seeds 5 HEALTHY non-passive members. Pre-refactor this would
            // have returned 5 via the legacy nodeStatesById fallback. Post-refactor:
            // snapshot is the sole source, so the absent-snapshot read returns 0.
            var observer = observerWith(GenerationSnapshotSource.noop());

            assertThat(observer.topologyMode()).isEqualTo(TopologyObserver.TopologyMode.BOOTING);
            assertThat(observer.healthyActiveNodeCount()).isZero();
        }

        @Test
        void readyNodeCount_zeroWhenSnapshotAbsentAndBooting() {
            var observer = observerWith(GenerationSnapshotSource.noop());

            assertThat(observer.topologyMode()).isEqualTo(TopologyObserver.TopologyMode.BOOTING);
            assertThat(observer.readyNodeCount()).isZero();
        }

        @Test
        void coreNodes_emptyWhenSnapshotAbsentAndBooting() {
            var observer = observerWith(GenerationSnapshotSource.noop());

            assertThat(observer.topologyMode()).isEqualTo(TopologyObserver.TopologyMode.BOOTING);
            assertThat(observer.coreNodes()).isEmpty();
        }

        @Test
        void effectiveMembership_emptyLegacyWhenSnapshotAbsent() {
            // `Source.LEGACY` now indicates "snapshot absent" rather than "legacy
            // fallback served". The returned member set is empty — the in-memory
            // `nodeStatesById` is no longer leaked.
            var observer = observerWith(GenerationSnapshotSource.noop());

            assertThat(observer.effectiveMembership().source())
                .isEqualTo(EffectiveMembership.Source.LEGACY);
            assertThat(observer.effectiveMembership().coreMemberIds()).isEmpty();
        }
    }

    @Nested
    class NormalReadsSnapshotOnly {
        @Test
        void afterFirstQuorumSnapshot_readsAreSnapshotProjections() {
            var source = new StubSource();
            var observer = observerWith(source);

            source.set(new StubView(Set.of(SELF, PEER_A, PEER_B),
                                    Set.of(SELF, PEER_A, PEER_B),
                                    3,
                                    5));

            assertThat(observer.healthyActiveNodeCount()).isEqualTo(3);
            assertThat(observer.readyNodeCount()).isEqualTo(3);
            assertThat(observer.coreNodes()).containsExactlyInAnyOrder(SELF, PEER_A, PEER_B);
            assertThat(observer.topologyMode()).isEqualTo(TopologyObserver.TopologyMode.NORMAL);
        }

        @Test
        void normalMode_snapshotCleared_readsReturnZeroNotLegacy() {
            // Hardens the contract that once we've flipped to NORMAL, a subsequent
            // empty snapshot does NOT reopen the legacy fallback. Reads are zero/empty.
            var source = new StubSource();
            var observer = observerWith(source);

            // Trip into NORMAL.
            source.set(new StubView(Set.of(SELF, PEER_A, PEER_B),
                                    Set.of(SELF, PEER_A, PEER_B),
                                    3,
                                    5));
            assertThat(observer.healthyActiveNodeCount()).isEqualTo(3);
            assertThat(observer.topologyMode()).isEqualTo(TopologyObserver.TopologyMode.NORMAL);

            // Clear the snapshot — even with 5 entries still seeded in nodeStatesById,
            // the observer must report zero/empty.
            source.clear();
            assertThat(observer.healthyActiveNodeCount()).isZero();
            assertThat(observer.readyNodeCount()).isZero();
            assertThat(observer.coreNodes()).isEmpty();
            assertThat(observer.effectiveMembership().coreMemberIds()).isEmpty();
        }

        @Test
        void snapshotDisagreesWithNodeStatesById_snapshotWins() {
            // Snapshot lists only PEER_C — a node NOT seeded by the constructor.
            // The in-memory map still holds SELF/PEER_A/PEER_B. The snapshot must win.
            var source = new StubSource();
            source.set(new StubView(Set.of(PEER_C),
                                    Set.of(PEER_C),
                                    1,
                                    5));
            var observer = observerWith(source);

            assertThat(observer.coreNodes()).containsExactly(PEER_C);
            assertThat(observer.healthyActiveNodeCount()).isEqualTo(1);
        }
    }

    @Nested
    class ModeIsPurelyInformational {
        @Test
        void bootingMode_subQuorumSnapshot_readsAreStillSnapshotValues() {
            // Below-quorum snapshot: mode stays BOOTING but reads still come from
            // the snapshot, not the in-memory fallback.
            var source = new StubSource();
            var observer = observerWith(source);

            source.set(new StubView(Set.of(SELF, PEER_A),
                                    Set.of(SELF, PEER_A),
                                    2,
                                    5));

            assertThat(observer.topologyMode()).isEqualTo(TopologyObserver.TopologyMode.BOOTING);
            assertThat(observer.healthyActiveNodeCount()).isEqualTo(2);
            assertThat(observer.readyNodeCount()).isEqualTo(2);
            assertThat(observer.coreNodes()).containsExactlyInAnyOrder(SELF, PEER_A);
        }

        @Test
        void modeTransitionsOnceAndStaysNormal() {
            var source = new StubSource();
            var observer = observerWith(source);

            assertThat(observer.topologyMode()).isEqualTo(TopologyObserver.TopologyMode.BOOTING);

            source.set(new StubView(Set.of(SELF, PEER_A, PEER_B),
                                    Set.of(SELF, PEER_A, PEER_B),
                                    3,
                                    5));
            // Touch a read to evaluate the transition.
            assertThat(observer.healthyActiveNodeCount()).isEqualTo(3);
            assertThat(observer.topologyMode()).isEqualTo(TopologyObserver.TopologyMode.NORMAL);

            // Subsequent below-quorum snapshot does not revert mode.
            source.set(new StubView(Set.of(SELF), Set.of(SELF), 1, 5));
            assertThat(observer.healthyActiveNodeCount()).isEqualTo(1);
            assertThat(observer.topologyMode()).isEqualTo(TopologyObserver.TopologyMode.NORMAL);
        }
    }
}
