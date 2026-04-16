// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.Set;


/// Manages floating IPs for elected load balancers. §11.1a
public interface FloatingIpProvider {
    Promise<Unit> attach(String floatingIp, String targetNodeId);
    Promise<IpOwnership> verify(String floatingIp);
    Promise<Set<String>> compatibleZones(String floatingIp);
}
