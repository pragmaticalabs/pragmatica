// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfig;
import org.pragmatica.aether.config.cluster.DiffPlan;
import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;

/// The commit recovery compares the ledger's config hash with the cluster's. The ledger hashes the RESOLVED config; the cluster
/// stores the TOML as the operator sent it, `${env:...}` references unresolved, so the cluster's side is resolved before it is
/// hashed. Otherwise a landed commit whose config holds a reference reads as "the cluster moved without this apply's config in it":
/// ABORTED, rollback only, and a rollback would delete the nodes the committed config holds.
class ApplyCommitViewTest {
    private static final ClusterName CLUSTER = ClusterName.clusterName("reader-test").unwrap();
    private static final String REF = config("${env:PATH}");
    private static final String PLAIN = config("literal-token-value");
    private static final String UNSET = config("${env:AETHER_TEST_NO_SUCH_VARIABLE_7f3a}");
    private static final DiffPlan EMPTY = DiffPlan.diffPlan(List.of(), List.of(), List.of(), List.of());

    private static String config(String credentials) {
        return """
               config_version = "1.0.0"
               [cluster]
               name = "reader-test"
               version = "1.0.0"
               [source.eu-1]
               type = "cloud"
               provider = "hetzner"
               region = "eu-central"
               credentials = "%s"
               zone = "fsn1"
               [source.eu-1.worker]
               count = 1
               """.formatted(credentials);
    }

    @RegisterExtension
    final LedgerDirExtension ledger = new LedgerDirExtension();

    private HttpServer cluster;
    private String originalEndpoint;
    private String storedToml;

    @BeforeEach
    @SuppressWarnings("JBCT-EX-01")
    void fakeCluster() throws Exception {
        cluster = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        cluster.createContext("/", exchange -> {
            var body = ("{\"tomlContent\": \"" + storedToml.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\", \"configVersion\": 1}").getBytes(StandardCharsets.UTF_8);

            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        cluster.start();
        originalEndpoint = ClusterHttpClient.ENDPOINT_OVERRIDE.get();
        ClusterHttpClient.ENDPOINT_OVERRIDE.set("http://127.0.0.1:" + cluster.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        ClusterHttpClient.ENDPOINT_OVERRIDE.set(originalEndpoint);
        cluster.stop(0);
    }

    private static ClusterBootstrapConfig resolved(String toml) {
        return ClusterApplyCommand.parseResolved(toml).unwrap();
    }

    /// An apply started the normal way (its ledger carries the resolved config's hash), moved to COMMITTING, with the cluster
    /// holding `stored` at a later version (the commit landed), then resumed with the real `clusterView()`.
    private Result<ApplyResult> committingThenResume(String stored, ClusterBootstrapConfig started) {
        ApplyOrchestrator.executeAndPersist(EMPTY, started, started, (plan, from, to, recorder) -> Causes.cause("halt").result());

        var state = ApplyState.read(CLUSTER).unwrap().unwrap();

        ApplyState.save(state.withPhase(ApplyPhase.COMMITTING)).unwrap();
        storedToml = stored;

        return ApplyOrchestrator.resume(started, started, _ -> Causes.cause("no provider").result(),
                                        (plan, from, to, recorder) -> Result.success(ApplyResult.applyResult(plan, 0, 0, 0)),
                                        ApplyOrchestrator::clusterView);
    }

    /// v-2447's P5: red before the fix (the cluster's raw TOML hashed differently from the ledger's resolved config).
    @Test
    void aLandedCommitWhoseConfigHoldsAReference_isRecognisedAsLanded() {
        var result = committingThenResume(REF, resolved(REF));

        assertThat(result.isSuccess()).as("a landed commit with ${env:} must be recognised as committed; got " + result).isTrue();
        assertThat(ApplyState.read(CLUSTER).unwrap().isPresent()).as("finalised: the ledger is closed, not ABORTED").isFalse();
    }

    /// v-2447's P6 control: the same flow with no reference.
    @Test
    void control_aLandedCommitWithoutAReference_isRecognisedAsLanded() {
        assertThat(committingThenResume(PLAIN, resolved(PLAIN)).isSuccess()).isTrue();
    }

    @Test
    void aStoredReferenceThatDoesNotResolve_refusesTyped_andIsNotReadAsNotLanded() {
        var result = committingThenResume(UNSET, resolved(REF));

        result.onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("cannot compare: must refuse"))
              .onFailure(cause -> assertThat(cause.message()).contains("AETHER_TEST_NO_SUCH_VARIABLE_7f3a").contains("cannot determine the commit state").contains("Nothing was changed"));
        assertThat(ApplyState.read(CLUSTER).unwrap().unwrap().phase()).as("not moved to ABORTED").isEqualTo(ApplyPhase.COMMITTING);
    }

    @Test
    void aClusterThatCannotBeReached_stillMeansRetry_notAnError() {
        ApplyOrchestrator.executeAndPersist(EMPTY, resolved(REF), resolved(REF), (plan, from, to, recorder) -> Causes.cause("halt").result());
        ApplyState.save(ApplyState.read(CLUSTER).unwrap().unwrap().withPhase(ApplyPhase.COMMITTING)).unwrap();
        cluster.stop(0);

        ApplyOrchestrator.resume(resolved(REF), resolved(REF), _ -> Causes.cause("no provider").result(),
                                 (plan, from, to, recorder) -> Result.success(ApplyResult.applyResult(plan, 0, 0, 0)), ApplyOrchestrator::clusterView)
                         .onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("cannot decide"))
                         .onFailure(cause -> assertThat(cause.message()).contains("could not be read").contains("retry"));
        assertThat(ApplyState.read(CLUSTER).unwrap().unwrap().phase()).isEqualTo(ApplyPhase.COMMITTING);
    }
}
