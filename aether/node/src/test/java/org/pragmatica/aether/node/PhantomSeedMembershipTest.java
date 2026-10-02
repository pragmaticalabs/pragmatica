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

package org.pragmatica.aether.node;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.dht.ConsistentHashRing;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.dht.DHTNode;
import org.pragmatica.dht.DHTTopologyListener;
import org.pragmatica.dht.storage.MemoryStorageEngine;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.swim.SwimConfig;
import org.pragmatica.swim.SwimMember;
import org.pragmatica.swim.SwimMember.MemberState;
import org.pragmatica.swim.SwimMembershipListener;
import org.pragmatica.swim.SwimMessage;
import org.pragmatica.swim.SwimMessage.Ack;
import org.pragmatica.swim.SwimMessage.MembershipUpdate;
import org.pragmatica.swim.SwimMessage.Ping;
import org.pragmatica.swim.SwimProtocol;
import org.pragmatica.swim.SwimTransport;
import org.pragmatica.swim.TransportObservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.swim.SwimConfig.swimConfig;

/// #1830 at the membership level: a real `SwimProtocol` feeding a real `MembershipFsm` through the production
/// router (`AetherNode.routeSwimEdgeToMembershipFsm`), whose DEPARTING edge prunes a real DHT ring through
/// `DHTTopologyListener.onNodeDeparting` — the wiring `AetherNode.assembleNode` installs. Both the FSM and the
/// ring are seeded from the static core list, as at boot, so every seed starts as a counted MEMBER in the ring.
///
/// Run-7 timeline, time-scaled (suspectTimeout 150 ms; the cold-boot window is the `booting` flag): a
/// replacement boots listing a core that departed before it started, whose IP now belongs to another node.
/// The phantom must leave the membership count and the ring within the bound — at the refutation if the
/// dial says so, else when the window closes — while a genuine seed that comes up late stays.
class PhantomSeedMembershipTest {
    private static final NodeId SELF = new NodeId("core-self");
    private static final NodeId PHANTOM = new NodeId("core-phantom");
    private static final NodeId GENUINE = new NodeId("core-genuine");
    private static final NodeId RECYCLED_HOLDER = new NodeId("core-recycled-holder");
    private static final InetSocketAddress SELF_ADDR = new InetSocketAddress("127.0.0.1", 9100);
    private static final InetSocketAddress PHANTOM_ADDR = new InetSocketAddress("127.0.0.1", 9101);
    private static final InetSocketAddress GENUINE_ADDR = new InetSocketAddress("127.0.0.1", 9102);
    /// Shorter than the SWIM FAULTY residency (3 × 150 ms), so a removal inside it cannot come from the
    /// sweep → tombstone → re-probe path that took 3 min 40 s in run 7.
    private static final Duration WITHIN_RESIDENCY = Duration.ofMillis(300);
    private static final Duration PAST_RESIDENCY = Duration.ofMillis(700);

    private final AtomicBoolean booting = new AtomicBoolean(true);
    private final AtomicBoolean genuineUp = new AtomicBoolean(false);
    private final List<NodeId> enteredDeparting = new CopyOnWriteArrayList<>();
    private ConsistentHashRing<NodeId> ring;
    private MembershipFsm membership;
    private SwimProtocol swim;
    private DHTTopologyListener dhtTopology;

    @BeforeEach
    void bootWithStaticSeeds() {
        var seeds = Set.of(PHANTOM, GENUINE);
        dhtTopology = DHTTopologyListener.dhtTopologyListener(DHTNode.dhtNode(SELF,
                                                                                 MemoryStorageEngine.memoryStorageEngine(),
                                                                                 ringSeededWith(seeds),
                                                                                 DHTConfig.DEFAULT));

        membership = MembershipFsm.membershipFsm();
        membership.onEnteredDeparting(this::onEnteredDeparting);
        membership.seed(seeds);
        swim = SwimProtocol.swimProtocol(tightConfig(),
                                         new SeedTransport(genuineUp),
                                         new SilentListener(),
                                         SELF,
                                         SELF_ADDR,
                                         booting::get)
                           .unwrap();
        swim.addObservationListener(observation -> AetherNode.routeSwimEdgeToMembershipFsm(observation, membership));
        swim.addSeedMember(PHANTOM, PHANTOM_ADDR);
        swim.addSeedMember(GENUINE, GENUINE_ADDR);
    }

    @AfterEach
    void stop() {
        swim.stop();
    }

    @Test
    void recycledIpPhantom_leavesMembershipAndRing_onTheRefutation_whileStillBooting() {
        assertThat(membership.isCountedMember(PHANTOM)).as("boot: the static seed is a counted MEMBER").isTrue();
        assertThat(ring.nodes()).as("boot: and sits in the ring").contains(PHANTOM);

        swim.start();
        // QUIC dials the seed's address and 6nphj answers: Hello identity mismatch.
        swim.recordTransportHint(PHANTOM, new TransportObservation.IdentityRefuted(PHANTOM, RECYCLED_HOLDER));

        await().atMost(Duration.ofSeconds(3))
               .until(() -> enteredDeparting.contains(PHANTOM));
        assertThat(booting.get()).as("departed without waiting for the cold-boot window").isTrue();
        assertThat(membership.isCountedMember(PHANTOM)).isFalse();
        assertThat(ring.nodes()).as("pruned from the DHT ring at the DEPARTING edge").doesNotContain(PHANTOM);
        assertThat(membership.isCountedMember(RECYCLED_HOLDER)).as("the answering node is never touched").isFalse();
        assertThat(enteredDeparting).as("nothing but the phantom departs").containsExactly(PHANTOM);
    }

    @Test
    void silentPhantom_leavesMembershipAndRing_whenTheWindowCloses() {
        swim.start();
        // Cold boot: the phantom's FAULTY is suppressed, so it stays a counted MEMBER in the ring.
        await().atMost(Duration.ofSeconds(3))
               .until(() -> swim.members().get(PHANTOM).state() == MemberState.FAULTY);
        assertThat(membership.isCountedMember(PHANTOM)).isTrue();
        assertThat(ring.nodes()).contains(PHANTOM);

        // The genuine seed comes up inside the window: it answers probes and gossips its own Alive.
        genuineUp.set(true);
        swim.onMessage(GENUINE_ADDR,
                       new Ping(GENUINE, 1L, List.of(MembershipUpdate.membershipUpdate(GENUINE, MemberState.ALIVE, 5, GENUINE_ADDR))));

        booting.set(false);

        await().atMost(WITHIN_RESIDENCY)
               .until(() -> enteredDeparting.contains(PHANTOM));
        assertThat(membership.isCountedMember(PHANTOM)).isFalse();
        assertThat(ring.nodes()).doesNotContain(PHANTOM);

        await().pollDelay(PAST_RESIDENCY)
               .atMost(PAST_RESIDENCY.plusSeconds(1))
               .until(() -> true);
        assertThat(enteredDeparting).as("the genuine slow seed is never departed").doesNotContain(GENUINE);
        assertThat(membership.isCountedMember(GENUINE)).isTrue();
        assertThat(ring.nodes()).contains(GENUINE);
    }

    /// The FSM holds one DEPARTING listener: prune the ring exactly as `AetherNode.assembleNode` does, THEN
    /// record the edge. The record is what the test thread awaits, so it must come last: recorded first, the
    /// test could assert the ring while `DHTNode.changeRing` (which diffs every partition's replica set
    /// since #1823) was still pruning it.
    private void onEnteredDeparting(NodeId node) {
        dhtTopology.onNodeDeparting(node);
        enteredDeparting.add(node);
    }

    private ConsistentHashRing<NodeId> ringSeededWith(Set<NodeId> seeds) {
        ring = ConsistentHashRing.consistentHashRing();
        ring.addNode(SELF);
        seeds.forEach(ring::addNode);

        return ring;
    }

    private static SwimConfig tightConfig() {
        return swimConfig(timeSpan(50).millis(),
                          timeSpan(20).millis(),
                          3,
                          timeSpan(150).millis(),
                          8,
                          timeSpan(50).millis()).withJoinGrace(timeSpan(0).millis())
                                                .withLhmMaxScore(1);
    }

    /// The phantom never answers. The genuine seed answers its probes once it is up.
    private record SeedTransport(AtomicBoolean genuineUp, AtomicReference<SwimMessageHandler> handler) implements SwimTransport {
        SeedTransport(AtomicBoolean genuineUp) {
            this(genuineUp, new AtomicReference<>());
        }

        @Override
        public Promise<Unit> send(InetSocketAddress target, SwimMessage message) {
            if (genuineUp.get() && target.equals(GENUINE_ADDR) && message instanceof Ping ping) {
                handler.get().onMessage(GENUINE_ADDR, Ack.ack(GENUINE, ping.sequence(), List.of()));
            }

            return Promise.success(Unit.unit());
        }

        @Override
        public Promise<Unit> start(int port, SwimMessageHandler handler) {
            this.handler.set(handler);

            return Promise.success(Unit.unit());
        }

        @Override
        public Promise<Unit> stop() {
            return Promise.success(Unit.unit());
        }
    }

    private static final class SilentListener implements SwimMembershipListener {
        @Override public void onMemberJoined(SwimMember member) {}
        @Override public void onMemberSuspect(SwimMember member) {}
        @Override public void onMemberFaulty(SwimMember member, boolean firstHand) {}
        @Override public void onMemberLeft(NodeId nodeId) {}
    }
}
