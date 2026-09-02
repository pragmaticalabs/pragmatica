package org.pragmatica.swim;

import java.net.InetSocketAddress;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.swim.SwimMessage.Announce;
import org.pragmatica.swim.SwimTransport.SwimMessageHandler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.swim.SwimConfig.swimConfig;

/// Cross-cluster ANNOUNCE gate (`handleAnnounce`).
///
/// The gate existed and was wired from the start, but `SwimConfig.DEFAULT` carries an empty cluster
/// name, empty means "no gating", and nothing ever set it — so it rejected nothing on any node. It
/// is now fed from `AetherNodeConfig.clusterName`. These tests pin all four cells of the comparison,
/// two of which are what make arming it safe during a rolling upgrade.
///
/// This is the ONLY cross-cluster ANNOUNCE isolation: the transport's `isAnnounceAllowed` is a
/// per-source RATE LIMITER, not an allowlist.
class SwimAnnounceClusterGateTest {
    private static final NodeId SELF_ID = new NodeId("node-self");
    private static final NodeId NODE_A = new NodeId("node-a");
    private static final InetSocketAddress SELF_ADDR = new InetSocketAddress("127.0.0.1", 9000);
    private static final InetSocketAddress ADDR_A = new InetSocketAddress("127.0.0.1", 9001);

    @Test
    void announce_isAdmitted_whenClusterNamesMatch() {
        assertAnnounceAdmitted("prod-eu", "prod-eu", true, "Matching cluster names must be admitted");
    }

    @Test
    void announce_isRejected_whenClusterNamesDiffer() {
        assertAnnounceAdmitted("prod-eu",
                               "staging-eu",
                               false,
                               "A foreign cluster's ANNOUNCE must be rejected — this is the misconfiguration "
                               + "(stale or copy-pasted seed list) the gate exists to catch");
    }

    /// Rolling-upgrade direction 1: an old node that predates the naming still announces an empty
    /// name. A named node must accept it, or the upgrade window breaks membership.
    @Test
    void announce_isAdmitted_whenSenderIsUnnamed_soUpgradesDoNotBreak() {
        assertAnnounceAdmitted("prod-eu",
                               "",
                               true,
                               "An unnamed sender is 'did not tell us', not 'mismatch' — rejecting it would "
                               + "break a mixed-version cluster mid-upgrade");
    }

    /// Rolling-upgrade direction 2: an old node has no expectation configured, so it must accept a
    /// newly-named peer. This is the short-circuit that keeps the gate inert until every node is named.
    @Test
    void announce_isAdmitted_whenReceiverIsUnnamed_soUpgradesDoNotBreak() {
        assertAnnounceAdmitted("",
                               "prod-eu",
                               true,
                               "An empty expectation means this node was never told its cluster — it cannot "
                               + "judge a mismatch and must not try");
    }

    private static void assertAnnounceAdmitted(String expectedName,
                                               String announcedName,
                                               boolean admitted,
                                               String because) {
        var protocol = SwimProtocol.swimProtocol(configNamed(expectedName),
                                                 new RecordingTransport(),
                                                 new RecordingListener(),
                                                 SELF_ID,
                                                 SELF_ADDR,
                                                 () -> false)
                                   .unwrap();

        try {
            var nodeInfoA = NodeInfo.nodeInfo(NODE_A, new NodeAddress("127.0.0.1", 9001));

            protocol.onMessage(ADDR_A, new Announce(nodeInfoA, announcedName, 0));

            assertThat(protocol.members().containsKey(NODE_A)).as(because).isEqualTo(admitted);
        } finally {
            protocol.stop();
        }
    }

    private static SwimConfig configNamed(String clusterName) {
        return swimConfig(timeSpan(20).millis(),
                          timeSpan(20).millis(),
                          3,
                          timeSpan(100).millis(),
                          8,
                          timeSpan(20).millis()).withJoinGrace(timeSpan(0).millis())
                                                .withClusterName(clusterName);
    }

    // -- Test infrastructure (per this module's per-file convention) --

    static class RecordingTransport implements SwimTransport {
        final CopyOnWriteArrayList<Object> sentMessages = new CopyOnWriteArrayList<>();
        final AtomicReference<SwimMessageHandler> handler = new AtomicReference<>();

        @Override public Promise<Unit> send(InetSocketAddress target, SwimMessage message) {
            sentMessages.add(message);
            return Promise.success(Unit.unit());
        }

        @Override public Promise<Unit> start(int port, SwimMessageHandler handler) {
            this.handler.set(handler);
            return Promise.success(Unit.unit());
        }

        @Override public Promise<Unit> stop() {
            handler.set(null);
            return Promise.success(Unit.unit());
        }
    }

    static class RecordingListener implements SwimMembershipListener {
        @Override public void onMemberJoined(SwimMember member) {}
        @Override public void onMemberSuspect(SwimMember member) {}
        @Override public void onMemberFaulty(SwimMember member, boolean firstHand) {}
        @Override public void onMemberLeft(NodeId nodeId) {}
    }
}
