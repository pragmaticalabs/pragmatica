// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.cluster.state.kvstore;

/// Decides which keys a worker replica is fed and snapshotted from. One definition used by the replica ring's append AND by the replica
/// snapshot capture. The aether implementation is an exhaustive switch over the sealed key type.
public interface ReplicaKeyFilter {
    /// True when the key is EXCLUDED from the replica.
    boolean excludes(StructuredKey key);

    static ReplicaKeyFilter includeAll() {
        return _ -> false;
    }
}
