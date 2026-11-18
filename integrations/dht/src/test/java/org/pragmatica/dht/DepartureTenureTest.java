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

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.DHTNode.dhtNode;
import static org.pragmatica.dht.DHTRebalancerTest.LOCAL;
import static org.pragmatica.dht.DHTRebalancerTest.value;
import static org.pragmatica.dht.DepartureDirectRuns.delivererOf;
import static org.pragmatica.dht.DepartureDirectRuns.carriesKey;
import static org.pragmatica.dht.DepartureDirectRuns.direct;
import static org.pragmatica.dht.DepartureDirectRuns.otherTargetKey;
import static org.pragmatica.dht.DepartureDirectRuns.sameDeliverer;
import static org.pragmatica.dht.DepartureDirectRuns.fiveNodeRing;
import static org.pragmatica.dht.DepartureDirectRuns.soleKey;
import static org.pragmatica.dht.DepartureDirectRuns.takeover;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;

/// #3345 tenures (I1, I2): a copy acked in an earlier tenure never counts after a re-entry, and a node entering the ring after a
/// re-plan's observation gets no page from that re-plan, so every page it gets carries its current tenure.
class DepartureTenureTest {
    private static final DHTConfig RF1 = new DHTConfig(1, 1, 1, DHTConfig.DEFAULT_TIMEOUT);

    /// The target acks the key, then leaves V and returns (each change observed by a re-plan); the copy of its earlier tenure
    /// does not make the key safe, so the key is paged to it again. Another target stays silent, so no exit closes the run first.
    @Test
    @Timeout(20)
    void copyAckedBeforeLeaving_isPagedAgainAfterTheReEntry() {
        var ring = fiveNodeRing();
        var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF1);
        var k = soleKey(ring, "tn");
        var target = delivererOf(ring, k, Set.of(LOCAL));
        var silent = otherTargetKey(ring, target, "tn-silent");
        node.putLocal(k, value("payload")).await();
        node.putLocal(silent, value("payload")).await();
        var handle = new AtomicReference<DeparturePushRun>();
        var silentSends = new AtomicInteger();
        var network = ScriptedNetwork.scripted((_, sent) -> carriesKey(sent, silent)
                                                            ? leaveAndReturnAtSecond(ring, target, silentSends, handle)
                                                            : Reply.ACK);
        var run = direct(node, network, 800, 100, Set::of, handle);

        assertThat(silentSends).as("the target left and returned").hasValueGreaterThanOrEqualTo(2);
        assertThat(network.sends()).as("once per tenure")
                                   .filteredOn(sent -> sent.target().equals(target) && carriesKey(sent, k))
                                   .hasSize(2);
        assertThat(run.report().owed()).as("only the silent target's key").isEqualTo(1);
    }

    /// The stale-tag race: the key's new deliverer enters the ring after the first re-plan observed it and before that re-plan
    /// pages. That re-plan pages the deliverer it observed; the next observation pages the joiner once, in its current tenure, so
    /// its ack counts: one send per target, nothing owed.
    @Test
    @Timeout(20)
    void targetEntersBetweenObservationAndUse_oneSendPerTarget_nothingOwed() {
        var ring = fiveNodeRing();
        var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF1);
        var k = soleKey(ring, "st");
        node.putLocal(k, value("payload")).await();
        var observed = delivererOf(ring, k, Set.of(LOCAL));
        var joiner = takeover(ring, k);
        var entered = new AtomicBoolean();
        var network = ScriptedNetwork.scripted((_, _) -> Reply.ACK);
        var run = direct(node, network, 1_500, 100, () -> enterDuringObservation(ring, joiner, entered));

        assertThat(entered).as("the joiner entered inside the first observation").isTrue();
        assertThat(network.targets()).containsExactly(observed, joiner);
        assertThat(run.report().owed()).isZero();
    }

    /// Control: the joiner is in the ring from the start: one send, to it, nothing owed.
    @Test
    @Timeout(20)
    void targetPresentFromTheStart_oneSend_nothingOwed() {
        var ring = fiveNodeRing();
        var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF1);
        var k = soleKey(ring, "st");
        node.putLocal(k, value("payload")).await();
        var joiner = takeover(ring, k);
        ring.addNode(joiner);
        var network = ScriptedNetwork.scripted((_, _) -> Reply.ACK);
        var run = direct(node, network, 1_500, 100, Set::of);

        assertThat(network.targets()).containsExactly(joiner);
        assertThat(run.report().owed()).isZero();
    }

    /// A stale ack: page [a, b, c] to the target is silent; meanwhile the target leaves V (observed) and re-enters the ring. The
    /// re-entry is observed at the next send; a, b and c are paged in the current tenure and acked, and right after each such ack
    /// the target acks the silent page of its earlier tenure too. That page is not current (P4), so its ack never counts: it never
    /// replaces the current holder record, and a, b and c stay safe (with the tenure clause of `current` off it replaces the
    /// record after every current ack, and a, b and c are owed).
    @Test
    @Timeout(20)
    void staleTenureAckAfterACurrentTenureAck_currentHolderKeepsItsCopies() {
        var ring = fiveNodeRing();
        var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF1);
        var a = soleKey(ring, "sh");
        var keys = List.of(a, sameDeliverer(ring, a, "sh-b"), sameDeliverer(ring, a, "sh-c"));
        var target = delivererOf(ring, a, Set.of(LOCAL));
        keys.forEach(key -> node.putLocal(key, value("payload")).await());
        var handle = new AtomicReference<DeparturePushRun>();
        var script = new StaleAckScript(ring, target, handle, new AtomicReference<>(), new AtomicReference<>(), new AtomicBoolean());
        var network = ScriptedNetwork.scripted((_, sent) -> staleAckSchedule(sent, script));
        script.network().set(network);
        var run = direct(node, network, 1_500, 100, Set::of, handle);

        assertThat(script.observed()).as("the re-entry was observed").isTrue();
        assertThat(network.sends()).as("[a, b, c] in each tenure, acked in the current one")
                                   .filteredOn(sent -> sent.target().equals(target) && sent.response().entries().size() == 3)
                                   .hasSizeGreaterThanOrEqualTo(2);
        assertThat(run.report().owed()).as("a, b and c, acked in the current tenure, are not owed").isZero();
    }

    private record StaleAckScript(ConsistentHashRing<NodeId> ring,
                                  NodeId target,
                                  AtomicReference<DeparturePushRun> handle,
                                  AtomicReference<ScriptedNetwork> network,
                                  AtomicReference<TimedSend> stale,
                                  AtomicBoolean observed) {}

    /// The target's first page leaves V (observed) and re-enters the ring, silent; the first send after the return observes the
    /// re-entry.
    /// The target acks every later page, then acks the stale one; sends elsewhere are silent.
    private static Reply staleAckSchedule(TimedSend sent, StaleAckScript script) {
        if (sent.target().equals(script.target()) && script.stale().compareAndSet(null, sent)) {
            script.ring().removeNode(script.target());
            script.handle().get().onViewChange();
            script.ring().addNode(script.target());
            return Reply.DROP;
        }
        if (script.stale().get() != null
            && script.ring().nodes().contains(script.target())
            && script.observed().compareAndSet(false, true)) {
            script.handle().get().onViewChange();
        }
        if (sent.target().equals(script.target())) {
            script.network().get().deliver(sent, Reply.ACK);
            script.network().get().deliver(script.stale().get(), Reply.ACK);
        }
        return Reply.DROP;
    }

    /// The drain set is read inside the re-plan's observation, right after its ring snapshot: the joiner enters there, once.
    private static Set<NodeId> enterDuringObservation(ConsistentHashRing<NodeId> ring, NodeId joiner, AtomicBoolean entered) {
        if (StackWalker.getInstance().walk(frames -> frames.anyMatch(frame -> frame.getMethodName().equals("observeView")))
            && entered.compareAndSet(false, true)) {
            ring.addNode(joiner);
        }
        return Set.of();
    }

    private static Reply leaveAndReturnAtSecond(ConsistentHashRing<NodeId> ring,
                                                NodeId target,
                                                AtomicInteger sends,
                                                AtomicReference<DeparturePushRun> handle) {
        if (sends.getAndIncrement() == 1) {
            ring.removeNode(target);
            handle.get().onViewChange();
            ring.addNode(target);
            handle.get().onViewChange();
        }
        return Reply.DROP;
    }
}
