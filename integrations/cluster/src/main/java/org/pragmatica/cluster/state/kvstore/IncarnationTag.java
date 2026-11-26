// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.cluster.state.kvstore;

import org.pragmatica.serialization.Codec;


/// The identity of one cluster life: the committed cluster incarnation number, its per-incarnation id and the lineage it belongs to. Tags
/// compare by EQUALITY of all three, never by number alone: a re-minted incarnation 1 is a different life. The number orders only WITHIN
/// one lineage; `incarnationId` names this particular incarnation; `lineageId` names the cluster lineage the number counts in. [#NONE] (no incarnation committed yet) matches nothing, not even
/// another NONE, so two unrelated pre-genesis clusters never compare equal.
@Codec
public record IncarnationTag(long incarnation, String incarnationId, String lineageId) {
    public static final IncarnationTag NONE = new IncarnationTag(0L, "", "");

    public static IncarnationTag incarnationTag(long incarnation, String incarnationId, String lineageId) {
        return new IncarnationTag(incarnation, incarnationId, lineageId);
    }

    public boolean isNone() {
        return incarnationId.isEmpty();
    }

    /// True only when neither tag is [#NONE] and all parts are equal.
    public boolean matches(IncarnationTag other) {
        return ! isNone()
               && !other.isNone()
               && equals(other);
    }
}
