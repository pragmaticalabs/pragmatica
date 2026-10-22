// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.pragmatica.aether.config.ApiKeyEntry;
import org.pragmatica.aether.config.SecurityMode;
import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.aether.forge.api.OperatorKey;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import static org.pragmatica.aether.ember.EmberCluster.emberCluster;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// #1105 — with a sibling `aether.toml` declaring API keys the embedded nodes run `SecurityMode.API_KEY`, and every
/// call Forge itself makes to a node must carry the operator's key or the node refuses it. What is left of Forge's
/// own node calls once the dashboard proxies are gone is its event poll ([NodeEventPoll], behind
/// `ForgeServer.pollNodeEvents`), driven here against a REAL three-node Ember cluster whose management API
/// enforces keys.
///
/// The keyless poll is the in-run CONTROL: it proves the cluster actually refuses an unkeyed call, so a keyed
/// success is evidence the key was sent, not that nothing was checked.
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ForgeProxyApiKeyForgeTest {
    private static final int BASE_PORT = 15500;
    private static final int BASE_MGMT_PORT = 15700;
    private static final int BASE_APP_HTTP_PORT = 15800;
    private static final int NODES = 3;
    private static final String NODE_PREFIX = "fpk";
    private static final String OPERATOR_KEY = "forge-1105-operator-key";
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(240);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);
    private static final int POLLS = 20;
    private static final int CLIENT_SLACK = 3;

    private EmberCluster cluster;

    @BeforeAll
    void setUp() {
        cluster = emberCluster(NODES, BASE_PORT, BASE_MGMT_PORT, BASE_APP_HTTP_PORT, NODE_PREFIX);
        // MUST precede start(): every node reads the security mode and the key map at construction.
        cluster.withAppHttpSecurity(SecurityMode.API_KEY,
                                    Map.of(OPERATOR_KEY,
                                           ApiKeyEntry.apiKeyEntry("forge-1105-operator", Set.of("service"), "ADMIN")));
        LifecycleAwait.settled("cluster start in setUp()", cluster, cluster.start());
        await().alias("leader elected")
               .atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> cluster.currentLeader()
                                   .isPresent());
    }

    @AfterAll
    void tearDown() {
        if (cluster != null) {
            LifecycleAwait.bestEffort("cluster stop in tearDown()", cluster, cluster.stop());
        }
    }

    /// v1533 F1: Forge's own event poll ([NodeEventPoll], behind `ForgeServer.pollNodeEvents`) carries the operator key
    /// too. Under `API_KEY` the keyed fetch returns a non-empty event timeline; a keyless fetch of the same route is
    /// refused in the same run.
    @Test
    void forgeEventPoll_carriesTheOperatorKey_andReadsTheTimeline() {
        var port = cluster.getLeaderManagementPort().unwrap();
        var keyed = NodeEventPoll.nodeEventPoll(() -> Option.some(OPERATOR_KEY));

        await().alias("keyed event poll returns a non-empty timeline")
               .atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> keyed.fetch(port, "")
                                 .fold(_ -> false, body -> body.startsWith("[") && body.contains("\"type\"")));

        var keyless = NodeEventPoll.nodeEventPoll(OperatorKey.none()).fetch(port, "");

        assertThat(keyless.isFailure()).as("control: the cluster refuses a keyless event poll").isTrue();
        keyless.onFailure(cause -> assertThat(cause.message()).containsAnyOf("HTTP 401", "HTTP 403"));
    }

    /// v1533 C1: one poller keeps ONE `HttpClient` across polls. A client per fetch leaves a live selector thread per
    /// 2 s tick until a GC reclaims it. Counts the JDK client selector threads that appear during the polls.
    ///
    /// Two in-run controls keep the bound from passing vacuously: the poller's own client must appear (the count can
    /// see a client at all), and a client per fetch, exactly the regression, must raise the count past the bound
    /// in this same JVM (its threads survive long enough to be counted).
    @Test
    void forgeEventPoll_reusesOneClient_acrossPolls() {
        var port = cluster.getLeaderManagementPort().unwrap();
        var before = selectorThreads();
        var poll = NodeEventPoll.nodeEventPoll(() -> Option.some(OPERATOR_KEY));

        for (int i = 0; i < POLLS; i++) {
            assertThat(poll.fetch(port, "").isSuccess()).as("keyed poll %d succeeds", i).isTrue();
        }

        var created = newSince(before);

        assertThat(created).as("control: the poller's own client selector thread is visible").isNotEmpty();
        assertThat(created).as("one poller, one client across %d polls (other JVM clients allowed)", POLLS)
                           .hasSizeLessThanOrEqualTo(CLIENT_SLACK);

        var beforeControl = selectorThreads();

        for (int i = 0; i < POLLS; i++) {
            NodeEventPoll.nodeEventPoll(() -> Option.some(OPERATOR_KEY)).fetch(port, "");
        }

        assertThat(newSince(beforeControl)).as("control: a client per fetch is visible above the bound")
                                           .hasSizeGreaterThan(CLIENT_SLACK);
    }

    private static Set<Thread> selectorThreads() {
        return Thread.getAllStackTraces()
                     .keySet()
                     .stream()
                     .filter(thread -> thread.getName().startsWith("HttpClient-")
                                       && thread.getName().endsWith("-SelectorManager"))
                     .collect(Collectors.toSet());
    }

    private static Set<Thread> newSince(Set<Thread> before) {
        return selectorThreads().stream()
                                .filter(thread -> !before.contains(thread))
                                .collect(Collectors.toSet());
    }
}
