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
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentEngine;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// #3274: a warning row the FSM2 leader COMMITTED reaches `GET /api/v1/warnings` on a NON-leader core, over HTTP, and leaves it when the
/// condition recovers. Every other pin of the route reads a stub or a store; this one runs the whole path: leader FSM, WARNINGS
/// transaction, replication, the follower's store, `WarningsRoutes`, the management server.
///
/// The family is `invalid-placement` (a committed target whose placement does not parse), chosen over `slice-start-awaiting-target`
/// because the latter is no family (no row is ever committed for it, `Warnings.FAMILIES`) and because this one needs no bounded wait: the
/// leader raises it at its next placement pass over the committed target, and a committed valid placement closes it (`placement-valid`).
///
/// The assertions are on the code AND the exact subject, so no other producer satisfies them: the entry is looked up by both.
@PortBudget
class EmberWarningsRouteFsm2Test {
    private static final int CORES = 3;
    private static final int MGMT_OFFSET = 40;
    private static final int APP_HTTP_OFFSET = 80;
    private static final EmberTestPorts.Block PORTS = new EmberTestPorts.Block(EmberTestPorts.POOL_FIRST,
                                                                               EmberTestPorts.POOL_LAST,
                                                                               EmberTestPorts.POOL_STEP,
                                                                               2 * CORES + 1,
                                                                               MGMT_OFFSET,
                                                                               APP_HTTP_OFFSET);
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(180).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(60).seconds();
    private static final TimeSpan COMMIT_BOUND = TimeSpan.timeSpan(30).seconds();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final long OPEN_BOUND_MS = 120_000L;
    private static final long CLOSE_BOUND_MS = 120_000L;
    private static final String CODE = "invalid-placement";
    private static final Artifact SLICE = Artifact.artifact("org.example:warnings-route-slice:1.0.0").unwrap();
    private static final Pattern REVISION = Pattern.compile("\"revision\"\\s*:\\s*(\\d+)");
    private static final Pattern ENTRY = Pattern.compile("\\{\\s*\"code\"\\s*:\\s*\"" + CODE + "\"\\s*,\\s*\"subject\"\\s*:\\s*\""
                                                         + Pattern.quote(SLICE.asString())
                                                         + "\"[^{}]*?\"members\"\\s*:\\s*\\{[^{}]*\\}\\s*,\\s*\"message\"\\s*:\\s*\"([^\"]*)\"");

    private EmberCluster cluster;

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            cluster.stop().await(STOP_BOUND);
        }
    }

    @Test
    @Timeout(600)
    void leaderCommittedWarning_isServedByANonLeaderOverHttp_andLeavesWhenTheConditionRecovers() {
        cluster = EmberTestPorts.startedCluster(PORTS, EmberWarningsRouteFsm2Test::fsm2Cluster, START_BOUND);
        awaitCondition(() -> "a leader", 90_000L, () -> cluster.currentLeader().isPresent());
        var leaderId = cluster.currentLeader().or("");
        var leader = cluster.getNode(leaderId).unwrap();
        var follower = cluster.status()
                              .nodes()
                              .stream()
                              .filter(node -> !node.id().equals(leaderId))
                              .findFirst()
                              .orElseThrow();
        var invalid = SliceTargetValue.sliceTargetValue(SLICE.version(), 1).withPlacement("NOT_A_PLACEMENT");

        awaitCondition(() -> "the follower serves /api/v1/warnings (200); last: " + warnings(follower.mgmtPort()),
                       OPEN_BOUND_MS,
                       () -> warnings(follower.mgmtPort()).startsWith("200 "));
        assertThat(warnings(follower.mgmtPort())).as("control: nothing of the family is open before the target is committed")
                                                 .doesNotContain(SLICE.asString());

        commitTarget(leader, Option.none(), invalid);

        awaitCondition(() -> "the open " + CODE + " for " + SLICE.asString() + " on " + follower.id() + "; last: " + warnings(follower.mgmtPort()),
                       OPEN_BOUND_MS,
                       () -> entryMessage(warnings(follower.mgmtPort())).isPresent());
        var opened = warnings(follower.mgmtPort());

        assertThat(follower.id()).as("the answering node is a non-leader core").isNotEqualTo(leaderId);
        assertThat(entryMessage(opened).or("")).as("the entry carries a message").isNotBlank();
        assertThat(revisionOf(opened)).as("the answer is labelled by a committed revision").isGreaterThan(0L);

        commitTarget(leader, Option.<AetherValue> some(invalid), invalid.withPlacement("CORE_ONLY"));

        awaitCondition(() -> "the " + CODE + " for " + SLICE.asString() + " gone on " + follower.id() + "; last: " + warnings(follower.mgmtPort()),
                       CLOSE_BOUND_MS,
                       () -> closed(warnings(follower.mgmtPort())));
        assertThat(revisionOf(warnings(follower.mgmtPort()))).as("the recovery is a later committed state")
                                                             .isGreaterThan(revisionOf(opened));
    }

    private static EmberCluster fsm2Cluster(int basePort) {
        var created = emberCluster(CORES, basePort, basePort + MGMT_OFFSET, basePort + APP_HTTP_OFFSET, "e3274");

        created.withDeploymentEngine(DeploymentEngine.FSM2);

        return created;
    }

    /// One leader-authorized transaction replacing the SLICE target `expected` with `replacement`; it must be accepted.
    private static void commitTarget(AetherNode leader, Option<AetherValue> expected, SliceTargetValue replacement) {
        var key = SliceTargetKey.sliceTargetKey(SLICE.base());
        var authority = leader.kvStore().getTyped(LeaderKey.INSTANCE, LeaderValue.class).unwrap();
        var id = UUID.randomUUID().toString();
        var transaction = new KVCommand.LeaderTransaction<AetherKey, AetherValue>(key,
                                                                                  id,
                                                                                  authority,
                                                                                  List.of(),
                                                                                  List.of(new KVCommand.Mutation<AetherKey, AetherValue>(key,
                                                                                                                                         expected,
                                                                                                                                         Option.some(replacement))));
        var results = leader.<Object> apply(List.of(transaction)).await(COMMIT_BOUND).unwrap();

        assertThat(results).as("the target transaction commits")
                           .anyMatch(result -> result instanceof KVCommand.TransactionResult accepted && accepted.transactionId().equals(id) && accepted.accepted());
    }

    private static Option<String> entryMessage(String response) {
        var matcher = ENTRY.matcher(response);

        return response.startsWith("200 ") && matcher.find()
               ? Option.some(matcher.group(1))
               : Option.none();
    }

    /// Closed means: answered 200 (a refusal is not an empty list) and no entry of the code for the subject.
    private static boolean closed(String response) {
        return response.startsWith("200 ") && entryMessage(response).isEmpty();
    }

    private static long revisionOf(String response) {
        var matcher = REVISION.matcher(response);

        return matcher.find()
               ? Long.parseLong(matcher.group(1))
               : -1L;
    }

    /// `status body`, so a caller can assert the status class with `startsWith`.
    @SuppressWarnings("JBCT-EX-01")
    private static String warnings(int mgmtPort) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://127.0.0.1:" + mgmtPort + "/api/v1/warnings"))
                                 .timeout(REQUEST_TIMEOUT)
                                 .GET()
                                 .build();

        try (var client = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build()) {
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());

            return response.statusCode() + " " + response.body();
        } catch (Exception e) {
            return "-1 " + e;
        }
    }

    private static void awaitCondition(Supplier<String> what, long budgetMs, BooleanSupplier condition) {
        var deadline = System.currentTimeMillis() + budgetMs;

        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleepQuietly(500L);
        }
        throw new AssertionError("timed out: " + what.get());
    }

    @SuppressWarnings("JBCT-EX-01")
    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
