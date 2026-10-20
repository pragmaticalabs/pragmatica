// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.prefetch;

import java.util.List;

import org.pragmatica.serialization.Codec;
import org.pragmatica.storage.PrefetchHint;


/// The storage prefetch hints one node gossips to a peer over SWIM, as they cross the wire: which storage
/// instance the blocks belong to, and the hottest blocks of it with how often each was read.
///
/// Carried as the payload of a SWIM `AppGossip` datagram on the prefetch channel ([StoragePrefetchChannel]). The
/// instance is named once for the whole list, not per hint: a datagram carries one instance's hints. The tier a
/// hint came from is not carried -- it never varied, so it told the receiver nothing.
@Codec
public record StoragePrefetchGossip(String instance, List<Hint> hints) {
    public StoragePrefetchGossip {
        hints = List.copyOf(hints);
    }

    /// One hot block: its id in hex (portable between nodes) and the reads counted since the last hint.
    @Codec
    public record Hint(String blockIdHex, int accessCount) {
        public static Hint hint(String blockIdHex, int accessCount) {
            return new Hint(blockIdHex, accessCount);
        }
    }

    public static StoragePrefetchGossip storagePrefetchGossip(String instance, List<Hint> hints) {
        return new StoragePrefetchGossip(instance, hints);
    }

    /// The wire form of `hints`, as collected by a prefetch manager of `instance`.
    public static StoragePrefetchGossip from(String instance, List<PrefetchHint> hints) {
        return storagePrefetchGossip(instance,
                                     hints.stream()
                                          .map(hint -> Hint.hint(hint.blockIdHex(),
                                                                 hint.accessCount()))
                                          .toList());
    }

    /// The hints as a prefetch manager takes them.
    public List<PrefetchHint> toPrefetchHints() {
        return hints.stream()
                    .map(hint -> PrefetchHint.prefetchHint(hint.blockIdHex(),
                                                           hint.accessCount()))
                    .toList();
    }
}
