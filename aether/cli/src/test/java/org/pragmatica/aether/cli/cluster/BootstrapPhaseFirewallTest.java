// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.cli.cluster.ClusterBootstrapOrchestrator.BootstrapContext;
import org.pragmatica.aether.config.cluster.CloudProviderName;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfig;
import org.pragmatica.aether.config.cluster.ClusterIdentity;
import org.pragmatica.aether.config.cluster.CoreTopology;
import org.pragmatica.aether.config.cluster.FirewallRule;
import org.pragmatica.aether.config.cluster.InfrastructureConfig;
import org.pragmatica.aether.config.cluster.LoadBalancerMode;
import org.pragmatica.aether.config.cluster.NetworkingType;
import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.config.cluster.OperationsConfig;
import org.pragmatica.aether.config.cluster.RoleSubTable;
import org.pragmatica.aether.config.cluster.SourceProfile;
import org.pragmatica.aether.config.cluster.SourceType;
import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.IngressHandle;
import org.pragmatica.aether.environment.InstanceId;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.ProvisionRequest;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// #574 — `[source.X.firewall] allow_ingress` was parsed, validated, diffed and scaffolded into user
/// configs by `aether cluster init`, with ZERO consumers on any provisioning path. Every layer the
/// operator touched confirmed it worked. On Hetzner — the one provider actually run — a server with
/// no firewall association accepts ALL inbound traffic (§6.2), so the gap failed OPEN.
class BootstrapPhaseFirewallTest {

    private record OpenCall(String sourceId, int port, String protocol, String cidr, String description) {}

    /// Records ingress calls and hands back one firewall id per source, mirroring the real provider:
    /// every rule of a source lands on ONE firewall.
    private static final class RecordingCompute implements ComputeProvider {
        final List<OpenCall> opened = new ArrayList<>();
        final Map<String, Long> idsBySource = new java.util.HashMap<>();
        long nextId = 100L;

        @Override
        public Promise<IngressHandle> openIngress(String sourceId,
                                                  int port,
                                                  String protocol,
                                                  String sourceCidr,
                                                  String description) {
            opened.add(new OpenCall(sourceId, port, protocol, sourceCidr, description));

            var id = idsBySource.computeIfAbsent(sourceId, _ -> nextId++);

            return Promise.success(IngressHandle.ingressHandle(Long.toString(id)));
        }

        @Override
        public Promise<InstanceInfo> createFrom(ProvisionRequest request) {
            throw new AssertionError("firewall phase must not provision");
        }

        @Override
        public Promise<Unit> terminate(InstanceId instanceId) {
            throw new AssertionError("firewall phase must not terminate");
        }

        @Override
        public Promise<List<InstanceInfo>> listInstances() {
            throw new AssertionError("firewall phase must not list instances");
        }

        @Override
        public Promise<InstanceInfo> instanceStatus(InstanceId instanceId) {
            throw new AssertionError("firewall phase must not poll status");
        }
    }

    private static SourceProfile hetznerSource(String name,
                                               LoadBalancerMode lbMode,
                                               List<FirewallRule> rules) {
        return SourceProfile.sourceProfile(name,
                                            SourceType.CLOUD,
                                            Option.some(CloudProviderName.HETZNER),
                                            Option.some("token"),
                                            Option.some("fsn1"),
                                            Option.empty(),
                                            Option.empty(),
                                            Option.empty(),
                                            Option.empty(),
                                            lbMode,
                                            List.of(),
                                            Option.empty(),
                                            Map.of(),
                                            Map.of(NodeRole.CORE,
                                                    RoleSubTable.roleSubTable(NodeRole.CORE,
                                                                                Option.some(3),
                                                                                Option.empty(),
                                                                                Option.empty(),
                                                                                "default")),
                                            rules);
    }

    private static BootstrapContext contextWith(SourceProfile source) {
        var config = ClusterBootstrapConfig.clusterBootstrapConfig("1.0.0",
                                                                    ClusterIdentity.clusterIdentity("test", "1.0.0").unwrap(),
                                                                    CoreTopology.defaultCoreTopology(),
                                                                    Map.of(source.name(), source),
                                                                    Map.of(),
                                                                    InfrastructureConfig.infrastructureConfig(NetworkingType.MANUAL),
                                                                    OperationsConfig.defaultOperationsConfig());

        return BootstrapContext.bootstrapContext(config,
                                                  BootstrapState.initialState("test", "h", "now"),
                                                  List.of(),
                                                  List.of());
    }

    private static Result<BootstrapContext> run(BootstrapContext ctx, RecordingCompute compute) {
        return BootstrapPhaseFirewall.execute(ctx, (_, _, _, _) -> Result.success(compute));
    }

    /// REQ-5.1.8.1 — a "tcp+udp" entry expands to TWO provider-level rules.
    @Test
    void execute_tcpUdpRule_expandsToTwoProviderRulesOnOneFirewall() {
        var rules = List.of(FirewallRule.firewallRule(8070, "tcp+udp", "0.0.0.0/0", Option.empty()));
        var compute = new RecordingCompute();

        var result = run(contextWith(hetznerSource("eu-1", LoadBalancerMode.NONE, rules)), compute);

        assertTrue(result.isSuccess(), () -> "phase should succeed: " + result);
        assertEquals(List.of("tcp", "udp"), compute.opened.stream().map(OpenCall::protocol).toList(),
                     "tcp+udp must expand to exactly two provider rules");
        assertEquals(List.of(100L), result.unwrap().firewallIdsFor("eu-1"),
                     "Both rules land on ONE firewall, so exactly one id is threaded to provision");
    }

    /// The id must reach provision — otherwise the firewall exists but no server references it, and
    /// the node comes up wide open (§6.2).
    @Test
    void execute_threadsFirewallIdIntoContextForProvision() {
        var rules = List.of(FirewallRule.firewallRule(8070, "tcp", "10.0.0.0/8", Option.empty()));
        var compute = new RecordingCompute();

        var result = run(contextWith(hetznerSource("eu-1", LoadBalancerMode.NONE, rules)), compute);

        assertEquals(List.of(100L), result.unwrap().firewallIdsFor("eu-1"));
    }

    /// One firewall = one CreatedResource, so destroy issues exactly one delete rather than N.
    @Test
    void execute_recordsOneFirewallResource_forMultipleRules() {
        var rules = List.of(FirewallRule.firewallRule(8070, "tcp", "0.0.0.0/0", Option.empty()),
                            FirewallRule.firewallRule(9000, "tcp+udp", "10.0.0.0/8", Option.empty()));
        var compute = new RecordingCompute();

        var result = run(contextWith(hetznerSource("eu-1", LoadBalancerMode.NONE, rules)), compute);

        assertEquals(3, compute.opened.size(), "1 tcp + (tcp,udp) = 3 provider rules");
        var firewalls = result.unwrap()
                              .state()
                              .createdResources()
                              .stream()
                              .filter(resource -> resource instanceof CreatedResource.CloudFirewall)
                              .toList();

        assertEquals(1, firewalls.size(), "Multiple rules share ONE firewall — record it once");
    }

    /// REQ-5.1.8.2 — elected LB with no explicit block auto-opens app_http on BOTH protocols so
    /// HTTP/3 works out of the box.
    @Test
    void execute_electedLbWithoutFirewallBlock_autoOpensAppHttpOnBothProtocols() {
        var compute = new RecordingCompute();

        var result = run(contextWith(hetznerSource("eu-1", LoadBalancerMode.ELECTED, List.of())), compute);

        assertTrue(result.isSuccess(), () -> "phase should succeed: " + result);
        assertEquals(List.of("tcp", "udp"), compute.opened.stream().map(OpenCall::protocol).toList());
        assertEquals(List.of(8070, 8070), compute.opened.stream().map(OpenCall::port).toList(),
                     "auto-created rules target app_http (default 8070)");
        assertEquals(List.of("0.0.0.0/0", "0.0.0.0/0"), compute.opened.stream().map(OpenCall::cidr).toList());
    }

    /// An explicit block wins — auto-creation exists only to keep an elected LB reachable, never to
    /// widen what the operator declared.
    @Test
    void execute_electedLbWithExplicitBlock_doesNotAutoOpenAppHttp() {
        var rules = List.of(FirewallRule.firewallRule(9443, "tcp", "10.0.0.0/8", Option.empty()));
        var compute = new RecordingCompute();

        run(contextWith(hetznerSource("eu-1", LoadBalancerMode.ELECTED, rules)), compute);

        assertEquals(List.of(9443), compute.opened.stream().map(OpenCall::port).toList(),
                     "explicit rules replace the auto-created pair entirely");
    }

    /// REQ-5.1.8.3 — cluster (8090) and management (8080) stay operator-managed. Aether must never
    /// open them, consistent with `[infrastructure.networking] type = "manual"`.
    @Test
    void execute_neverOpensClusterOrManagementPorts() {
        var compute = new RecordingCompute();

        run(contextWith(hetznerSource("eu-1", LoadBalancerMode.ELECTED, List.of())), compute);

        assertTrue(compute.opened.stream().noneMatch(call -> call.port() == 8090 || call.port() == 8080),
                   () -> "cluster/management ports are operator-managed, but got: " + compute.opened);
    }

    /// No rules and no elected LB — nothing to do, and no provider call at all.
    @Test
    void execute_sourceWithoutRulesOrElectedLb_issuesNoProviderCall() {
        var compute = new RecordingCompute();

        var result = run(contextWith(hetznerSource("eu-1", LoadBalancerMode.NONE, List.of())), compute);

        assertTrue(result.isSuccess(), () -> "phase should succeed: " + result);
        assertTrue(compute.opened.isEmpty(), "no declared ingress must mean no provider call");
    }

    /// A refused openIngress must FAIL the phase. Continuing would provision nodes believing rules
    /// are in force when they are not — the failure mode this whole change exists to remove.
    @Test
    void execute_whenProviderRefusesIngress_failsPhase() {
        var rules = List.of(FirewallRule.firewallRule(8070, "tcp", "0.0.0.0/0", Option.empty()));
        var ctx = contextWith(hetznerSource("eu-1", LoadBalancerMode.NONE, rules));

        var result = BootstrapPhaseFirewall.execute(ctx,
                                                     (_, _, _, _) -> Result.success(new RefusingCompute()));

        assertTrue(result.isFailure(), "a refused ingress rule must abort bootstrap, not proceed");
    }

    private static final class RefusingCompute implements ComputeProvider {
        @Override
        public Promise<InstanceInfo> createFrom(ProvisionRequest request) {
            throw new AssertionError("unused");
        }

        @Override
        public Promise<Unit> terminate(InstanceId instanceId) {
            throw new AssertionError("unused");
        }

        @Override
        public Promise<List<InstanceInfo>> listInstances() {
            throw new AssertionError("unused");
        }

        @Override
        public Promise<InstanceInfo> instanceStatus(InstanceId instanceId) {
            throw new AssertionError("unused");
        }
    }
}
