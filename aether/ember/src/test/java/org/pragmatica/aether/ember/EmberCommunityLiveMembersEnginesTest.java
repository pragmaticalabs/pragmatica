// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.GovernorAnnouncementKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentEngine;
import org.pragmatica.aether.slice.kvstore.AetherValue.GovernorAnnouncementValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// #3159: the community live-member count the management API serves (`ManageableNode#communityLiveMembers`) is the SAME number under both
/// deployment engines. It used to come from the LEGACY `ClusterDeploymentManager` alone, which is never active under FSM2, so an FSM2 leader
/// answered "absent" for a community with a committed roster. A real three-node cluster per engine: the leader commits a one-member roster
/// whose member is a live follower (answering the leader's pings, so the leader's wired liveness view does not observe it absent) and the
/// leader's own reading is polled; a follower never reports a count (it is not the observer).
@PortBudget
class EmberCommunityLiveMembersEnginesTest {
    private static final int CLUSTER_SIZE = 3;
    private static final int MGMT_OFFSET = 40;
    private static final int APP_OFFSET = 80;
    private static final EmberTestPorts.Block PORTS = new EmberTestPorts.Block(EmberTestPorts.POOL_FIRST,
                                                                                EmberTestPorts.POOL_LAST,
                                                                                EmberTestPorts.POOL_STEP,
                                                                                2 * CLUSTER_SIZE,
                                                                                MGMT_OFFSET,
                                                                                APP_OFFSET);
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(120).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(60).seconds();
    private static final TimeSpan APPLY_BOUND = TimeSpan.timeSpan(30).seconds();
    private static final long WAIT_BOUND_MS = 60_000L;
    private static final String COMMUNITY = "east";

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private EmberCluster cluster;

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            cluster.stop().await(STOP_BOUND);
        }
    }

    @Test
    @Timeout(300)
    void communityLiveMembers_isTheCommittedRosterCount_underLegacy() {
        assertRosterCountAndFollowerAbsence(DeploymentEngine.LEGACY);
    }

    @Test
    @Timeout(300)
    void communityLiveMembers_isTheCommittedRosterCount_underFsm2() {
        assertRosterCountAndFollowerAbsence(DeploymentEngine.FSM2);
    }

    private void assertRosterCountAndFollowerAbsence(DeploymentEngine engine) {
        cluster = EmberTestPorts.startedCluster(PORTS, candidate -> {
            var started = emberCluster(CLUSTER_SIZE, candidate, candidate + MGMT_OFFSET, candidate + APP_OFFSET, "clm");

            started.withDeploymentEngine(engine);
            return started;
        }, START_BOUND);
        await("a leader is elected", () -> cluster.currentLeader().isPresent());
        var leader = leader();
        var member = cluster.allNodes().stream().map(AetherNode::self).filter(id -> !id.equals(leader.self())).findFirst().orElseThrow();

        commit(leader, ActivationDirectiveKey.activationDirectiveKey(member), ActivationDirectiveValue.worker(COMMUNITY, ""));
        commit(leader, GovernorAnnouncementKey.forCommunity(COMMUNITY), GovernorAnnouncementValue.governorAnnouncementValue(member, List.of(member), ""));

        await("the leader serves the roster count (engine " + engine + ")", () -> leader.communityLiveMembers(COMMUNITY).equals(Option.some(1)));
        await("the management route serves the same count (engine " + engine + ")", () -> routeLiveMembers().equals(Option.some("1")));
        var follower = cluster.allNodes().stream().filter(node -> !node.self().equals(leader.self())).findFirst().orElseThrow();

        assertThat(follower.communityLiveMembers(COMMUNITY)).as("a follower is not the observer (engine %s)", engine).isEqualTo(Option.none());
    }

    /// `liveMembers` of the single-community route over real HTTP on the leader's management port: the instance the route reads is the one
    /// handed to the management server, not the node `leader()` returns, so a wiring gap on that instance shows only here (#3159).
    private Option<String> routeLiveMembers() {
        var port = cluster.getLeaderManagementPort().unwrap();
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + "/api/v1/cluster/communities/" + COMMUNITY))
                                 .GET()
                                 .build();
        var body = Result.lift(Causes::fromThrowable, () -> HTTP.send(request, HttpResponse.BodyHandlers.ofString()))
                         .filter(Causes.cause("community route did not answer 200"), response -> response.statusCode() == 200)
                         .map(HttpResponse::body)
                         .or("");
        var matcher = Pattern.compile("\"liveMembers\":\"?([A-Za-z0-9_-]+)\"?").matcher(body);

        return matcher.find()
               ? Option.some(matcher.group(1))
               : Option.none();
    }

    private AetherNode leader() {
        return cluster.currentLeader().flatMap(cluster::getNode).unwrap();
    }

    private void commit(AetherNode node, AetherKey key, AetherValue replacement) {
        var id = UUID.randomUUID().toString();
        var authority = node.kvStore().getTyped(LeaderKey.INSTANCE, LeaderValue.class).unwrap();
        var transaction = new KVCommand.LeaderTransaction<AetherKey, AetherValue>(key,
                                                                                  id,
                                                                                  authority,
                                                                                  List.of(),
                                                                                  List.of(new KVCommand.Mutation<AetherKey, AetherValue>(key,
                                                                                                                                         Option.none(),
                                                                                                                                         Option.some(replacement))));
        var results = node.<Object> apply(List.of(transaction)).await(APPLY_BOUND).unwrap();

        assertThat(results).as("the leader transaction on %s is accepted", key.asString())
                           .anyMatch(outcome -> outcome instanceof KVCommand.TransactionResult accepted
                                                && accepted.transactionId().equals(id)
                                                && accepted.accepted());
    }

    private static void await(String what, BooleanSupplier condition) {
        var deadline = System.currentTimeMillis() + WAIT_BOUND_MS;

        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out waiting: " + what);
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted waiting: " + what);
            }
        }
    }
}
