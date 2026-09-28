// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.pragmatica.aether.api.ClusterEvent;
import org.pragmatica.aether.api.ClusterEvent.CommunityMemberJoined;
import org.pragmatica.aether.api.ClusterEvent.CommunityMinted;
import org.pragmatica.aether.api.ClusterEvent.CommunityStateChanged;
import org.pragmatica.aether.api.ClusterEvent.Severity;
import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.GovernorAnnouncementKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.GovernorAnnouncementValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// #1652 live path: a community formed by REAL worker admissions (`addWorkerNode`, not a synthetic
/// `NodeJoined` — the #367 lesson) is observable through `GET /cluster/communities` and the cluster
/// event log, and so is its degradation when a member is killed.
///
/// Three cores plus three workers: the default viability floor is 3, so the community forms (FORMING →
/// ACTIVE) at exactly three live members, and killing one non-governor worker drops the leader's live
/// count below the floor (ACTIVE → DEGRADED) once the community-absence window (20s default) passes.
@Execution(ExecutionMode.SAME_THREAD)
class CommunityObservabilityForgeTest {
    private static final TimeSpan BUDGET = TimeSpan.timeSpan(180).seconds();
    private static final int WORKERS = 3;
    private final EmberCluster cluster = EmberCluster.emberCluster(3, 44100, 44200, 44300, "community-obs");
    private final HttpClient http = HttpClient.newHttpClient();

    @AfterEach
    void stop() {
        LifecycleAwait.bestEffort("stop community-obs cluster", cluster, cluster.stop());
    }

    @Test
    void communityLifecycle_isObservableThroughTheRouteAndEvents_fromFormationToDegradation() {
        LifecycleAwait.settled("start community-obs cluster", cluster, cluster.start());
        await().atMost(BUDGET.duration())
               .until(() -> cluster.currentLeader().isPresent());
        var workers = admitWorkers();
        var community = communityOf(workers.getFirst());

        await().atMost(BUDGET.duration())
               .until(() -> field(communityJson(community), "state").equals(Option.some("ACTIVE"))
                            && field(communityJson(community), "liveMembers").equals(Option.some(String.valueOf(WORKERS))));
        await().atMost(BUDGET.duration())
               .untilAsserted(() -> assertFormationEvents(community));

        var victim = nonGovernor(community, workers);

        LifecycleAwait.nodeSettled("kill worker " + victim.id(), cluster, cluster.killNode(victim.id(), false));
        await().atMost(BUDGET.duration())
               .until(() -> field(communityJson(community), "state").equals(Option.some("DEGRADED")));
        assertThat(field(communityJson(community), "liveMembers").map(Integer::parseInt)).hasValueSatisfying(live -> assertThat(live).isLessThan(WORKERS));
        await().atMost(BUDGET.duration())
               .untilAsserted(() -> assertThat(stateChanges(community)).anySatisfy(CommunityObservabilityForgeTest::assertDegradedEdge));
    }

    private static void assertDegradedEdge(ClusterEvent event) {
        assertThat(event.details()).containsEntry("from", "ACTIVE")
                                   .containsEntry("to", "DEGRADED");
        assertThat(event.severity()).isEqualTo(Severity.WARNING);
    }

    private List<NodeId> admitWorkers() {
        var workers = new ArrayList<NodeId>();

        for (int index = 0; index < WORKERS; index++) {
            workers.add(LifecycleAwait.nodeSettled("admit community-obs worker", cluster, cluster.addWorkerNode()));
        }

        return workers;
    }

    private String communityOf(NodeId worker) {
        await().atMost(BUDGET.duration())
               .until(() -> directive(worker).isPresent());

        return directive(worker).unwrap()
                                .communityId();
    }

    private void assertFormationEvents(String community) {
        var events = communityEvents(community);

        assertThat(events).anyMatch(CommunityMinted.class::isInstance);
        assertThat(events).filteredOn(CommunityMemberJoined.class::isInstance)
                          .hasSizeGreaterThanOrEqualTo(WORKERS);
        assertThat(stateChanges(community)).anySatisfy(event -> assertThat(event.details()).containsEntry("from", "FORMING")
                                                                                           .containsEntry("to", "ACTIVE"));
    }

    private List<ClusterEvent> stateChanges(String community) {
        return communityEvents(community).stream()
                                         .filter(CommunityStateChanged.class::isInstance)
                                         .toList();
    }

    private List<ClusterEvent> communityEvents(String community) {
        return leader().eventAggregator()
                       .events()
                       .await()
                       .or(List.of())
                       .stream()
                       .filter(event -> community.equals(event.details().get("communityId")))
                       .toList();
    }

    private NodeId nonGovernor(String community, List<NodeId> workers) {
        var governor = leader().kvStore()
                               .getTyped(GovernorAnnouncementKey.forCommunity(community), GovernorAnnouncementValue.class)
                               .unwrap()
                               .governorId();

        return workers.stream()
                      .filter(worker -> !worker.equals(governor))
                      .findFirst()
                      .orElseThrow();
    }

    private Option<ActivationDirectiveValue> directive(NodeId worker) {
        return leader().kvStore()
                       .getTyped(ActivationDirectiveKey.activationDirectiveKey(worker), ActivationDirectiveValue.class)
                       .filter(value -> !value.communityId().isEmpty());
    }

    private AetherNode leader() {
        return cluster.currentLeader()
                      .flatMap(cluster::getNode)
                      .unwrap();
    }

    /// The single-community detail route, over real HTTP on the leader's management port — the path an
    /// operator's CLI takes, including JSON serialization of the absent-as-null fields.
    private String communityJson(String community) {
        var port = cluster.getLeaderManagementPort().unwrap();
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + "/api/v1/cluster/communities/" + community))
                                 .GET()
                                 .build();

        return Result.lift(Causes::fromThrowable, () -> http.send(request, HttpResponse.BodyHandlers.ofString()))
                     .filter(Causes.cause("community route did not answer 200"), response -> response.statusCode() == 200)
                     .map(HttpResponse::body)
                     .or("");
    }

    private static Option<String> field(String json, String name) {
        var matcher = Pattern.compile("\"" + name + "\":\"?([A-Za-z0-9_-]+)\"?").matcher(json);

        return matcher.find()
               ? Option.some(matcher.group(1))
               : Option.none();
    }
}
