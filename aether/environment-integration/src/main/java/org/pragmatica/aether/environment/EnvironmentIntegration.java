// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment;

import java.util.ServiceLoader;
import java.util.function.Predicate;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.net.tcp.security.CertificateProvider;

import static org.pragmatica.lang.Option.empty;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;


public interface EnvironmentIntegration {
    Option<EnvironmentIntegration> SPI = Option.from(ServiceLoader.load(EnvironmentIntegration.class).findFirst());

    Option<ComputeProvider> compute();

    /// Explicit in-process/local provisioning capability. Cloud integrations never opt into this
    /// escape hatch; it permits fresh local clusters before committed source configuration exists.
    default Option<ComputeProvider> localCompute() {
        return empty();
    }

    /// Trusted in-process harness admission, independent of peer-supplied role labels.
    /// Production environments leave this denied and use committed provisioning intent.
    default boolean isLocalCoreAdmissionAuthorized(String nodeId) {
        return false;
    }

    default boolean isLocalWorkerAdmissionAuthorized(String nodeId) {
        return false;
    }

    Option<SecretsProvider> secrets();
    Option<LoadBalancerProvider> loadBalancer();

    default Option<DiscoveryProvider> discovery() {
        return empty();
    }

    default Option<CertificateProvider> certificateProvider() {
        return empty();
    }

    default Option<DnsProvider> dns() {
        return empty();
    }

    default Option<FloatingIpProvider> floatingIp() {
        return empty();
    }

    static EnvironmentIntegration withLocalCompute(ComputeProvider provider) {
        return withLocalCompute(provider, _ -> false);
    }

    static EnvironmentIntegration withLocalCompute(ComputeProvider provider, Predicate<String> coreAdmissions) {
        return withLocalCompute(provider, coreAdmissions, _ -> false);
    }

    static EnvironmentIntegration withLocalCompute(ComputeProvider provider,
                                                   Predicate<String> coreAdmissions,
                                                   Predicate<String> workerAdmissions) {
        record LocalEnvironment(ComputeProvider provider,
                                Predicate<String> coreAdmissions,
                                Predicate<String> workerAdmissions) implements EnvironmentIntegration {
            @Override
            public boolean isLocalCoreAdmissionAuthorized(String nodeId) {
                return coreAdmissions.test(nodeId);
            }

            @Override
            public boolean isLocalWorkerAdmissionAuthorized(String nodeId) {
                return workerAdmissions.test(nodeId);
            }

            @Override
            public Option<ComputeProvider> compute() {
                return some(provider);
            }

            @Override
            public Option<ComputeProvider> localCompute() {
                return some(provider);
            }

            @Override
            public Option<SecretsProvider> secrets() {
                return empty();
            }

            @Override
            public Option<LoadBalancerProvider> loadBalancer() {
                return empty();
            }
        }

        return new LocalEnvironment(provider, coreAdmissions, workerAdmissions);
    }

    static EnvironmentIntegration withCompute(ComputeProvider compute) {
        return environmentIntegration(some(compute), empty(), empty(), empty(), empty(), empty(), empty());
    }

    static EnvironmentIntegration environmentIntegration(Option<ComputeProvider> compute,
                                                         Option<SecretsProvider> secrets,
                                                         Option<LoadBalancerProvider> loadBalancer) {
        return environmentIntegration(compute, secrets, loadBalancer, empty(), empty(), empty(), empty());
    }

    static EnvironmentIntegration environmentIntegration(Option<ComputeProvider> compute,
                                                         Option<SecretsProvider> secrets,
                                                         Option<LoadBalancerProvider> loadBalancer,
                                                         Option<DiscoveryProvider> discovery) {
        return environmentIntegration(compute, secrets, loadBalancer, discovery, empty(), empty(), empty());
    }

    static EnvironmentIntegration environmentIntegration(Option<ComputeProvider> compute,
                                                         Option<SecretsProvider> secrets,
                                                         Option<LoadBalancerProvider> loadBalancer,
                                                         Option<DiscoveryProvider> discovery,
                                                         Option<CertificateProvider> certificateProvider) {
        return environmentIntegration(compute, secrets, loadBalancer, discovery, certificateProvider, empty(), empty());
    }

    static EnvironmentIntegration environmentIntegration(Option<ComputeProvider> compute,
                                                         Option<SecretsProvider> secrets,
                                                         Option<LoadBalancerProvider> loadBalancer,
                                                         Option<DiscoveryProvider> discovery,
                                                         Option<CertificateProvider> certificateProvider,
                                                         Option<DnsProvider> dns) {
        return environmentIntegration(compute, secrets, loadBalancer, discovery, certificateProvider, dns, empty());
    }

    static EnvironmentIntegration environmentIntegration(Option<ComputeProvider> compute,
                                                         Option<SecretsProvider> secrets,
                                                         Option<LoadBalancerProvider> loadBalancer,
                                                         Option<DiscoveryProvider> discovery,
                                                         Option<CertificateProvider> certificateProvider,
                                                         Option<DnsProvider> dns,
                                                         Option<FloatingIpProvider> floatingIp) {
        return FacetedEnvironment.facetedEnvironment(compute,
                                                     secrets,
                                                     loadBalancer,
                                                     discovery,
                                                     certificateProvider,
                                                     dns,
                                                     floatingIp).unwrap();
    }

    record FacetedEnvironment(Option<ComputeProvider> compute,
                              Option<SecretsProvider> secrets,
                              Option<LoadBalancerProvider> loadBalancer,
                              Option<DiscoveryProvider> discovery,
                              Option<CertificateProvider> certificateProvider,
                              Option<DnsProvider> dns,
                              Option<FloatingIpProvider> floatingIp) implements EnvironmentIntegration {
        public static Result<FacetedEnvironment> facetedEnvironment(Option<ComputeProvider> compute,
                                                                    Option<SecretsProvider> secrets,
                                                                    Option<LoadBalancerProvider> loadBalancer,
                                                                    Option<DiscoveryProvider> discovery,
                                                                    Option<CertificateProvider> certificateProvider,
                                                                    Option<DnsProvider> dns,
                                                                    Option<FloatingIpProvider> floatingIp) {
            return success(new FacetedEnvironment(compute,
                                                  secrets,
                                                  loadBalancer,
                                                  discovery,
                                                  certificateProvider,
                                                  dns,
                                                  floatingIp));
        }
    }
}
