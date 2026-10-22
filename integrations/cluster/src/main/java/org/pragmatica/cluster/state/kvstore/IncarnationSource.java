// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.cluster.state.kvstore;

import java.util.function.Function;

/// Tells the store which committed value is the cluster incarnation, without the store knowing the key family. The tag is read from storage
/// before and after every committed batch; a batch that touches an incarnation key, or changes the tag, clears the replica ring.
public interface IncarnationSource {
    /// The committed tag as `lookup` sees storage, or [IncarnationTag#NONE] when no incarnation is committed.
    IncarnationTag read(Function<StructuredKey, Object> lookup);

    /// True for the key (or keys) that carry the incarnation.
    boolean isIncarnationKey(StructuredKey key);

    /// No incarnation anywhere: the tag is always NONE, so the ring records and serves nothing.
    static IncarnationSource none() {
        return new IncarnationSource() {
            @Override
            public IncarnationTag read(Function<StructuredKey, Object> lookup) {
                return IncarnationTag.NONE;
            }

            @Override
            public boolean isIncarnationKey(StructuredKey key) {
                return false;
            }
        };
    }
}
