// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfig;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigParser;
import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.config.cluster.SourceProfile;
import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.ProvisionedNode;
import org.pragmatica.aether.environment.hetzner.HetznerComputeProvider;
import org.pragmatica.aether.environment.hetzner.HetznerEnvironmentConfig;
import org.pragmatica.cloud.hetzner.HetznerClient;
import org.pragmatica.cloud.hetzner.HetznerError;
import org.pragmatica.cloud.hetzner.api.Server;
import org.pragmatica.cloud.hetzner.api.Server.CreateServerRequest;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.cloud.hetzner.HetznerConfig.hetznerConfig;

/// G1-f: `aether cluster apply` names every cloud server after its minted node id and lets the provider's name uniqueness (Hetzner refuses
/// a second server with an existing name, P-G1 A2/A3) decide a repeated create, instead of label lookups (labels are not unique, A8).
///
/// Driven through the real [HetznerComputeProvider] over a project fake that REFUSES a duplicate name with the `409 uniqueness_error` the
/// live probe measured, so the assertions read what a Hetzner project would do. No cloud provider is called.
class ApplyNameDedupTest {
    private static final ClusterName CLUSTER = ClusterName.clusterName("prodcluster").unwrap();
    private static final String TOML = """
            config_version = "1.0.0"

            [cluster]
            name = "prodcluster"
            version = "1.0.0"

            [source.eu-1]
            type = "cloud"
            provider = "hetzner"
            region = "eu-central"
            credentials = "hcloud-token"
            zone = "fsn1"

            [source.eu-1.core]
            count = 3

            [source.eu-1.worker]
            count = 2
            """;
    private static final String PEER = "aether-prodcluster-node-01k0peer000000000000000000:10.0.0.1:8090";
    private static final WaveNodeProvisioning.JoinInputs INPUTS = new WaveNodeProvisioning.JoinInputs("wave-secret", List.of(PEER));
    private static final String NODE = "aether-prodcluster-node-01k0aaaaaaaaaaaaaaaaaaaaaa";
    /// The server name of NODE's create: the cluster-scoped operation id, `<cluster>-<nodeId>`.
    private static final String OP = CLUSTER.value() + "-" + NODE;

    @RegisterExtension
    final LedgerDirExtension ledger = new LedgerDirExtension();

    private final Consumer<Duration> originalPause = ApplyReconciler.pause;
    private final HetznerProject project = new HetznerProject();
    private final ComputeProvider provider = HetznerComputeProvider.hetznerComputeProvider(project.client(), config()).unwrap();

    @AfterEach
    void restore() {
        ApplyReconciler.pause = originalPause;
    }

    // ---- AP-1: the name is the minted id, lower case, and the operation label rides beside the existing labels

    @Test
    void ap1_anApplyCreate_namesTheServerWithTheMintedNodeId_andAddsTheOperationLabel() {
        var recorder = ledger.recorder(CLUSTER);

        provision(recorder, 1);

        var nodeId = onlyRow(recorder).nodeId();
        var sent = project.createRequests.getFirst();

        assertThat(sent.name()).isEqualTo(CLUSTER.value() + "-" + nodeId).isEqualTo(sent.name().toLowerCase()).matches("[a-z0-9-]{1,63}");
        assertThat(sent.labels()).containsEntry("aether.op", CLUSTER.value() + "-" + nodeId)
                                 .containsEntry("aether-node-id", nodeId)
                                 .containsEntry("aether-cluster", CLUSTER.value())
                                 .containsEntry(WaveNodeProvisioning.APPLY_LABEL, recorder.applyId());
    }

    // ---- AP-2: a resumed INTENDED row is looked up first, and re-issued under the same name only when nothing is found

    @Test
    void ap2_intendedRow_whoseVmIsFoundByLabel_createsNothing() {
        var recorder = ledger.recorder(CLUSTER);

        recorder.intend(NODE, "hetzner").unwrap();
        project.preload(OP, ours(recorder));
        noPauses();

        ApplyReconciler.reconcileIntents(recorder, computes()).unwrap();

        assertThat(rowOf(recorder, NODE).state()).isEqualTo(ApplyResource.State.CREATED);
        assertThat(project.createRequests).as("the lookup found it: no create call at all").isEmpty();
    }

    @Test
    void ap2_intendedRow_whoseVmIsNotFound_isCreatedOnceMoreUnderTheSameName() {
        var recorder = ledger.recorder(CLUSTER);

        recorder.intend(NODE, "hetzner").unwrap();
        noPauses();

        ApplyReconciler.reconcileIntents(recorder, computes()).unwrap();

        assertThat(rowOf(recorder, NODE).state()).isEqualTo(ApplyResource.State.ABSENT);

        provision(recorder, 1);

        assertThat(project.createRequests).hasSize(1);
        assertThat(project.createRequests.getFirst().name()).as("the same name, not a new id").isEqualTo(OP);
        assertThat(rowOf(recorder, NODE).state()).isEqualTo(ApplyResource.State.CREATED);
        assertThat(recorder.snapshot().resources()).as("no second row: the node is the same node").hasSize(1);
    }

    @Test
    void ap2_intendedRow_whoseCreateLandedAfterTheLookupWindow_isAdoptedByTheRefusedName() {
        var recorder = ledger.recorder(CLUSTER);

        recorder.intend(NODE, "hetzner").unwrap();
        project.preload(OP, ours(recorder));
        project.hideFromLabelListings = true;
        noPauses();

        ApplyReconciler.reconcileIntents(recorder, computes()).unwrap();

        assertThat(rowOf(recorder, NODE).state()).as("every label listing was empty").isEqualTo(ApplyResource.State.ABSENT);

        provision(recorder, 1);

        assertThat(project.servers()).as("the name refused the second server: still exactly one").hasSize(1);
        assertThat(project.createRequests).as("one refused attempt").hasSize(1);
        assertThat(rowOf(recorder, NODE).state()).isEqualTo(ApplyResource.State.CREATED);
        assertThat(rowOf(recorder, NODE).serverId().or("")).isEqualTo(String.valueOf(project.servers().getFirst().id()));
    }

    @Test
    void ap2_absentRowOfAnotherAction_isNotReissuedByThisOne() {
        var other = "AddRole|other||->n";
        var recorder = ledger.recorder(CLUSTER, List.of(LedgerDirExtension.TEST_ACTION, other));

        recorder.intend(NODE, "hetzner").unwrap();
        noPauses();
        ApplyReconciler.reconcileIntents(recorder, computes()).unwrap();
        recorder.beginAction(other).unwrap();

        provision(recorder, 1);

        assertThat(project.createRequests).hasSize(1);
        assertThat(project.createRequests.getFirst().name()).as("the other action's id is its own to re-issue").doesNotContain(NODE);
        assertThat(rowOf(recorder, NODE).state()).isEqualTo(ApplyResource.State.ABSENT);
    }

    // ---- AP-3: a confirmed server that vanished is never created again

    @Test
    void ap3_createdRow_whoseVmIsGone_isNotCreatedAgain_andTheNextCreateTakesANewId() {
        var recorder = ledger.recorder(CLUSTER);

        recorder.intend(NODE, "hetzner").unwrap();
        recorder.created(NODE, "9001").unwrap();

        var report = ApplyReconciler.audit(recorder, CLUSTER, computes()).unwrap();

        assertThat(report.gone()).as("the report names the id").containsExactly(NODE);
        assertThat(rowOf(recorder, NODE).state()).isEqualTo(ApplyResource.State.GONE);

        provision(recorder, 1);

        assertThat(project.createRequests).hasSize(1);
        assertThat(project.createRequests.getFirst().name()).as("a new node with a new id, never the vanished one").doesNotContain(NODE);
    }

    // ---- AP-4: a name held by something that is not ours is refused, typed, never adopted

    @Test
    void ap4_nameHeldByAnotherCluster_isRefusedAndNotAdopted() {
        var recorder = ledger.recorder(CLUSTER);

        recorder.intend(NODE, "hetzner").unwrap();
        project.preload(OP, Map.of("aether-cluster", "other-cluster", "aether-node-id", NODE));
        noPauses();
        ApplyReconciler.reconcileIntents(recorder, computes()).unwrap();

        assertThat(rowOf(recorder, NODE).state()).as("a foreign server carrying the node-id label is not the node's VM").isEqualTo(ApplyResource.State.ABSENT);

        var refused = provisionResult(recorder, 1);

        assertThat(refused.isFailure()).isTrue();
        refused.onFailure(cause -> assertThat(cause.message()).contains("does not carry this cluster's and this apply's labels"));
        assertThat(rowOf(recorder, NODE).state()).isEqualTo(ApplyResource.State.INTENDED);
        assertThat(rowOf(recorder, NODE).serverId().isEmpty()).isTrue();
        assertThat(project.servers()).hasSize(1);
    }

    @Test
    void ap4_nameHeldByThisClusterButAnotherApply_isRefusedAndNotAdopted() {
        var recorder = ledger.recorder(CLUSTER);

        recorder.intend(NODE, "hetzner").unwrap();
        project.preload(OP,
                        Map.of("aether-cluster", CLUSTER.value(), "aether-node-id", NODE, WaveNodeProvisioning.APPLY_LABEL, "another-apply"));
        noPauses();
        ApplyReconciler.reconcileIntents(recorder, computes()).unwrap();

        var refused = provisionResult(recorder, 1);

        assertThat(refused.isFailure()).isTrue();
        refused.onFailure(cause -> assertThat(cause.message()).contains("does not carry this cluster's and this apply's labels"));
        assertThat(rowOf(recorder, NODE).state()).isEqualTo(ApplyResource.State.INTENDED);
        assertThat(project.servers()).hasSize(1);
    }

    // ---- AP-6: two concurrent resumes of one INTENDED row make exactly one server

    @Test
    void ap6_twoConcurrentResumesOfOneIntendedRow_makeExactlyOneServer() throws Exception {
        var first = ledger.recorder(CLUSTER);

        first.intend(NODE, "hetzner").unwrap();
        noPauses();

        var second = ApplyRecorder.applyRecorder(first.snapshot());

        second.beginAction(LedgerDirExtension.TEST_ACTION).unwrap();

        var pool = Executors.newFixedThreadPool(2);
        var bothInFlight = new CyclicBarrier(2);

        project.beforeCreate = () -> await(bothInFlight);

        try {
            var runs = List.of(pool.submit(() -> resume(first)), pool.submit(() -> resume(second)));

            for (var run : runs) {
                assertThat(run.get(60, TimeUnit.SECONDS).isSuccess()).as("each resume ends well: one made the server, the other adopted it").isTrue();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(project.createRequests).as("both resumes asked, under one name").hasSize(2)
                                          .allSatisfy(request -> assertThat(request.name()).isEqualTo(OP));
        assertThat(project.servers()).as("the name refused the second").hasSize(1);
        assertThat(rowOf(first, NODE).state()).isEqualTo(ApplyResource.State.CREATED);
        assertThat(rowOf(second, NODE).state()).isEqualTo(ApplyResource.State.CREATED);
        assertThat(rowOf(second, NODE).serverId()).isEqualTo(rowOf(first, NODE).serverId());
    }

    // ---- helpers

    private Result<Unit> resume(ApplyRecorder recorder) {
        return ApplyReconciler.reconcileIntents(recorder, computes()).flatMap(_ -> provisionResult(recorder, 1).map(_ -> Unit.unit()));
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("the two resumes never met at the provider call", e);
        }
    }

    private void provision(ApplyRecorder recorder, int count) {
        provisionResult(recorder, count).unwrap();
    }

    private Result<List<ProvisionedNode>> provisionResult(ApplyRecorder recorder, int count) {
        var desired = parse();

        return WaveExecutor.provisionCloudNodes(provider, desired, source(desired), NodeRole.WORKER, count, INPUTS, recorder);
    }

    private Function<String, Result<ComputeProvider>> computes() {
        return _ -> Result.success(provider);
    }

    private static void noPauses() {
        ApplyReconciler.pause = _ -> {};
    }

    private Map<String, String> ours(ApplyRecorder recorder) {
        return Map.of("aether-cluster", CLUSTER.value(), "aether-node-id", NODE, WaveNodeProvisioning.APPLY_LABEL, recorder.applyId());
    }

    private static ApplyResource onlyRow(ApplyRecorder recorder) {
        return recorder.snapshot().resources().getFirst();
    }

    private static ApplyResource rowOf(ApplyRecorder recorder, String nodeId) {
        return recorder.snapshot().resources().stream().filter(row -> row.nodeId().equals(nodeId)).findFirst().orElseThrow();
    }

    private static ClusterBootstrapConfig parse() {
        return ClusterBootstrapConfigParser.parse(TOML).unwrap();
    }

    private static SourceProfile source(ClusterBootstrapConfig desired) {
        return desired.sources().get("eu-1");
    }

    private static HetznerEnvironmentConfig config() {
        return HetznerEnvironmentConfig.hetznerEnvironmentConfig(hetznerConfig("test-token"), "cx22", "ubuntu-24.04", "fsn1", List.of(1L, 2L),
                                                                 List.of(10L), List.of(5L), "#!/bin/bash\necho hello").unwrap();
    }

    /// A Hetzner project as far as the apply path touches it: servers by name, with the atomic `409 uniqueness_error` on a second create
    /// of a name that exists (P-G1 A2/A3), label-selector listings (which can be made to lag, A6), and nothing else.
    private static final class HetznerProject implements InvocationHandler {
        final List<CreateServerRequest> createRequests = new ArrayList<>();
        volatile boolean hideFromLabelListings;
        volatile Runnable beforeCreate = () -> {};
        private final Map<String, Server> byName = new LinkedHashMap<>();
        private final AtomicLong ids = new AtomicLong(100);

        HetznerClient client() {
            return (HetznerClient) Proxy.newProxyInstance(HetznerClient.class.getClassLoader(), new Class<?>[]{HetznerClient.class}, this);
        }

        synchronized List<Server> servers() {
            return List.copyOf(byName.values());
        }

        synchronized void preload(String name, Map<String, String> labels) {
            byName.put(name, server(name, labels));
        }

        private Server server(String name, Map<String, String> labels) {
            return new Server(ids.incrementAndGet(), name, "running", null, null, null, List.of(), Map.copyOf(labels), new Server.Location("fsn1"));
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            return switch (method.getName()) {
                case "createServer" -> createServer((CreateServerRequest) args[0]);
                case "listServers" -> args == null
                                      ? Promise.success(servers())
                                      : Promise.success(selected((String) args[0]));
                case "getServer" -> Promise.success(byId((Long) args[0]));
                case "listSshKeys", "listFirewalls" -> Promise.success(List.of());
                default -> throw new UnsupportedOperationException("the apply path must not call " + method.getName());
            };
        }

        private Promise<Server> createServer(CreateServerRequest request) {
            beforeCreate.run();

            synchronized (this) {
                createRequests.add(request);

                if (byName.containsKey(request.name())) {
                    return new HetznerError.ApiError(409, "uniqueness_error", "server name is already used").promise();
                }

                var created = server(request.name(), request.labels());

                byName.put(request.name(), created);

                return Promise.success(created);
            }
        }

        private synchronized Server byId(long id) {
            return byName.values().stream().filter(server -> server.id() == id).findFirst().orElseThrow();
        }

        private synchronized List<Server> selected(String selector) {
            return hideFromLabelListings
                   ? List.of()
                   : byName.values().stream().filter(server -> matches(server, selector)).toList();
        }

        private static boolean matches(Server server, String selector) {
            for (var term : selector.split(",")) {
                var pair = term.split("=", 2);

                if (!pair[1].equals(server.labels().get(pair[0]))) {
                    return false;
                }
            }

            return true;
        }
    }
}
