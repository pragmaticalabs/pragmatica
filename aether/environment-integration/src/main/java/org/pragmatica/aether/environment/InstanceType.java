// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment;

import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.success;


public sealed interface InstanceType {
    record OnDemand() implements InstanceType {
        public static Result<OnDemand> onDemand() {
            return success(new OnDemand());
        }
    }

    record Spot() implements InstanceType {
        public static Result<Spot> spot() {
            return success(new Spot());
        }
    }

    InstanceType ON_DEMAND = OnDemand.onDemand().unwrap();
    InstanceType SPOT = Spot.spot().unwrap();

    record unused() implements InstanceType {
        public static Result<unused > unused() {
            return success(new unused());
        }
    }
}
