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
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfig;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigParser;
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
    private static final String TYPE = System.getProperty("aether.live.hetzner.type", "cx23");
    private static final String IMAGE = System.getProperty("aether.live.hetzner.image", "ubuntu-24.04");
    private static final String SUFFIX = HexFormat.of().formatHex(new byte[] {(byte) System.nanoTime(), (byte) (System.nanoTime() >> 8), (byte) (System.nanoTime() >> 16)});
    private static final String CLUSTER_NAME = "p3live" + SUFFIX;
    private static final String CONFIG = """
            config_version = "1.0.0"
            [cluster]
            name = "%s"
            version = "1.0.0"
            [source.eu-1]
            type = "cloud"
            provider = "hetzner"
            region = "eu-central"
            credentials = "hcloud-token"
            zone = "fsn1"
            [source.eu-1.worker]
            count = %d
            instance_type = "%s"
            image = "%s"
            """.formatted(CLUSTER_NAME, SERVERS, TYPE, IMAGE);
    private static final WaveNodeProvisioning.JoinInputs FAKE_JOIN = new WaveNodeProvisioning.JoinInputs("live-test-secret",
                                                                                                           List.of("aether-live-node-01k0peer000000000000000000:10.0.0.1:8090"));

    @RegisterExtension
    final LedgerDirExtension ledger = new LedgerDirExtension();

    private final ClusterName cluster = ClusterName.clusterName(CLUSTER_NAME).unwrap();

    private ClusterBootstrapConfig desired() {
        return ClusterBootstrapConfigParser.parse(CONFIG).unwrap();
    }

    /// Always runs (no cloud): the config the paid run sends parses, and the spec it renders asks for exactly the server
    /// type, image and location intended, with the apply labels.
    @Test
    void liveConfig_parsesAndRendersTheSpecThatWillBeSent() {
        var desired = desired();
        var source = desired.sources().get("eu-1");
        var spec = WaveNodeProvisioning.cloudProvisionSpec(desired, source, NodeRole.WORKER, "aether-" + CLUSTER_NAME + "-node-x", FAKE_JOIN, "0123456789abcdefghijklmnop").unwrap();

        assertThat(spec.instanceSize()).isEqualTo(TYPE);
        assertThat(spec.imageId().or("")).isEqualTo(IMAGE);
        assertThat(spec.placement().isPresent()).isTrue();
        assertThat(spec.context().extraTags()).containsEntry("aether-cluster", CLUSTER_NAME).containsEntry("aether-apply", "0123456789abcdefghijklmnop");
        assertThat(SERVERS).as("the cost bound: three smallest servers").isLessThanOrEqualTo(3);
    }

    @Test
    @Timeout(value = 25, unit = java.util.concurrent.TimeUnit.MINUTES)
    void paidRun_applyKillResumeAuditRollback_onRealHetznerServers() {
        Assumptions.assumeTrue(System.getenv("HCLOUD_TOKEN") != null && !System.getenv("HCLOUD_TOKEN").isBlank(), "HCLOUD_TOKEN is not in the environment (run through with-hcloud)");
        Assumptions.assumeTrue(Boolean.getBoolean("aether.live.hetzner"), "-Daether.live.hetzner=true is not set");

        var desired = desired();
        var source = desired.sources().get("eu-1");
        var action = ApplyAction.actionKey(new DiffAction.AddRole(sourceNameOrDefault("eu-1"), NodeRole.WORKER, SERVERS));
        var compute = ProviderResolver.resolveCloudCompute(source).unwrap();
        var cleanup = ProviderResolver.resolveCloudComputeForCleanup("hetzner").unwrap();
        var report = new java.util.ArrayList<String>();

        try {
            var recorder = ledger.recorder(cluster, List.of(action));
            var applyId = recorder.applyId();

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
            sweep(cleanup, report);
            System.out.println("=== P3 LIVE RUN REPORT ===\n" + String.join("\n", report) + "\n=== END ===");
        }
    }

    private static List<String> listByApplyLabel(ComputeProvider provider, String applyId) {
        return provider.listInstances(Map.of("aether-apply", applyId)).await().unwrap().stream().map(info -> info.id().value()).toList();
    }

    /// Whatever the run left under `aether-cluster=<throwaway>` is deleted, and the inventory is asserted empty afterwards.
    private static void sweep(ComputeProvider provider, List<String> report) {
        var leftovers = provider.listInstances(Map.of("aether-cluster", CLUSTER_NAME)).await().fold(_ -> List.<String> of(), list -> list.stream().map(info -> info.id().value()).toList());

        leftovers.forEach(id -> report.add("SWEEP terminating leftover " + id + ": " + provider.terminate(new InstanceId(id)).await().fold(cause -> "FAILED " + cause.message(), _ -> "ok")));

        var after = provider.listInstances(Map.of("aether-cluster", CLUSTER_NAME)).await().fold(cause -> List.of("LISTING FAILED: " + cause.message()), list -> list.stream().map(info -> info.id().value()).toList());

        report.add("INVENTORY AUDIT aether-cluster=" + CLUSTER_NAME + " after the run: " + after);
        assertThat(after).as("post-run inventory by label must be empty").isEmpty();
    }
}
