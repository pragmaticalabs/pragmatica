// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.environment;

import java.util.Set;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import static org.pragmatica.aether.environment.IpOwnership.ipOwnership;


public record NoopFloatingIpProvider() implements FloatingIpProvider {
    private static final IpOwnership LOCAL_OWNERSHIP = ipOwnership(true, "localhost");
    private static final Set<String> LOCAL_ZONES = Set.of("local");

    public static NoopFloatingIpProvider noopFloatingIpProvider() {
        return new NoopFloatingIpProvider();
    }

    @Override
    public Promise<Unit> attach(String floatingIp, String targetNodeId) {
        return Promise.unitPromise();
    }

    @Override
    public Promise<IpOwnership> verify(String floatingIp) {
        return Promise.success(LOCAL_OWNERSHIP);
    }

    @Override
    public Promise<Set<String>> compatibleZones(String floatingIp) {
        return Promise.success(LOCAL_ZONES);
    }
}
