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

package org.pragmatica.dht;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.DhtClientCluster.bytes;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #1777 track 2: the catch-up gate. A node that became a replica of a partition through a ring change, or
/// at boot, answers "not caught up" for it until anti-entropy has filled it from the nodes that may hold its
/// data — the current co-replicas and the surviving previous holders — and reads count "absent" only from
/// replicas that are serving.
///
/// Runs a small multi-node cluster over a synchronous in-process network. Every node has a real
/// [DHTNode], [DHTAntiEntropy], [DHTTopologyListener] and [DistributedDHTClient]; no periodic task runs, so
/// every catch-up step in a test is one the test asked for.
class DHTCatchUpSilentHolderTest {
    /// v1820 (#1823 r2): two replicas restart (pending since boot), the third — the only holder left — is live but silent.
    /// The boot walk adds non-owners that answer SERVING with nothing. Must the restarted replicas wait?
    @Test
    void s1_rollingRestart_silentLastHolder_restartedReplicasDoNotServeEmpty() throws InterruptedException {
        var cluster = DhtClientCluster.of(6, timeSpan(1).millis());
        var key = bytes("s1-only-on-silent-holder");
        var replicas = cluster.replicasOf(key);

        cluster.seedOnReplicas(key);
        cluster.restart(replicas.get(0));
        cluster.restart(replicas.get(1));
        cluster.silence(replicas.get(2));
        for (int round = 0; round < 3; round++) {
            cluster.member(replicas.get(0)).antiEntropy().catchUpNow();
            cluster.member(replicas.get(1)).antiEntropy().catchUpNow();
            Thread.sleep(5);
        }
        var read = cluster.member(cluster.readerOutside(replicas)).client().get(key).await();
        assertThat(read).as("never an authoritative absent while the only holder is silent")
                        .isNotEqualTo(Result.success(Option.<byte[]>none()));
    }

    /// v1820 (#1823 r4): with a join since boot the walk is RF + 1 long and reaches a serving NON-owner holding
    /// nothing. It must never anchor a timed-out decision while the only holder is silent.
    @Test
    void s1b_walkNodeServingEmpty_neverAnchors_afterAJoinSinceBoot() throws InterruptedException {
        var cluster = DhtClientCluster.of(6, timeSpan(1).millis());
        var key = bytes("s1b-only-on-silent-holder");
        var replicas = cluster.replicasOf(key);

        cluster.seedOnReplicas(key);
        cluster.restart(replicas.get(0));
        cluster.restart(replicas.get(1));

        NodeId joiner = null;
        for (int i = 0; i < 20_000 && joiner == null; i++) {
            var candidate = new NodeId("s1b-join-" + i);
            var probe = ConsistentHashRing.<NodeId>consistentHashRing();
            cluster.member(replicas.get(0)).node().ring().nodes().forEach(probe::addNode);
            probe.addNode(candidate);
            if (!probe.nodesFor(key, 4).contains(candidate)) joiner = candidate;
        }
        cluster.joinWithoutCatchUp(joiner);
        assertThat(cluster.member(replicas.get(0)).node().previousHolders(cluster.partitionOf(key)))
            .as("control: the walk reaches a serving non-owner").anyMatch(id -> !replicas.contains(id));

        cluster.silence(replicas.get(2));
        for (int round = 0; round < 3; round++) {
            cluster.member(replicas.get(0)).antiEntropy().catchUpNow();
            cluster.member(replicas.get(1)).antiEntropy().catchUpNow();
            Thread.sleep(5);
        }
        var read = cluster.member(cluster.readerOutside(replicas)).client().get(key).await();
        assertThat(read).as("never an authoritative absent while the only holder is silent")
                        .isNotEqualTo(Result.success(Option.<byte[]>none()));
    }
}
