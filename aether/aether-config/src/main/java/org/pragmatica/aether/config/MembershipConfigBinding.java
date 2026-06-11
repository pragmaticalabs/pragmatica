// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config;

import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// TOML binding shape for the `[membership]` section (membership v2 spec §14).
///
/// **Why a separate type.** The behavioural [`org.pragmatica.aether.deployment.membership.MembershipConfig`]
/// record lives in `aether-deployment`, which depends on `aether-config`. The root TOML
/// binder ([`AetherConfig`]) lives in `aether-config` and therefore cannot import the
/// deployment-side record without inverting the module dependency direction. This binding
/// captures the raw scalar value; [`org.pragmatica.aether.Main#run`] (which sits above
/// both modules in the dependency graph) lifts a present binding into the deployment-side
/// record before it is threaded into [`org.pragmatica.aether.node.AetherNodeConfig`].
///
/// **One split timeout `T` (cluster-topology-overhaul Wave 9, item 2 — CONFIG-BREAKING).**
/// The former `ntt_departure_timeout` and `quorum_loss_drain_threshold` TOML keys are collapsed
/// into a single `split_timeout` key. `splitTimeout` governs BOTH the minority quorum-loss
/// self-drain AND the majority departure-verdict / re-provision; the no-double-active ordering
/// is preserved by the natural detection-lag between the two observation points (see
/// `MembershipConfig`), not by a second knob. Default: 15s.
public record MembershipConfigBinding(TimeSpan splitTimeout) {
    public static final TimeSpan DEFAULT_SPLIT_TIMEOUT = timeSpan(15).seconds();

    public static MembershipConfigBinding membershipConfigBinding() {
        return new MembershipConfigBinding(DEFAULT_SPLIT_TIMEOUT);
    }
}
