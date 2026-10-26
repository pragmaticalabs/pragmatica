// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.cluster.state.kvstore;

import java.util.List;


/// One entry of the commit ring: the plain `Put`/`Remove` commands a batch actually applied (post-merge, refused commands skipped),
/// applied at `slot`. `from` is the previous entry's `slot + 1`, so the entries form a contiguous chain. An EFFECT entry has a real batch id; a
/// RANGE MARKER has no effects, `batchId = NONE` and `slot = to - 1`, and means "batches in [from, to) applied, none with a recorded effect".
public record ReplicaEntry(IncarnationTag tag, long from, long slot, String batchId, List<KVCommand<?>> effects) {
    public static final String NONE = "";

    public ReplicaEntry {
        effects = List.copyOf(effects);
    }

    public boolean isMarker() {
        return effects.isEmpty();
    }
}
