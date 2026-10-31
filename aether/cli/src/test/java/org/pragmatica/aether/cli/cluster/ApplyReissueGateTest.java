// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
// Adopted from the v-g1 verifier probes (G1FVerifierProbesTest), adapted to the cluster-scoped operation id.
package org.pragmatica.aether.cli.cluster;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
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
import org.pragmatica.aether.environment.InstanceId;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.InstanceStatus;
import org.pragmatica.aether.environment.InstanceType;
import org.pragmatica.aether.environment.ProvisionRequest;
import org.pragmatica.aether.environment.ProvisionedNode;
import org.pragmatica.aether.environment.hetzner.HetznerComputeProvider;
import org.pragmatica.aether.environment.hetzner.HetznerEnvironmentConfig;
import org.pragmatica.cloud.hetzner.HetznerClient;
import org.pragmatica.cloud.hetzner.HetznerError;
import org.pragmatica.cloud.hetzner.api.Server;
import org.pragmatica.cloud.hetzner.api.Server.CreateServerRequest;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.cloud.hetzner.HetznerConfig.hetznerConfig;

/// G1-f hazards the verifier found: the re-issue is gated on the provider honouring an operation id, a terminated row is never re-issued, a
/// vanished confirmed server is never created under its name, and the exact lost-reply-plus-delete window on Hetzner is characterised.
class ApplyReissueGateTest {
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
    private static final WaveNodeProvisioning.JoinInputs INPUTS = new WaveNodeProvisioning.JoinInputs("wave-secret",
            List.of("aether-prodcluster-node-01k0peer000000000000000000:10.0.0.1:8090"));
    private static final String NODE = "aether-prodcluster-node-01k0aaaaaaaaaaaaaaaaaaaaaa";
    private static final String OP = CLUSTER.value() + "-" + NODE;

    @RegisterExtension
    final LedgerDirExtension ledger = new LedgerDirExtension();

    private final java.util.function.Consumer<java.time.Duration> originalPause = ApplyReconciler.pause;
    private final Project project = new Project();
    private final ComputeProvider hetzner = HetznerComputeProvider.hetznerComputeProvider(project.client(), config()).unwrap();

    @AfterEach
    void restore() { ApplyReconciler.pause = originalPause; }

    // ---- the residual window on Hetzner: a create whose reply was lost, then deleted by anyone BEFORE the resume's first listing, is re-created once (a name is free after its server is deleted, P-G1 A5). Characterised, not closed.
    // (was: H5: lost reply (server created, reply lost) + delete from outside before the resume's lookup -> the re-issue makes a SECOND server for one intent
    @Test
    void hetznerLostReplyThenDelete_reissueCreatesOnce_theKnownResidualWindow() {
        var recorder = ledger.recorder(CLUSTER);
        ApplyReconciler.pause = _ -> {};
        project.loseNextReply = true;
        // first run: the id is minted by the supplier we pin to NODE
        var first = WaveNodeProvisioning.provisionCloud(hetzner, parse(), source(), NodeRole.WORKER, 1, INPUTS, () -> NODE, recorder);
        assertThat(first.isFailure()).as("lost reply -> CreateUnconfirmed").isTrue();
        assertThat(project.accepted).as("the provider DID create it").containsExactly(OP);
        assertThat(rowOf(recorder, NODE).state()).isEqualTo(ApplyResource.State.INTENDED);

        project.delete(NODE);   // somebody (operator, a sweep, a cost script) deletes the server before the resume looks

        ApplyReconciler.reconcileIntents(recorder, name -> Result.success(hetzner)).unwrap();
        assertThat(rowOf(recorder, NODE).state()).isEqualTo(ApplyResource.State.ABSENT);
        var second = WaveNodeProvisioning.provisionCloud(hetzner, parse(), source(), NodeRole.WORKER, 1, INPUTS,
                                                         WaveNodeProvisioning.reissuedThenMinted(recorder, () -> "minted-should-not-be-used"), recorder);
        assertThat(second.isSuccess()).isTrue();
        assertThat(project.accepted).as("TWO accepted creates under one name (one deleted from outside)").containsExactly(OP, OP);
    }

    // ---- H4: a confirmed (CREATED) server that vanished is never created again under its name; the first resume creates nothing at all
    @Test
    void createdRowWhoseVmVanished_resumeCreatesNothing_andNeverTheSameName() {
        var recorder = ledger.recorder(CLUSTER);
        ApplyReconciler.pause = _ -> {};
        var made = WaveNodeProvisioning.provisionCloud(hetzner, parse(), source(), NodeRole.WORKER, 1, INPUTS, () -> NODE, recorder);
        assertThat(made.isSuccess()).isTrue();
        project.delete(NODE);
        project.accepted.clear();
        // what WaveExecutor.executeAddition does on a resume of this action: remainder = count - recordedFor(key)   [private; simulated, stated]
        int remainder = Math.max(0, 1 - recorder.recordedFor(LedgerDirExtension.TEST_ACTION));
        assertThat(remainder).as("CREATED counts as recorded: nothing to create").isZero();
        var report = ApplyReconciler.audit(recorder, CLUSTER, name -> Result.success(hetzner)).unwrap();
        assertThat(report.gone()).containsExactly(OP);
        assertThat(project.accepted).isEmpty();
        // a SECOND resume of the same (still IN_PROGRESS) action: GONE no longer counts -> remainder 1 -> a NEW id (never NODE)
        int remainder2 = Math.max(0, 1 - recorder.recordedFor(LedgerDirExtension.TEST_ACTION));
        var again = WaveNodeProvisioning.provisionCloud(hetzner, parse(), source(), NodeRole.WORKER, remainder2, INPUTS,
                                                        WaveNodeProvisioning.reissuedThenMinted(recorder, WaveNodeProvisioning.mintedIds(CLUSTER)), recorder);
        assertThat(again.isSuccess()).isTrue();
        assertThat(project.accepted).doesNotContain(OP);
    }

    // ---- H4/H5 hazard: the re-issue is only safe where the provider refuses a duplicate name. A provider that does not (AWS, GCP, Azure honour no operation id) gets the SAME node id created twice.
    @Test
    void ungatedReissue_onAProviderWithoutNameUniqueness_wouldMakeTwoServersWithOneNodeId() {
        var plain = new PlainProvider();
        var recorder = ledger.recorder(CLUSTER);
        ApplyReconciler.pause = _ -> {};
        recorder.intend(NODE, "aws").unwrap();
        plain.createdEarlierButNotYetListed(NODE, recorder.applyId());   // the original create landed late: invisible to the 12 listings
        ApplyReconciler.reconcileIntents(recorder, name -> Result.success(plain)).unwrap();
        assertThat(rowOf(recorder, NODE).state()).isEqualTo(ApplyResource.State.ABSENT);
        plain.becomeListable();                                           // ... and is visible a moment later
        var second = WaveNodeProvisioning.provisionCloud(plain, parse(), source(), NodeRole.WORKER, 1, INPUTS,
                                                         WaveNodeProvisioning.reissuedThenMinted(recorder, () -> "minted-should-not-be-used"), recorder);
        assertThat(plain.honoursOperationId()).isFalse();
        assertThat(plain.countWithNodeId(NODE)).as("two live servers carry one node id").isEqualTo(2);
    }

    // ---- the gate: with the candidate patch (re-issue only for a provider that honours an operation id) the same scenario leaves ONE server per node id
    @Test
    void gatedReissue_providerWithoutNameUniqueness_absentIdIsNotReissued() {
        var plain = new PlainProvider();
        var recorder = ledger.recorder(CLUSTER);
        ApplyReconciler.pause = _ -> {};
        recorder.intend(NODE, "aws").unwrap();
        plain.createdEarlierButNotYetListed(NODE, recorder.applyId());
        ApplyReconciler.reconcileIntents(recorder, name -> Result.success(plain)).unwrap();
        plain.becomeListable();
        var second = WaveExecutor.provisionCloudNodes(plain, parse(), source(), NodeRole.WORKER, 1, INPUTS, recorder);
        assertThat(second.isSuccess()).isTrue();
        assertThat(plain.countWithNodeId(NODE)).as("only the late-landing original carries the node id").isEqualTo(1);
    }

    // ---- brief row "a TERMINATED row -> nothing": a terminated id is never re-issued (a name is free again after its server is deleted, P-G1 A5). Pin for the F8 survivor.
    @Test
    void terminatedRow_isNeverReissued_theNextCreateTakesANewId() {
        var recorder = ledger.recorder(CLUSTER);
        recorder.intend(NODE, "hetzner").unwrap();
        recorder.created(NODE, "9001").unwrap();
        recorder.terminated(NODE).unwrap();
        assertThat(rowOf(recorder, NODE).state()).isEqualTo(ApplyResource.State.TERMINATED);

        var made = WaveExecutor.provisionCloudNodes(hetzner, parse(), source(), NodeRole.WORKER, 1, INPUTS, recorder);

        assertThat(made.isSuccess()).isTrue();
        assertThat(project.accepted).hasSize(1).doesNotContain(OP);
    }

    private static ApplyResource rowOf(ApplyRecorder recorder, String nodeId) {
        return recorder.snapshot().resources().stream().filter(row -> row.nodeId().equals(nodeId)).findFirst().orElseThrow();
    }

    private static ClusterBootstrapConfig parse() { return ClusterBootstrapConfigParser.parse(TOML).unwrap(); }

    private static SourceProfile source() { return parse().sources().get("eu-1"); }

    private static HetznerEnvironmentConfig config() {
        return HetznerEnvironmentConfig.hetznerEnvironmentConfig(hetznerConfig("test-token"), "cx22", "ubuntu-24.04", "fsn1", List.of(1L, 2L),
                                                                 List.of(10L), List.of(5L), "#!/bin/bash\necho hello").unwrap();
    }

    /// A provider with NO name uniqueness and no operation-id support (the AWS/GCP/Azure shape): createFrom always makes a server.
    private static final class PlainProvider implements ComputeProvider {
        private final List<InstanceInfo> servers = new ArrayList<>();
        private boolean listable = true;
        private final AtomicLong ids = new AtomicLong(500);

        void createdEarlierButNotYetListed(String nodeId, String applyId) { servers.add(info(nodeId, applyId)); listable = false; }
        void becomeListable() { listable = true; }
        synchronized long countWithNodeId(String nodeId) { return servers.stream().filter(s -> s.nodeId().equals(Option.some(nodeId))).count(); }

        private InstanceInfo info(String nodeId, String applyId) {
            return new InstanceInfo(new InstanceId(String.valueOf(ids.incrementAndGet())), InstanceStatus.RUNNING, List.of("10.0.0.9"), InstanceType.ON_DEMAND,
                                    Map.of("aether-cluster", CLUSTER.value(), "aether-node-id", nodeId, "aether-apply", applyId), Option.some(nodeId), Option.none());
        }

        @Override public org.pragmatica.aether.environment.ProviderDefaults providerDefaults() { return org.pragmatica.aether.environment.ProviderDefaults.providerDefaults("m5.large", "img", "img", "z1", Option.none(), false); }
        @Override public synchronized Promise<InstanceInfo> createFrom(ProvisionRequest request) {
            var ctx = request.context();
            var applyId = ctx.extraTags().getOrDefault("aether-apply", "missing");
            var made = info(ctx.nodeId().or("none"), applyId);
            servers.add(made);
            return Promise.success(made);
        }
        @Override public Promise<Unit> terminate(InstanceId id) { return Promise.success(Unit.unit()); }
        @Override public synchronized Promise<List<InstanceInfo>> listInstances() { return Promise.success(listable ? List.copyOf(servers) : List.of()); }
        @Override public Promise<InstanceInfo> instanceStatus(InstanceId id) { return Promise.success(servers.getFirst()); }
    }

    private static final class Project implements InvocationHandler {
        final List<String> accepted = new ArrayList<>();
        volatile boolean loseNextReply;
        private final Map<String, Server> byName = new LinkedHashMap<>();
        private final AtomicLong ids = new AtomicLong(100);

        HetznerClient client() {
            return (HetznerClient) Proxy.newProxyInstance(HetznerClient.class.getClassLoader(), new Class<?>[]{HetznerClient.class}, this);
        }

        synchronized void delete(String name) { byName.remove(name); }

        @Override
        public synchronized Object invoke(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "createServer" -> create((CreateServerRequest) args[0]);
                case "listServers" -> Promise.success(args == null ? List.copyOf(byName.values()) : selected((String) args[0]));
                case "getServer" -> Promise.success(byName.values().stream().filter(s -> s.id() == (Long) args[0]).findFirst().orElseThrow());
                case "listSshKeys", "listFirewalls" -> Promise.success(List.of());
                default -> throw new UnsupportedOperationException(method.getName());
            };
        }

        private Promise<Server> create(CreateServerRequest request) {
            if (byName.containsKey(request.name())) {
                return new HetznerError.ApiError(409, "uniqueness_error", "server name is already used").promise();
            }
            var created = new Server(ids.incrementAndGet(), request.name(), "running", null, null, null, List.of(), Map.copyOf(request.labels()), new Server.Location("fsn1"));
            byName.put(request.name(), created);
            accepted.add(request.name());
            if (loseNextReply) {
                loseNextReply = false;
                return Causes.cause("read timeout: the reply was lost").promise();
            }
            return Promise.success(created);
        }

        private List<Server> selected(String selector) {
            return byName.values().stream().filter(s -> {
                for (var term : selector.split(",")) {
                    var pair = term.split("=", 2);
                    if (!pair[1].equals(s.labels().get(pair[0]))) return false;
                }
                return true;
            }).toList();
        }
    }
}
