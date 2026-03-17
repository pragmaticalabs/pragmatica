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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Result;
import org.pragmatica.swim.SwimMember.MemberState;
import org.pragmatica.swim.SwimMessage.Ack;
import org.pragmatica.swim.SwimMessage.MembershipUpdate;
import org.pragmatica.swim.SwimMessage.Ping;
import org.pragmatica.swim.SwimMessage.PingReq;
import org.pragmatica.swim.SwimTransport.SwimMessageHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Core SWIM protocol implementation providing failure detection and membership dissemination.
///
/// The protocol operates in periodic ticks. Each tick:
/// 1. Select a random non-self ALIVE/SUSPECT member
/// 2. Send Ping with piggybacked membership updates
/// 3. Wait probeTimeout for Ack
/// 4. If no Ack, send PingReq to indirectProbes random other members
/// 5. If still no Ack, mark SUSPECT
/// 6. After suspectTimeout, SUSPECT transitions to FAULTY
public final class SwimProtocol implements SwimMessageHandler {
    private static final Logger LOG = LoggerFactory.getLogger(SwimProtocol.class);

    private final SwimConfig config;
    private final SwimTransport transport;
    private final SwimMembershipListener listener;
    private final NodeId selfId;
    private final InetSocketAddress selfAddress;
    private final Map<NodeId, SwimMember> members = new ConcurrentHashMap<>();
    private final Map<Long, PendingProbe> pendingProbes = new ConcurrentHashMap<>();
    private final Map<Long, InetSocketAddress> pendingRelays = new ConcurrentHashMap<>();
    private final Map<NodeId, Long> suspectTimestamps = new ConcurrentHashMap<>();
    private final PiggybackBuffer piggybackBuffer;
    private final AtomicLong sequenceCounter = new AtomicLong(0);
    private volatile ScheduledExecutorService scheduler;
    private volatile ScheduledFuture<?> tickFuture;

    private SwimProtocol(SwimConfig config,
                         SwimTransport transport,
                         SwimMembershipListener listener,
                         NodeId selfId,
                         InetSocketAddress selfAddress) {
        this.config = config;
        this.transport = transport;
        this.listener = listener;
        this.selfId = selfId;
        this.selfAddress = selfAddress;
        this.piggybackBuffer = PiggybackBuffer.piggybackBuffer(config.maxPiggyback());
    }

    /// Factory creating a SWIM protocol instance.
    public static Result<SwimProtocol> swimProtocol(SwimConfig config,
                                                    SwimTransport transport,
                                                    SwimMembershipListener listener,
                                                    NodeId selfId,
                                                    InetSocketAddress selfAddress) {
        return Result.success(new SwimProtocol(config, transport, listener, selfId, selfAddress));
    }

    /// Start the protocol: begin periodic probing.
    public Result<SwimProtocol> start() {
        if (scheduler != null) {
            return SwimError.General.PROTOCOL_ALREADY_RUNNING.result();
        }

        scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());
        var periodMillis = config.period().toMillis();
        tickFuture = scheduler.scheduleAtFixedRate(this::tick, periodMillis, periodMillis, TimeUnit.MILLISECONDS);
        LOG.info("SWIM protocol started for node {}", selfId.id());
        return Result.success(this);
    }

    /// Stop the protocol.
    public Result<SwimProtocol> stop() {
        if (scheduler == null) {
            return SwimError.General.PROTOCOL_NOT_RUNNING.result();
        }

        tickFuture.cancel(false);
        scheduler.shutdown();
        scheduler = null;
        tickFuture = null;
        LOG.info("SWIM protocol stopped for node {}", selfId.id());
        return Result.success(this);
    }

    /// Add a seed member to the membership list.
    public void addSeedMember(NodeId nodeId, InetSocketAddress address) {
        if (selfId.equals(nodeId)) {
            return;
        }

        var member = SwimMember.swimMember(nodeId, address);
        members.put(nodeId, member);
        listener.onMemberJoined(member);
        addMemberUpdate(member);
    }

    /// Return an unmodifiable snapshot of the current membership.
    public Map<NodeId, SwimMember> members() {
        return Collections.unmodifiableMap(members);
    }

    /// Mark a member as ALIVE, resetting SUSPECT or FAULTY state.
    /// Called when external evidence (e.g. TCP connection) confirms the node is reachable.
    /// This enables SWIM to detect future departures of previously-FAULTY members.
    public void markAlive(NodeId nodeId) {
        if (selfId.equals(nodeId)) {
            return;
        }

        var member = members.get(nodeId);

        if (member == null || member.state() == MemberState.ALIVE) {
            return;
        }

        var alive = member.withState(MemberState.ALIVE)
                          .withIncarnation(member.incarnation() + 1);
        members.put(nodeId, alive);
        suspectTimestamps.remove(nodeId);
        listener.onMemberJoined(alive);
        addMemberUpdate(alive);
        LOG.info("Member {} externally marked ALIVE (was {})", nodeId.id(), member.state());
    }

    // -- SwimMessageHandler --

    @Override
    public void onMessage(InetSocketAddress sender, SwimMessage message) {
        switch (message) {
            case Ping ping -> handlePing(sender, ping);
            case Ack ack -> handleAck(ack);
            case PingReq pingReq -> handlePingReq(pingReq);
        }
    }

    // -- Internal tick --

    private void tick() {
        expireSuspectMembers();

        var target = selectRandomProbableTarget();

        if (target == null) {
            return;
        }

        var seq = sequenceCounter.incrementAndGet();
        var piggyback = piggybackBuffer.takeUpdates(config.maxPiggyback());
        var ping = Ping.ping(selfId, seq, piggyback);

        pendingProbes.put(seq, PendingProbe.pendingProbe(target.nodeId(), System.currentTimeMillis(), false));
        transport.send(target.address(), ping);

        scheduleProbeTimeout(seq);
    }

    private void expireSuspectMembers() {
        var now = System.currentTimeMillis();
        var suspectTimeoutMillis = config.suspectTimeout().toMillis();

        suspectTimestamps.forEach((nodeId, timestamp) -> expireSuspectIfOverdue(nodeId, timestamp, now, suspectTimeoutMillis));
        // Clean up stale relays older than probe timeout (target never responded)
        pendingRelays.keySet().removeIf(seq -> !pendingProbes.containsKey(seq));
    }

    private void expireSuspectIfOverdue(NodeId nodeId, long timestamp, long now, long suspectTimeoutMillis) {
        if (now - timestamp < suspectTimeoutMillis) {
            return;
        }

        var member = members.get(nodeId);

        if (member != null && member.state() == MemberState.SUSPECT) {
            transitionToFaulty(member);
        }

        suspectTimestamps.remove(nodeId);
    }

    private void transitionToFaulty(SwimMember member) {
        var faulty = member.withState(MemberState.FAULTY);
        members.put(member.nodeId(), faulty);
        listener.onMemberFaulty(faulty);
        addMemberUpdate(faulty);
        LOG.info("Member {} marked FAULTY", member.nodeId().id());
    }

    private SwimMember selectRandomProbableTarget() {
        var candidates = members.values().stream()
                                .filter(this::isProbable)
                                .toList();

        if (candidates.isEmpty()) {
            return null;
        }

        return candidates.get((int) (Math.random() * candidates.size()));
    }

    private boolean isProbable(SwimMember member) {
        return member.state() == MemberState.ALIVE || member.state() == MemberState.SUSPECT;
    }

    private void scheduleProbeTimeout(long seq) {
        var timeoutMillis = config.probeTimeout().toMillis();

        scheduler.schedule(() -> onProbeTimeout(seq), timeoutMillis, TimeUnit.MILLISECONDS);
    }

    private void onProbeTimeout(long seq) {
        var probe = pendingProbes.get(seq);

        if (probe == null) {
            return;
        }

        if (probe.indirectSent()) {
            markSuspect(probe.targetId());
            pendingProbes.remove(seq);
            return;
        }

        sendIndirectProbes(seq, probe);
    }

    private void sendIndirectProbes(long seq, PendingProbe probe) {
        pendingProbes.put(seq, PendingProbe.pendingProbe(probe.targetId(), probe.startTime(), true));

        var others = selectRandomOtherMembers(probe.targetId(), config.indirectProbes());
        var pingReq = PingReq.pingReq(selfId, probe.targetId(), seq);

        others.forEach(other -> transport.send(other.address(), pingReq));

        scheduleProbeTimeout(seq);
    }

    private List<SwimMember> selectRandomOtherMembers(NodeId exclude, int count) {
        var candidates = new ArrayList<>(members.values().stream()
                                                 .filter(m -> !m.nodeId().equals(exclude) && isProbable(m))
                                                 .toList());

        Collections.shuffle(candidates);
        return candidates.subList(0, Math.min(count, candidates.size()));
    }

    private void markSuspect(NodeId nodeId) {
        var member = members.get(nodeId);

        if (member == null || member.state() != MemberState.ALIVE) {
            return;
        }

        var suspect = member.withState(MemberState.SUSPECT);
        members.put(nodeId, suspect);
        suspectTimestamps.put(nodeId, System.currentTimeMillis());
        listener.onMemberSuspect(suspect);
        addMemberUpdate(suspect);
        LOG.info("Member {} marked SUSPECT", nodeId.id());
    }

    // -- Message handlers --

    private void handlePing(InetSocketAddress sender, Ping ping) {
        processPiggyback(ping.piggyback());
        var piggyback = piggybackBuffer.takeUpdates(config.maxPiggyback());
        var ack = Ack.ack(selfId, ping.sequence(), piggyback);
        transport.send(sender, ack);
    }

    private void handleAck(Ack ack) {
        processPiggyback(ack.piggyback());
        pendingProbes.remove(ack.sequence());
        markAliveIfNeeded(ack.from());
        // Forward Ack to the original requester if this was a relayed probe
        var requester = pendingRelays.remove(ack.sequence());
        if (requester != null) {
            var forwardAck = Ack.ack(ack.from(), ack.sequence(), ack.piggyback());
            transport.send(requester, forwardAck);
        }
    }

    private void handlePingReq(PingReq pingReq) {
        var target = members.get(pingReq.target());

        if (target == null) {
            return;
        }

        // Resolve requester's address for Ack forwarding
        var requester = members.get(pingReq.from());
        if (requester != null) {
            pendingRelays.put(pingReq.sequence(), requester.address());
        }

        var piggyback = piggybackBuffer.takeUpdates(config.maxPiggyback());
        var ping = Ping.ping(selfId, pingReq.sequence(), piggyback);
        transport.send(target.address(), ping);
    }

    private void markAliveIfNeeded(NodeId nodeId) {
        var member = members.get(nodeId);

        if (member == null || member.state() == MemberState.ALIVE) {
            return;
        }

        var alive = member.withState(MemberState.ALIVE);
        members.put(nodeId, alive);
        suspectTimestamps.remove(nodeId);
        addMemberUpdate(alive);
    }

    private void processPiggyback(List<MembershipUpdate> updates) {
        updates.forEach(this::applyUpdate);
    }

    private void applyUpdate(MembershipUpdate update) {
        if (selfId.equals(update.nodeId())) {
            handleSelfUpdate(update);
            return;
        }

        var existing = members.get(update.nodeId());

        if (existing == null) {
            applyNewMember(update);
            return;
        }

        applyExistingMember(existing, update);
    }

    private void handleSelfUpdate(MembershipUpdate update) {
        if (update.state() == MemberState.SUSPECT || update.state() == MemberState.FAULTY) {
            addMemberUpdate(MembershipUpdate.membershipUpdate(selfId, MemberState.ALIVE, update.incarnation() + 1, selfAddress));
        }
    }

    private void applyNewMember(MembershipUpdate update) {
        var member = SwimMember.swimMember(update.nodeId(), update.state(), update.incarnation(), update.address());
        members.put(update.nodeId(), member);

        if (update.state() == MemberState.ALIVE) {
            listener.onMemberJoined(member);
        }
    }

    private void applyExistingMember(SwimMember existing, MembershipUpdate update) {
        if (update.incarnation() < existing.incarnation()) {
            return;
        }

        var updated = SwimMember.swimMember(update.nodeId(), update.state(), update.incarnation(), update.address());
        members.put(update.nodeId(), updated);

        notifyStateChange(existing.state(), updated);
    }

    private void notifyStateChange(MemberState oldState, SwimMember updated) {
        if (oldState == updated.state()) {
            return;
        }

        switch (updated.state()) {
            case ALIVE -> listener.onMemberJoined(updated);
            case SUSPECT -> notifySuspect(updated);
            case FAULTY -> notifyFaulty(updated);
        }
    }

    private void notifySuspect(SwimMember updated) {
        suspectTimestamps.put(updated.nodeId(), System.currentTimeMillis());
        listener.onMemberSuspect(updated);
    }

    private void notifyFaulty(SwimMember updated) {
        suspectTimestamps.remove(updated.nodeId());
        listener.onMemberFaulty(updated);
    }

    private void addMemberUpdate(SwimMember member) {
        piggybackBuffer.addUpdate(MembershipUpdate.membershipUpdate(member.nodeId(), member.state(), member.incarnation(), member.address()));
    }

    private void addMemberUpdate(MembershipUpdate update) {
        piggybackBuffer.addUpdate(update);
    }

    /// Pending probe tracking: which node is being probed and whether indirect probes were sent.
    record PendingProbe(NodeId targetId, long startTime, boolean indirectSent) {
        static PendingProbe pendingProbe(NodeId targetId, long startTime, boolean indirectSent) {
            return new PendingProbe(targetId, startTime, indirectSent);
        }
    }
}
