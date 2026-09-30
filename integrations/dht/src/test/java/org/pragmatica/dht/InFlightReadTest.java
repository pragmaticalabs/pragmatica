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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.InFlightRead.inFlightRead;
import static org.pragmatica.lang.Promise.promise;

class InFlightReadTest {
    private static final NodeId A = new NodeId("a");
    private static final NodeId B = new NodeId("b");
    private static final NodeId C = new NodeId("c");
    private static final NodeId D = new NodeId("d");
    private static final long FAR_FUTURE = System.nanoTime() + 3_600_000_000_000L;

    private static InFlightRead read(long deadlineNanos, int maxReissues) {
        return inFlightRead(new byte[]{1},
                            QuorumCollector.quorumCollector(2, 3, promise()),
                            deadlineNanos,
                            maxReissues);
    }

    @Test
    void nextReplacement_skipsAddressedNodes_pickingFirstNewOne() {
        var read = read(FAR_FUTURE, 3);
        read.expect(A, "1");
        read.markAddressed(B);

        assertThat(read.nextReplacement(List.of(A, B, C, D))).isEqualTo(Option.some(C));
        assertThat(read.nextReplacement(List.of(A, B, C, D))).isEqualTo(Option.some(D));
    }

    @Test
    void nextReplacement_isEmpty_whenReplicaSetHoldsNobodyNew() {
        var read = read(FAR_FUTURE, 3);
        read.markAddressed(A);
        read.markAddressed(B);

        assertThat(read.nextReplacement(List.of(A, B))).isEqualTo(Option.none());
    }

    @Test
    void nextReplacement_isEmpty_afterBudgetSpent() {
        var read = read(FAR_FUTURE, 2);

        assertThat(read.nextReplacement(List.of(A, B, C, D)).isPresent()).isTrue();
        assertThat(read.nextReplacement(List.of(A, B, C, D)).isPresent()).isTrue();
        assertThat(read.nextReplacement(List.of(A, B, C, D))).isEqualTo(Option.none());
    }

    @Test
    void nextReplacement_isEmpty_pastTheReadDeadline() {
        var read = read(System.nanoTime() - 1, 3);

        assertThat(read.nextReplacement(List.of(A, B, C, D))).isEqualTo(Option.none());
    }

    @Test
    void claim_handsBackCorrelationIdOnce() {
        var read = read(FAR_FUTURE, 3);
        read.expect(A, "id-1");

        assertThat(read.claim(A)).isEqualTo(Option.some("id-1"));
        assertThat(read.claim(A)).isEqualTo(Option.none());
    }

    @Test
    void nextReplacement_neverHandsOutSameNodeTwice_underConcurrentDepartures() throws InterruptedException {
        var read = read(FAR_FUTURE, 100);
        var picked = Collections.synchronizedList(new ArrayList<NodeId>());
        var candidates = List.of(A, B, C, D);
        var threads = new ArrayList<Thread>();

        for (var i = 0; i < 8; i++) {
            threads.add(Thread.ofPlatform().start(() -> read.nextReplacement(candidates).onPresent(picked::add)));
        }
        for (var thread : threads) {
            thread.join();
        }

        assertThat(picked).doesNotHaveDuplicates().hasSize(4);
    }
}
