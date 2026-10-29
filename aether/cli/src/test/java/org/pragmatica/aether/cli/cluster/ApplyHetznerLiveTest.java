// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.pragmatica.aether.config.cluster.CloudCredentialSchema;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfig;
import org.pragmatica.aether.config.cluster.DiffAction;
import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.InstanceId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.environment.SourceName.sourceNameOrDefault;

/// The PAID run of cluster-apply P3 (owner-approved cap EUR 2), driving the real wave code against REAL Hetzner servers:
/// apply, kill after the first created row, resume, audit, rollback, second rollback, then a label sweep.
///
/// It never runs in CI or an ordinary build: it needs `HCLOUD_TOKEN` in the environment (only `with-hcloud` provides it) AND
/// `-Daether.live.hetzner=true`. It creates at most [#SERVERS] servers of the smallest type, labelled
/// `aether-cluster=<throwaway>`, and its `finally` deletes by that label whatever is left. No aether cluster is needed: the
/// claims are provider-side (label stamping, create-response tags, listing right after a create, terminate by id, terminate of
/// an absent id, adopt, label sweep); the servers boot with a fake join input and never join.
///
/// Run (owner): `with-hcloud mvn -pl aether/cli test -Dtest=ApplyHetznerLiveTest -Daether.live.hetzner=true
/// [-Daether.live.hetzner.type=<server type>]`.
class ApplyHetznerLiveTest {
    static final int SERVERS = 3;
    /// 26 characters of the lowercase Crockford alphabet (no i, l, o, u); the first is 0-7 (a ULID timestamp fits 48 bits).
    static final String CROCKFORD_ULID = "^[0-7][0-9a-hjkmnp-tv-z]{25}$";
    private static final String TYPE = System.getProperty("aether.live.hetzner.type", "cx23");
    private static final String IMAGE = System.getProperty("aether.live.hetzner.image", "ubuntu-24.04");
    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();
    /// Dated and random: the sweep's safety must not depend on nobody else using a `p3live*` name.
    private static final String CLUSTER_NAME = "p3live" + java.time.LocalDate.now().toString().replace("-", "") + randomHex(5);
    /// The credentials reference an operator writes (`${env:HCLOUD_TOKEN}`), resolved by [ClusterApplyCommand#parseResolved], the reader
    /// `aether cluster apply --resume` uses (it resolves references exactly as `aether cluster bootstrap` does). The scalar `credentials` is the Hetzner `api_token` verbatim, so a literal here
    /// (it once said "hcloud-token") is sent to Hetzner as the token and answered with 401.
    private static final String CONFIG = configFor("HCLOUD_TOKEN");

    private static String configFor(String tokenEnvVar) {
        return """
            config_version = "1.0.0"
            [cluster]
            name = "%s"
            version = "1.0.0"
            [source.eu-1]
            type = "cloud"
            provider = "hetzner"
            region = "eu-central"
            credentials = "${env:%s}"
            zone = "fsn1"
            [source.eu-1.worker]
            count = %d
            instance_type = "%s"
            image = "%s"
            """.formatted(CLUSTER_NAME, tokenEnvVar, SERVERS, TYPE, IMAGE);
    }
    /// A fresh random secret per run: the servers boot with it, public and without a firewall, for the length of the run.
    private static final WaveNodeProvisioning.JoinInputs FAKE_JOIN = new WaveNodeProvisioning.JoinInputs(randomHex(16),
                                                                                                           List.of("aether-live-node-01k0peer000000000000000000:10.0.0.1:8090"));

    private static String randomHex(int bytes) {
        var raw = new byte[bytes];

        RANDOM.nextBytes(raw);

        return HexFormat.of().formatHex(raw);
    }

    @RegisterExtension
    final LedgerDirExtension ledger = new LedgerDirExtension();

    private final ClusterName cluster = ClusterName.clusterName(CLUSTER_NAME).unwrap();

    /// The config as `aether cluster apply --resume` reads it: through the command's own reader, not a resolver call of the test's
    /// own, so the paid run exercises the apply's credential path.
    private static ClusterBootstrapConfig resolved(String toml) {
        return ClusterApplyCommand.parseResolved(toml).unwrap();
    }

    /// Always runs: the api_token the create path is configured with is the ENVIRONMENT's value, never the reference text or a
    /// literal. Uses `PATH`, which every JVM has, through the same config template and resolver as the paid run uses with
    /// `HCLOUD_TOKEN`.
    @Test
    void liveConfig_resolvesTheTokenFromTheEnvironment_notALiteral() {
        var source = resolved(configFor("PATH")).sources().get("eu-1");
        var apiToken = CloudCredentialSchema.credentials(source, "hetzner").get("api_token");

        assertThat(apiToken).isEqualTo(System.getenv("PATH")).isNotEqualTo("${env:PATH}").isNotEqualTo("hcloud-token");
        assertThat(CONFIG).as("the paid run's config references the real variable").contains("credentials = \"${env:HCLOUD_TOKEN}\"");
    }

    /// Always runs: the apply id of the paid run is a real ULID, and so is the shared fixture. `ULID.parse` is lenient (it maps the
    /// look-alike letters), so the strict Crockford alphabet, which has no i, l, o or u, is checked on the text itself.
    @Test
    void applyIds_theMintedOneAndTheFixtureAreCanonicalUlids() {
        assertThat(ApplyState.newApplyId()).matches(CROCKFORD_ULID);
        assertThat(ApplyStateFixtures.APPLY_ID).as("the fixture id").matches(CROCKFORD_ULID);
        assertThat("0123456789abcdefghijklmnop").as("the old fixture contained i, l, o").doesNotMatch(CROCKFORD_ULID);
    }

    /// Always runs (no cloud): the config the paid run sends parses, and the spec it renders asks for exactly the server
    /// type, image and location intended, with the apply labels.
    @Test
    void liveConfig_parsesAndRendersTheSpecThatWillBeSent() {
        // PATH stands in for HCLOUD_TOKEN: the rendering needs the reference resolved, and every JVM has PATH.
        var desired = resolved(configFor("PATH"));
        var source = desired.sources().get("eu-1");
        var spec = WaveNodeProvisioning.cloudProvisionSpec(desired, source, NodeRole.WORKER, "aether-" + CLUSTER_NAME + "-node-x", FAKE_JOIN, "0123456789abcdefghjkmnpqrs").unwrap();

        assertThat(spec.instanceSize()).isEqualTo(TYPE);
        assertThat(spec.imageId().or("")).isEqualTo(IMAGE);
        assertThat(spec.placement().isPresent()).isTrue();
        assertThat(spec.context().extraTags()).containsEntry("aether-cluster", CLUSTER_NAME).containsEntry("aether-apply", "0123456789abcdefghjkmnpqrs");
        assertThat(SERVERS).as("the cost bound: three smallest servers").isLessThanOrEqualTo(3);
    }

    @Test
    @Timeout(value = 25, unit = java.util.concurrent.TimeUnit.MINUTES)
    void paidRun_applyKillResumeAuditRollback_onRealHetznerServers() {
        Assumptions.assumeTrue(System.getenv("HCLOUD_TOKEN") != null && !System.getenv("HCLOUD_TOKEN").isBlank(), "HCLOUD_TOKEN is not in the environment (run through with-hcloud)");
        Assumptions.assumeTrue(Boolean.getBoolean("aether.live.hetzner"), "-Daether.live.hetzner=true is not set");

        var desired = resolved(CONFIG);
        var source = desired.sources().get("eu-1");
        var action = ApplyAction.actionKey(new DiffAction.AddRole(sourceNameOrDefault("eu-1"), NodeRole.WORKER, SERVERS));
        // the create path exactly as WaveExecutor builds it, the lookup/audit/rollback provider through the function the apply uses
        var compute = ProviderResolver.resolveCloudCompute(source).unwrap();
        var cleanup = ApplyOrchestrator.productionComputes(cluster).apply("hetzner").unwrap();
        var report = new java.util.ArrayList<String>();

        // The create path must be configured with the environment's token, not a literal (a 401 would only show at the first create).
        assertThat(CloudCredentialSchema.credentials(source, "hetzner").get("api_token")).isEqualTo(System.getenv("HCLOUD_TOKEN")).isNotEqualTo("hcloud-token");

        var runApplyId = new java.util.concurrent.atomic.AtomicReference<>("");

        try {
            var minted = ApplyState.newApplyId();
            var recorder = ledger.recorder(cluster, List.of(action), minted);
            var applyId = recorder.applyId();

            assertThat(applyId).as("the run's apply id is a canonical ULID").matches(CROCKFORD_ULID);
            report.add("apply id " + applyId + " is a valid ULID");

            runApplyId.set(applyId);

            report.add("cluster=" + CLUSTER_NAME + " applyId=" + applyId + " type=" + TYPE + " image=" + IMAGE + " servers<=" + SERVERS);

            // 1. apply, killed right after the first CREATED row
            var harness = new ApplyChaosHarness();

            ApplyRecorder.stepHook = step -> harness.reach(ApplyChaosHarness.Step.valueOf(step.name()));
            harness.armKill(ApplyChaosHarness.Step.S3, 1);

            try {
                WaveExecutor.provisionCloudNodes(compute, desired, source, NodeRole.WORKER, SERVERS, FAKE_JOIN, recorder);
                fail("the kill seam did not fire");
            } catch (ApplyChaosHarness.Killed killed) {
                report.add("killed at " + killed.getMessage());
            }

            ApplyRecorder.stepHook = _ -> {};

            var afterKill = ledger.ledger(cluster);
            var labelled = listByApplyLabel(cleanup, applyId);

            report.add("after kill: ledger rows=" + afterKill.resources().stream().map(row -> row.state() + ":" + row.serverId().or("-")).toList()
                       + " provider servers under the apply label=" + labelled);
            assertThat(afterKill.resources()).as("one CREATED row, written after the provider answered").hasSize(1);
            assertThat(afterKill.resources().getFirst().state()).isEqualTo(ApplyResource.State.CREATED);
            assertThat(labelled).as("a listing right after the create finds the server by the apply label").containsExactly(afterKill.resources().getFirst().serverId().or(""));

            // 2. resume: lookup (nothing INTENDED), executor creates only the remainder, audit
            var resumed = ApplyRecorder.applyRecorder(afterKill);
            var computes = (java.util.function.Function<String, org.pragmatica.lang.Result<ComputeProvider>>) _ -> org.pragmatica.lang.Result.success(cleanup);

            ApplyReconciler.reconcileIntents(resumed, computes).onFailure(cause -> fail(cause.message()));
            resumed.beginAction(action).unwrap();
            WaveExecutor.provisionCloudNodes(compute, desired, source, NodeRole.WORKER, SERVERS - resumed.recordedFor(action), FAKE_JOIN, resumed).onFailure(cause -> fail(cause.message()));
            resumed.finishAction().unwrap();

            var audit = ApplyReconciler.audit(resumed, cluster, computes).unwrap();
            var afterResume = ledger.ledger(cluster);

            report.add("after resume: rows=" + afterResume.resources().size() + " provider servers=" + listByApplyLabel(cleanup, applyId).size() + " audit clean=" + audit.clean());
            assertThat(afterResume.resources()).hasSize(SERVERS);
            assertThat(listByApplyLabel(cleanup, applyId)).hasSize(SERVERS);
            assertThat(audit.clean()).isTrue();
            assertThat(afterResume.resources()).extracting(ApplyResource::nodeId).doesNotHaveDuplicates();

            // 3. rollback, then a second rollback
            var servers = afterResume.resources().stream().map(row -> row.serverId().or("")).toList();
            var out = new java.util.ArrayList<String>();

            ApplyOrchestrator.rollback(cluster, computes, _ -> org.pragmatica.lang.Result.unitResult(), org.pragmatica.lang.Option::none, out::add).onFailure(cause -> fail(cause.message()));
            report.addAll(out);

            var stillThere = listByApplyLabel(cleanup, applyId);

            report.add("after rollback: provider servers under the apply label=" + stillThere + " ledger present=" + ApplyState.read(cluster).unwrap().isPresent());
            assertThat(stillThere).isEmpty();
            assertThat(ApplyState.read(cluster).unwrap().isPresent()).isFalse();

            ApplyOrchestrator.rollback(cluster, computes, _ -> org.pragmatica.lang.Result.unitResult(), org.pragmatica.lang.Option::none, out::add)
                             .onSuccess(_ -> fail("a second rollback has nothing to do"))
                             .onFailure(cause -> report.add("second rollback: " + cause.message()));

            // 4. information for Q9: what does a terminate of an ABSENT id answer on Hetzner
            report.add("terminate of an already deleted server " + servers.getFirst() + " answers: "
                       + cleanup.terminate(new InstanceId(servers.getFirst())).await().fold(cause -> cause.getClass().getSimpleName() + " / " + cause.message(), _ -> "success"));
        } finally {
            ApplyRecorder.stepHook = _ -> {};

            var remaining = sweep(cleanup, CLUSTER_NAME, runApplyId.get(), report);

            System.out.println("=== P3 LIVE RUN REPORT ===\n" + String.join("\n", report) + "\n=== END ===");
            assertThat(remaining).as("post-run inventory by label must be empty").isEmpty();
        }
    }

    private static List<String> listByApplyLabel(ComputeProvider provider, String applyId) {
        return provider.listInstances(Map.of("aether-apply", applyId)).await().unwrap().stream().map(info -> info.id().value()).toList();
    }

    /// Whatever the run left is deleted, but only a server that BOTH carries `aether-cluster=<throwaway>` AND `aether-apply`
    /// (this run's apply id; any non-blank one when the run died before it had one) as the provider REPORTS it: a selector the
    /// provider ignored, or a server of another run, is never deleted. A thread interrupted by the test timeout would make
    /// every `await()` below fail before the first delete, so the flag is cleared first. Returns what is still listed under
    /// the cluster label afterwards.
    static List<String> sweep(ComputeProvider provider, String clusterName, String applyId, List<String> report) {
        var interrupted = Thread.interrupted();

        if (interrupted) {
            report.add("SWEEP: the thread was interrupted (test timeout); flag cleared so the sweep can run");
        }

        provider.listInstances(Map.of("aether-cluster", clusterName))
                .await()
                .onFailure(cause -> report.add("SWEEP LISTING FAILED: " + cause.message()))
                .onSuccess(listed -> listed.forEach(info -> sweepOne(provider, info, clusterName, applyId, report)));

        return provider.listInstances(Map.of("aether-cluster", clusterName))
                       .await()
                       .fold(cause -> List.of("LISTING FAILED: " + cause.message()),
                             listed -> listed.stream().map(info -> info.id().value()).toList());
    }

    private static void sweepOne(ComputeProvider provider, org.pragmatica.aether.environment.InstanceInfo info, String clusterName, String applyId, List<String> report) {
        var id = info.id().value();
        var cluster = info.tags().get("aether-cluster");
        var apply = info.tags().get("aether-apply");
        var ours = clusterName.equals(cluster) && apply != null && !apply.isBlank() && (applyId.isEmpty() || applyId.equals(apply));

        report.add(ours
                   ? "SWEEP terminating leftover " + id + ": " + provider.terminate(new InstanceId(id)).await().fold(cause -> "FAILED " + cause.message(), _ -> "ok")
                   : "SWEEP REFUSED to delete " + id + " (cluster=" + cluster + ", apply=" + apply + "): not both labels of this run");
    }
}
