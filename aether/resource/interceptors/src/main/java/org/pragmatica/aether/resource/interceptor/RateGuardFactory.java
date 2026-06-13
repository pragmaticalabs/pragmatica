// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.interceptor;

import org.pragmatica.aether.resource.ResourceFactory;
import org.pragmatica.aether.slice.RateGuard;
import org.pragmatica.lang.Promise;


public final class RateGuardFactory implements ResourceFactory<RateGuard, RateGuardConfig> {
    @Override
    public Class<RateGuard> resourceType() {
        return RateGuard.class;
    }

    @Override
    public Class<RateGuardConfig> configType() {
        return RateGuardConfig.class;
    }

    @Override
    public Promise<RateGuard> provision(RateGuardConfig config) {
        return Promise.success(DefaultRateGuard.defaultRateGuard(config));
    }
}
