// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.stream;

import org.pragmatica.serialization.Codec;


/// Why an offset is no longer readable: the cause a refused read names, defined once. The ordinal is the wire code
/// (append before [#UNKNOWN] and bump the wire generation, never reorder), and the codes are dense.
///
/// The first group is a retention decision (`EXPIRED`), the second is data gone with no retention decision behind it
/// (`LOST`), the last three say nothing about a removal: [#UNRECORDED] is a real removal whose cause no raiser states,
/// [#NOT_APPLICABLE] is a refusal that is not a removal, and [#UNKNOWN] is reserved for decoding a code this node does
/// not know (a newer node's cause); no constructor ever writes it.
@Codec
public enum RemovalCause {
    /// Retention reclaimed the offset, and the reader does not know which bound removed it.
    RETENTION,
    /// The ring's age eviction.
    AGE,
    /// The ring's entry-count capacity.
    COUNT,
    /// The ring's byte cap while its data region is at the declared size.
    BYTES,
    /// The ring evicted within what it has because the node budget refused its growth (the freeze).
    RING_FROZEN,
    /// The owner's eviction while the row's listed bytes exceed the partition's share.
    OVER_SHARE,
    /// The oldest queued run was dropped at the queue cap.
    DROPPED_RUN,
    /// The owner's put of a block it listed failed or was refused.
    BLOCK_PUT_FAILED,
    /// A listed block was confirmed absent by its owner.
    BLOCK_ABSENT,
    /// The range between the row's last listed end and the ring tail, counted at activation.
    UNLISTED_TAIL,
    /// Every copy that held the record was lost.
    LIFE_LOST,
    /// A real removal whose cause the raiser does not state.
    UNRECORDED,
    /// The refusal is not a removal.
    NOT_APPLICABLE,
    /// A code this node does not know, sent by a newer one: reserved for decoding.
    UNKNOWN
}
