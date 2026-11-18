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
import org.junit.jupiter.api.Timeout;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.dht.DHTRebalancerTest.Reply;
import org.pragmatica.dht.DHTRebalancerTest.ScriptedNetwork;
import org.pragmatica.dht.DHTRebalancerTest.TimedSend;
import org.pragmatica.dht.DepartureDirectRuns.Direct;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.DHTNode.dhtNode;
import static org.pragmatica.dht.DHTRebalancerTest.LOCAL;
import static org.pragmatica.dht.DHTRebalancerTest.value;
import static org.pragmatica.dht.DepartureDirectRuns.delivererOf;
import static org.pragmatica.dht.DepartureDirectRuns.direct;
import static org.pragmatica.dht.DepartureDirectRuns.fiveNodeRing;
import static org.pragmatica.dht.DepartureDirectRuns.sameDeliverer;
import static org.pragmatica.dht.DepartureDirectRuns.soleKey;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;

/// #3345 P4: a page lives in ONE tenure of its target. The target's first page is silent; meanwhile the target leaves V (a
/// re-plan observes it) and re-enters the ring. A page of the earlier tenure is dead from the observation of the re-entry on: it
/// is never split, resent or counted live, so its keys are paged again in the current tenure and acked there. Each test bounds
/// the data pages the target gets: the earlier tenure's one page, then the current tenure's pages.
class DepartureCurrentTenureTest {
    private static final long OBSERVE_AFTER_NANOS = TimeUnit.MILLISECONDS.toNanos(300);

    private record Script(ConsistentHashRing<NodeId> ring, NodeId target, AtomicReference<DeparturePushRun> handle,
                          AtomicLong returnedAt, AtomicBoolean observed, AtomicInteger pagesOfTwo) {}

    /// The 2-key schedule: the page [a, b] is silent and split after the silence while the re-entry is not yet observed (the
    /// target is acking by then); the re-entry is observed 3 `resendAfter` later. Bound: 2 data pages to the target, [a, b] of
    /// each tenure. Before P4 the halves of the earlier tenure were resent to the deadline, never counted, and kept a and b
    /// covered, so both were owed.
    @Test
    @Timeout(20)
    void pageSplitAfterSilence_targetReentersBeforeObservation_nothingOwed() {
        var outcome = runOver("cp", 2, DepartureCurrentTenureTest::observeLater);

        assertThat(outcome.direct().report().owed()).as("acked in the current tenure").isZero();
        assertThat(outcome.dataPages()).as("[a, b] once per tenure, no halves of the earlier tenure").isLessThanOrEqualTo(2);
    }

    /// One key: the page [a] of the earlier tenure is never resent once the re-entry is observed, and its acks before that never
    /// count. Bound: 2 data pages to the target, [a] of each tenure.
    @Test
    @Timeout(20)
    void singleKeyPage_targetReentersBeforeObservation_isNotResent() {
        var outcome = runOver("cs", 1, DepartureCurrentTenureTest::observeLater);

        assertThat(outcome.direct().report().owed()).as("acked in the current tenure").isZero();
        assertThat(outcome.dataPages()).as("[a] once per tenure").isLessThanOrEqualTo(2);
    }

    /// The re-entry is observed inside the first page's send, while that page waits for its ack: it is not counted live, so a and
    /// b are paged again at once in the current tenure; that page is silent too and its halves are acked. Bound: 4 data pages to
    /// the target, [a, b] of each tenure and the current tenure's halves.
    @Test
    @Timeout(20)
    void pageAwaitingItsAck_reentryObserved_isNotCountedLive() {
        var outcome = runOver("cl", 2, DepartureCurrentTenureTest::observeAtOnce);

        assertThat(outcome.direct().report().owed()).as("acked in the current tenure").isZero();
        assertThat(outcome.dataPages()).as("no split of the earlier tenure's page").isLessThanOrEqualTo(4);
    }

    private record Outcome(Direct direct, long dataPages) {}

    private static Outcome runOver(String prefix, int keyCount, BiFunction<TimedSend, Script, Reply> schedule) {
        var ring = fiveNodeRing();
        var node = dhtNode(LOCAL, memoryStorageEngine(), ring, new DHTConfig(1, 1, 1, DHTConfig.DEFAULT_TIMEOUT));
        var a = soleKey(ring, prefix);
        var keys = keyCount == 1 ? List.of(a) : List.of(a, sameDeliverer(ring, a, prefix + "-b"));
        var target = delivererOf(ring, a, Set.of(LOCAL));
        keys.forEach(key -> node.putLocal(key, value("payload")).await());
        var handle = new AtomicReference<DeparturePushRun>();
        var script = new Script(ring, target, handle, new AtomicLong(), new AtomicBoolean(), new AtomicInteger());
        var network = ScriptedNetwork.scripted((_, sent) -> schedule.apply(sent, script));
        var direct = direct(node, network, 1_500, 100, Set::of, handle);
        return new Outcome(direct, network.sends().stream().filter(sent -> isTarget(sent, script) && !sent.response().entries().isEmpty()).count());
    }

    /// The target's first page: it leaves V (observed) and re-enters the ring, silent. Any send 300 ms after the return observes
    /// the re-entry, once. The target acks every later send; every other node is silent.
    private static Reply observeLater(TimedSend sent, Script script) {
        if (isTarget(sent, script) && script.returnedAt().get() == 0) {
            leaveAndReturn(script);
            script.returnedAt().set(System.nanoTime());
            return Reply.DROP;
        }
        if (script.returnedAt().get() != 0
            && System.nanoTime() - script.returnedAt().get() >= OBSERVE_AFTER_NANOS
            && script.observed().compareAndSet(false, true)) {
            script.handle().get().onViewChange();
        }
        return isTarget(sent, script) ? Reply.ACK : Reply.DROP;
    }

    /// The target's first page: it leaves V and re-enters, each observed, silent; its second page of two keys is silent; it acks
    /// every other send; every other node is silent.
    private static Reply observeAtOnce(TimedSend sent, Script script) {
        if (!isTarget(sent, script)) {
            return Reply.DROP;
        }
        if (sent.response().entries().size() == 2 && script.pagesOfTwo().incrementAndGet() <= 2) {
            if (script.pagesOfTwo().get() == 1) {
                leaveAndReturn(script);
                script.handle().get().onViewChange();
            }
            return Reply.DROP;
        }
        return Reply.ACK;
    }

    private static void leaveAndReturn(Script script) {
        script.ring().removeNode(script.target());
        script.handle().get().onViewChange();
        script.ring().addNode(script.target());
    }

    private static boolean isTarget(TimedSend sent, Script script) {
        return sent.target().equals(script.target());
    }
}
