// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;

/// G1-g: who creates the nodes of a pool, read from the running cluster's management API (auto-heal setting, committed topology,
/// live nodes). The cluster is a map of canned answers; no network.
class ClusterPoolAuthorityTest {
    private static final String ON = "{\"enabled\":true}";
    private static final String OFF = "{\"enabled\":false}";
    private static final String CONFIG = "{\"desiredTopology\":[{\"sourceName\":\"eu-1\",\"role\":\"core\",\"count\":5},"
                                         + "{\"sourceName\":\"eu-1\",\"role\":\"worker\",\"count\":2}]}";

    private final Map<ManagementRoute, Result<String>> cluster = new LinkedHashMap<>();
    private final List<ManagementRoute> asked = new ArrayList<>();
    private final List<Duration> pauses = new ArrayList<>();
    private final AtomicLong clock = new AtomicLong();
    private final List<String> progress = new ArrayList<>();

    private ClusterPoolAuthority authority() {
        return ClusterPoolAuthority.clusterPoolAuthority(route -> {
            asked.add(route);

            return cluster.getOrDefault(route, Causes.cause("no answer for " + route).result());
        }, pauses::add, clock::get, Duration.ofSeconds(100), Duration.ofSeconds(10));
    }

    private void answer(ManagementRoute route, String json) {
        cluster.put(route, Result.success(json));
    }

    @Test
    void coreWithAutoHealOn_isTheLeadersWhenTheTargetIsCommitted() {
        answer(ManagementRoute.CLUSTER_AUTO_HEAL_STATUS, ON);
        answer(ManagementRoute.CLUSTER_CONFIG_GET, CONFIG);

        assertThat(authority().actorFor("eu-1", NodeRole.CORE, 5).unwrap()).isEqualTo(PoolAuthority.Actor.LEADER);
    }

    @Test
    void autoHealAnswerWithoutTheFlag_isReadAsEnabled_theSafeDirection() {
        answer(ManagementRoute.CLUSTER_AUTO_HEAL_STATUS, "{}");
        answer(ManagementRoute.CLUSTER_CONFIG_GET, CONFIG);

        assertThat(authority().actorFor("eu-1", NodeRole.CORE, 5).unwrap()).isEqualTo(PoolAuthority.Actor.LEADER);
    }

    @Test
    void coreWithAutoHealOff_isApplys_withoutReadingTheConfig() {
        answer(ManagementRoute.CLUSTER_AUTO_HEAL_STATUS, OFF);

        assertThat(authority().actorFor("eu-1", NodeRole.CORE, 5).unwrap()).isEqualTo(PoolAuthority.Actor.APPLY);
        assertThat(asked).containsExactly(ManagementRoute.CLUSTER_AUTO_HEAL_STATUS);
    }

    @Test
    void workerWithAutoHealOff_isStillTheLeaders_theSwitchDoesNotGateWorkerProvisioning() {
        answer(ManagementRoute.CLUSTER_AUTO_HEAL_STATUS, OFF);
        answer(ManagementRoute.CLUSTER_CONFIG_GET, CONFIG);

        assertThat(authority().actorFor("eu-1", NodeRole.WORKER, 2).unwrap()).isEqualTo(PoolAuthority.Actor.LEADER);
    }

    @Test
    void poolCommittedBelowTheWantedCount_isRefusedNamingBothCounts() {
        answer(ManagementRoute.CLUSTER_AUTO_HEAL_STATUS, ON);
        answer(ManagementRoute.CLUSTER_CONFIG_GET, CONFIG);

        authority().actorFor("eu-1", NodeRole.WORKER, 4)
                   .onSuccess(actor -> assertThat(actor).isNull())
                   .onFailure(ClusterPoolAuthorityTest::assertNamedRefusal);
    }

    private static void assertNamedRefusal(Cause cause) {
        assertThat(cause).isInstanceOf(PoolAuthority.PoolRefused.class);
        assertThat(cause.message()).contains("eu-1.worker")
                                   .contains("committed target is 2")
                                   .contains("needs 4")
                                   .contains("Nothing was created");
    }

    @Test
    void poolWithNoCommittedEntry_isRefused() {
        answer(ManagementRoute.CLUSTER_AUTO_HEAL_STATUS, ON);
        answer(ManagementRoute.CLUSTER_CONFIG_GET, CONFIG);

        assertThat(authority().actorFor("us-9", NodeRole.WORKER, 1).isFailure()).isTrue();
    }

    @Test
    void unreadableAutoHealSetting_isRefusedNotGuessed() {
        authority().actorFor("eu-1", NodeRole.CORE, 5)
                   .onSuccess(actor -> assertThat(actor).isNull())
                   .onFailure(cause -> assertThat(cause).isInstanceOf(PoolAuthority.PoolStateUnreadable.class));
    }

    @Test
    void unreadableCommittedConfig_isRefusedNotGuessed() {
        answer(ManagementRoute.CLUSTER_AUTO_HEAL_STATUS, ON);

        assertThat(authority().actorFor("eu-1", NodeRole.CORE, 5).isFailure()).isTrue();
    }

    @Test
    void awaitLeader_pollsUntilTheRoleIsLive_andReportsEachPoll() {
        var polls = new ArrayList<String>(List.of(live(1), live(2), live(3)));
        // the last answer repeats and time passes, so a wait that never stops on 3 of 3 ends at the timeout as an assertion failure
        var counting = ClusterPoolAuthority.clusterPoolAuthority(route -> Result.success(polls.size() > 1
                                                                                         ? polls.removeFirst()
                                                                                         : polls.getFirst()),
                                                                 duration -> {
                                                                     pauses.add(duration);
                                                                     clock.addAndGet(duration.toMillis());
                                                                 },
                                                                 clock::get,
                                                                 Duration.ofSeconds(100),
                                                                 Duration.ofSeconds(10));

        counting.awaitLeader(NodeRole.CORE, 3, progress::add).unwrap();

        assertThat(progress).hasSize(3);
        assertThat(progress.getFirst()).contains("1 live of 3");
        assertThat(progress.getLast()).contains("3 live of 3");
        assertThat(pauses).hasSize(2).containsOnly(Duration.ofSeconds(10));
    }

    @Test
    void awaitLeader_countsOnlySwimAliveNodesOfTheRole() {
        answer(ManagementRoute.NODES_LIVE, "{\"nodes\":[{\"role\":\"core\",\"swimAlive\":true},{\"role\":\"core\",\"swimAlive\":false},"
                                           + "{\"role\":\"worker\",\"swimAlive\":true}]}");

        authority().awaitLeader(NodeRole.CORE, 1, progress::add).unwrap();

        assertThat(progress.getFirst()).contains("1 live of 1");
    }

    @Test
    void awaitLeader_givesUpAtTheTimeout_namingWhatIsLiveAndWhatWasWanted() {
        answer(ManagementRoute.NODES_LIVE, live(1));
        var ticking = ClusterPoolAuthority.clusterPoolAuthority(route -> cluster.get(route),
                                                                duration -> clock.addAndGet(duration.toMillis()),
                                                                clock::get,
                                                                Duration.ofSeconds(100),
                                                                Duration.ofSeconds(10));

        ticking.awaitLeader(NodeRole.CORE, 3, progress::add)
               .onSuccess(unit -> assertThat(unit).isNull())
               .onFailure(cause -> assertThat(cause).isInstanceOf(PoolAuthority.LeaderDidNotConverge.class));

        assertThat(progress).hasSize(11);
        assertThat(progress.getLast()).contains("1 live of 3");
    }

    private static String live(int core) {
        var nodes = new StringBuilder("{\"nodes\":[");

        for (int i = 0; i < core; i++) {
            nodes.append(i == 0 ? "" : ",").append("{\"role\":\"core\",\"swimAlive\":true}");
        }

        return nodes.append("]}").toString();
    }
}
