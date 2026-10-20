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
package org.pragmatica.swim;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.swim.SwimMember.MemberState;
import org.pragmatica.swim.SwimMessage.AppGossip;
import org.pragmatica.swim.SwimProtocolTest.RecordingListener;
import org.pragmatica.swim.SwimProtocolTest.RecordingTransport;
import org.pragmatica.swim.SwimProtocolTest.SentMessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.swim.SwimConfig.swimConfig;

/// Application gossip: a [GossipChannel]'s payload goes to ONE random ALIVE member per probe round in a datagram
/// of its own, bounded by the budget it was offered, and what arrives is dropped unless it is from a member on a
/// registered channel and within a fixed rate.
class SwimAppGossipTest {
    private static final NodeId SELF_ID = new NodeId("node-self");
    private static final InetSocketAddress SELF_ADDR = new InetSocketAddress("127.0.0.1", 9800);
    private static final int CHANNEL = 7;
    private static final int BUDGET = PiggybackBuffer.piggybackBudgetFor(SELF_ID);

    private RecordingTransport transport;
    private SwimProtocol protocol;
    private final CopyOnWriteArrayList<Integer> offeredBudgets = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<NodeId> deliveredFrom = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<byte[]> delivered = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        transport = new RecordingTransport();
        protocol = SwimProtocol.swimProtocol(swimConfig(timeSpan(1).seconds(), timeSpan(500).millis(), 3, timeSpan(5).seconds(), 8, timeSpan(1).seconds()),
                                             transport,
                                             new RecordingListener(),
                                             SELF_ID,
                                             SELF_ADDR)
                               .unwrap();
    }

    private static NodeId peer(int index) {
        return new NodeId("node-peer-" + index);
    }

    private static InetSocketAddress addressOf(int index) {
        return new InetSocketAddress("127.0.0.1", 9900 + index);
    }

    private void member(int index, MemberState state) {
        protocol.putMemberForTest(peer(index), addressOf(index), state);
    }

    private GossipChannel producing(byte[] payload) {
        return new GossipChannel() {
            @Override
            public Option<byte[]> outgoing(int budgetBytes) {
                offeredBudgets.add(budgetBytes);

                return Option.some(payload);
            }

            @Override
            public void incoming(NodeId from, byte[] received) {
                deliveredFrom.add(from);
                delivered.add(received);
            }
        };
    }

    private List<SentMessage> appGossipSent() {
        return transport.sentMessages.stream().filter(sent -> sent.message() instanceof AppGossip).toList();
    }

    @Nested
    class Sending {
        @Test
        void sendAppGossip_oneRound_sendsOneDatagramToOneAliveMember() {
            IntStream.range(0, 3).forEach(i -> member(i, MemberState.ALIVE));
            protocol.registerGossipChannel(CHANNEL, producing(new byte[]{1, 2, 3}));

            protocol.sendAppGossip();

            var sent = appGossipSent();

            assertThat(sent).hasSize(1);
            var gossip = (AppGossip) sent.getFirst().message();

            assertThat(gossip.from()).isEqualTo(SELF_ID);
            assertThat(gossip.channel()).isEqualTo(CHANNEL);
            assertThat(gossip.payload()).containsExactly(1, 2, 3);
            assertThat(IntStream.range(0, 3).mapToObj(SwimAppGossipTest::addressOf)).contains(sent.getFirst().target());
            assertThat(protocol.appGossipStats().sent()).isEqualTo(1);
        }

        @Test
        void sendAppGossip_manyRounds_reachesEveryAliveMember_andNeverAnyOther() {
            IntStream.range(0, 3).forEach(i -> member(i, MemberState.ALIVE));
            member(3, MemberState.SUSPECT);
            member(4, MemberState.FAULTY);
            protocol.registerGossipChannel(CHANNEL, producing(new byte[]{9}));

            IntStream.range(0, 200).forEach(i -> protocol.sendAppGossip());

            var targets = new HashSet<InetSocketAddress>();

            appGossipSent().forEach(sent -> targets.add(sent.target()));

            assertThat(appGossipSent()).hasSize(200);
            assertThat(targets).containsExactlyInAnyOrder(addressOf(0), addressOf(1), addressOf(2));
        }

        @Test
        void sendAppGossip_offersTheChannelTheDatagramBudget() {
            member(0, MemberState.ALIVE);
            protocol.registerGossipChannel(CHANNEL, producing(new byte[]{1}));

            protocol.sendAppGossip();

            assertThat(offeredBudgets).containsExactly(BUDGET);
        }

        @Test
        void sendAppGossip_payloadOverTheBudget_isDroppedAndCounted_notSent() {
            member(0, MemberState.ALIVE);
            protocol.registerGossipChannel(CHANNEL, producing(new byte[BUDGET + 1]));

            protocol.sendAppGossip();

            assertThat(appGossipSent()).isEmpty();
            assertThat(protocol.appGossipStats().dropped()).isEqualTo(1);
        }

        @Test
        void sendAppGossip_payloadExactlyTheBudget_isSent() {
            member(0, MemberState.ALIVE);
            protocol.registerGossipChannel(CHANNEL, producing(new byte[BUDGET]));

            protocol.sendAppGossip();

            assertThat(appGossipSent()).hasSize(1);
            assertThat(protocol.appGossipStats().dropped()).isZero();
        }

        @Test
        void sendAppGossip_withNoAliveMember_asksNoChannelAndSendsNothing() {
            member(0, MemberState.SUSPECT);
            protocol.registerGossipChannel(CHANNEL, producing(new byte[]{1}));

            protocol.sendAppGossip();

            assertThat(appGossipSent()).isEmpty();
            assertThat(offeredBudgets).as("a channel is asked only when there is a peer to tell").isEmpty();
        }

        @Test
        void sendAppGossip_withNoChannel_sendsNothing() {
            member(0, MemberState.ALIVE);

            protocol.sendAppGossip();

            assertThat(appGossipSent()).isEmpty();
        }

        @Test
        void sendAppGossip_channelWithNothingToSay_sendsNothing() {
            member(0, MemberState.ALIVE);
            protocol.registerGossipChannel(CHANNEL, new GossipChannel() {
                @Override
                public Option<byte[]> outgoing(int budgetBytes) {
                    return Option.none();
                }

                @Override
                public void incoming(NodeId from, byte[] payload) {}
            });

            protocol.sendAppGossip();

            assertThat(appGossipSent()).isEmpty();
            assertThat(protocol.appGossipStats().sent()).isZero();
        }

        @Test
        void registerGossipChannel_again_replacesTheChannel() {
            member(0, MemberState.ALIVE);
            var first = new AtomicInteger();
            protocol.registerGossipChannel(CHANNEL, new GossipChannel() {
                @Override
                public Option<byte[]> outgoing(int budgetBytes) {
                    first.incrementAndGet();

                    return Option.none();
                }

                @Override
                public void incoming(NodeId from, byte[] payload) {}
            });
            protocol.registerGossipChannel(CHANNEL, producing(new byte[]{5}));

            protocol.sendAppGossip();

            assertThat(first).hasValue(0);
            assertThat(appGossipSent()).hasSize(1);
        }

        @Test
        void tick_runsAppGossip_onTheProbeCadence() {
            var ticking = SwimProtocol.swimProtocol(swimConfig(timeSpan(50).millis(), timeSpan(40).millis(), 3, timeSpan(5).seconds(), 8, timeSpan(20).millis()),
                                                    transport,
                                                    new RecordingListener(),
                                                    SELF_ID,
                                                    SELF_ADDR)
                                      .unwrap();

            ticking.putMemberForTest(peer(0), addressOf(0), MemberState.ALIVE);
            ticking.registerGossipChannel(CHANNEL, producing(new byte[]{4}));
            ticking.start();
            try {
                await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(appGossipSent()).isNotEmpty());
            } finally {
                ticking.stop();
            }
        }
    }

    @Nested
    class Receiving {
        private void arrives(NodeId from, int channel, byte[] payload) {
            protocol.onMessage(addressOf(0), AppGossip.appGossip(from, channel, payload));
        }

        @BeforeEach
        void registerChannel() {
            member(0, MemberState.ALIVE);
            protocol.registerGossipChannel(CHANNEL, producing(new byte[]{1}));
        }

        @Test
        void onMessage_fromAMemberOnARegisteredChannel_isDeliveredToTheChannel() {
            arrives(peer(0), CHANNEL, new byte[]{7, 8});

            assertThat(deliveredFrom).containsExactly(peer(0));
            assertThat(delivered).hasSize(1);
            assertThat(delivered.getFirst()).containsExactly(7, 8);
            assertThat(protocol.appGossipStats().received()).isEqualTo(1);
            assertThat(protocol.appGossipStats().dropped()).isZero();
        }

        @Test
        void onMessage_onAnUnregisteredChannel_isDroppedAndCounted() {
            arrives(peer(0), CHANNEL + 1, new byte[]{1});

            assertThat(delivered).isEmpty();
            assertThat(protocol.appGossipStats().dropped()).isEqualTo(1);
        }

        @Test
        void onMessage_fromANodeThatIsNotAMember_isDroppedAndCounted() {
            arrives(peer(99), CHANNEL, new byte[]{1});

            assertThat(delivered).isEmpty();
            assertThat(protocol.appGossipStats().dropped()).isEqualTo(1);
        }

        @Test
        void onMessage_fromAMemberPrunedOutOfTheMembershipScope_isDropped() {
            protocol.setMembershipEligibility(id -> !id.equals(peer(0)));
            member(1, MemberState.ALIVE);

            arrives(peer(0), CHANNEL, new byte[]{1});

            assertThat(delivered).isEmpty();
            assertThat(protocol.appGossipStats().dropped()).isEqualTo(1);
        }

        @Test
        void onMessage_pastTheRate_isDroppedAndCounted_nothingIsQueued() {
            IntStream.range(0, 20).forEach(i -> arrives(peer(0), CHANNEL, new byte[]{(byte) i}));

            assertThat(delivered).as("the first 8 of a burst in one window").hasSize(8);
            assertThat(delivered.stream().map(payload -> (int) payload[0]).toList()).containsExactly(0, 1, 2, 3, 4, 5, 6, 7);
            assertThat(protocol.appGossipStats().received()).isEqualTo(8);
            assertThat(protocol.appGossipStats().dropped()).isEqualTo(12);
        }

        @Test
        void onMessage_afterTheWindowPasses_isAcceptedAgain() {
            IntStream.range(0, 20).forEach(i -> arrives(peer(0), CHANNEL, new byte[]{1}));
            var deliveredInBurst = delivered.size();

            await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(100)).untilAsserted(() -> {
                arrives(peer(0), CHANNEL, new byte[]{2});
                assertThat(delivered.size()).isGreaterThan(deliveredInBurst);
            });
        }

        @Test
        void onMessage_datagramsThatWouldBeDroppedAnyway_doNotUseUpTheRateBudget() {
            IntStream.range(0, 50).forEach(i -> arrives(peer(0), CHANNEL + 1, new byte[]{1}));
            IntStream.range(0, 50).forEach(i -> arrives(peer(99), CHANNEL, new byte[]{1}));

            arrives(peer(0), CHANNEL, new byte[]{3});

            assertThat(delivered).hasSize(1);
        }

        @Test
        void appGossip_payloadIsCopiedOnConstruction() {
            var source = new byte[]{1, 2};
            var gossip = AppGossip.appGossip(peer(0), CHANNEL, source);

            source[0] = 99;

            assertThat(gossip.payload()).containsExactly(1, 2);
        }
    }

    @Test
    void appGossipStats_startAtZero() {
        assertThat(protocol.appGossipStats()).isEqualTo(SwimProtocol.AppGossipStats.appGossipStats(0, 0, 0));
    }
}
