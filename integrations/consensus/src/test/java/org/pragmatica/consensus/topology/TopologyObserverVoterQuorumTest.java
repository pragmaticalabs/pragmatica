/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package org.pragmatica.consensus.topology;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.rabia.ClusterConfig;
import org.pragmatica.consensus.rabia.VoterConfiguration;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #2306: the topology's quorum edge counts INSTALLED VOTERS only. A core member outside the installed roster (an
/// observer, such as an auto-heal replacement still joining) is in the membership view's counts but cannot vote, so it
/// must not make two surviving voters of five look like a quorum: the engine could not commit and the node would
/// neither pause nor report the loss. Counting the observer (dropping the intersection in `haveQuorum`) turns the
/// first test red.
class TopologyObserverVoterQuorumTest {
    private static final NodeId SELF = nodeId("node-self").unwrap();
    private static final NodeId A = nodeId("node-a").unwrap();
    private static final NodeId B = nodeId("node-b").unwrap();
    private static final NodeId C = nodeId("node-c").unwrap();
    private static final NodeId D = nodeId("node-d").unwrap();
    private static final NodeId OBSERVER = nodeId("node-observer").unwrap();
    private static final VoterConfiguration FIVE_VOTERS = new VoterConfiguration(0, new ClusterConfig(List.of(SELF, A, B, C, D)));

    private static NodeInfo info(NodeId id, int port) {
        return NodeInfo.nodeInfo(id, NodeAddress.nodeAddress("localhost", port).unwrap());
    }

    /// 5 configured cores, `quorumSize() == 3`; a short reconcile interval so the periodic evaluation re-reads the view.
    private static TopologyConfig fiveCoreConfig() {
        return new TopologyConfig(SELF,
                                  5,
                                  timeSpan(50).millis(),
                                  timeSpan(1).seconds(),
                                  List.of(info(SELF, 7000), info(A, 7001), info(B, 7002), info(C, 7003), info(D, 7004)));
    }

    private record View(Set<NodeId> members) implements MembershipView {
        @Override public Set<NodeId> coreMemberIds() { return members; }

        @Override public Set<NodeId> onDutyMemberIds() { return members; }

        @Override public int healthyOnDutyCount() { return members.size(); }

        @Override public int desiredCoreSize() { return 5; }
    }

    private static final class Source implements GenerationSnapshotSource {
        private final AtomicReference<Option<MembershipView>> view = new AtomicReference<>(Option.none());

        void show(Set<NodeId> members) { view.set(Option.some(new View(members))); }

        @Override public Option<MembershipView> currentMembershipView() { return view.get(); }

        @Override public long observedRabiaTerm() { return 0L; }
    }

    private final List<ClusterStateNotification> notifications = new CopyOnWriteArrayList<>();
    private final Source source = new Source();

    private TopologyObserver startedObserverWithFiveVotersAndAFullView() {
        var router = MessageRouter.mutable();

        router.addRoute(NetworkServiceMessage.ListConnectedNodes.class, _ -> {});
        router.addRoute(NetworkServiceMessage.ConnectNode.class, _ -> {});
        router.addRoute(NetworkServiceMessage.DisconnectNode.class, _ -> {});
        router.addRoute(ClusterStateNotification.class, notifications::add);
        var observer = TopologyObserver.topologyObserver(fiveCoreConfig(), router, source).unwrap();

        observer.installVoterConfiguration(FIVE_VOTERS);
        source.show(Set.of(SELF, A, B, C, D));
        observer.start().await();
        awaitTrue(() -> observer.inQuorum().getAsBoolean(), "control: five voters in the view establish quorum");
        return observer;
    }

    @Test
    void twoVotersPlusAnObserver_areNotAQuorumOfFive() {
        var observer = startedObserverWithFiveVotersAndAFullView();

        source.show(Set.of(SELF, A, OBSERVER));

        awaitTrue(() -> !observer.inQuorum().getAsBoolean(), "3 members in the view but only 2 are installed voters: quorum is lost");
        assertThat(notifications.getLast().state()).isEqualTo(ClusterStateNotification.State.PASSIVE);
        observer.stop().await();
    }

    @Test
    void threeVoters_areAQuorumOfFive_evenWithAnObserverPresent() {
        var observer = startedObserverWithFiveVotersAndAFullView();

        source.show(Set.of(SELF, A, B, OBSERVER));
        awaitTicks(8);

        assertThat(observer.inQuorum().getAsBoolean()).as("3 installed voters in the view: quorum holds").isTrue();
        assertThat(notifications).extracting(ClusterStateNotification::state).containsExactly(ClusterStateNotification.State.ACTIVE);
        observer.stop().await();
    }

    private static void awaitTrue(BooleanSupplier condition, String description) {
        var deadline = System.nanoTime() + timeSpan(3).seconds().nanos();

        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            sleepBriefly(10);
        }
        assertThat(condition.getAsBoolean()).as(description).isTrue();
    }

    private static void awaitTicks(int ticks) {
        sleepBriefly(50L * ticks);
    }

    private static void sleepBriefly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
