// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.pragmatica.aether.deployment.cluster.ClusterTopologyManager.CircuitBreakerState;
import org.pragmatica.aether.deployment.cluster.ClusterTopologyManager.LastProvisionFailure;
import org.pragmatica.aether.deployment.membership.ntt.LeaderReconciler.ProvisioningDecisionSnapshot;
import org.pragmatica.lang.Option;


/// #336 observability — assembled provisioning-diagnostics view for the management API, combining
/// the three independently-sourced facts that together explain WHY a deficit is or is not being
/// filled, without log-scraping:
///   - `decision`: the END-of-pass reconcile snapshot from the leader [`LeaderReconciler`] (trigger,
///     configured vs counted-core membership, effective capacity, the arm + reached-full-membership
///     latches, quorum safety, deficit-run age, and the precise suppression reason);
///   - `circuitBreaker`: the provisioning circuit-breaker state from the
///     [`org.pragmatica.aether.deployment.cluster.ClusterTopologyManager`] (consecutive failures,
///     trip threshold, next-allowed instant, tripped flag);
///   - `lastProvisionFailure`: the most recent provisioning failure (`cause` + epoch-millis), empty
///     when none has been recorded.
///
/// Assembled and surfaced only on a node that is the leader and owns a `ClusterTopologyManager`; the
/// node accessor returns `Option.empty()` otherwise.
public record ProvisioningDiagnostics(ProvisioningDecisionSnapshot decision,
                                      CircuitBreakerState circuitBreaker,
                                      Option<LastProvisionFailure> lastProvisionFailure) {}
