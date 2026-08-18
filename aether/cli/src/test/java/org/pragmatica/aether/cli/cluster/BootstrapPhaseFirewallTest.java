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

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

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

    /// #615 — REQ-5.1.8.2's auto-open and the warning it mandates were BOTH gated on `managesIngressFor`,
    /// which requires Hetzner. An elected LB on AWS/GCP/Azure got its app_http port neither opened nor
    /// mentioned: a clean-looking bootstrap and an LB that serves nothing.
    @Test
    void execute_electedLbOnProviderWithoutIngress_warnsThatPortWasNotOpened() {
        var ctx = contextWith(cloudSource("us-1", CloudProviderName.AWS, LoadBalancerMode.ELECTED, List.of()));

        var output = captureStdoutOf(() -> BootstrapPhaseFirewall.execute(ctx,
                                                                           (_, _, _, _) -> Result.success(new RefusingCompute()))).output();

        assertTrue(output.contains("WARN"), "the operator must be told, not left with a silent no-op: " + output);
        assertTrue(output.contains("us-1"), "the warning must name the source: " + output);
        assertTrue(output.contains("aws"), "the warning must name the provider: " + output);
        assertTrue(output.contains("NOT opened"), "the warning must say the port was not opened: " + output);
    }

    /// The regression that matters: such a cluster has ZERO manageable sources, so it takes the
    /// `applicable == 0` early return. A warning emitted after that return would never fire.
    @Test
    void execute_electedLbOnProviderWithoutIngress_warnsEvenThoughNoSourceIsManageable() {
        var ctx = contextWith(cloudSource("gcp-1", CloudProviderName.GCP, LoadBalancerMode.ELECTED, List.of()));

        var result = captureStdoutOf(() -> BootstrapPhaseFirewall.execute(ctx,
                                                                           (_, _, _, _) -> Result.success(new RefusingCompute())));

        assertTrue(result.value().isSuccess(), "an unmanageable source is not an error, only a warning");
        assertTrue(result.output().contains("WARN"), "the early return must not swallow the warning: " + result.output());
    }

    @Test
    void execute_hetznerElectedLb_doesNotEmitTheUnmanagedIngressWarning() {
        // Hetzner DOES auto-open app_http here, so it must get REQ-5.1.8.2's own warning instead of the
        // "not opened" one — asserting the absence keeps the two paths from converging on one message.
        var ctx = contextWith(hetznerSource("eu-1", LoadBalancerMode.ELECTED, List.of()));

        var output = captureStdoutOf(() -> BootstrapPhaseFirewall.execute(ctx,
                                                                           (_, _, _, _) -> Result.success(new RecordingCompute()))).output();

        assertTrue(!output.contains("NOT opened"), "Hetzner opens the port, so it must not claim otherwise: " + output);
    }

    @Test
    void execute_nonElectedLbOnProviderWithoutIngress_staysSilent() {
        // Nothing was promised, so there is nothing to warn about — the warning must not become noise on
        // every non-Hetzner cloud source.
        var ctx = contextWith(cloudSource("us-2", CloudProviderName.AWS, LoadBalancerMode.NONE, List.of()));

        var output = captureStdoutOf(() -> BootstrapPhaseFirewall.execute(ctx,
                                                                           (_, _, _, _) -> Result.success(new RefusingCompute()))).output();

        assertTrue(!output.contains("WARN"), "no elected LB means no unopened-port promise: " + output);
    }

    private static SourceProfile cloudSource(String name,
                                             CloudProviderName provider,
                                             LoadBalancerMode lbMode,
                                             List<FirewallRule> rules) {
        return SourceProfile.sourceProfile(name,
                                            SourceType.CLOUD,
                                            Option.some(provider),
                                            Option.some("token"),
                                            Option.some("us-east-1"),
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

    /// The warning IS the behaviour under test, and it is written to stdout in the same style as
    /// REQ-5.1.8.2's mandated warning beside it — so stdout is what has to be asserted on.
    private record Captured(String output, Result<BootstrapContext> value) {}

    private static Captured captureStdoutOf(Supplier<Result<BootstrapContext>> action) {
        var original = System.out;
        var buffer = new ByteArrayOutputStream();

        try (var stream = new PrintStream(buffer, true, StandardCharsets.UTF_8)) {
            System.setOut(stream);

            var value = action.get();

            return new Captured(buffer.toString(StandardCharsets.UTF_8), value);
        } finally {
            System.setOut(original);
        }
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
