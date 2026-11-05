// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import org.pragmatica.lang.Option;


/// One VM row of the ledger. The invariant of the apply: every billed VM has a row at or before the moment it
/// can exist (cluster-apply spec 4.2). `serverId` is empty until the provider has answered.
public record ApplyResource(String actionKey, String nodeId, String provider, Option<String> serverId, State state) {
    public enum State {
        INTENDED,
        CREATED,
        JOINED,
        TERMINATING,
        TERMINATE_FAILED,
        TERMINATED,
        ABSENT,
        GONE,
        ADOPTED;
        /// True when a VM may still exist at the provider and is billed. TERMINATING counts: a kill between
        /// the TERMINATING write and the provider's answer leaves the VM standing.
        public boolean holdsVm() {
            return switch (this) {
                case CREATED, JOINED, ADOPTED, TERMINATING, TERMINATE_FAILED -> true;
                default -> false;
            };
        }
    }

    public static ApplyResource applyResource(String actionKey,
                                              String nodeId,
                                              String provider,
                                              Option<String> serverId,
                                              State state) {
        return new ApplyResource(actionKey, nodeId, provider, serverId, state);
    }
}
