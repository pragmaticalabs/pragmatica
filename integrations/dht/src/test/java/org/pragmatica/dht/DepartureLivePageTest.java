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
import org.pragmatica.consensus.net.WriteOutcome;
import org.pragmatica.dht.DHTRebalancerTest.Reply;
import org.pragmatica.dht.DHTRebalancerTest.ScriptedNetwork;
import org.pragmatica.dht.DHTRebalancerTest.TimedSend;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.DHTNode.dhtNode;
import static org.pragmatica.dht.DHTRebalancerTest.LOCAL;
import static org.pragmatica.dht.DHTRebalancerTest.PEER_A;
import static org.pragmatica.dht.DHTRebalancerTest.key;
import static org.pragmatica.dht.DHTRebalancerTest.value;
import static org.pragmatica.dht.DepartureDirectRuns.delivererOf;
import static org.pragmatica.dht.DepartureDirectRuns.direct;
import static org.pragmatica.dht.DepartureDirectRuns.fiveNodeRing;
import static org.pragmatica.dht.DepartureDirectRuns.soleKey;
import static org.pragmatica.dht.DepartureDirectRuns.takeover;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;

/// #3345. One predicate `mayAct(page)` (registered, not cancelled, current) decides send, resend, split and the count of
/// live chains, so a page a re-plan cancelled is dead like a page of an earlier tenure: never split, never resent, and it does not
/// hold the exit. A probe not answered in time is sent again while the window lasts and is never a lost holder, so a
/// slow-answering live holder costs no re-push. Every delay is driven by event order, not by the wall clock.
class DepartureLivePageTest {
    private static final DHTConfig RF1 = new DHTConfig(1, 1, 1, DHTConfig.DEFAULT_TIMEOUT);
    private static final long WINDOW_MS = 1_500;
    private static final long RESEND_MS = 100;
    private static final int KEYS = 64;
    static final int PROBE_DELAY_ROUNDS = 2;

    /// A two-key page goes to D; inside that send a joiner J takes both keys over, the re-plan cancels D's page and pages J,
    /// which acks. D stays silent: its cancelled page must not be split into single-key pages resent to D until the deadline.
    @Test
    @Timeout(30)
    void cancelledPage_silentTargetAfterReplan_neverSplitAndRunExitsEarly() {
        var ring = fiveNodeRing();
        var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF1);
        var first = soleKey(ring, "z1");
        var silent = delivererOf(ring, first, Set.of(LOCAL));
        var joiner = takeover(ring, first);
        var second = movedWith(ring, first, silent, joiner);
        node.putLocal(first, value("v1")).await();
        node.putLocal(second, value("v2")).await();
        var handle = new AtomicReference<DeparturePushRun>();
        var network = ScriptedNetwork.scripted((index, sent) -> index == 0
                                                                ? joinAtFirstSend(ring, joiner, handle, Reply.DROP)
                                                                : sent.target().equals(joiner) ? Reply.ACK : Reply.DROP);
        var started = System.nanoTime();
        var direct = direct(node, network, WINDOW_MS, RESEND_MS, Set::of, handle);
        var elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        var toSilent = network.sends().stream().filter(sent -> sent.target().equals(silent)).toList();

        assertThat(network.sends().getFirst().response().entries()).as("one two-key page to D first").hasSize(2);
        assertThat(network.sends().stream().filter(sent -> sent.target().equals(joiner))).as("control: J paged").isNotEmpty();
        assertThat(toSilent).as("no page to D after the re-plan cancelled its page").hasSize(1);
        assertThat(direct.run().exitAttempts()).as("the run attempted its exit").isPositive();
        assertThat(elapsedMs).as("the run exits early, not at the deadline").isLessThan(WINDOW_MS / 2);
        assertThat(direct.report().owed()).isZero();
    }

    /// The encode-failure split site (`encodeFailed` -> `splitPage`): D's two-key page cannot be encoded, and inside that send the
    /// joiner takes both keys over, so the re-plan cancels D's page before the refusal is handled. A cancelled page that fails to
    /// encode is never split into single-key pages: D gets exactly the one page and no key is marked undeliverable. The joiner's probes are
    /// answered late so the run is still open when the refusal is handled (a closed run would stop the split by itself, masking it).
    @Test
    @Timeout(30)
    void cancelledPage_encodeFailedAfterReplan_neverSplit() {
        var ring = fiveNodeRing();
        var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF1);
        var first = soleKey(ring, "z5");
        var refusing = delivererOf(ring, first, Set.of(LOCAL));
        var joiner = takeover(ring, first);
        var second = movedWith(ring, first, refusing, joiner);
        node.putLocal(first, value("v1")).await();
        node.putLocal(second, value("v2")).await();
        var handle = new AtomicReference<DeparturePushRun>();
        var network = ScriptedNetwork.scripted((index, sent) -> index == 0
                                                                ? joinAtFirstSend(ring, joiner, handle, ENCODE_FAILED)
                                                                : sent.target().equals(joiner) ? Reply.ACK : ENCODE_FAILED);
        var self = new AtomicReference<ScriptedNetwork>();
        var probeSends = new CopyOnWriteArrayList<TimedSend>();
        self.set(network);
        network.probesAnswer(probe -> answerEarlierProbe(probe, probeSends, self));
        var direct = direct(node, network, WINDOW_MS, RESEND_MS, Set::of, handle);

        assertThat(network.sends().getFirst().response().entries()).as("one two-key page to D first").hasSize(2);
        assertThat(network.sends().stream().filter(sent -> sent.target().equals(joiner))).as("control: J paged").isNotEmpty();
        assertThat(network.sends().stream().filter(sent -> sent.target().equals(refusing))).as("D's cancelled page never split")
                                                                                           .hasSize(1);
        assertThat(direct.report().undeliverable()).as("no single-key page of the cancelled page failed").isZero();
        assertThat(direct.report().owed()).isZero();
    }

    /// P2, the resend site: D's page is unanswered while D leaves the live ring WITHOUT any re-plan (the run's view V still has D). May-act
    /// is read from V, never the live ring: D's page is still resent while its window lasts.
    @Test
    @Timeout(30)
    void ringChangeWithoutObservation_unansweredPageStillResent() {
        var ring = fiveNodeRing();
        var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF1);
        var key = soleKey(ring, "z6");
        var target = delivererOf(ring, key, Set.of(LOCAL));
        node.putLocal(key, value("v1")).await();
        var network = ScriptedNetwork.scripted((index, _) -> index == 0 ? silentRingLoss(ring, target) : Reply.DROP);
        var direct = direct(node, network, WINDOW_MS, RESEND_MS, Set::of);

        assertThat(network.sends().stream().filter(sent -> sent.target().equals(target))).as("D's page is resent from V, not gated on the live ring")
                                                                                         .hasSizeGreaterThan(1);
        assertThat(direct.report()).isNotNull();
    }

    /// P2, the ack site: D's ack arrives while D has left the live ring WITHOUT any re-plan (V still has D). The ack counts (`inTenure` reads V):
    /// the key is safe at D and nothing stays owed.
    @Test
    @Timeout(30)
    void ringChangeWithoutObservation_ackStillCounts() {
        var ring = fiveNodeRing();
        var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF1);
        var key = soleKey(ring, "z7");
        var target = delivererOf(ring, key, Set.of(LOCAL));
        node.putLocal(key, value("v1")).await();
        var network = ScriptedNetwork.scripted((index, _) -> index == 0 ? silentRingLoss(ring, target, Reply.ACK) : Reply.ACK);
        var direct = direct(node, network, WINDOW_MS, RESEND_MS, Set::of);

        assertThat(direct.report().owed()).as("the ack of a node the ring dropped unobserved still counts").isZero();
    }

    private static Reply silentRingLoss(ConsistentHashRing<NodeId> ring, NodeId target) {
        return silentRingLoss(ring, target, Reply.DROP);
    }

    private static Reply silentRingLoss(ConsistentHashRing<NodeId> ring, NodeId target, Reply answer) {
        ring.removeNode(target);

        return answer;
    }

    private static final Reply ENCODE_FAILED = Reply.refuse(id -> new WriteOutcome.EncodeFailed(id, "MigrationDataResponse"));

    /// The split site: D answers its two-key page with a nack inside the send that lets the joiner take both keys over, so
    /// the page is cancelled when its nack pause ends: a cancelled page is never halved, D gets exactly the one page. The joiner's probe is
    /// answered late (event order) so that the run is still open when the pause ends.
    @Test
    @Timeout(30)
    void cancelledPage_nackedAfterReplan_neverHalved() {
        var ring = fiveNodeRing();
        var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF1);
        var first = soleKey(ring, "z3");
        var nacking = delivererOf(ring, first, Set.of(LOCAL));
        var joiner = takeover(ring, first);
        var second = movedWith(ring, first, nacking, joiner);
        node.putLocal(first, value("v1")).await();
        node.putLocal(second, value("v2")).await();
        var handle = new AtomicReference<DeparturePushRun>();
        var network = ScriptedNetwork.scripted((index, sent) -> index == 0
                                                                ? joinAtFirstSend(ring, joiner, handle, Reply.NACK)
                                                                : sent.target().equals(joiner) ? Reply.ACK : Reply.DROP);
        var self = new AtomicReference<ScriptedNetwork>();
        var probeSends = new CopyOnWriteArrayList<TimedSend>();
        self.set(network);
        network.probesAnswer(probe -> answerEarlierProbe(probe, probeSends, self));
        var direct = direct(node, network, WINDOW_MS, RESEND_MS, Set::of, handle);

        assertThat(network.sends().getFirst().response().entries()).as("one two-key page to D first").hasSize(2);
        assertThat(network.sends().stream().filter(sent -> sent.target().equals(joiner))).as("control: J paged").isNotEmpty();
        assertThat(network.probes()).as("control: the run stayed open past D's nack pause").hasSizeGreaterThan(PROBE_DELAY_ROUNDS);
        assertThat(network.sends().stream().filter(sent -> sent.target().equals(nacking))).as("D's cancelled page never halved")
                                                                                          .hasSize(1);
        assertThat(direct.report().owed()).isZero();
    }

    /// 64 keys in one page, acked at once; every probe is answered only when the probe has been sent PROBE_DELAY_ROUNDS more
    /// times (twice the resend wait). The slow answer must count: no lost holder, no re-push, nothing owed.
    @Test
    @Timeout(30)
    void slowProbes_answeredAfterTwoResendWaits_noLossNoRepushNothingOwed() {
        var ring = ConsistentHashRing.<NodeId>consistentHashRing();
        List.of(LOCAL, PEER_A).forEach(ring::addNode);
        var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF1);
        IntStream.range(0, KEYS).forEach(i -> node.putLocal(key("probe-" + i), value("payload")).await());
        var self = new AtomicReference<ScriptedNetwork>();
        var probeSends = new CopyOnWriteArrayList<TimedSend>();
        var network = ScriptedNetwork.scripted((_, _) -> Reply.ACK);
        self.set(network);
        network.probesAnswer(probe -> answerEarlierProbe(probe, probeSends, self));
        var direct = direct(node, network, WINDOW_MS, RESEND_MS, Set::of);
        var keySends = network.sends().stream().mapToLong(sent -> sent.response().entries().size()).sum();

        assertThat(network.probes()).as("control: the probe was re-sent before its answer").hasSizeGreaterThan(PROBE_DELAY_ROUNDS);
        assertThat(direct.run().holderLosses()).as("a slow probe is no lost holder").isZero();
        assertThat(keySends).as("n * the resend rounds the probes take").isLessThanOrEqualTo((long) KEYS * PROBE_DELAY_ROUNDS);
        assertThat(direct.report().owed()).isZero();
    }

    /// The join happens inside the first send, so the re-plan meets D's page in flight; J's chain starts from it.
    private static Reply joinAtFirstSend(ConsistentHashRing<NodeId> ring,
                                         NodeId joiner,
                                         AtomicReference<DeparturePushRun> handle,
                                         Reply answer) {
        ring.addNode(joiner);
        handle.get().onViewChange();

        return answer;
    }

    /// The answer to probe send r is delivered at probe send r + PROBE_DELAY_ROUNDS.
    static Reply answerEarlierProbe(TimedSend probe, List<TimedSend> probeSends, AtomicReference<ScriptedNetwork> self) {
        probeSends.add(probe);
        var due = probeSends.size() - 1 - PROBE_DELAY_ROUNDS;

        if (due >= 0) {
            self.get().deliver(probeSends.get(due), Reply.ACK);
        }

        return Reply.DROP;
    }

    /// A second key of LOCAL with deliverer `silent` that `joiner` also takes over.
    private static byte[] movedWith(ConsistentHashRing<NodeId> ring, byte[] first, NodeId silent, NodeId joiner) {
        var plus = ConsistentHashRing.<NodeId>consistentHashRing();
        ring.nodes().forEach(plus::addNode);
        plus.addNode(joiner);

        return IntStream.range(0, 20_000)
                        .mapToObj(i -> soleKey(ring, "z2-" + i))
                        .filter(candidate -> !Arrays.equals(candidate, first))
                        .filter(candidate -> delivererOf(ring, candidate, Set.of(LOCAL)).equals(silent))
                        .filter(candidate -> delivererOf(plus, candidate, Set.of(LOCAL)).equals(joiner))
                        .findFirst()
                        .orElseThrow();
    }
}
