// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
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
        return DefaultRateGuard.defaultRateGuard(config)
                               .<RateGuard> map(guard -> guard)
                               .async();
    }
}
