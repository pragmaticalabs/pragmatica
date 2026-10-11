// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
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
