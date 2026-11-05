// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node.machine;

import org.pragmatica.aether.artifact.Version;


/// The two versions a committed `VersionRoutingKey` names during a rolling update (the committed-target gate, B-154).
public record RoutingEntry(Version oldVersion, Version newVersion) {
    public static RoutingEntry routingEntry(Version oldVersion, Version newVersion) {
        return new RoutingEntry(oldVersion, newVersion);
    }

    public boolean names(Version version) {
        return oldVersion.equals(version) || newVersion.equals(version);
    }
}
