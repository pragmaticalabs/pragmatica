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

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.pragmatica.dht.DhtClientCluster.bytes;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #420 PR-0a: the scenario controls for `locate` (T-L1, T-L2), on [DhtClientCluster]. `locate` does not exist yet; each
/// enabled test asserts what the cluster does TODAY, with the controls the design names, and the real assertion sits
/// `@Disabled` beside it until PR-4b (`DhtLocator`) adds `locate`.
class DhtLocateTest {
    private static final int RING_SIZE = 6;

    /// T-L1 scenario: X is a ring member that is NOT a replica of the key, distinct from the reader, holding a stray copy
    /// and silent. A `get` reads every replica, so only the fallback probe of non-replica members reaches X; its
    /// timeout degrades to "no entry". PR-4b flips the outcome: `locate` must be `Unknown`, not absent.
    @Test
    void tL1_silentNonReplicaHolder_getAnswersEmpty_beforePR4b() {
        var cluster = DhtClientCluster.of(RING_SIZE, timeSpan(1).millis());
        var key = bytes("tl1-stray-copy");
        var replicas = cluster.replicasOf(key);
        var x = cluster.readerOutside(replicas);
        var reader = cluster.readerOutsideOtherThan(replicas, x);

        cluster.seedOnly(x, key);
        cluster.silence(x);

        assertThat(cluster.members()).as("control: DC has 6 members, inside the probe limit of 8").hasSize(RING_SIZE);
        assertThat(replicas).as("control: RF 3").hasSize(3);
        assertThat(replicas).as("control: X is not a replica of the key").doesNotContain(x);
        assertThat(reader).as("control: the reader is not X").isNotEqualTo(x);
        assertThat(cluster.holds(x, key)).as("control: X holds the stray copy").isTrue();
        assertThat(replicas.stream().anyMatch(id -> cluster.holds(id, key))).as("control: no replica holds the key")
                                                                           .isFalse();
        assertThat(cluster.member(reader).client().get(key).await())
            .as("T-L1 (flips in PR-4b, where locate must be Unknown): a timeout of the fallback probe is scored as no entry today")
            .isEqualTo(Result.success(Option.<byte[]>none()));
    }

    /// T-L1 positive control: with X answering, the same read finds the stray copy through the fallback probe, so the
    /// silent arm above is the probe timeout and nothing else.
    @Test
    void tL1_answeringNonReplicaHolder_getFindsStrayCopyThroughFallback() {
        var cluster = DhtClientCluster.of(RING_SIZE, timeSpan(1).millis());
        var key = bytes("tl1-stray-copy");
        var replicas = cluster.replicasOf(key);
        var x = cluster.readerOutside(replicas);
        var reader = cluster.readerOutsideOtherThan(replicas, x);

        cluster.seedOnly(x, key);

        var read = cluster.member(reader).client().get(key).await();

        assertThat(read.or(Option.none()).isPresent()).as("control: the fallback probe reaches X when X answers").isTrue();
    }

    /// The real T-L1 assertion: `locate` is `Unknown` with X silent, `Found` with X answering, `Absent` with no holder.
    @Disabled("PR-4b adds DhtLocator and locate; enable then and assert Unknown / Found / Absent")
    @Test
    void tL1_locateIsUnknown_whenTheOnlyHolderIsSilent() {
        fail("PR-4b: assert locate is Unknown with X silent, Found with X answering, Absent with no holder");
    }

    /// T-L2 scenario: X, a replica holding the only copy, is told DEPARTING on every member, so it is pruned from every
    /// ring. Controls: X is not in the reader ring set for the key, X still holds, and no new replica holds the key.
    /// Today the get misses; PR-4b flips the outcome: `locate` from a survivor must be `Found`.
    @Test
    void tL2_departingHolderPruned_getAnswersEmpty_beforePR4b() {
        var cluster = DhtClientCluster.of(RING_SIZE, timeSpan(1).millis());
        var key = bytes("tl2-departing-holder");
        var replicas = cluster.replicasOf(key);
        var x = replicas.getFirst();
        var reader = cluster.readerOutside(replicas);

        cluster.seedOnly(x, key);
        cluster.members().forEach(member -> member.listener().onNodeDeparting(x));

        var readerSet = cluster.member(reader).node().ring().nodesFor(key, 3);

        assertThat(readerSet).as("control: X is not in the reader ring set for the key").doesNotContain(x);
        assertThat(cluster.holds(x, key)).as("control: X still holds its copy").isTrue();
        assertThat(readerSet.stream().anyMatch(id -> cluster.holds(id, key)))
            .as("control: no new replica holds the key").isFalse();
        assertThat(cluster.member(reader).client().get(key).await())
            .as("T-L2 (flips in PR-4b, where locate from a survivor must be Found): the get misses the departing drainer copy today")
            .isEqualTo(Result.success(Option.<byte[]>none()));
    }

    /// The real T-L2 assertion: `locate` from a survivor is `Found`, on a DC network whose `livePeers` excludes X.
    @Disabled("PR-4b adds DhtLocator and locate; enable then and assert Found on a network whose livePeers excludes X")
    @Test
    void tL2_locateFindsTheDepartingHoldersCopy() {
        fail("PR-4b: assert locate from a survivor is Found, with livePeers excluding X (not routed through filterByLiveness)");
    }
}
