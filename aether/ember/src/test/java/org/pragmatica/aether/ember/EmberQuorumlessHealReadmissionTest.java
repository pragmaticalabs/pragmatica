// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// #3119 on a REAL in-JVM cluster: 3 of 5 cores black-holed for 20 s, so the two survivors (2 < 3) and the three silenced
/// nodes all lose quorum and publish `quorum=lost` on SWIM, and peers go DEAD in each other's membership FSM. After the
/// heal every node must report quorum again within [#HEAL_BOUND_MS]. Since #2940 a DEAD peer reporting quorum loss was
/// not re-admitted, and each node's own report clears only after it re-admits its peers: a circular wait in which no
/// node logged "Quorum established" again (the F4 E06 regression). The heal step mirrors E06 (sorted first three ids).
@PortBudget
class EmberQuorumlessHealReadmissionTest {
    private static final int SIZE = 5;
    private static final int SILENCED = 3;
    private static final int MGMT_OFFSET = 40;
    private static final int APP_HTTP_OFFSET = 80;
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(120).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(60).seconds();
    private static final long PARTITION_MS = 20_000L;
    /// Re-admission is one SWIM dissemination after the heal plus a re-election; E06 allowed 240 s, this states 120 s.
    private static final long HEAL_BOUND_MS = 120_000L;

    private final HttpClient http = HttpClient.newHttpClient();
    private EmberCluster cluster;

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            cluster.stop().await(STOP_BOUND);
        }
    }

    @Test
    @Timeout(600)
    void everyNodeLosesQuorum_afterTheHeal_allFiveReportQuorumWithinTheBound() {
        cluster = EmberTestPorts.startedCluster(new EmberTestPorts.Block(EmberTestPorts.POOL_FIRST,
                                                                          EmberTestPorts.POOL_LAST,
                                                                          EmberTestPorts.POOL_STEP,
                                                                          2 * SIZE + 6,
                                                                          MGMT_OFFSET,
                                                                          APP_HTTP_OFFSET),
                                                basePort -> emberCluster(SIZE, basePort, basePort + MGMT_OFFSET, basePort + APP_HTTP_OFFSET, "qh"),
                                                START_BOUND);
        awaitCondition("the booted cluster reports quorum on every node", HEAL_BOUND_MS, this::everyNodeReportsQuorum);

        var silenced = cluster.allNodes().stream().map(node -> node.self().id()).sorted().limit(SILENCED).toList();

        var blackholedAt = System.currentTimeMillis();

        silenced.forEach(id -> cluster.getNode(id).onPresent(node -> node.blackhole(true)));
        awaitCondition("control: every node reports the quorum lost within the partition", PARTITION_MS, this::noNodeReportsQuorum);
        pause(Math.max(0L, blackholedAt + PARTITION_MS - System.currentTimeMillis()));
        silenced.forEach(id -> cluster.getNode(id).onPresent(node -> node.blackhole(false)));

        var healedAt = System.currentTimeMillis();

        awaitCondition("all five report quorum after the heal (#3119)", HEAL_BOUND_MS,
                       () -> cluster.allNodes().stream().anyMatch(AetherNode::isLeader) && everyNodeReportsQuorum());
        System.out.println("EmberQuorumlessHealReadmissionTest: quorum on all " + SIZE + " in "
                           + (System.currentTimeMillis() - healedAt) + " ms after the heal");
        assertThat(cluster.status().nodes()).as("no node was lost to the partition").hasSize(SIZE);
    }

    private boolean everyNodeReportsQuorum() {
        var nodes = cluster.status().nodes();

        return nodes.size() == SIZE && nodes.stream().allMatch(node -> health(node.mgmtPort()).contains("\"quorum\":true"));
    }

    private boolean noNodeReportsQuorum() {
        return cluster.status().nodes().stream().noneMatch(node -> health(node.mgmtPort()).contains("\"quorum\":true"));
    }

    private String health(int mgmtPort) {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + mgmtPort + "/api/v1/health"))
                                 .timeout(Duration.ofSeconds(5))
                                 .GET()
                                 .build();
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString()).body();
        } catch (Exception e) {
            return "{\"error\":\"" + e.getClass().getSimpleName() + "\"}";
        }
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitCondition(String what, long boundMs, BooleanSupplier condition) {
        var deadline = System.currentTimeMillis() + boundMs;

        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out waiting: " + what);
            }
            pause(250);
        }
    }
}
