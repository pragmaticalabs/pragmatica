// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment;

import java.util.List;
import java.util.Map;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;


public interface ComputeProvider {
    /// The single per-provider provisioning surface (RFC-0016 §2): translate a fully-resolved,
    /// provider-agnostic [ProvisionRequest] into a native create call. [ProvisionRequest#resolve]
    /// has already applied the precedence (spec field > provider config > loud fallback), so this
    /// performs NO re-derivation — every field it needs is on the request. This is the ONLY
    /// provisioning method a provider implements; [#provision(ProvisionSpec)] is a non-overridable
    /// boundary that resolves then delegates here.
    Promise<InstanceInfo> createFrom(ProvisionRequest request);
    Promise<Unit> terminate(InstanceId instanceId);
    Promise<List<InstanceInfo>> listInstances();
    Promise<InstanceInfo> instanceStatus(InstanceId instanceId);

    default Promise<Unit> restart(InstanceId id) {
        return EnvironmentError.operationNotSupported("restart").promise();
    }

    default Promise<Unit> applyTags(InstanceId id, Map<String, String> tags) {
        return EnvironmentError.operationNotSupported("applyTags").promise();
    }

    /// Ensure ONE ingress rule is in force for `sourceId`, returning the provider resource that
    /// carries it (spec REQ-5.1.8.4). MUST be create-or-patch and idempotent: a `"tcp+udp"` entry
    /// expands to two rules (REQ-5.1.8.1) and every rule of a source lands on the SAME provider
    /// resource, so the second call patches what the first created and returns the same
    /// [IngressHandle].
    ///
    /// The handle exists so the caller can apply the rule AT instance-create. Do NOT implement this
    /// as a post-create mutation: on Hetzner an unassociated server accepts all inbound traffic
    /// (§6.2), so opening ingress after the fact leaves the node briefly wide open.
    ///
    /// Rules the caller did not name are left untouched (REQ-5.1.8.1) — this never reconciles a
    /// provider resource down to the requested set.
    ///
    /// Defaults to a loud refusal: a provider that cannot manage ingress must FAIL rather than let
    /// the caller believe a rule is in force. Operators of such providers manage ingress themselves
    /// (§6.2), and pre-flight rejects the config before this is ever reached.
    default Promise<IngressHandle> openIngress(String sourceId,
                                               int port,
                                               String protocol,
                                               String sourceCidr,
                                               String description) {
        return EnvironmentError.operationNotSupported("openIngress").promise();
    }

    /// Withdraw one rule previously placed by [#openIngress]. Removing the last rule of a source
    /// disposes the provider resource itself.
    ///
    /// MUST only ever touch resources this provider created — see `CreatedResource` tracking and
    /// the 2026-08-03 test-pg incident (#572), where an unscoped cleanup deleted standing shared
    /// infrastructure.
    default Promise<Unit> closeIngress(String sourceId, int port, String protocol, String sourceCidr) {
        return EnvironmentError.operationNotSupported("closeIngress").promise();
    }

    default Promise<List<InstanceInfo>> listInstances(Map<String, String> tagFilter) {
        return listInstances().map(instances -> filterByTags(instances, tagFilter));
    }

    /// Config-level fallbacks consumed by [ProvisionRequest#resolve] — the provider's second
    /// precedence tier (instance size / image / zone / user-data) plus its stock image fallback and
    /// image capability. A provider that resolves everything from the spec may keep the inert
    /// [ProviderDefaults#none]; any provider with config-level defaults overrides this.
    default ProviderDefaults providerDefaults() {
        return ProviderDefaults.none();
    }

    /// The provisioning boundary every producer (bootstrap seed, `CloudProviderSupport`, CTM
    /// auto-heal) funnels through: the static, non-overridable [ProvisionRequest#resolve] choke then
    /// [#createFrom]. Providers implement ONLY createFrom — this method is not overridden by any
    /// provider, so resolution can never be re-opened per-provider (the #442/#459 defect class).
    default Promise<InstanceInfo> provision(ProvisionSpec spec) {
        return ProvisionRequest.resolve(spec,
                                        providerDefaults())
                               .async()
                               .flatMap(this::createFrom);
    }

    /// Convenience seed entry (bootstrap primitive / tests): provision a core-role node from the
    /// provider's defaults, routed through the [#provision(ProvisionSpec)] boundary. A provider that
    /// needs a provider-specific seed (e.g. Docker's `default` cluster) overrides this; the rest
    /// inherit the generic core seed. The seed context carries NO cluster name — [Option#empty],
    /// not a placeholder that a label sweep could later fail to distinguish — and production
    /// provisioning flows through `buildCloudProvisionSpec`, which stamps the real cluster — and
    /// [SourceName#DEFAULT] as its source, which no source-scoped selector resolves. The seed used to
    /// carry a blank source and therefore no `aether-source` label at all; a cloud provider reached
    /// through here is refused at its cluster-label precondition either way.
    default Promise<InstanceInfo> provision(InstanceType instanceType) {
        return seedSpec(instanceType).async()
                       .flatMap(this::provision);
    }

    private static Result<ProvisionSpec> seedSpec(InstanceType instanceType) {
        var context = ProvisionContext.provisionContext(Option.<ClusterName> empty(),
                                                        "core",
                                                        SourceName.DEFAULT,
                                                        ProvisionContext.PROVISIONED_BY_BOOTSTRAP);

        return ProvisionSpec.provisionSpec(instanceType, "", "core", context);
    }

    default Promise<List<InstanceInfo>> listInstances(TagSelector selector) {
        return listInstances(selector.requiredTags());
    }

    @Contract
    default void resetProvisionerState(Option<ClusterName> clusterName) {}

    /// Confirm INFRASTRUCTURE readiness of a freshly-created instance: poll
    /// [#instanceStatus] (this provider's OWN primitive) until it reports
    /// [InstanceStatus#RUNNING], bounded by [ReadinessPolicy#timeout]. On success the
    /// original [#provision] [InstanceInfo] is returned re-stamped to RUNNING; on a
    /// boot-crash (STOPPING/TERMINATED) or timeout the returned [Promise] FAILS with
    /// [EnvironmentError.ProvisionReadinessTimeout] so the caller (CTM) frees the slot
    /// instead of minting a phantom node. Readiness here is infra-only — it does NOT
    /// wait for cluster-join / first-pong / KV-registration (that is CTM's concern).
    default Promise<InstanceInfo> confirmRunning(InstanceInfo created, ReadinessPolicy policy) {
        return pollUntilRunning(created, policy).timeout(policy.timeout())
                               .mapError(cause -> toReadinessTimeout(created, policy, cause));
    }

    private Promise<InstanceInfo> pollUntilRunning(InstanceInfo created, ReadinessPolicy policy) {
        return instanceStatus(created.id()).flatMap(observed -> routeByStatus(created, observed, policy));
    }

    private Promise<InstanceInfo> routeByStatus(InstanceInfo created, InstanceInfo observed, ReadinessPolicy policy) {
        return switch (observed.status()) {
            case InstanceStatus.Running ignored -> Promise.success(created.withStatus(InstanceStatus.RUNNING));
            case InstanceStatus.Provisioning ignored -> retryPoll(created, policy);
            default -> ComputeProviderLog.bootCrashed(created.id(), observed.status()).promise();
        };
    }

    private Promise<InstanceInfo> retryPoll(InstanceInfo created, ReadinessPolicy policy) {
        return Promise.promise(policy.pollInterval(),
                               Result::unitResult)
                      .flatMap(ignored -> pollUntilRunning(created, policy));
    }

    private static Cause toReadinessTimeout(InstanceInfo created, ReadinessPolicy policy, Cause cause) {
        return switch (cause) {
            case EnvironmentError.ProvisionReadinessTimeout ignored -> cause;
            default -> ComputeProviderLog.readinessTimeout(created.id(),
                                                           policy.timeout().millis(),
                                                           cause);
        };
    }

    private static List<InstanceInfo> filterByTags(List<InstanceInfo> instances, Map<String, String> tagFilter) {
        return instances.stream()
                        .filter(instance -> matchesTags(instance, tagFilter))
                        .toList();
    }

    private static boolean matchesTags(InstanceInfo instance, Map<String, String> tagFilter) {
        return tagFilter.entrySet()
                        .stream()
                        .allMatch(entry -> entry.getValue()
                                                .equals(instance.tags().get(entry.getKey())));
    }
}
