// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.governor;

import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.GovernorAnnouncementKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.GovernorAnnouncementValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.concurrent.CancellableTask;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.swim.SwimMember;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public interface GovernorAnnouncer {
    TimeSpan DEFAULT_REANNOUNCE_INTERVAL = TimeSpan.timeSpan(30).seconds();

    @Contract
    void start();

    @Contract
    void stop();

    @Contract
    void onMembershipChange(List<SwimMember> swimMembers);

    boolean isGovernor();
    Option<NodeId> currentGovernor();

    static GovernorAnnouncer governorAnnouncer(NodeId self,
                                               ClusterNode<KVCommand<AetherKey>> cluster,
                                               HlcClock hlcClock,
                                               Supplier<String> communityIdSupplier,
                                               Supplier<String> tcpAddressSupplier,
                                               Supplier<Epoch> observedCoreEpochSupplier) {
        return governorAnnouncer(self,
                                 cluster,
                                 hlcClock,
                                 communityIdSupplier,
                                 tcpAddressSupplier,
                                 observedCoreEpochSupplier,
                                 DEFAULT_REANNOUNCE_INTERVAL);
    }

    static GovernorAnnouncer governorAnnouncer(NodeId self,
                                               ClusterNode<KVCommand<AetherKey>> cluster,
                                               HlcClock hlcClock,
                                               Supplier<String> communityIdSupplier,
                                               Supplier<String> tcpAddressSupplier,
                                               Supplier<Epoch> observedCoreEpochSupplier,
                                               TimeSpan reannounceInterval) {
        return new GovernorAnnouncerRecord(self,
                                           cluster,
                                           hlcClock,
                                           communityIdSupplier,
                                           tcpAddressSupplier,
                                           observedCoreEpochSupplier,
                                           reannounceInterval,
                                           new AtomicBoolean(false),
                                           new AtomicReference<>(Option.none()),
                                           new AtomicReference<>(List.of()),
                                           new AtomicReference<>(GovernorAnnouncementValue.governorAnnouncementValue(self,
                                                                                                                     0)),
                                           CancellableTask.cancellableTask());
    }
}

record GovernorAnnouncerRecord(NodeId self,
                               ClusterNode<KVCommand<AetherKey>> cluster,
                               HlcClock hlcClock,
                               Supplier<String> communityIdSupplier,
                               Supplier<String> tcpAddressSupplier,
                               Supplier<Epoch> observedCoreEpochSupplier,
                               TimeSpan reannounceInterval,
                               AtomicBoolean started,
                               AtomicReference<Option<NodeId>> currentGovernorRef,
                               AtomicReference<List<NodeId>> lastAliveMembers,
                               AtomicReference<GovernorAnnouncementValue> lastAnnouncement,
                               CancellableTask periodicTask) implements GovernorAnnouncer {
    private static final Logger log = LoggerFactory.getLogger(GovernorAnnouncerRecord.class);

    @Contract
    @Override
    public void start() {
        started.set(true);
    }

    @Contract
    @Override
    public void stop() {
        started.set(false);
        periodicTask.cancel();
    }

    @Override
    public boolean isGovernor() {
        return currentGovernorRef.get()
                                 .filter(self::equals)
                                 .isPresent();
    }

    @Override
    public Option<NodeId> currentGovernor() {
        return currentGovernorRef.get();
    }

    @Contract
    @Override
    public void onMembershipChange(List<SwimMember> swimMembers) {
        if (!started.get()) {return;}

        var previous = currentGovernorRef.get();
        var elected = GovernorElection.evaluateElection(self, swimMembers, previous);
        var aliveMembers = swimMembers.stream().filter(m -> m.state() == SwimMember.MemberState.ALIVE).map(SwimMember::nodeId).toList();
        lastAliveMembers.set(List.copyOf(aliveMembers));

        switch (elected) {
            case GovernorState.Governor g -> onSelfElected(previous, aliveMembers);
            case GovernorState.Follower f -> onFollowerElected(previous, f.governorId(), aliveMembers);
        }
    }

    @Contract
    private void onSelfElected(Option<NodeId> previous, List<NodeId> aliveMembers) {
        if (aliveMembers.isEmpty()) {
            log.debug("Self elected but community has no alive members — skipping announcement");

            return;
        }

        currentGovernorRef.set(Option.some(self));

        if (previous.filter(self::equals).isPresent()) {return;}

        writeGovernorChange(aliveMembers);
        startPeriodicReannouncement();
    }

    @Contract
    private void onFollowerElected(Option<NodeId> previous, NodeId governorId, List<NodeId> aliveMembers) {
        currentGovernorRef.set(Option.some(governorId));
        if (previous.filter(self::equals).isPresent()) {
            periodicTask.cancel();
            if (aliveMembers.isEmpty()) {writeDissolved();}
        }
    }

    @Contract
    private void writeGovernorChange(List<NodeId> aliveMembers) {
        var priorValue = lastAnnouncement.get();
        var updated = priorValue.withGovernorChange(self,
                                                    aliveMembers,
                                                    tcpAddressSupplier.get(),
                                                    observedCoreEpochSupplier.get(),
                                                    hlcClock.now());
        applyAnnouncement(updated, "governor-change");
    }

    @Contract
    private void writeReannouncement() {
        var current = lastAnnouncement.get();
        var refreshed = current.withMembers(lastAliveMembers.get(), tcpAddressSupplier.get());
        applyAnnouncement(refreshed, "reannounce");
    }

    @Contract
    private void writeDissolved() {
        var current = lastAnnouncement.get();
        var dissolved = current.withDissolved();
        applyAnnouncement(dissolved, "dissolved");
    }

    @Contract
    private void applyAnnouncement(GovernorAnnouncementValue value, String reason) {
        var communityId = communityIdSupplier.get();

        if (communityId == null || communityId.isEmpty()) {
            log.warn("Cannot announce governor: communityId is blank ({})", reason);

            return;
        }

        var key = GovernorAnnouncementKey.forCommunity(communityId);
        var command = new KVCommand.Put<AetherKey, AetherValue>(key, value);
        cluster.apply(List.<KVCommand<AetherKey>> of(command)).onFailure(cause -> log.warn("Failed to write GovernorAnnouncementKey({}) [{}]: {}",
                                                                                           communityId,
                                                                                           reason,
                                                                                           cause.message())).onSuccess(_ -> lastAnnouncement.set(value));
    }

    @Contract
    private void startPeriodicReannouncement() {
        periodicTask.cancel();
        periodicTask.set(SharedScheduler.scheduleAtFixedRate(this::tickReannounce, reannounceInterval));
    }

    @Contract
    private void tickReannounce() {
        if (!started.get() || !isGovernor()) {return;}

        var alive = lastAliveMembers.get();

        if (alive.isEmpty()) {
            writeDissolved();
            periodicTask.cancel();

            return;
        }

        writeReannouncement();
    }
}
