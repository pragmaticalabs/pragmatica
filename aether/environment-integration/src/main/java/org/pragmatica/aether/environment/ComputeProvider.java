// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment;

import java.util.List;
import java.util.Map;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;


public interface ComputeProvider {
    Promise<InstanceInfo> provision(InstanceType instanceType);
    Promise<Unit> terminate(InstanceId instanceId);
    Promise<List<InstanceInfo>> listInstances();
    Promise<InstanceInfo> instanceStatus(InstanceId instanceId);

    default Promise<Unit> restart(InstanceId id) {
        return EnvironmentError.operationNotSupported("restart").promise();
    }

    default Promise<Unit> applyTags(InstanceId id, Map<String, String> tags) {
        return EnvironmentError.operationNotSupported("applyTags").promise();
    }

    default Promise<List<InstanceInfo>> listInstances(Map<String, String> tagFilter) {
        return listInstances().map(instances -> filterByTags(instances, tagFilter));
    }

    /// The single per-provider provisioning surface (RFC-0016 §2). Providers migrated to the
    /// [ProvisionRequest] contract translate a fully-resolved, provider-agnostic request into a
    /// native create call here. [ProvisionRequest#resolve] has already applied the precedence
    /// (spec field > provider config > loud fallback), so this method performs NO re-derivation:
    /// every field it needs is on the request. The default reports the provider has not migrated.
    default Promise<InstanceInfo> createFrom(ProvisionRequest request) {
        return EnvironmentError.operationNotSupported("createFrom").promise();
    }

    /// Config-level fallbacks consumed by [ProvisionRequest#resolve]. Only meaningful for a
    /// provider with [#usesProvisionRequest] `true`; the inert default is never reached for an
    /// unported provider (it takes the legacy dispatch branch in [#provision(ProvisionSpec)]).
    default ProviderDefaults providerDefaults() {
        return ProviderDefaults.none();
    }

    /// Transitional bridge (RFC-0016 W1): a provider migrated to the [ProvisionRequest] contract
    /// overrides this to `true` and implements [#createFrom] + [#providerDefaults]. Until every
    /// provider is ported, an unported provider keeps the legacy `provision(spec.instanceType())`
    /// dispatch below. Once all providers are ported this flag and the legacy branch are removed
    /// and `provision(spec)` unconditionally routes through the static [ProvisionRequest#resolve]
    /// choke.
    default boolean usesProvisionRequest() {
        return false;
    }

    /// The provisioning boundary every producer (bootstrap seed, `CloudProviderSupport`, CTM
    /// auto-heal) funnels through. For a migrated provider it routes through the static,
    /// non-overridable [ProvisionRequest#resolve] then [#createFrom]; an unported provider keeps
    /// the legacy dispatch so in-JVM (Ember) and not-yet-ported cloud providers are unaffected.
    default Promise<InstanceInfo> provision(ProvisionSpec spec) {
        return usesProvisionRequest()
               ? ProvisionRequest.resolve(spec,
                                          providerDefaults())
                                 .async()
                                 .flatMap(this::createFrom)
               : provision(spec.instanceType());
    }

    default Promise<List<InstanceInfo>> listInstances(TagSelector selector) {
        return listInstances(selector.requiredTags());
    }

    @Contract
    default void resetProvisionerState(String clusterName) {}

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
