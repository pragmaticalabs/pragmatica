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
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.swim.SwimMember.MemberState;
import org.pragmatica.swim.SwimMessage.Ack;
import org.pragmatica.swim.SwimMessage.MembershipUpdate;
import org.pragmatica.swim.SwimMessage.Ping;
import org.pragmatica.swim.SwimMessage.PingReq;
import org.pragmatica.swim.SwimTransport.SwimMessageHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;

/// Core SWIM protocol implementation providing failure detection and membership dissemination.
///
/// The protocol operates in periodic ticks. Each tick:
/// 1. Select a round-robin non-self ALIVE/SUSPECT member
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
    private final Map<Long, RelayInfo> pendingRelays = new ConcurrentHashMap<>();
    private final Map<NodeId, Long> suspectTimestamps = new ConcurrentHashMap<>();
    private final PiggybackBuffer piggybackBuffer;
    private final AtomicLong sequenceCounter = new AtomicLong(0);
    private final AtomicReference<Option<ScheduledFuture<?>>> tickFuture = new AtomicReference<>(none());
    private final AtomicInteger probeIndex = new AtomicInteger(0);
    private final Map<NodeId, Long> revivalTimestamps = new ConcurrentHashMap<>();

    /// Tracks a relayed PingReq: maps the relay's own sequence to the original requester info.
    private record RelayInfo(long originalSequence, InetSocketAddress requesterAddress, long createdAt) {}

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
    /// Result wrapper retained for flatMap(SwimProtocol::start) composition.
    @SuppressWarnings("JBCT-VO-02")
    public static Result<SwimProtocol> swimProtocol(SwimConfig config,
                                                    SwimTransport transport,
                                                    SwimMembershipListener listener,
                                                    NodeId selfId,
                                                    InetSocketAddress selfAddress) {
        return Result.success(new SwimProtocol(config, transport, listener, selfId, selfAddress));
    }

    /// Start the protocol: begin periodic probing via SharedScheduler.
    /// First tick delayed by startupDelay to allow all TCP connections to establish after quorum.
    public Result<SwimProtocol> start() {
        if (tickFuture.get().isPresent()) {
            return SwimError.General.PROTOCOL_ALREADY_RUNNING.result();
        }

        tickFuture.set(option(SharedScheduler.scheduleAtFixedRate(this::tick, config.startupDelay(), config.period())));
        LOG.info("SWIM protocol started for node {} (first probe in {}ms)", selfId.id(), config.startupDelay().millis());
        return Result.success(this);
    }

    /// Stop the protocol.
    public Result<SwimProtocol> stop() {
        if (!tickFuture.get().isPresent()) {
            return SwimError.General.PROTOCOL_NOT_RUNNING.result();
        }

        tickFuture.getAndSet(none()).onPresent(f -> f.cancel(false));
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

        option(members.get(nodeId))
            .filter(member -> member.state() != MemberState.ALIVE)
            .onPresent(member -> applyAliveRevival(nodeId, member));
    }

    // -- SwimMessageHandler --

    @Override
    public void onMessage(InetSocketAddress sender, SwimMessage message) {
        LOG.trace("SWIM recv from {}: {}", sender, message.getClass().getSimpleName());
        switch (message) {
            case Ping ping -> handlePing(sender, ping);
            case Ack ack -> handleAck(ack);
            case PingReq pingReq -> handlePingReq(sender, pingReq);
        }
    }

    // -- Internal tick --

    private void tick() {
        expireSuspectMembers();
        cleanupFaultyMembers();
        selectNextProbeTarget().onPresent(this::probeTarget);
    }

    private void probeTarget(SwimMember target) {
        var seq = sequenceCounter.incrementAndGet();
        var piggyback = piggybackBuffer.peekUpdates(config.maxPiggyback());
        var ping = Ping.ping(selfId, seq, piggyback);

        pendingProbes.put(seq, PendingProbe.pendingProbe(target.nodeId(), System.currentTimeMillis(), false));
        transport.send(target.address(), ping);

        scheduleProbeTimeout(seq);
    }

    private void expireSuspectMembers() {
        var now = System.currentTimeMillis();
        var suspectTimeoutMillis = config.suspectTimeout().millis();

        suspectTimestamps.forEach((nodeId, timestamp) -> expireSuspectIfOverdue(nodeId, timestamp, now, suspectTimeoutMillis));
        // Clean up stale relays by age, not by pendingProbes presence
        var relayTimeoutMillis = config.probeTimeout().millis() * 3;
        pendingRelays.entrySet().removeIf(entry -> now - entry.getValue().createdAt() > relayTimeoutMillis);
    }

    /// Remove FAULTY members after suspect timeout to prevent unbounded growth.
    private void cleanupFaultyMembers() {
        var now = System.currentTimeMillis();
        var cleanupThreshold = config.suspectTimeout().millis() * 3;

        members.entrySet().removeIf(entry -> isFaultyAndExpired(entry, now, cleanupThreshold));
    }

    private boolean isFaultyAndExpired(Map.Entry<NodeId, SwimMember> entry, long now, long threshold) {
        var member = entry.getValue();

        if (member.state() != MemberState.FAULTY) {
            return false;
        }

        // Remove if no suspectTimestamp exists (already cleaned) or if it's old enough
        return option(suspectTimestamps.get(member.nodeId()))
            .map(suspectTime -> now - suspectTime > threshold)
            .or(true);
    }

    private void expireSuspectIfOverdue(NodeId nodeId, long timestamp, long now, long suspectTimeoutMillis) {
        if (now - timestamp < suspectTimeoutMillis) {
            return;
        }

        option(members.get(nodeId))
            .filter(member -> member.state() == MemberState.SUSPECT)
            .onPresent(this::transitionToFaulty);

        suspectTimestamps.remove(nodeId);
    }

    private void transitionToFaulty(SwimMember member) {
        var faulty = member.withState(MemberState.FAULTY);
        members.put(member.nodeId(), faulty);
        suspectTimestamps.put(member.nodeId(), System.currentTimeMillis());
        listener.onMemberFaulty(faulty);
        addMemberUpdate(faulty);
        LOG.warn("Member {} marked FAULTY", member.nodeId().id());
    }

    /// Round-robin selection: each member probed exactly once per round.
    private Option<SwimMember> selectNextProbeTarget() {
        var candidates = members.values().stream()
                                .filter(this::isProbable)
                                .toList();

        if (candidates.isEmpty()) {
            return none();
        }

        var index = probeIndex.getAndUpdate(i -> (i + 1) % candidates.size()) % candidates.size();
        return option(candidates.get(index));
    }

    private boolean isProbable(SwimMember member) {
        if (member.state() != MemberState.ALIVE && member.state() != MemberState.SUSPECT) {
            return false;
        }

        return option(revivalTimestamps.get(member.nodeId()))
            .map(revivalTime -> isRevivalGraceExpired(member.nodeId(), revivalTime))
            .or(true);
    }

    private boolean isRevivalGraceExpired(NodeId nodeId, long revivalTime) {
        if (System.currentTimeMillis() - revivalTime < config.revivalGrace().millis()) {
            return false;
        }

        revivalTimestamps.remove(nodeId);
        return true;
    }

    private void scheduleProbeTimeout(long seq) {
        SharedScheduler.schedule(() -> onProbeTimeout(seq), config.probeTimeout());
    }

    private void onProbeTimeout(long seq) {
        option(pendingProbes.get(seq))
            .onPresent(probe -> handleProbeTimeout(seq, probe));
    }

    private void handleProbeTimeout(long seq, PendingProbe probe) {
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
        option(members.get(nodeId))
            .filter(member -> member.state() == MemberState.ALIVE)
            .onPresent(member -> applySuspect(nodeId, member));
    }

    private void applySuspect(NodeId nodeId, SwimMember member) {
        var suspect = member.withState(MemberState.SUSPECT);
        members.put(nodeId, suspect);
        suspectTimestamps.put(nodeId, System.currentTimeMillis());
        listener.onMemberSuspect(suspect);
        addMemberUpdate(suspect);
        LOG.warn("Member {} marked SUSPECT", nodeId.id());
    }

    // -- Message handlers --

    private void handlePing(InetSocketAddress sender, Ping ping) {
        processPiggyback(ping.piggyback());
        var piggyback = piggybackBuffer.peekUpdates(config.maxPiggyback());
        var ack = Ack.ack(selfId, ping.sequence(), piggyback);
        transport.send(sender, ack);
    }

    private void handleAck(Ack ack) {
        processPiggyback(ack.piggyback());
        processAckProbe(ack);
        forwardRelay(ack);
    }

    private void processAckProbe(Ack ack) {
        pendingProbes.remove(ack.sequence());
        markAliveIfNeeded(ack.from());
    }

    private void forwardRelay(Ack ack) {
        option(pendingRelays.remove(ack.sequence()))
            .onPresent(relay -> forwardAckToRequester(ack, relay));
    }

    private void forwardAckToRequester(Ack ack, RelayInfo relay) {
        var forwardAck = Ack.ack(ack.from(), relay.originalSequence(), ack.piggyback());
        transport.send(relay.requesterAddress(), forwardAck);
    }

    private void handlePingReq(InetSocketAddress requesterAddress, PingReq pingReq) {
        option(members.get(pingReq.target()))
            .onPresent(target -> relayPingReq(requesterAddress, pingReq, target));
    }

    private void relayPingReq(InetSocketAddress requesterAddress, PingReq pingReq, SwimMember target) {
        var relaySeq = sequenceCounter.incrementAndGet();
        pendingRelays.put(relaySeq, new RelayInfo(pingReq.sequence(), requesterAddress, System.currentTimeMillis()));

        var piggyback = piggybackBuffer.peekUpdates(config.maxPiggyback());
        var ping = Ping.ping(selfId, relaySeq, piggyback);
        transport.send(target.address(), ping);
    }

    /// Bump incarnation when marking alive via Ack — prevents stale SUSPECT piggyback from overriding.
    private void markAliveIfNeeded(NodeId nodeId) {
        option(members.get(nodeId))
            .filter(member -> member.state() != MemberState.ALIVE)
            .onPresent(member -> applyAliveFromAck(nodeId, member));
    }

    private void applyAliveFromAck(NodeId nodeId, SwimMember member) {
        var alive = member.withState(MemberState.ALIVE)
                          .withIncarnation(member.incarnation() + 1);
        members.put(nodeId, alive);
        suspectTimestamps.remove(nodeId);
        addMemberUpdate(alive);
    }

    private void applyAliveRevival(NodeId nodeId, SwimMember member) {
        var alive = member.withState(MemberState.ALIVE)
                          .withIncarnation(member.incarnation() + 1);
        members.put(nodeId, alive);
        suspectTimestamps.remove(nodeId);
        revivalTimestamps.put(nodeId, System.currentTimeMillis());
        listener.onMemberJoined(alive);
        addMemberUpdate(alive);
        LOG.info("Member {} externally marked ALIVE (was {})", nodeId.id(), member.state());
    }

    private void processPiggyback(List<MembershipUpdate> updates) {
        updates.forEach(this::applyUpdate);
    }

    private void applyUpdate(MembershipUpdate update) {
        if (selfId.equals(update.nodeId())) {
            handleSelfUpdate(update);
            return;
        }

        var existing = option(members.get(update.nodeId()));

        if (existing.isPresent()) {
            existing.onPresent(member -> applyExistingMember(member, update));
        } else {
            applyNewMember(update);
        }
    }

    /// Notify listener when self is suspected — application may want to log/alert.
    private void handleSelfUpdate(MembershipUpdate update) {
        if (update.state() == MemberState.SUSPECT || update.state() == MemberState.FAULTY) {
            LOG.warn("Self suspected/faulted by remote node, refuting with incarnation {}", update.incarnation() + 1);
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

    /// Enforce SWIM state priority at same incarnation: FAULTY > SUSPECT > ALIVE.
    /// At equal incarnation, only allow state progression (ALIVE->SUSPECT->FAULTY), not regression.
    private void applyExistingMember(SwimMember existing, MembershipUpdate update) {
        if (update.incarnation() < existing.incarnation()) {
            return;
        }

        // Same incarnation: only accept if update state has higher or equal priority
        if (update.incarnation() == existing.incarnation()
            && statePriority(update.state()) < statePriority(existing.state())) {
            return;
        }

        var updated = SwimMember.swimMember(update.nodeId(), update.state(), update.incarnation(), update.address());
        members.put(update.nodeId(), updated);

        notifyStateChange(existing.state(), updated);
    }

    /// State priority for SWIM: FAULTY > SUSPECT > ALIVE.
    private static int statePriority(MemberState state) {
        return switch (state) {
            case ALIVE -> 0;
            case SUSPECT -> 1;
            case FAULTY -> 2;
        };
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
