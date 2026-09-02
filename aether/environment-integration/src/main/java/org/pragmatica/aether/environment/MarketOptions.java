// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.success;


/// Purchasing-market options carried on a resolved [ProvisionRequest]. The coarse
/// [InstanceType] marker on the request records ON_DEMAND vs SPOT; this type carries the
/// richer spot parameters a provider needs to place a spot/preemptible instance (price cap
/// and interruption behavior). [ProvisionRequest#resolve] mints [#ON_DEMAND] for non-spot
/// roles and [#spot] for a `spot` role. Only the AWS spot arm consumes the spot parameters
/// today (RFC-0016 §2.5-ii); other providers ignore them.
public sealed interface MarketOptions {
    record OnDemand() implements MarketOptions {
        public static Result<OnDemand> onDemand() {
            return success(new OnDemand());
        }
    }

    record Spot(Option<String> maxPrice, InterruptionBehavior interruptionBehavior) implements MarketOptions {
        public static Result<Spot> spot(Option<String> maxPrice, InterruptionBehavior interruptionBehavior) {
            return success(new Spot(maxPrice, interruptionBehavior));
        }
    }

    /// Behavior on spot interruption, mirroring the EC2 spot vocabulary. In rc3 reclamation
    /// is handled as abrupt node failure via auto-heal (no preemption-notice drain), so the
    /// default is [#TERMINATE].
    enum InterruptionBehavior {
        TERMINATE,
        STOP,
        HIBERNATE
    }

    MarketOptions ON_DEMAND = OnDemand.onDemand().unwrap();

    /// Default spot options: no price cap (accept the on-demand-capped market rate) and
    /// terminate on interruption. Richer per-role spot configuration rides W2.
    static MarketOptions spot() {
        return Spot.spot(Option.empty(),
                         InterruptionBehavior.TERMINATE)
                   .unwrap();
    }
}
