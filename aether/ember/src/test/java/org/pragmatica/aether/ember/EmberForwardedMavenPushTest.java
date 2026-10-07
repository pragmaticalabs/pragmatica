// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.config.SecurityMode;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// #1983 on a real 3-node cluster with management security ON: an authenticated Maven push must succeed through EVERY
/// node, not only the DEPLOYMENT task-group owner. `ARTIFACT_PUT` is routed to the owner; a non-owner forwards it, and
/// before #1983 the owner ran the forwarded request without the validated SecurityContext, so the in-route push gate
/// (`MavenProtocolRoutes.admitPush`) saw an anonymous caller and answered 401. Which node owns the group is not
/// asserted: pushing through all three guarantees at least two are non-owners.
@PortBudget
class EmberForwardedMavenPushTest {
    private static final int CLUSTER_SIZE = 3;
    /// `EmberCluster.start` builds a slot pool of `2 * clusterSize`, so a block must cover twice the
    /// node count even though only [#CLUSTER_SIZE] slots are used here.
    private static final int SLOTS = 2 * CLUSTER_SIZE;
    private static final int MGMT_OFFSET = 40;
    private static final int APP_HTTP_OFFSET = 80;
    private static final int FIRST_CANDIDATE_BASE = 30000;
    private static final int LAST_CANDIDATE_BASE = 31800;
    private static final int CANDIDATE_STEP = 200;
    /// #1667: probed through the shared EmberTestPorts, which also probes each node's SWIM UDP port.
    private static final EmberTestPorts.Block PORTS = new EmberTestPorts.Block(FIRST_CANDIDATE_BASE,
                                                                                LAST_CANDIDATE_BASE,
                                                                                CANDIDATE_STEP,
                                                                                SLOTS,
                                                                                MGMT_OFFSET,
                                                                                APP_HTTP_OFFSET);
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(120).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(60).seconds();

    private static final String CLUSTER_SECRET = "ember-980-bootstrap-admin-key-secret";

    /// HKDF-SHA256(ikm = CLUSTER_SECRET, salt = "aether-ca-seed", info =
    /// "aether-bootstrap-admin-key-v1", 32 bytes), base64url-unpadded, `aeth_`-prefixed — computed in
    /// Python, not by this codebase. See the class javadoc for why that matters.
    private static final String DERIVED_KEY = "aeth_xsxGTf4Qb1-ZNRtrjIDARJxcvHBw0HApinN6F3HyB_o";

    /// The same derivation over `"ember-980-a-different-cluster-secret"`. Well-formed, `aeth_`-shaped,
    /// and belonging to another cluster — the control that proves the validator discriminates rather
    /// than accepting anything key-shaped.
    private static final String FOREIGN_KEY = "aeth_ct_X-okyJ0EvtQY0lmoLz41vAnxJTGTqo-3liu28bCc";

    /// `BootstrapAdminKeyLeg.KEY_ID`, repeated rather than imported: `aether/ember` does not depend on
    /// `aether/node`'s internals, and the operator-visible key id is part of the contract being pinned.
    private static final String BOOTSTRAP_KEY_ID = "bootstrap-admin";

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final long LEADER_ELECTION_BUDGET_MS = 60_000L;
    private static final long KEY_REGISTRATION_BUDGET_MS = 120_000L;
    private static final long POLL_INTERVAL_MS = 500L;

    private EmberCluster cluster;

    private record Response(int status, String body) {}

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            assertThat(cluster.stop().await(STOP_BOUND).fold(Cause::message, _ -> "stopped"))
                .describedAs("cluster stop must complete within %s", STOP_BOUND)
                .isEqualTo("stopped");
        }
    }

    /// One cluster boot, four assertions, because booting a second three-node cluster to split them
    /// would double the wall time and the flake surface for no added isolation — they are stages of a
    /// single claim.
    @Test
    @Timeout(420)
    void authenticatedPush_succeedsThroughEveryNode_owner_and_nonOwners_alike() {
        var leaderPort = startClusterAndResolveLeaderPort();

        assertThat(awaitRegisteredKey(leaderPort)).describedAs("the derived admin key is registered").contains(BOOTSTRAP_KEY_ID);

        var mgmtPorts = cluster.status().nodes().stream().map(EmberCluster.NodeStatus::mgmtPort).toList();

        assertThat(mgmtPorts).describedAs("three nodes, so at least two are not the group owner").hasSize(CLUSTER_SIZE);

        var statuses = new java.util.ArrayList<String>();

        for (var path : PUSH_PATHS) {
            for (var port : mgmtPorts) {
                statuses.add(path + "@" + port + "=" + push(port, path).status());
            }
        }

        // Admitted means the in-route gate let it through: any answer but 401/403. The owner may still refuse the
        // probe's content (400), which is the artifact handler judging the pom, not authorization.
        assertThat(statuses).describedAs("PUT /repository with the ADMIN key, through each node, per path shape: %s", statuses)
                            .allSatisfy(entry -> assertThat(Integer.parseInt(entry.substring(entry.lastIndexOf('=') + 1)))
                                .describedAs(entry)
                                .isNotIn(401, 403));
    }

    /// Control: the same push with NO key is refused on every node, so the 2xx above is authentication that was
    /// REACHED and satisfied, not a gate that is off.
    @Test
    @Timeout(420)
    void unauthenticatedPush_isRefusedOnEveryNode() {
        var leaderPort = startClusterAndResolveLeaderPort();

        awaitRegisteredKey(leaderPort);

        for (var port : cluster.status().nodes().stream().map(EmberCluster.NodeStatus::mgmtPort).toList()) {
            assertThat(exchange(port, PUSH_PATH, null, HttpRequest.BodyPublishers.ofString(POM), "PUT").status())
                .describedAs("no key, node on mgmt port %d", port)
                .isIn(401, 403);
        }
    }

    private static Response push(int mgmtPort, String path) {
        return exchange(mgmtPort, path, DERIVED_KEY, HttpRequest.BodyPublishers.ofString(POM), "PUT");
    }

    /// One path per shape: a single-segment group, which matches `ARTIFACT_PUT` and so is task-group routed (forwarded
    /// from a non-owner), and a slashed group, as a Maven client sends it.
    private static final java.util.List<String> PUSH_PATHS = java.util.List.of("/repository/probe1983/probe/1.0.0/probe-1.0.0.pom",
                                                                               "/repository/org/aether/probe1983/probe/1.0.0/probe-1.0.0.pom");
    private static final String PUSH_PATH = PUSH_PATHS.getFirst();
    private static final String POM = "<project><modelVersion>4.0.0</modelVersion><groupId>org.aether.probe1983</groupId>"
                                      + "<artifactId>probe</artifactId><version>1.0.0</version></project>";

    private int startClusterAndResolveLeaderPort() {
        cluster = EmberTestPorts.startedCluster(PORTS, EmberForwardedMavenPushTest::incidentPostureCluster, START_BOUND);

        // Leadership is not established at the instant `start()` returns, so this waits rather than
        // asserting immediately — the first version asserted straight away and failed on a cluster that
        // had formed perfectly well, which would have read as a defect in the code under test.
        var leaderPort = awaitLeaderManagementPort();

        assertThat(leaderPort.isPresent())
            .describedAs("a formed cluster must elect a leader within %dms; without one the bootstrap "
                         + "admin key leg never arms and this test would be measuring nothing",
                         LEADER_ELECTION_BUDGET_MS)
            .isTrue();

        return leaderPort.unwrap();
    }

    /// The leader can change while the test runs (a fresh cluster churns) and these routes are
    /// leader-bound, so the port is re-resolved per request rather than captured once.
    private int leaderPort(int fallback) {
        return cluster.getLeaderManagementPort().or(fallback);
    }

    private Option<Integer> awaitLeaderManagementPort() {
        var deadline = System.currentTimeMillis() + LEADER_ELECTION_BUDGET_MS;

        while (System.currentTimeMillis() < deadline) {
            var port = cluster.getLeaderManagementPort();

            if (port.isPresent()) {
                return port;
            }

            sleepQuietly();
        }

        return cluster.getLeaderManagementPort();
    }

    /// Poll `GET /api/v1/cluster/keys` WITH the derived key until the bootstrap key's registration is
    /// visible. Returns the last thing actually observed — body, or `HTTP <status>` — so a failure
    /// reports what the cluster answered rather than a bare timeout.
    private String awaitRegisteredKey(int mgmtPort) {
        var deadline = System.currentTimeMillis() + KEY_REGISTRATION_BUDGET_MS;
        var last = "<never read>";

        while (System.currentTimeMillis() < deadline) {
            var response = send(leaderPort(mgmtPort), "/api/v1/cluster/keys", DERIVED_KEY);

            if (response.status() == 200) {
                last = response.body();

                if (last.contains(BOOTSTRAP_KEY_ID)) {
                    return last;
                }
            } else {
                last = "HTTP " + response.status();
            }

            sleepQuietly();
        }

        return last;
    }

    @SuppressWarnings("JBCT-EX-01")
    private static Response send(int mgmtPort, String path, String apiKey) {
        return exchange(mgmtPort, path, apiKey, HttpRequest.BodyPublishers.noBody(), "GET");
    }

    @SuppressWarnings("JBCT-EX-01")
    private static Response exchange(int mgmtPort,
                                     String path,
                                     String apiKey,
                                     HttpRequest.BodyPublisher body,
                                     String method) {
        var builder = HttpRequest.newBuilder()
                                 .uri(URI.create("http://127.0.0.1:" + mgmtPort + path))
                                 .timeout(REQUEST_TIMEOUT)
                                 .method(method, body);

        if (apiKey != null) {
            builder.header("X-API-Key", apiKey);
        }

        try (var client = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build()) {
            var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());

            return new Response(response.statusCode(), response.body());
        } catch (Exception e) {
            // A transport failure is not an authentication result. -1 can never equal 200 and never
            // contains the key id, so it cannot fake either a pass or a control; it is reported
            // verbatim so a wedged port does not read as a 401.
            System.err.println("  probe transport failure for " + path + ": " + e);

            return new Response(-1, "");
        }
    }

    @SuppressWarnings("JBCT-EX-01")
    private static void sleepQuietly() {
        try {
            Thread.sleep(POLL_INTERVAL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /// The incident's cluster (#1667: built per attempt, so a port lost between probe and bind retries on a fresh block).
    private static EmberCluster incidentPostureCluster(int basePort) {
        var built = emberCluster(CLUSTER_SIZE, basePort, basePort + MGMT_OFFSET, basePort + APP_HTTP_OFFSET, "adminkey");

        built.withClusterSecret(CLUSTER_SECRET.getBytes(StandardCharsets.UTF_8));
        // Reproduce the INCIDENT'S posture exactly: management security ON, and NOT ONE key configured
        // — which is every cluster `aether cluster init` creates, because it never writes one. Ember's
        // default `SecurityMode.NONE` disables `ManagementServer`'s security gate outright
        // (`securityEnabled` at its dispatch), so under the default every request answers 200 and this
        // test would measure the absence of a gate rather than the presence of a credential. That is
        // not hypothetical: it is what the first run of this test did, and control 1 below is what
        // caught it.
        built.withAppHttpSecurity(SecurityMode.API_KEY, Map.of());

        return built;
    }

}
