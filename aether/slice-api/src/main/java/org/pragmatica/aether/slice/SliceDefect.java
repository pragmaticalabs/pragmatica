// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.utils.Causes;

/// A failure the slice BRIDGE produced around a method, as opposed to a [Cause] the slice method itself
/// returned (#1573). Only these count toward the leader's "every instance of this version is broken"
/// verdict: a business failure the slice returns deliberately never does, whatever its rate.
///
/// The variants are the bridge's own failure points: the method threw instead of returning, the
/// request could not be decoded or the response encoded with this version's codec, or the method is not
/// part of this version. An execution timeout is deliberately NOT a defect — a stall is as consistent
/// with overload or a downstream outage as with a broken version.
public sealed interface SliceDefect extends Cause {
    /// The method threw synchronously instead of returning a Promise.
    record MethodThrew(Cause origin, String message) implements SliceDefect, Cause.Wrapped {
        public static final Fn1<MethodThrew, Cause> FACTORY = Causes.forOneValue("Slice method threw: %s",
                                                                                  MethodThrew::new);
    }

    /// The request could not be decoded, or the response encoded, by this version's codec.
    record CodecFailed(Cause origin, String message) implements SliceDefect, Cause.Wrapped {
        public static final Fn1<CodecFailed, Cause> FACTORY = Causes.forOneValue("Slice codec failed: %s",
                                                                                  CodecFailed::new);
    }

    /// The invoked method is not part of this slice version.
    record MethodNotFound(String method, String message) implements SliceDefect {
        public static final Fn1<MethodNotFound, String> FACTORY = Causes.forOneValue("Method not found: %s",
                                                                                     MethodNotFound::new);
    }
}
