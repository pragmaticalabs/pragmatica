// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.forge;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.awaitility.core.ConditionTimeoutException;
import org.pragmatica.aether.stream.StreamReadRouter.ReplicaSetView;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.HttpResult;
import org.pragmatica.lang.Option;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;
import static org.pragmatica.http.JdkHttpOperations.jdkHttpOperations;

import org.pragmatica.aether.ember.EmberCluster;

/// #457 — in-JVM Ember proof of RF=2 stream owner-kill failover: the ONLY prior coverage of the
/// #445-arc owner-failover path was the cloud suite `02-chaos/test-stream-replica-failover.sh`, so
/// every regression check cost a paid Hetzner run. This reproduces its assertion set on the fast
/// single-JVM Forge loop.
///
/// This is the shared base for two concrete variants (#491): the DEFAULT membership variant
/// ([StreamOwnerFailoverTest]) observes phase-9 RF-restoration as a non-failing sensor for the two
/// residuals that still block RF-rebuild — #498 (SWIM false-removal-under-churn) and #499 (HRW-divergence
/// empty-owner catch-up stall) — while the membership-PINNED variant ([StreamOwnerFailoverPinnedTest],
/// @Disabled until #499) disables auto-heal on every node and raises the SWIM/hello/split timeouts to
/// suppress #498, isolating #499 as the HARD acceptance gate for when it closes. Both share phases 1–8
/// (all HARD) verbatim; they differ only in the [#pinMembership], [#configureCluster] and
/// [#assertsConvergence] hooks.
///
/// Topology: the `test-stream-repl` blueprint (`streams.repl-failover-events`, partitions=1,
/// replicas=2, min-sync-replicas=2). RF=2 + synchronous replication is the only topology with a
/// promotable CAUGHT_UP non-owner replica — `POST /api/streams` mints RF=1 (owner-only), which
/// structurally cannot fail over. Each publish AWAITs a replica ack (min-sync-2), so a successful
/// pre-kill batch is itself proof the replica is in sync.
///
/// The flow mirrors the cloud script phase-for-phase: deploy the RF=2 blueprint → gate on the replica
/// set being PLACED (owner + >=1 non-owner) → publish N and confirm the full history is readable →
/// identify the HRW owner + a CAUGHT_UP non-owner replica → KILL the owner
/// ([EmberCluster#killNode]) → the partition re-resolves to a new owner that serves ALL N pre-kill
/// events (complete history, no truncation) → post-repair live batches land in order → the replica set
/// re-converges with RF restored (a CAUGHT_UP non-owner, no CAUGHT_UP replica lagging the owner tail).
///
/// In-JVM equivalences vs the cloud script (called out in the #457 report): (1) the owner kill is a
/// GRACEFUL [EmberCluster#killNode] (a clean `node.stop()` SWIM leave), not a `docker kill` silent
/// death — this exercises the failover MECHANISM deterministically rather than SWIM detection latency
/// (a separate #94-class concern). (2) Publish/read go through the slice's app-HTTP routes (the real
/// consumer path). (3) The replica-set view is read from the SAME `StreamReadRouter.replicaSnapshot`
/// the management `/api/streams/replicas` sensor serves, but IN-JVM off the owner node directly —
/// the HTTP sensor is `taskGroup(STREAMING)`-routed to a single delegate (the #490 observability
/// gap: operators cannot obtain a per-node owner view over HTTP), so it never yields the
/// owner-authoritative (`servedByOwner`) view when queried per node; the in-JVM snapshot on the node
/// whose `servedByOwner()` is true is the authoritative one.
abstract class AbstractStreamOwnerFailover {
    private static final System.Logger LOG = System.getLogger(AbstractStreamOwnerFailover.class.getName());
    private static final int NODES = 5;
    private static final int INSTANCES = 5;
    private static final int N_EVENTS = 20;
    private static final int K_EVENTS = 5;
    private static final int PARTITION = 0;

    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(240);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);
    private static final Duration PLACEMENT_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration FAILOVER_TIMEOUT = Duration.ofSeconds(180);
    private static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(90);
    private static final Duration CONVERGE_TIMEOUT = Duration.ofSeconds(120);
    private static final long POLL_GAP_NANOS = Duration.ofMillis(20).toNanos();

    private static final String STREAM_SLICE = TestArtifacts.STREAM_REPL_SLICE;
    private static final String STREAM_NAME = "repl-failover-events";
    private static final String BLUEPRINT_ID = "forge.test:stream-owner-failover:1.0.0";
    private static final String ERROR_FALLBACK = "{\"error\":\"request failed\"}";

    private static final Pattern EVENT_OBJECT = Pattern.compile("\\{[^{}]*\"offset\"[^{}]*}");
    private static final Pattern OFFSET_FIELD = Pattern.compile("\"offset\"\\s*:\\s*(\\d+)");
    private static final Pattern PAYLOAD_FIELD = Pattern.compile("\"payload\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern NODE_COUNT_FIELD = Pattern.compile("\"nodeCount\"\\s*:\\s*(\\d+)");

    private EmberCluster cluster;
    private final HttpOperations http = jdkHttpOperations();

    private record Event(long offset, String payload) {}

    // --- variant hooks ------------------------------------------------------

    /// Base app-port allocation for this variant's cluster. Overridden by the pinned variant so the two
    /// concrete classes never contend for the same TCP ports when the suite runs both.
    int basePort() {
        return 14000;
    }

    int baseMgmtPort() {
        return 14100;
    }

    int baseAppHttpPort() {
        return 14200;
    }

    String nodePrefix() {
        return "sof";
    }

    /// Applied once, after cluster construction and BEFORE [#startAndAwaitReady] calls `start()`. Default
    /// is a no-op; the pinned variant raises the SWIM / transport / membership timeouts here (via
    /// [EmberCluster#withRaisedSwimTimeouts]) so the transient QuorumLost→PASSIVE false-removal cascade
    /// does not fire during the single graceful owner-kill. Must run before `start()` to take effect.
    void configureCluster(EmberCluster cluster) {}

    /// Applied once, after the cluster is fully member-complete and before the slice is deployed. Default
    /// (unpinned) is a no-op — the DEFAULT membership variant leaves auto-heal on so phase 9 remains a
    /// live SWIM sensor. The pinned variant disables auto-heal on every node's CTM here.
    void pinMembership(EmberCluster cluster) {}

    /// True → phase 9 asserts RF-restoration convergence HARD (pinned variant). False → phase 9 is a
    /// non-failing SWIM sensor (default variant).
    abstract boolean assertsConvergence();

    @BeforeAll
    void setUp() {
        var configProvider = ConfigurationProvider.builder()
                                                  .withSystemProperties("aether.")
                                                  .withEnvironment("AETHER_")
                                                  .build();
        cluster = emberCluster(NODES, basePort(), baseMgmtPort(), baseAppHttpPort(), nodePrefix(), Option.some(configProvider));
        configureCluster(cluster);
        startAndAwaitReady();
    }

    @AfterAll
    void tearDown() {
        if (cluster != null) {
            var leaderPort = cluster.getLeaderManagementPort().or(anyMgmtPort());
            httpDelete(leaderPort, "/api/blueprints/" + BLUEPRINT_ID);
            cluster.stop().await();
        }
    }

    /// THE #457 gate: publish N acked events into an RF=2 stream, kill the partition's HRW owner, and
    /// prove the promoted replica serves the COMPLETE history, accepts live batches in order, and the
    /// replica set re-converges with RF restored.
    @Test
    void ownerKill_promotedReplicaServesCompleteHistory_andRestoresRf() {
        var port = appPort();

        assertThat(head(port))
            .describedAs("fresh RF=2 stream starts empty (offset 0)")
            .isZero();

        // The RF=2 config must be COMMITTED and the replica set PLACED (owner + >=1 non-owner) before the
        // first publish, else the min-sync-2 barrier has no replica to await and pre-kill markers race
        // placement. First proof RF=2 replication is in effect in-JVM.
        await().atMost(PLACEMENT_TIMEOUT).pollInterval(POLL_INTERVAL).until(this::replicaSetPlaced);

        var published = drainAfterPublish(port, "pre", 0, N_EVENTS, DRAIN_TIMEOUT);
        assertContiguousBatch(published, 0L, "pre", N_EVENTS);

        // Owner + a promotable CAUGHT_UP non-owner replica must both exist (else killing the owner cannot
        // preserve history). The min-sync-2 publishes above already proved a replica is in sync; wait for
        // its registry state to settle to CAUGHT_UP.
        await().atMost(CONVERGE_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> ownerView().map(this::hasCaughtUpNonOwner).or(false));
        var owner = ownerView().flatMap(ReplicaSetView::ownerNodeId).or("");
        assertThat(owner).describedAs("HRW owner identified before kill").isNotBlank();

        // Kill the HRW owner (graceful SWIM leave -> deterministic membership shrink -> HRW re-resolves).
        cluster.killNode(owner).await();

        await().atMost(FAILOVER_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> ownerChangedAndAuthoritative(owner));
        var newOwner = ownerView().flatMap(ReplicaSetView::ownerNodeId).or("");
        assertThat(newOwner)
            .describedAs("partition re-resolved to a NEW owner after the kill")
            .isNotBlank().isNotEqualTo(owner);

        // THE assertion the non-destructive suites lack: the promoted owner serves the COMPLETE pre-kill
        // history (count == N, contiguous), not merely >=1.
        var recovered = drain(appPort(), 0L, N_EVENTS, deadline(FAILOVER_TIMEOUT));
        assertContiguousBatch(recovered, 0L, "pre", N_EVENTS);

        // Post-repair liveness: the new owner accepts further live batches and serves the full ordered
        // tail 0..N+K-1 contiguously (no reordering, no offset gap after the pre-kill history).
        var live = drainAfterPublish(appPort(), "post", N_EVENTS, K_EVENTS, DRAIN_TIMEOUT);
        assertContiguousBatch(live, N_EVENTS, "post", K_EVENTS);
        var fullTail = drain(appPort(), 0L, N_EVENTS + K_EVENTS, deadline(DRAIN_TIMEOUT));
        assertThat(fullTail)
            .describedAs("full ordered tail of %d+%d events present on new owner", N_EVENTS, K_EVENTS)
            .hasSize(N_EVENTS + K_EVENTS);
        assertInOrder(fullTail);

        // Phase 9 — RF restoration. The membership-pinned variant asserts this HARD (the #457 acceptance
        // gate, @Disabled until #499); the default variant observes it as a non-failing sensor (post-kill
        // RF-restoration can stall on #498 SWIM false-removal and #499 HRW-divergence). Phases 1–8 above
        // are HARD in both variants.
        if (assertsConvergence()) {
            assertRfRestorationConverges();
        } else {
            observeRfRestoration();
        }
    }

    /// Phase 9 (pinned) — RF restoration HARD assertion (the #457 acceptance gate), ACTIVE on
    /// [StreamOwnerFailoverPinnedTest] since #499 closed (2026-07-23, converged 3×). Pinning membership
    /// (auto-heal off + raised SWIM/hello/split timeouts) suppresses #498 — the gate confirmed
    /// `swimDeadStuck` stays empty through the failover. The long-standing phase-9 stall was a
    /// FIRE-ONCE backfill-completion ack lost during the replacement's member-view bootstrap window
    /// (masked in the console by a killed-node zombie scheduler, #501) — see the pinned test's class doc
    /// for the full mechanism. On timeout this dumps every live node's replica view before rethrowing.
    private void assertRfRestorationConverges() {
        try {
            await().atMost(CONVERGE_TIMEOUT)
                   .pollInterval(POLL_INTERVAL)
                   .until(this::convergedWithRfRestored);
        } catch (ConditionTimeoutException timeout) {
            // Failure-path observability: the assertion otherwise dies blind — dump EVERY live
            // node's replica snapshot so the non-converged view is diagnosable from the log.
            dumpAllReplicaViews();
            throw timeout;
        }

        ownerView().onPresent(view -> LOG.log(System.Logger.Level.INFO,
                                              "#491 RF-restoration converged: "
                                              + "ownerCaughtUp={0} caughtUpNonOwner={1} finalView={2}",
                                              ownerIsCaughtUp(view),
                                              hasCaughtUpNonOwner(view),
                                              view));
    }

    /// Per-node replica-view dump on phase-9 failure: one WARNING line per LIVE node (killed nodes are
    /// removed from [EmberCluster#allNodes]), each tagged with the reporting node's own id so the
    /// failing view is attributable in the shared forge console.
    private void dumpAllReplicaViews() {
        for (var node : cluster.allNodes()) {
            LOG.log(System.Logger.Level.WARNING,
                    "phase-9 FAIL view self={0}: {1}",
                    node.self(),
                    node.streamReadRouter().replicaSnapshot(STREAM_NAME, PARTITION));
        }
    }

    /// Phase 9 (default) — non-failing sensor for #498 (SWIM false-removal) + #499 (HRW-divergence):
    /// post-kill RF-restoration can stall on either; convergence here is observed, not asserted. The
    /// membership-pinned variant ([StreamOwnerFailoverPinnedTest], @Disabled until #499) is the HARD gate.
    /// Bounded poll up to [#CONVERGE_TIMEOUT], then a single log of the outcome + final owner-authoritative
    /// view (WARNING if it did not converge, INFO if it did) — never a test failure.
    private void observeRfRestoration() {
        var deadline = deadline(CONVERGE_TIMEOUT);
        var converged = convergedWithRfRestored();

        while (!converged && System.nanoTime() < deadline) {
            LockSupport.parkNanos(POLL_GAP_NANOS);
            converged = convergedWithRfRestored();
        }

        var finalView = ownerView().map(ReplicaSetView::toString).or("<no owner-authoritative view>");

        LOG.log(converged ? System.Logger.Level.INFO : System.Logger.Level.WARNING,
                "#491 phase-9 sensor (#498 SWIM + #499 HRW-divergence): RF-restoration converged={0} finalView={1}",
                converged,
                finalView);
    }

    // --- replica-set view (in-JVM, owner-authoritative) ---------------------

    /// The owner-authoritative replica-set view: the registry is authoritative only on the partition's
    /// HRW owner (`servedByOwner()` true), so scan every live node's in-JVM `replicaSnapshot` and return
    /// the owner's. The HTTP `/api/streams/replicas` sensor is `taskGroup(STREAMING)`-routed to a single
    /// delegate, so it cannot yield the owner view per-node — the in-JVM snapshot can (#490).
    private Option<ReplicaSetView> ownerView() {
        for (var node : cluster.allNodes()) {
            var view = node.streamReadRouter().replicaSnapshot(STREAM_NAME, PARTITION);

            if (view.servedByOwner()) {
                return Option.some(view);
            }
        }

        return Option.none();
    }

    private boolean replicaSetPlaced() {
        return ownerView().map(view -> view.replicas().size() >= 2
                                       && view.replicas().stream().anyMatch(r -> !r.hrwOwner()))
                          .or(false);
    }

    private boolean ownerChangedAndAuthoritative(String oldOwner) {
        return ownerView().flatMap(ReplicaSetView::ownerNodeId)
                          .map(owner -> !owner.isBlank() && !owner.equals(oldOwner))
                          .or(false);
    }

    private boolean convergedWithRfRestored() {
        return ownerView().map(view -> noCaughtUpReplicaLagsTail(view) && hasCaughtUpNonOwner(view)).or(false);
    }

    private boolean hasCaughtUpNonOwner(ReplicaSetView view) {
        return view.replicas().stream().anyMatch(r -> "CAUGHT_UP".equals(r.state()) && !r.hrwOwner());
    }

    private boolean ownerIsCaughtUp(ReplicaSetView view) {
        return view.replicas().stream().anyMatch(r -> r.hrwOwner() && "CAUGHT_UP".equals(r.state()));
    }

    /// True iff no CAUGHT_UP replica's confirmedOffset lags the owner's true tail (ownerHeadOffset-1) —
    /// the #333 write-idle-residual sensor. ownerHeadOffset is head+1, so the last durable offset is
    /// ownerHeadOffset-1; a CAUGHT_UP replica must have confirmed it.
    private boolean noCaughtUpReplicaLagsTail(ReplicaSetView view) {
        if (view.ownerHeadOffset() < 1) {
            return false;
        }

        var tail = view.ownerHeadOffset() - 1;

        return view.replicas().stream()
                   .filter(r -> "CAUGHT_UP".equals(r.state()))
                   .allMatch(r -> r.confirmedOffset() >= tail);
    }

    // --- assertions ---------------------------------------------------------

    private void assertContiguousBatch(List<Event> events, long base, String tag, int count) {
        assertThat(events)
            .describedAs("consumer must receive exactly %d '%s' events (no dups, no gaps)", count, tag)
            .hasSize(count);

        for (int i = 0; i < count; i++) {
            assertThat(events.get(i).offset())
                .describedAs("event %d offset (contiguous from base %d)", i, base)
                .isEqualTo(base + i);
            assertThat(events.get(i).payload())
                .describedAs("event %d payload (in publish order)", i)
                .isEqualTo(tag + "-" + i);
        }
    }

    /// Offsets 0..size-1 are contiguous on the promoted owner (log correctly sequenced, no reordering).
    private void assertInOrder(List<Event> events) {
        for (int i = 0; i < events.size(); i++) {
            assertThat(events.get(i).offset())
                .describedAs("offset at index %d is contiguous on the new owner", i)
                .isEqualTo((long) i);
        }
    }

    // --- consumers ----------------------------------------------------------

    private List<Event> drainAfterPublish(int port, String tag, int base, int count, Duration budget) {
        publishBatch(port, tag, count);

        return drain(port, base, count, deadline(budget));
    }

    private List<Event> drain(int port, long base, int count, long deadlineNanos) {
        var collected = new ArrayList<Event>();
        var offset = base;

        while (collected.size() < count && System.nanoTime() < deadlineNanos) {
            var events = readEvents(port, offset, count);

            if (events.isEmpty()) {
                LockSupport.parkNanos(POLL_GAP_NANOS);
                continue;
            }

            collected.addAll(events);
            offset = events.getLast().offset() + 1;
        }

        return List.copyOf(collected);
    }

    private List<Event> drainAll(int port) {
        var all = new ArrayList<Event>();
        var offset = 0L;

        while (true) {
            var events = readEvents(port, offset, 200);

            if (events.isEmpty()) {
                return List.copyOf(all);
            }

            all.addAll(events);
            offset = events.getLast().offset() + 1;
        }
    }

    private long head(int port) {
        return drainAll(port).size();
    }

    // --- publishing ---------------------------------------------------------

    private void publishBatch(int port, String tag, int count) {
        for (int i = 0; i < count; i++) {
            publish(port, tag + "-" + i);
        }
    }

    private void publish(int port, String payload) {
        var body = "{\"payload\":\"" + payload + "\"}";
        var response = httpPost(port, "/api/stream-repl/publish", body);

        assertThat(response)
            .describedAs("publish '%s' must succeed (min-sync-2: resolves only after a replica ack)", payload)
            .doesNotContain("\"error\"")
            .contains("published");
    }

    private List<Event> readEvents(int port, long fromOffset, int maxEvents) {
        var body = "{\"fromOffset\":" + fromOffset + ",\"maxEvents\":" + maxEvents + "}";

        return parseEvents(httpPost(port, "/api/stream-repl/read", body));
    }

    private static List<Event> parseEvents(String body) {
        var events = new ArrayList<Event>();
        Matcher objects = EVENT_OBJECT.matcher(body);

        while (objects.find()) {
            var object = objects.group();
            Matcher offset = OFFSET_FIELD.matcher(object);
            Matcher payload = PAYLOAD_FIELD.matcher(object);

            if (offset.find() && payload.find()) {
                events.add(new Event(Long.parseLong(offset.group(1)), payload.group(1)));
            }
        }

        return List.copyOf(events);
    }

    // --- deployment + readiness --------------------------------------------

    private void startAndAwaitReady() {
        cluster.start()
               .await()
               .onFailure(cause -> {
                   throw new AssertionError("Cluster start failed: " + cause.message());
               });

        await().atMost(WAIT_TIMEOUT).pollInterval(POLL_INTERVAL).until(() -> cluster.currentLeader().isPresent());
        await().atMost(WAIT_TIMEOUT).pollInterval(POLL_INTERVAL).until(this::allNodesHealthy);
        await().atMost(WAIT_TIMEOUT).pollInterval(POLL_INTERVAL).until(() -> allNodesAreMembers(NODES));

        pinMembership(cluster);

        deployStreamSlice();

        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .failFast(this::failIfSliceFailed)
               .until(this::appHttpReady);
    }

    private void deployStreamSlice() {
        var blueprint = """
            id = "%s"

            [[slices]]
            artifact = "%s"
            instances = %d
            """.formatted(BLUEPRINT_ID, STREAM_SLICE, INSTANCES);
        var leaderPort = cluster.getLeaderManagementPort().or(anyMgmtPort());
        var response = postBlueprintWithRetry(leaderPort, blueprint);

        assertThat(response)
            .describedAs("RF=2 stream-slice deployment")
            .doesNotContain("\"error\"")
            .contains("\"status\":\"applied\"");
    }

    private boolean appHttpReady() {
        var ports = cluster.getAvailableAppHttpPorts();

        if (ports.isEmpty()) {
            return false;
        }

        var body = httpPost(ports.getFirst(), "/api/stream-repl/read", "{\"fromOffset\":0,\"maxEvents\":1}");

        return !body.contains("\"error\"") && body.contains("events");
    }

    private void failIfSliceFailed() {
        var failed = cluster.slicesStatus()
                            .stream()
                            .anyMatch(s -> s.artifact().equals(STREAM_SLICE) && s.state().equals("FAILED"));

        if (failed) {
            throw new AssertionError("RF=2 stream slice deployment FAILED: " + STREAM_SLICE);
        }
    }

    private int appPort() {
        return cluster.getAvailableAppHttpPorts()
                      .stream()
                      .findFirst()
                      .orElseThrow(() -> new AssertionError("No app-http route is ready"));
    }

    private int anyMgmtPort() {
        return cluster.status().nodes().getFirst().mgmtPort();
    }

    private long deadline(Duration budget) {
        return System.nanoTime() + budget.toNanos();
    }

    private boolean allNodesHealthy() {
        return cluster.status()
                      .nodes()
                      .stream()
                      .allMatch(node -> checkNodeHealth(node.mgmtPort()));
    }

    private boolean checkNodeHealth(int port) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + "/api/health"))
                                 .GET()
                                 .timeout(Duration.ofSeconds(5))
                                 .build();
        return http.sendString(request)
                   .await()
                   .map(r -> r.statusCode() == 200 && r.body().contains("\"quorum\":true"))
                   .or(false);
    }

    private boolean allNodesAreMembers(int expected) {
        var leaderPort = cluster.getLeaderManagementPort().or(anyMgmtPort());
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + leaderPort + "/api/health"))
                                 .GET()
                                 .timeout(Duration.ofSeconds(5))
                                 .build();
        return http.sendString(request)
                   .await()
                   .map(r -> r.statusCode() == 200 && healthHasFullMembership(r.body(), expected))
                   .or(false);
    }

    private static boolean healthHasFullMembership(String body, int expected) {
        if (!body.contains("\"quorum\":true")) {
            return false;
        }

        var matcher = NODE_COUNT_FIELD.matcher(body);

        return matcher.find() && Integer.parseInt(matcher.group(1)) >= expected;
    }

    // --- HTTP ---------------------------------------------------------------

    private String postBlueprintWithRetry(int port, String body) {
        var lastResponse = ERROR_FALLBACK;

        for (int attempt = 1; attempt <= 3; attempt++) {
            lastResponse = httpPostToml(port, "/api/blueprints", body);

            if (!lastResponse.contains("\"error\"")) {
                return lastResponse;
            }

            if (attempt < 3) {
                LockSupport.parkNanos(Duration.ofSeconds(2).toNanos());
            }
        }

        return lastResponse;
    }

    private String httpPostToml(int port, String path, String body) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + path))
                                 .header("Content-Type", "application/toml")
                                 .POST(HttpRequest.BodyPublishers.ofString(body))
                                 .timeout(Duration.ofSeconds(10))
                                 .build();
        return http.sendString(request)
                   .await()
                   .map(HttpResult::body)
                   .or(ERROR_FALLBACK);
    }

    private String httpPost(int port, String path, String body) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + path))
                                 .header("Content-Type", "application/json")
                                 .POST(HttpRequest.BodyPublishers.ofString(body))
                                 .timeout(Duration.ofSeconds(15))
                                 .build();
        return http.sendString(request)
                   .await()
                   .map(HttpResult::body)
                   .or(ERROR_FALLBACK);
    }

    private String httpDelete(int port, String path) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + path))
                                 .DELETE()
                                 .timeout(Duration.ofSeconds(10))
                                 .build();
        return http.sendString(request)
                   .await()
                   .map(HttpResult::body)
                   .or(ERROR_FALLBACK);
    }
}
