// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

/// Membership v2 / E2 — the outcome of a [`ClusterTopologyManager#provisionReplacement`] call,
/// distinguishing a real boot from a no-boot deferral so the `LeaderReconciler` can decide whether
/// its in-flight placeholder still represents an arriving VM.
///
/// The auto-heal loop stamps an in-flight placeholder BEFORE calling `provisionReplacement` and
/// counts it toward `effectiveCapacity` (so a deficit is not double-dispatched while a replacement
/// boots). That accounting is only correct when a VM is genuinely coming. Three outcomes exist:
///
/// - [`#dispatched`] — a real boot was initiated; a VM is coming, so the reconciler KEEPS the
///   in-flight placeholder until membership presence (or the TTL sweep) clears it.
/// - [`Deferred`] — NO boot happened (the provisioning circuit is open, or no healthy peers were
///   visible to seed PEERS). Nothing is coming, so the reconciler must REMOVE the placeholder to
///   keep the raw deficit visible — otherwise a phantom placeholder masks the deficit and wedges
///   auto-heal until an operator intervenes. A deferral is NOT a failure: the breaker is already
///   open (or will re-arm on its own), so no new provisioning failure is recorded.
///
/// A genuine boot FAILURE is NOT modeled here — it stays in the `Promise` failure channel so the
/// existing `onFailure(recordProvisioningFailure)` (breaker trip after 3) and the reconciler's
/// `onFailure` placeholder-removal both keep working unchanged.
public sealed interface ProvisionDisposition {
    /// A real provision boot was initiated — a VM is genuinely on its way.
    record Dispatched() implements ProvisionDisposition {}

    /// A no-boot deferral: nothing was provisioned and nothing is coming on this pass.
    record Deferred(DeferralReason reason) implements ProvisionDisposition {}

    /// Why a provision was deferred without booting anything. Observability-only — both reasons
    /// drive the SAME reconciler action (remove the placeholder, do not record a failure).
    enum DeferralReason {
        /// The #148 runaway-provisioning circuit is open; provisioning is suspended for the backoff
        /// window. Re-armed on the next tick once the window clears or a node joins.
        CIRCUIT_OPEN,
        /// No healthy peers were visible to seed the replacement's PEERS list, so a boot would be
        /// DOA. Retried on the next reconcile tick once peers reappear.
        NO_HEALTHY_PEERS
    }

    static ProvisionDisposition dispatched() {
        return new Dispatched();
    }

    static ProvisionDisposition deferred(DeferralReason reason) {
        return new Deferred(reason);
    }
}
