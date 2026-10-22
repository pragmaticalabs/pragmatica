// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.cluster.state.kvstore;

/// The identity of one cluster life: the committed cluster incarnation number and its per-incarnation id. Tags compare by EQUALITY of both,
/// never by number alone: a re-minted incarnation 1 is a different life. [#NONE] (no incarnation committed yet) matches nothing, not even
/// another NONE, so two unrelated pre-genesis clusters never compare equal.
public record IncarnationTag(long incarnation, String incarnationId) {
    public static final IncarnationTag NONE = new IncarnationTag(0L, "");

    public static IncarnationTag incarnationTag(long incarnation, String incarnationId) {
        return new IncarnationTag(incarnation, incarnationId);
    }

    public boolean isNone() {
        return incarnationId.isEmpty();
    }

    /// True only when neither tag is [#NONE] and both parts are equal.
    public boolean matches(IncarnationTag other) {
        return !isNone() && !other.isNone() && equals(other);
    }
}
