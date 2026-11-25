// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice;

import org.pragmatica.serialization.Codec;


/// Where a stream keeps its records: the storage mode of a stream's declaration ([StreamDeclaration#storage]). The ordinal is the
/// wire code (append before [#UNKNOWN] and bump the wire generation, never reorder), and the codes are dense.
///
/// A declaration that names no mode is [#PERSISTENT] (the default of a stream is persistence); [#UNKNOWN] is reserved for decoding a
/// code this node does not know (a newer node's mode), and no constructor ever writes it.
@Codec
public enum StreamStorage {
    /// The stream's records are persisted: its write-ahead log and sealed tiers.
    PERSISTENT,
    /// The stream's records live in memory only.
    MEMORY,
    /// A code this node does not know, sent by a newer one: reserved for decoding. Must stay LAST.
    UNKNOWN
}
