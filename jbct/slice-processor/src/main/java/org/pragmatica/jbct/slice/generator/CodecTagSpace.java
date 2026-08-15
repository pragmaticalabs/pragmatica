// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.jbct.slice.generator;

/// The USER-range tag derivation, mirrored from `org.pragmatica.serialization.SliceCodec#hashedTag`
/// so the processor can detect a collision at COMPILE time rather than at slice load.
///
/// ## Why this is a copy and not a call
/// Consumers put `slice-processor` on `annotationProcessorPaths`, which resolves only that artifact's
/// compile-scoped transitive dependencies. Reaching `SliceCodec` from here would therefore mean
/// pulling `serialization-api` — and Netty behind it — onto the annotation-processor path of every
/// application that builds a slice. Six lines of a frozen algorithm is the cheaper trade.
///
/// ## What drift would and would not break
/// This value is used ONLY to compare two names for equality; the tag actually written to the wire is
/// always produced by `SliceCodec`. If the two ever diverge, this check degrades — it could miss a
/// collision, or claim one that does not exist — but no payload is encoded wrongly. Both sides pin
/// the same probe value in a test (`codecTagSpace_matchesSliceCodecDerivation`,
/// `hashedTag_pinnedProbeValue`) so divergence fails a build instead of going unnoticed.
final class CodecTagSpace {
    private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    static final int USER_TAG_BASE = 16384;
    static final int USER_TAG_LIMIT = 1 << 21;

    private CodecTagSpace() {}

    static int hashedTag(String className) {
        long hash = FNV_OFFSET_BASIS;

        for (var i = 0; i < className.length(); i++) {
            hash ^= className.charAt(i);
            hash *= FNV_PRIME;
        }

        return USER_TAG_BASE + (int) Long.remainderUnsigned(hash, USER_TAG_LIMIT - USER_TAG_BASE);
    }
}
