package org.pragmatica.swim;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.BootTokens;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.swim.SwimAnnounceClusterGateTest.RecordingListener;
import org.pragmatica.swim.SwimAnnounceClusterGateTest.RecordingTransport;
import org.pragmatica.swim.SwimProtocolTest.SentMessage;
import org.pragmatica.swim.SwimMember.MemberState;
import org.pragmatica.swim.SwimMessage.Ack;
import org.pragmatica.swim.SwimMessage.Announce;
import org.pragmatica.swim.SwimMessage.MembershipUpdate;
import org.pragmatica.swim.SwimMessage.Ping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.swim.SwimConfig.swimConfig;

/// Per-process BOOT TOKEN gate (owner ruling, session 28 — terminal removal is the identity model).
///
/// Both arms are pinned: a partitioned-but-live process that returns with the SAME token heals exactly
/// as before (higher incarnation supersedes), while a DIFFERENT token for a known NodeId retires the
/// identity — the known process is treated as dead (FAULTY) and the new one is refused, permanently.
class SwimBootTokenTest {
    private static final NodeId SELF_ID = new NodeId("node-self");
    private static final NodeId NODE_A = new NodeId("node-a");
    private static final NodeId NODE_B = new NodeId("node-b");
    private static final InetSocketAddress SELF_ADDR = new InetSocketAddress("127.0.0.1", 9000);
    private static final InetSocketAddress ADDR_A = new InetSocketAddress("127.0.0.1", 9001);
    private static final InetSocketAddress ADDR_B = new InetSocketAddress("127.0.0.1", 9002);
    private static final NodeInfo INFO_A = NodeInfo.nodeInfo(NODE_A, new NodeAddress("127.0.0.1", 9001));
    private static final long TOKEN = 0x1111L;
    private static final long OTHER_TOKEN = 0x2222L;

    private final CopyOnWriteArrayList<SwimObservation> observations = new CopyOnWriteArrayList<>();
    private final SwimProtocolTest.RecordingTransport targetTransport = new SwimProtocolTest.RecordingTransport();
    private int answeredPings;
    private SwimProtocol protocol;

    @BeforeEach
    void setUp() {
        protocol = SwimProtocol.swimProtocol(config(), targetTransport, new RecordingListener(), SELF_ID, SELF_ADDR, () -> false)
                               .unwrap();
        protocol.addObservationListener(observations::add);
    }

    @AfterEach
    void tearDown() {
        protocol.stop();
    }

    @Test
    void sameToken_higherIncarnation_healsAsBefore() {
        announce(5, TOKEN);
        gossip(MemberState.ALIVE, 5, TOKEN);
        gossip(MemberState.FAULTY, 5, TOKEN);
        assertThat(protocol.members().get(NODE_A).state()).as("precondition: the partitioned process is FAULTY")
                                                          .isEqualTo(MemberState.FAULTY);

        announce(6, TOKEN);
        gossip(MemberState.ALIVE, 6, TOKEN);

        assertThat(protocol.members().get(NODE_A).state()).as("same process, higher incarnation: heals").isEqualTo(MemberState.ALIVE);
        assertThat(protocol.members().get(NODE_A).incarnation()).isEqualTo(6);
        assertThat(joinAnnouncements()).as("both ANNOUNCEs of the same process reach the dial path").isEqualTo(2);
        assertThat(protocol.isRetiredIdentity(NODE_A)).isFalse();
        assertThat(protocol.bootTokenRefusals()).isZero();
    }

    @Test
    void differentToken_announce_retiresIdentity_andRefusesNewProcess() {
        announce(5, TOKEN);

        announce(6, OTHER_TOKEN);

        assertThat(protocol.isRetiredIdentity(NODE_A)).as("a different process reused a known NodeId").isTrue();
        assertThat(protocol.members().get(NODE_A).state()).as("the known process is treated as dead").isEqualTo(MemberState.FAULTY);
        assertThat(joinAnnouncements()).as("the refused process never reaches the dial path").isEqualTo(1);
        assertThat(protocol.bootTokenRefusals()).isEqualTo(1);

        announce(7, TOKEN);
        gossip(MemberState.ALIVE, 8, TOKEN);

        assertThat(protocol.members().get(NODE_A).state()).as("terminal: a retired identity never returns").isEqualTo(MemberState.FAULTY);
        assertThat(protocol.bootTokenRefusals()).isEqualTo(3);
    }

    /// Old expectation: a different token in gossip retired the identity. That trusted a datagram to say which process
    /// wrote it; hearsay cannot retire anything now.
    @Test
    void differentToken_inGossip_retiresNothing() {
        announce(5, TOKEN);

        gossip(MemberState.ALIVE, 99, OTHER_TOKEN);

        assertThat(protocol.isRetiredIdentity(NODE_A)).isFalse();
        assertThat(protocol.members().get(NODE_A).state()).isNotEqualTo(MemberState.FAULTY);
        assertThat(protocol.bootTokenRefusals()).isZero();
    }

    @Test
    void differentToken_inTheMembersOwnAnswerToOurProbe_retiresIdentity() {
        announce(5, TOKEN);
        protocol.probeOnceForTest();
        var probe = targetTransport.sentMessages.stream()
                                                .filter(sent -> sent.target().equals(ADDR_A) && sent.message() instanceof Ping)
                                                .map(sent -> (Ping) sent.message())
                                                .reduce((_, last) -> last)
                                                .orElseThrow();

        protocol.onMessage(ADDR_A,
                           Ack.ack(NODE_A, probe.sequence(), List.of(MembershipUpdate.membershipUpdate(NODE_A, MemberState.ALIVE, 99, ADDR_A, OTHER_TOKEN))));

        assertThat(protocol.isRetiredIdentity(NODE_A)).as("the process answering at the address is a different one").isTrue();
        assertThat(protocol.members().get(NODE_A).state()).isEqualTo(MemberState.FAULTY);
    }

    @Test
    void zeroToken_carriesNoProcessIdentity_andIsAdmitted() {
        announce(5, TOKEN);

        gossip(MemberState.ALIVE, 7, 0L);

        assertThat(protocol.members().get(NODE_A).state()).isEqualTo(MemberState.ALIVE);
        assertThat(protocol.members().get(NODE_A).incarnation()).isEqualTo(7);
        assertThat(protocol.bootTokenRefusals()).isZero();
    }

    @Test
    void selfSuspicion_aboutAnotherProcess_isNotRefuted() {
        protocol.announceJoin(NodeInfo.nodeInfo(SELF_ID, new NodeAddress("127.0.0.1", 9000)), "", 1, TOKEN, List.of());

        protocol.onMessage(ADDR_B, Ping.ping(NODE_B, 1, List.of(MembershipUpdate.membershipUpdate(SELF_ID,
                                                                                                    MemberState.SUSPECT,
                                                                                                    10,
                                                                                                    SELF_ADDR,
                                                                                                    OTHER_TOKEN))));
        assertThat(protocol.selfIncarnation()).as("a suspicion of a previous process with this NodeId is not ours to refute")
                                              .isEqualTo(1);

        protocol.onMessage(ADDR_B, Ping.ping(NODE_B, 2, List.of(MembershipUpdate.membershipUpdate(SELF_ID,
                                                                                                    MemberState.SUSPECT,
                                                                                                    10,
                                                                                                    SELF_ADDR,
                                                                                                    TOKEN))));
        assertThat(protocol.selfIncarnation()).as("control: a suspicion of THIS process is refuted").isEqualTo(11);
    }

    /// A NodeId retired in the shared registry (e.g. by the QUIC handshake) is never re-seeded from a
    /// channel reconnect. Control: an unrelated id is seeded.
    @Test
    void seedMember_forRetiredIdentity_isNotAdmitted() {
        var tokens = BootTokens.bootTokens(0x5E1FL);

        protocol.setBootTokens(tokens);
        tokens.admit(NODE_A, TOKEN);
        tokens.admit(NODE_A, OTHER_TOKEN);

        protocol.addSeedMember(NODE_A, ADDR_A);
        protocol.addSeedMember(NODE_B, ADDR_B);

        assertThat(protocol.members()).as("a retired identity is not re-seeded").doesNotContainKey(NODE_A);
        assertThat(protocol.members()).as("control: an unrelated seed is admitted").containsKey(NODE_B);
    }

    /// Retirement seen by ANOTHER layer (the QUIC handshake) marks the resident member FAULTY, and a
    /// probe ack answered by the new process at the same address must not revive it.
    @Test
    void probeAck_fromRetiredIdentity_doesNotReviveMember() {
        var transport = new RecordingTransport();
        var tokens = BootTokens.bootTokens(0x5E1FL);
        var probing = SwimProtocol.swimProtocol(probingConfig(), transport, new RecordingListener(), SELF_ID, SELF_ADDR)
                                  .unwrap();

        probing.setBootTokens(tokens);
        probing.addSeedMember(NODE_A, ADDR_A);
        probing.start();
        try {
            var sequence = awaitPingSequence(transport);

            tokens.admit(NODE_A, TOKEN);
            tokens.admit(NODE_A, OTHER_TOKEN);
            assertThat(probing.members().get(NODE_A).state()).as("the retirement listener marks the member FAULTY")
                                                             .isEqualTo(MemberState.FAULTY);

            probing.onMessage(ADDR_A, Ack.ack(NODE_A, sequence, List.of()));

            assertThat(probing.members().get(NODE_A).state()).as("an ack from the retired identity revives nothing")
                                                             .isEqualTo(MemberState.FAULTY);
        } finally {
            probing.stop();
        }
    }

    private static long awaitPingSequence(RecordingTransport transport) {
        var deadline = System.currentTimeMillis() + 5_000;

        while (System.currentTimeMillis() < deadline) {
            var ping = transport.sentMessages.stream()
                                             .filter(Ping.class::isInstance)
                                             .map(Ping.class::cast)
                                             .findFirst();

            if (ping.isPresent()) {
                return ping.get().sequence();
            }
            sleep(10);
        }
        throw new AssertionError("the protocol never probed the seeded member");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static SwimConfig probingConfig() {
        return swimConfig(timeSpan(20).millis(),
                          timeSpan(5).seconds(),
                          3,
                          timeSpan(10).seconds(),
                          8,
                          timeSpan(20).millis()).withJoinGrace(timeSpan(0).millis());
    }

    /// A refused ANNOUNCE is answered with an explicit IdentityRefused to the announcer's address.
    @Test
    void refusedAnnounce_isAnsweredWithIdentityRefused() {
        announce(5, TOKEN);
        assertThat(targetTransport.sentMessages).as("control: an admitted announce is not refused")
                                                .noneMatch(sent -> sent.message() instanceof SwimMessage.IdentityRefused);

        announce(6, OTHER_TOKEN);

        assertThat(targetTransport.sentMessages).filteredOn(sent -> sent.message() instanceof SwimMessage.IdentityRefused)
                                                .singleElement()
                                                .satisfies(sent -> assertThat(((SwimMessage.IdentityRefused) sent.message()).refused()).isEqualTo(NODE_A));
    }

    /// The refused process learns it: an IdentityRefused about ITS NodeId reaches the registry's
    /// self-refusal listener (the node then exits); one about another NodeId is ignored.
    @Test
    void identityRefused_forSelf_notifiesSelfRefusal_forOthers_isIgnored() {
        var tokens = BootTokens.bootTokens(TOKEN);
        var reasons = new java.util.concurrent.CopyOnWriteArrayList<String>();

        tokens.onSelfRefused(reasons::add);
        protocol.setBootTokens(tokens);

        protocol.onMessage(ADDR_B, SwimMessage.IdentityRefused.identityRefused(NODE_B, NODE_A, "not you"));
        assertThat(reasons).as("a refusal about another NodeId is not ours").isEmpty();

        protocol.onMessage(ADDR_B, SwimMessage.IdentityRefused.identityRefused(NODE_B, SELF_ID, "retired"));
        assertThat(reasons).containsExactly("retired");
    }

    /// The announce, then the process answering the probe that the announce earns when its token is not yet
    /// proven (a token already recorded needs no probe). The old expectation assumed the datagram's token was trusted.
    private void announce(long incarnation, long bootToken) {
        protocol.announceFromPinnedSourceForTest(ADDR_A, Announce.announce(INFO_A, "", incarnation, bootToken));
        var pings = targetTransport.sentMessages.stream()
                                                .filter(sent -> sent.target().equals(ADDR_A) && sent.message() instanceof Ping)
                                                .map(SentMessage::message)
                                                .map(Ping.class::cast)
                                                .toList();

        pings.stream()
             .skip(answeredPings)
             .forEach(ping -> protocol.onMessage(ADDR_A,
                                                 Ack.ack(NODE_A,
                                                         ping.sequence(),
                                                         List.of(MembershipUpdate.membershipUpdate(NODE_A, MemberState.ALIVE, incarnation, ADDR_A, bootToken)))));
        answeredPings = pings.size();
    }

    private void gossip(MemberState state, long incarnation, long bootToken) {
        protocol.onMessage(ADDR_B,
                           Ping.ping(NODE_B, incarnation, List.of(MembershipUpdate.membershipUpdate(NODE_A, state, incarnation, ADDR_A, bootToken))));
    }

    private long joinAnnouncements() {
        return observations.stream()
                           .filter(SwimObservation.JoinAnnounced.class::isInstance)
                           .count();
    }

    private static SwimConfig config() {
        return swimConfig(timeSpan(20).millis(),
                          timeSpan(20).millis(),
                          3,
                          timeSpan(100).millis(),
                          8,
                          timeSpan(20).millis()).withJoinGrace(timeSpan(0).millis());
    }
}
