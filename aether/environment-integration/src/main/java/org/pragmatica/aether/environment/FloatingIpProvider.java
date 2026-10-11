// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.environment;

import java.util.Set;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


public interface FloatingIpProvider {
    Promise<Unit> attach(String floatingIp, String targetNodeId);
    Promise<IpOwnership> verify(String floatingIp);
    Promise<Set<String>> compatibleZones(String floatingIp);
}
