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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.swim.SwimMember.MemberState;
import org.pragmatica.swim.SwimMessage.MembershipUpdate;
import org.pragmatica.swim.SwimMessage.Ping;
import org.pragmatica.swim.SwimTransport.SwimMessageHandler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.swim.SwimConfig.swimConfig;

/// #964 S1 regression: an undecodable gossiped `MemberState` must never enter `members`, at ANY
/// incarnation.
///
/// The bug this pins was created by #964 itself. `MemberState.UNKNOWN` was ranked WEAKEST in
/// `statePriority`, and the docstring claimed that made it unable to overwrite a state this node
/// understands. It did not: `applyExistingMember` consults priority only when
/// `update.incarnation() == existing.incarnation()`, so a HIGHER-incarnation `UNKNOWN` bypassed the
/// ranking entirely and was stored.
///
/// The result was a **membership zombie** — the #966 family:
///   - `members` says UNKNOWN while every listener still believes the prior state, because
///     `notifyStateChange` fires no listener for UNKNOWN;
///   - `isProbable` is an ALIVE/SUSPECT/OBSERVED allowlist, so the member is never probed again;
///   - `isFaultyAndExpired` requires FAULTY, so it is never swept.
/// Nothing resolves it and the slot is never reclaimed.
///
/// Ranking is the wrong instrument: priority answers "which of two states this node UNDERSTANDS
/// wins", and UNKNOWN is the absence of a state rather than a weaker one. Hence an explicit refusal.
class SwimUndecodableStateTest {
    private static final NodeId SELF_ID = new NodeId("node-self");
    private static final NodeId NODE_A = new NodeId("node-a");
    private static final NodeId NODE_B = new NodeId("node-b");
    private static final InetSocketAddress SELF_ADDR = new InetSocketAddress("127.0.0.1", 9100);
    private static final InetSocketAddress ADDR_A = new InetSocketAddress("127.0.0.1", 9101);
    private static final InetSocketAddress ADDR_B = new InetSocketAddress("127.0.0.1", 9102);

    private SwimProtocol protocol;

    private static SwimConfig config() {
        return swimConfig(timeSpan(20).millis(),
                          timeSpan(20).millis(),
                          3,
                          timeSpan(100).millis(),
                          8,
                          timeSpan(20).millis()).withJoinGrace(timeSpan(0).millis());
    }

    @BeforeEach
    void setUp() {
        protocol = SwimProtocol.swimProtocol(config(),
                                             new SilentTransport(),
                                             new SilentListener(),
                                             SELF_ID,
                                             SELF_ADDR,
                                             () -> false)
                               .unwrap();
    }

    private void gossip(MemberState state, long incarnation) {
        protocol.onMessage(ADDR_B, new Ping(NODE_B, incarnation + 1, List.of(new MembershipUpdate(NODE_A, state, incarnation, ADDR_A))));
    }

    private void seedAlive() {
        gossip(MemberState.ALIVE, 0);
        assertThat(protocol.members().get(NODE_A).state())
            .as("precondition: the member must be resident and ALIVE before the undecodable update")
            .isEqualTo(MemberState.ALIVE);
    }

    /// THE regression. Before the fix this stored UNKNOWN.
    @Test
    void higherIncarnationUnknown_doesNotOverwriteAResidentAliveMember() {
        seedAlive();

        gossip(MemberState.UNKNOWN, 5);

        assertThat(protocol.members().get(NODE_A).state()).isEqualTo(MemberState.ALIVE);
    }

    /// The incarnation is not adopted either. Storing the incarnation while keeping the state would
    /// silently raise the bar every later decodable update must clear.
    @Test
    void higherIncarnationUnknown_doesNotAdvanceTheStoredIncarnation() {
        seedAlive();

        gossip(MemberState.UNKNOWN, 5);

        assertThat(protocol.members().get(NODE_A).incarnation()).isZero();
    }

    /// The control that makes this an attribution rather than an observation: at EQUAL incarnation the
    /// pre-existing priority guard already rejected UNKNOWN, so equal-incarnation alone cannot
    /// distinguish a working fix from the bug. Only the higher-incarnation case can.
    @Test
    void equalIncarnationUnknown_isAlsoRejected() {
        seedAlive();

        gossip(MemberState.UNKNOWN, 0);

        assertThat(protocol.members().get(NODE_A).state()).isEqualTo(MemberState.ALIVE);
    }

    /// The member must remain PROBABLE. A stored UNKNOWN silently removed it from the probe allowlist,
    /// which is what turned the wrong state into a permanent zombie rather than a transient error.
    @Test
    void memberStaysProbableAfterAnUndecodableUpdate() {
        seedAlive();

        gossip(MemberState.UNKNOWN, 5);

        assertThat(protocol.members().get(NODE_A).state())
            .as("ALIVE is on the isProbable allowlist; UNKNOWN is not")
            .isEqualTo(MemberState.ALIVE);
    }

    /// The positive control for the whole harness: a DECODABLE higher-incarnation update at the same
    /// incarnation the UNKNOWN used IS adopted. Without this, every assertion above is satisfied by a
    /// protocol that ignores gossip entirely.
    @Test
    void higherIncarnationDecodableUpdate_isStillAdopted() {
        seedAlive();

        gossip(MemberState.SUSPECT, 5);

        assertThat(protocol.members().get(NODE_A).state()).isEqualTo(MemberState.SUSPECT);
        assertThat(protocol.members().get(NODE_A).incarnation()).isEqualTo(5);
    }

    /// The sibling path already refused to store an undecodable state for a member it had never seen.
    /// Pinned so the two paths cannot drift apart again — the asymmetry between them WAS the defect.
    @Test
    void unknownForAnUnseenMember_isNotAdmittedAtAll() {
        gossip(MemberState.UNKNOWN, 3);

        assertThat(protocol.members()).doesNotContainKey(NODE_A);
    }

    static class SilentTransport implements SwimTransport {
        private final AtomicReference<SwimMessageHandler> handler = new AtomicReference<>();

        @Override public Promise<Unit> start(int port, SwimMessageHandler messageHandler) {
            handler.set(messageHandler);
            return Promise.success(Unit.unit());
        }

        @Override public Promise<Unit> send(InetSocketAddress target, SwimMessage message) {
            return Promise.success(Unit.unit());
        }

        @Override public Promise<Unit> stop() {
            handler.set(null);
            return Promise.success(Unit.unit());
        }
    }

    static class SilentListener implements SwimMembershipListener {
        final CopyOnWriteArrayList<String> events = new CopyOnWriteArrayList<>();

        @Override public void onMemberJoined(SwimMember member) {
            events.add("JOINED:" + member.nodeId().id() + ":" + member.state());
        }

        @Override public void onMemberSuspect(SwimMember member) {
            events.add("SUSPECT:" + member.nodeId().id());
        }

        @Override public void onMemberFaulty(SwimMember member, boolean firstHand) {
            events.add("FAULTY:" + member.nodeId().id());
        }

        @Override public void onMemberLeft(NodeId nodeId) {
            events.add("LEFT:" + nodeId.id());
        }
    }
}
