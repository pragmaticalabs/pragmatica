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

/// #980 — the END-TO-END pin for the derived bootstrap admin key, and the only test in the tree that
/// reddens when the cluster secret stops reaching the node.
///
/// **Why it exists.** The unit tests around this feature each pin one link, and the links BETWEEN them
/// were measured unpinned: deleting `config.clusterSecret()` from `AetherNode`'s call to
/// `BootstrapAdminKeyLeg`, or `EmberCluster`'s pass-through, left **1,305 tests green**. The design of
/// #980 rests entirely on that secret travelling from configuration to the leg, so its correctness was
/// an accident nothing defended: a refactor of either line would pass CI and silently return cloud
/// bootstrap to the `401` this ticket exists to fix.
///
/// **What it drives.** A real three-node in-JVM cluster booted from a known cluster secret, through the
/// real `EmberCluster` -> `AetherNodeConfig` -> `AetherNode` -> `BootstrapAdminKeyLeg` wiring, real
/// leader election, and a real consensus commit of the key's hash into the KV store — then ordinary
/// authenticated HTTP requests against the live management API.
///
/// **Why the expected key is a hard-coded literal.** It is [#DERIVED_KEY], computed OUTSIDE this
/// codebase (Python `hmac`/`hashlib`, RFC 5869 extract-then-expand, salt `aether-ca-seed`, info
/// `aether-bootstrap-admin-key-v1`, 32 bytes, base64url unpadded, prefix `aeth_`). Calling
/// `ClusterSecretDerivation.bootstrapAdminKey(CLUSTER_SECRET)` here instead would compare the
/// production helper to itself — it would agree for every input, including a wrong one, and pin
/// nothing. Same failure shape as a regenerate-and-diff gate comparing generator output to generator
/// output.
///
/// **Why the wait condition is an AUTHENTICATED read, not a `200` from `/api/v1/health`.** This is the
/// trap this test fell into on its first run, and it is worth stating plainly: health returned `200`
/// **instantly, with no key at all**, because `KvStoreApiKeyValidator` succeeds with an empty context
/// while the KV store holds no keys — the no-credentials-anywhere path. The test never waited, and
/// `/api/v1/cluster/keys` on that same run returned `[]`: the key was not registered and the `200`
/// meant "authentication was never reached". The wait is therefore an authenticated
/// `GET /api/v1/cluster/keys`, which **cannot** succeed before the registration lands — a request
/// carrying a key goes to `checkKvStoreKey`, which finds no match in an empty store and refuses.
///
/// **Why the negative controls run afterwards, in the same run.** Once the key IS registered,
/// `hasConfiguredCredentials()` is true, so the same endpoint must refuse a request with no key and a
/// request bearing a well-formed key derived from a DIFFERENT secret. Running them at that moment —
/// rather than before, when the cluster is still in the no-credentials state — is what makes the
/// positive result mean authentication rather than absence of a gate.
///
/// Ports are probed free at run time, following `EmberClusterObservedNodeStateTest`: module test phases
/// run concurrently in CI, so a fixed port block is exposed to every other module (#939).
class EmberBootstrapAdminKeyAuthTest {
    private static final int CLUSTER_SIZE = 3;
    /// `EmberCluster.start` builds a slot pool of `2 * clusterSize`, so a block must cover twice the
    /// node count even though only [#CLUSTER_SIZE] slots are used here.
    private static final int SLOTS = 2 * CLUSTER_SIZE;
    private static final int MGMT_OFFSET = 40;
    private static final int APP_HTTP_OFFSET = 80;
    private static final int FIRST_CANDIDATE_BASE = 27700;
    private static final int LAST_CANDIDATE_BASE = 29500;
    private static final int CANDIDATE_STEP = 200;
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
    void derivedKey_isRegisteredByTheClusterAndAuthenticatesAgainstIt() {
        var mgmtPort = startClusterAndResolveLeaderPort();

        // THE PIN. Bounded, because the key becomes valid only after first leadership and the consensus
        // commit that registers its hash — the same wait the CLI's own poll loop absorbs. Reaching 200
        // here already proves the key authenticated; the body proves it is the registered, enumerable
        // credential rather than something the validator waved through.
        var keys = awaitRegisteredKey(mgmtPort);

        assertThat(keys)
            .describedAs("within %dms, GET /api/v1/cluster/keys authenticated with the key derived from "
                         + "this cluster's secret must succeed AND list the bootstrap key. Anything "
                         + "else means the secret did not reach BootstrapAdminKeyLeg, or the derived "
                         + "key was never registered in KV — the exact 401 that #980 exists to fix",
                         KEY_REGISTRATION_BUDGET_MS)
            .contains(BOOTSTRAP_KEY_ID);

        // The incident endpoint itself: phase 7's quorum poll is a GET of /api/v1/health, and it is
        // what returned `401 X-API-Key header required` from a healthy cluster.
        assertThat(send(leaderPort(mgmtPort), "/api/v1/health", DERIVED_KEY).status())
            .describedAs("the endpoint the bootstrap CLI polls must accept the derived key")
            .isEqualTo(200);

        // CONTROL 1 — authentication is REACHED, not bypassed. Same endpoint, same instant, no key.
        // Before registration this returns 200 from the no-credentials path, which is exactly why it
        // is asserted here and not earlier.
        assertThat(send(leaderPort(mgmtPort), "/api/v1/health", null).status())
            .describedAs("with a credential registered, an unauthenticated request must be REFUSED; a "
                         + "200 here would mean the assertions above proved nothing about "
                         + "authentication")
            .isNotEqualTo(200);

        // CONTROL 2 — the validator DISCRIMINATES rather than accepting any aeth_-shaped key.
        assertThat(send(leaderPort(mgmtPort), "/api/v1/health", FOREIGN_KEY).status())
            .describedAs("a well-formed key derived from a DIFFERENT cluster secret must be refused")
            .isNotEqualTo(200);
    }

    private int startClusterAndResolveLeaderPort() {
        var basePort = freeBasePort();

        cluster = emberCluster(CLUSTER_SIZE,
                               basePort,
                               basePort + MGMT_OFFSET,
                               basePort + APP_HTTP_OFFSET,
                               "adminkey");
        cluster.withClusterSecret(CLUSTER_SECRET.getBytes(StandardCharsets.UTF_8));
        // Reproduce the INCIDENT'S posture exactly: management security ON, and NOT ONE key configured
        // — which is every cluster `aether cluster init` creates, because it never writes one. Ember's
        // default `SecurityMode.NONE` disables `ManagementServer`'s security gate outright
        // (`securityEnabled` at its dispatch), so under the default every request answers 200 and this
        // test would measure the absence of a gate rather than the presence of a credential. That is
        // not hypothetical: it is what the first run of this test did, and control 1 below is what
        // caught it.
        cluster.withAppHttpSecurity(SecurityMode.API_KEY, Map.of());

        assertThat(cluster.start().await(START_BOUND).fold(Cause::message, _ -> "started"))
            .describedAs("a three-node cluster on a verified-free port block at %d must form within %s",
                         basePort,
                         START_BOUND)
            .isEqualTo("started");

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
        var builder = HttpRequest.newBuilder()
                                 .uri(URI.create("http://127.0.0.1:" + mgmtPort + path))
                                 .timeout(REQUEST_TIMEOUT)
                                 .GET();

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

    /// The first candidate base whose whole block — cluster ports (QUIC, so UDP as well as TCP),
    /// management ports and app-HTTP ports — binds free right now. Same helper and rationale as
    /// `EmberClusterObservedNodeStateTest`, on a disjoint candidate range so the two never contend.
    private static int freeBasePort() {
        for (int base = FIRST_CANDIDATE_BASE; base <= LAST_CANDIDATE_BASE; base += CANDIDATE_STEP) {
            if (blockIsFree(base)) {
                return base;
            }
        }
        throw new AssertionError("no free block of " + SLOTS + " consecutive ports found between "
                                 + FIRST_CANDIDATE_BASE + " and " + LAST_CANDIDATE_BASE
                                 + "; this box is too busy to run a cluster test");
    }

    private static boolean blockIsFree(int base) {
        for (int slot = 0; slot < SLOTS; slot++) {
            if (!udpFree(base + slot)
                || !tcpFree(base + slot)
                || !tcpFree(base + MGMT_OFFSET + slot)
                || !tcpFree(base + APP_HTTP_OFFSET + slot)) {
                return false;
            }
        }
        return true;
    }

    private static boolean tcpFree(int port) {
        try (var socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(loopback(port));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean udpFree(int port) {
        try (var socket = new DatagramSocket(null)) {
            socket.setReuseAddress(false);
            socket.bind(loopback(port));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static InetSocketAddress loopback(int port) {
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
    }
}
