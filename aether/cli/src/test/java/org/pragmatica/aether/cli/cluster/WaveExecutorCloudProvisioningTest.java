// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfig;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigParser;
import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.InstanceId;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.InstanceStatus;
import org.pragmatica.aether.environment.InstanceType;
import org.pragmatica.aether.environment.PlacementHint;
import org.pragmatica.aether.environment.ProvisionRequest;
import org.pragmatica.aether.environment.ProvisionSpec;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import static org.assertj.core.api.Assertions.assertThat;

/// #1695 / #1027 — `aether cluster apply` provisions CLOUD nodes through [WaveExecutor#provisionCloudNodes], the
/// composition the leader's auto-heal uses (except `ssh_key_ids`, which are empty on apply; see #1724). Driven against a fake provider that captures each [ProvisionSpec] it is
/// handed, so the assertions read exactly what a real provider would receive.
class WaveExecutorCloudProvisioningTest {
    private static final String ZONED = """
            config_version = "1.0.0"

            [cluster]
            name = "prod-cluster"
            version = "1.0.0"

            [source.eu-1]
            type = "cloud"
            provider = "hetzner"
            region = "eu-central"
            zone = "fsn1"

            [source.eu-1.core]
            count = 3

            [source.eu-1.worker]
            count = 2
            """;
    private static final String ZONELESS = """
            config_version = "1.0.0"

            [cluster]
            name = "prod-cluster"
            version = "1.0.0"

            [source.eu-1]
            type = "cloud"
            provider = "hetzner"
            region = "eu-central"

            [source.eu-1.core]
            count = 3
            """;
    private static final String PEER = "aether-prod-cluster-node-01k0peer000000000000000000:10.0.0.1:8090";
    private static final WaveNodeProvisioning.JoinInputs INPUTS = new WaveNodeProvisioning.JoinInputs("wave-secret",
                                                                                                        List.of(PEER));
    /// Bootstrap's ids for this cluster's three core members: what a restart-at-0 index would hand out again.
    private static final Set<String> LIVE_MEMBER_IDS = Set.of("eu-1-core-0", "eu-1-core-1", "eu-1-core-2");
    private static final Pattern INDEX_SCHEME = Pattern.compile("^eu-1-(core|worker|spot)-\\d+$");

    /// #1695: the node boots with its identity, secret and the live peers, so it can join. Before, the spec carried
    /// no user-data at all.
    @Test
    void provisionCloudNodes_rendersUserDataANodeCanJoinWith() {
        var provider = new CapturingProvider();
        var desired = parse(ZONED);

        WaveExecutor.provisionCloudNodes(provider, desired, source(desired), NodeRole.WORKER, 1, INPUTS).unwrap();

        var spec = provider.specs.getFirst();
        var userData = spec.userData().or("");
        var nodeId = spec.context().nodeId().unwrap();

        assertThat(userData).as("#1695: an apply-minted cloud node must boot with rendered user-data").isNotBlank();
        assertThat(userData).contains("# Cluster: prod-cluster")
                            .contains("AETHER_NODE_ID=\"" + nodeId + "\"")
                            .contains("# Role: worker")
                            .containsPattern("AETHER_SOURCE=\"?eu-1\"?")
                            .contains("AETHER_PEERS=\"" + PEER + "\"")
                            .contains("AETHER_CLUSTER_SECRET=\"wave-secret\"");
        assertThat(spec.context().peers()).isEqualTo(Option.some(PEER));
        assertThat(spec.context().clusterName().map(name -> name.value())).isEqualTo(Option.some("prod-cluster"));
    }

    /// #1695: a source that names a zone places the node there.
    @Test
    void provisionCloudNodes_zonedSource_placesInThatZone() {
        var provider = new CapturingProvider();
        var desired = parse(ZONED);

        WaveExecutor.provisionCloudNodes(provider, desired, source(desired), NodeRole.CORE, 1, INPUTS).unwrap();

        assertThat(provider.specs.getFirst().placement()).isEqualTo(Option.some(new PlacementHint.ZoneHint("fsn1")));
    }

    /// #1695: a zoneless source asks for NO placement, never a location literally named `default`.
    @Test
    void provisionCloudNodes_zonelessSource_leavesPlacementToTheProvider() {
        var provider = new CapturingProvider();
        var desired = parse(ZONELESS);

        WaveExecutor.provisionCloudNodes(provider, desired, source(desired), NodeRole.CORE, 1, INPUTS).unwrap();

        assertThat(provider.specs.getFirst().placement()).as("#1695: no zone means no placement hint")
                                                         .isEqualTo(Option.none());
    }

    /// #1027: two scale-up waves never produce the id of a live member, nor repeat each other's ids. An index that
    /// restarts at 0 per call produced `eu-1-core-0…` again on every wave.
    @Test
    void provisionCloudNodes_twoScaleUpWaves_neverReuseAnId() {
        var provider = new CapturingProvider();
        var desired = parse(ZONED);

        WaveExecutor.provisionCloudNodes(provider, desired, source(desired), NodeRole.CORE, 2, INPUTS).unwrap();
        WaveExecutor.provisionCloudNodes(provider, desired, source(desired), NodeRole.CORE, 2, INPUTS).unwrap();

        var ids = provider.specs.stream()
                                .map(spec -> spec.context().nodeId().unwrap())
                                .toList();

        assertThat(new HashSet<>(ids)).as("#1027: every provisioned node gets a fresh id").hasSize(4);
        assertThat(ids).as("#1027: never the id of a live member").doesNotContainAnyElementsOf(LIVE_MEMBER_IDS);
        assertThat(ids).allMatch(id -> id.startsWith("aether-prod-cluster-node-"), "the CTM id scheme");
    }

    /// #1027: a reprovision (one node) gets a fresh id too, and the SAME id is in the context and in the user-data
    /// the node boots with — a node must run under the id the cluster was told about.
    @Test
    void provisionCloudNodes_reprovision_getsAFreshIdCarriedIntoTheUserData() {
        var provider = new CapturingProvider();
        var desired = parse(ZONED);

        var tracked = WaveExecutor.provisionCloudNodes(provider, desired, source(desired), NodeRole.CORE, 1, INPUTS)
                                  .unwrap();

        var spec = provider.specs.getFirst();
        var nodeId = spec.context().nodeId().unwrap();

        assertThat(nodeId).doesNotMatch(INDEX_SCHEME);
        assertThat(LIVE_MEMBER_IDS).doesNotContain(nodeId);
        assertThat(spec.userData().or("")).contains("AETHER_NODE_ID=\"" + nodeId + "\"");
        assertThat(tracked).as("the id apply tracks is the id the node boots under")
                           .extracting(node -> node.nodeId())
                           .containsExactly(nodeId);
    }

    /// CodeRabbit on #1716 (CTO ruling: destroy the failing step's VMs). Create 2, fail the 3rd: both are terminated,
    /// and the CLI text (`Error: <message>`) says so for each.
    @Test
    void provisionCloudNodes_failurePartWay_destroysTheVmsItCreated() {
        var provider = new CapturingProvider();
        var desired = parse(ZONED);

        provider.failOnCall = 3;

        var result = WaveExecutor.provisionCloudNodes(provider, desired, source(desired), NodeRole.CORE, 3, INPUTS);

        assertThat(provider.specs).as("the wave stops at the failure").hasSize(3);
        assertThat(provider.terminated).as("every VM the failing step created is destroyed")
                                       .containsExactlyInAnyOrder("vm-1", "vm-2");
        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> assertThat(cause.message()).contains("apply failed part-way: capacity exhausted (test cause)")
                                                             .contains("destroyed: node " + nodeIdOf(provider, 0)
                                                                       + "  provider hetzner  server vm-1  ip 10.0.1.1")
                                                             .contains("destroyed: node " + nodeIdOf(provider, 1))
                                                             .contains("0 cloud VM(s) created by this apply are STILL RUNNING"));
    }

    /// A destroy that fails leaves a paid VM running: it is named loudly, with provider, server id and removal steps.
    @Test
    void provisionCloudNodes_failurePartWay_namesAVmWhoseDestroyFailedAsStillRunning() {
        var provider = new CapturingProvider();
        var desired = parse(ZONED);

        provider.failOnCall = 3;
        provider.terminateFails = "vm-1";

        var result = WaveExecutor.provisionCloudNodes(provider, desired, source(desired), NodeRole.CORE, 3, INPUTS);
        var first = nodeIdOf(provider, 0);

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> assertThat(cause.message()).contains("STILL RUNNING AND BILLED: node " + first
                                                                       + "  provider hetzner  server vm-1  ip 10.0.1.1")
                                                             .contains("destroy failed: provider refused the delete (test cause)")
                                                             .contains("remove: aether cluster drain " + first
                                                                       + " --wait --yes (if it joined), then hcloud server delete vm-1")
                                                             .contains("destroyed: node " + nodeIdOf(provider, 1))
                                                             .contains("1 cloud VM(s) created by this apply are STILL RUNNING"));
    }

    /// VMs from an EARLIER, completed step of the same apply are kept (they belong to the desired configuration), but
    /// the rollout records none of them either, so a later failure lists them too.
    @Test
    void partiallyProvisioned_laterFailure_listsEarlierStepsKeptVms() {
        var earlier = new WaveNodeProvisioning.CreatedNode(org.pragmatica.aether.environment.ProvisionedNode.provisionedNode("n-early",
                                                                                                                             "vm-10",
                                                                                                                             "10.0.9.1"),
                                                           "hetzner",
                                                           Option.none());
        var failing = new WaveNodeProvisioning.CreatedNode(org.pragmatica.aether.environment.ProvisionedNode.provisionedNode("n-late",
                                                                                                                             "vm-11",
                                                                                                                             "10.0.9.2"),
                                                           "hetzner",
                                                           Option.some(org.pragmatica.lang.Result.unitResult()));
        var inner = WaveNodeProvisioning.PartiallyProvisioned.partiallyProvisioned(List.of(failing),
                                                                                    org.pragmatica.lang.utils.Causes.cause("capacity exhausted (test cause)"));

        var message = WaveNodeProvisioning.PartiallyProvisioned.partiallyProvisioned(List.of(earlier), inner).message();

        assertThat(message).contains("kept, RUNNING AND BILLED (created by an earlier step of this apply")
                           .contains("node n-early  provider hetzner  server vm-10")
                           .contains("remove if unwanted: aether cluster drain n-early --wait --yes")
                           .contains("destroyed: node n-late  provider hetzner  server vm-11")
                           .contains("1 cloud VM(s) created by this apply are STILL RUNNING");
    }

    private static String nodeIdOf(CapturingProvider provider, int index) {
        return provider.specs.get(index).context().nodeId().unwrap();
    }

    /// The peers a wave node dials are the LIVE CORE members from `GET /api/v1/nodes/live`: a worker, a dead core
    /// member (SWIM not alive) and a member with no advertised address are not peers.
    @Test
    void corePeers_keepsOnlyLiveCoreMembersWithAnAddress() {
        var json = org.pragmatica.json.JsonMapper.defaultJsonMapper()
                                                  .readTree("""
                                                            {"nodes":[
                                                              {"nodeId":"c1","address":"10.0.0.1:8090","role":"CORE","swimAlive":true},
                                                              {"nodeId":"c2","address":"10.0.0.2:8090","role":"core","swimAlive":false},
                                                              {"nodeId":"w1","address":"10.0.0.3:8090","role":"WORKER","swimAlive":true},
                                                              {"nodeId":"c3","role":"CORE","swimAlive":true}
                                                            ],"liveCount":3,"zombieCount":0}
                                                            """)
                                                  .unwrap();

        assertThat(WaveNodeProvisioning.corePeers(json)).containsExactly("c1:10.0.0.1:8090");
    }

    private static ClusterBootstrapConfig parse(String toml) {
        return ClusterBootstrapConfigParser.parse(toml).unwrap();
    }

    private static org.pragmatica.aether.config.cluster.SourceProfile source(ClusterBootstrapConfig desired) {
        return desired.sources().get("eu-1");
    }

    /// Captures every spec handed to the provider boundary and answers with a running instance.
    private static final class CapturingProvider implements ComputeProvider {
        private final List<ProvisionSpec> specs = new ArrayList<>();
        private final List<String> terminated = new ArrayList<>();
        private int failOnCall = 0;
        private String terminateFails = "";

        @Override
        public Promise<InstanceInfo> provision(ProvisionSpec spec) {
            specs.add(spec);

            if (specs.size() == failOnCall) {
                return Promise.failure(org.pragmatica.lang.utils.Causes.cause("capacity exhausted (test cause)"));
            }

            return InstanceInfo.instanceInfo(InstanceId.instanceId("vm-" + specs.size()).unwrap(),
                                             InstanceStatus.RUNNING,
                                             List.of("10.0.1." + specs.size()),
                                             InstanceType.ON_DEMAND)
                               .async();
        }

        @Override
        public Promise<InstanceInfo> createFrom(ProvisionRequest request) {
            return Promise.failure(org.pragmatica.lang.utils.Causes.cause("not used: provision(spec) is captured"));
        }

        @Override
        public Promise<Unit> terminate(InstanceId instanceId) {
            if (instanceId.value().equals(terminateFails)) {
                return Promise.failure(org.pragmatica.lang.utils.Causes.cause("provider refused the delete (test cause)"));
            }

            terminated.add(instanceId.value());

            return Promise.unitPromise();
        }

        @Override
        public Promise<List<InstanceInfo>> listInstances() {
            return Promise.success(List.of());
        }

        @Override
        public Promise<InstanceInfo> instanceStatus(InstanceId instanceId) {
            return Promise.failure(org.pragmatica.lang.utils.Causes.cause("not used"));
        }
    }
}
