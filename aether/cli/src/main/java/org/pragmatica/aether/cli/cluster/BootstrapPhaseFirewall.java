// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.pragmatica.aether.cli.cluster.ClusterBootstrapOrchestrator.BootstrapContext;
import org.pragmatica.aether.config.cluster.CloudProviderName;
import org.pragmatica.aether.config.cluster.FirewallRule;
import org.pragmatica.aether.config.cluster.LoadBalancerMode;
import org.pragmatica.aether.config.cluster.SourceProfile;
import org.pragmatica.aether.config.cluster.SourceType;
import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.FirewallId;
import org.pragmatica.aether.environment.FirewallName;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.pragmatica.aether.cli.cluster.BootstrapPhase.CREATE_FIREWALL;
import static org.pragmatica.lang.Result.success;


/// Phase 2a — turn each cloud source's `[source.X.firewall] allow_ingress` into real provider
/// ingress rules (spec REQ-5.1.8.1/.2, §6.2), recording every created firewall so
/// `aether cluster destroy` reclaims it.
///
/// Runs BEFORE [BootstrapPhase#PROVISION] on purpose. The created firewall's id is threaded into
/// server-create via `firewall_ids`, so the rules are in force before the instance exists. Opening
/// ingress after create would leave a window in which the node is up and — per §6.2, a Hetzner
/// server with no firewall association accepts ALL inbound traffic — fully open.
///
/// **Bootstrap-only.** Editing `allow_ingress` on an existing cluster does NOT re-run this phase,
/// and `ClusterConfigApplier` currently no-ops the corresponding `DiffAction` while logging
/// "Applied config action" (#578). Until that lands, firewall config is honoured at bootstrap and
/// silently discarded on edit.
@SuppressWarnings({"JBCT-SEQ-01", "JBCT-UTIL-02"})
public sealed interface BootstrapPhaseFirewall {
    record unused() implements BootstrapPhaseFirewall {}

    /// REQ-5.1.8.3 — the cluster (8090) and management (8080) ports are OPERATOR-managed and must
    /// never be opened here, consistent with `[infrastructure.networking] type = "manual"`.
    /// Only `app_http` is ever auto-opened, and only under REQ-5.1.8.2.
    String HETZNER_PROVIDER = "hetzner";
    String AWS_PROVIDER = "aws";

    static Result<BootstrapContext> execute(BootstrapContext ctx) {
        return execute(ctx, ProviderResolver::resolveCloudCompute);
    }

    /// `computeResolver` is a seam so tests drive the phase without a real cloud call.
    static Result<BootstrapContext> execute(BootstrapContext ctx, ComputeResolver computeResolver) {
        var sources = ctx.config().sources();
        var applicable = sources.values().stream().filter(BootstrapPhaseFirewall::managesIngressFor).count();

        ClusterBootstrapOrchestrator.logPhase(CREATE_FIREWALL,
                                              "Applying ingress firewall rules for %d cloud source(s)",
                                              applicable);
        warnIfIngressUnmanaged(ctx);
        if (applicable == 0) {
            return success(ctx);
        }

        return applyForAllSources(ctx, computeResolver);
    }

    /// #615 — REQ-5.1.8.2's auto-open AND the warning it dictates were both Hetzner-only, because the
    /// entire phase is gated on [#managesIngressFor]. An elected LB on AWS/GCP/Azure therefore had its
    /// `app_http` port neither opened nor mentioned: the operator saw a clean bootstrap and a load
    /// balancer that served nothing, with no line anywhere pointing at ingress.
    ///
    /// Deliberately called BEFORE the `applicable == 0` early return — a cluster whose only cloud source
    /// is non-Hetzner takes exactly that path, so a warning emitted after it would never fire for the
    /// case it exists to cover.
    ///
    /// A WARNING, not an error. Security groups / VPC firewall rules / network security groups all deny
    /// inbound by default, so such a node is UNREACHABLE rather than exposed, and managing ingress
    /// yourself there is the supported arrangement PF-23 explicitly directs operators to. The defect is
    /// the silence, so silence is what this fixes.
    private static void warnIfIngressUnmanaged(BootstrapContext ctx) {
        var appHttpPort = ctx.config().operations().ports().appHttp();

        ctx.config().sources().forEach((name, source) -> warnIfElectedWithoutIngress(name, source, appHttpPort));
    }

    private static void warnIfElectedWithoutIngress(String name, SourceProfile source, int appHttpPort) {
        if (manages(source) || source.type() != SourceType.CLOUD || source.loadBalancer() != LoadBalancerMode.ELECTED) {
            return;
        }

        System.out.printf("  WARN: Source '%s' has an elected load balancer, but provider '%s' has no Aether-managed"
                         + " ingress, so app_http port %d was NOT opened. Open it yourself (AWS security group / GCP"
                         + " VPC firewall rule / Azure network security group) or the elected LB will not serve"
                         + " traffic.%n",
                          name,
                          providerName(source),
                          appHttpPort);
    }

    /// Providers whose `openIngress` is implemented. Adding one here is what makes the CREATE_FIREWALL
    /// phase actually run for it — and what silences the #615 "no ingress was opened" warning, since that
    /// warning exists precisely for providers NOT in this set.
    Set<String> MANAGES_INGRESS = Set.of(HETZNER_PROVIDER, AWS_PROVIDER);

    private static boolean manages(SourceProfile source) {
        return MANAGES_INGRESS.contains(providerName(source));
    }

    private static String providerName(SourceProfile source) {
        return source.provider()
                     .map(CloudProviderName::value)
                     .or("unknown");
    }

    /// The resolver seam: (source, clusterName) → a provider able to manage ingress.
    interface ComputeResolver {
        Result<ComputeProvider> resolve(SourceProfile source,
                                        List<Long> sshKeyIds,
                                        String userData,
                                        ClusterName clusterName);
    }

    @SuppressWarnings("JBCT-PAT-01")
    private static Result<BootstrapContext> applyForAllSources(BootstrapContext ctx, ComputeResolver computeResolver) {
        var nextCtx = ctx;

        for (var entry : ctx.config().sources().entrySet()) {
            var source = entry.getValue();

            if (!managesIngressFor(source)) {
                continue;
            }

            var result = applyForSource(nextCtx, source, computeResolver);

            if (result.isFailure()) {
                return result;
            }

            nextCtx = result.unwrap();
        }

        return success(nextCtx);
    }

    /// Only Hetzner cloud sources are managed. AWS/GCP/Azure are rejected earlier, at pre-flight
    /// (PF-23) — their `openIngress` is an honest refusal and their default security groups deny
    /// inbound, so an unmanaged source there is unreachable rather than exposed (§6.2).
    private static boolean managesIngressFor(SourceProfile source) {
        return source.type() == SourceType.CLOUD
               && manages(source)
               && !effectiveRules(source, 0).isEmpty();
    }

    private static Result<BootstrapContext> applyForSource(BootstrapContext ctx,
                                                           SourceProfile source,
                                                           ComputeResolver computeResolver) {
        var appHttpPort = ctx.config().operations().ports().appHttp();
        var rules = effectiveRules(source, appHttpPort);

        warnIfAutoCreated(source, appHttpPort);

        return computeResolver.resolve(source,
                                       ctx.sshKeyIdsFor(HETZNER_PROVIDER),
                                       "",
                                       ctx.config().cluster().name())
                              .flatMap(compute -> openAll(ctx, source, compute, rules));
    }

    @SuppressWarnings("JBCT-PAT-01")
    private static Result<BootstrapContext> openAll(BootstrapContext ctx,
                                                    SourceProfile source,
                                                    ComputeProvider compute,
                                                    List<FirewallRule> rules) {
        var firewallIds = new LinkedHashSet<FirewallId>();
        var nextCtx = ctx;

        for (var rule : rules) {
            for (var protocol : expandProtocols(rule.protocol())) {
                var currentCtx = nextCtx;
                var opened = compute.openIngress(source.name(),
                                                 rule.port(),
                                                 protocol,
                                                 rule.sourceCidr(),
                                                 rule.description().or(defaultDescription(source, rule)))
                                    .await();

                if (opened.isFailure()) {
                    return opened.map(_ -> currentCtx);
                }
                // The handle's id is provider-opaque text; a BLANK one would reach destroy's delete
                // call and match nothing, so the firewall would keep billing while the ledger claimed
                // it was reclaimed. Fail the phase instead — the orchestrator's cleanup then runs
                // while the just-created firewall is still the newest resource on the account.
                var id = FirewallId.firewallId(opened.unwrap().providerResourceId());

                if (id.isFailure()) {
                    return id.map(_ -> currentCtx);
                }
                // Every rule of a source lands on ONE firewall, so the record is added once — the
                // set collapses the repeats a multi-rule (or "tcp+udp") source produces. Recording
                // per-rule would make destroy issue N deletes for one resource.
                if (firewallIds.add(id.unwrap())) {
                    nextCtx = recordFirewall(nextCtx, source, id.unwrap());
                }
            }
        }

        return success(nextCtx.withFirewallIds(source.name(), List.copyOf(firewallIds)));
    }

    private static BootstrapContext recordFirewall(BootstrapContext ctx, SourceProfile source, FirewallId firewallId) {
        System.out.printf("  Ingress firewall %s in force for source '%s'%n", firewallId, source.name());
        // The name is DERIVED, never assembled here: FirewallName.forSource is the same derivation
        // the provider uses to name the firewall it creates, so the ledger records the name that is
        // actually on the resource — including its truncation to the 63 characters every cloud
        // accepts, which a locally concatenated string did not apply.
        var resource = CreatedResource.CloudFirewall.cloudFirewall(HETZNER_PROVIDER,
                                                                   firewallId,
                                                                   source.name(),
                                                                   FirewallName.forSource(ctx.config().cluster().name(),
                                                                                          source.name()));

        return ctx.withState(ctx.state().withResource(resource));
    }

    /// REQ-5.1.8.1 — a `"tcp+udp"` entry expands to TWO provider-level rules, one per protocol.
    private static List<String> expandProtocols(String protocol) {
        return "tcp+udp".equals(protocol)
               ? List.of("tcp", "udp")
               : List.of(protocol);
    }

    /// The rules actually applied: the operator's explicit `allow_ingress` when present, else the
    /// REQ-5.1.8.2 auto-created pair for an elected load balancer. `appHttpPort` of 0 is the
    /// applicability probe — it only needs to know WHETHER rules exist, not which port.
    private static List<FirewallRule> effectiveRules(SourceProfile source, int appHttpPort) {
        if (!source.firewallRules().isEmpty()) {
            return source.firewallRules();
        }

        if (source.loadBalancer() != LoadBalancerMode.ELECTED) {
            return List.of();
        }
        // Both protocols so HTTP/3 works out of the box (REQ-5.1.8.2).
        return List.of(FirewallRule.firewallRule(appHttpPort,
                                                 "tcp",
                                                 "0.0.0.0/0",
                                                 Option.some("auto: elected LB app_http")),
                       FirewallRule.firewallRule(appHttpPort,
                                                 "udp",
                                                 "0.0.0.0/0",
                                                 Option.some("auto: elected LB app_http")));
    }

    /// REQ-5.1.8.2 dictates this warning verbatim — an operator who did not ask for a wide-open
    /// port must be told one was opened, and told how to scope it.
    private static void warnIfAutoCreated(SourceProfile source, int appHttpPort) {
        if (!source.firewallRules().isEmpty() || source.loadBalancer() != LoadBalancerMode.ELECTED) {
            return;
        }

        System.out.printf("  WARN: Created permissive firewall rules (TCP+UDP) for elected LB on `%s`;"
                         + " declare `[source.%s.firewall]` to scope. (port %d, 0.0.0.0/0)%n",
                          source.name(),
                          source.name(),
                          appHttpPort);
    }

    private static String defaultDescription(SourceProfile source, FirewallRule rule) {
        return "aether " + source.name() + " " + rule.port();
    }
}
