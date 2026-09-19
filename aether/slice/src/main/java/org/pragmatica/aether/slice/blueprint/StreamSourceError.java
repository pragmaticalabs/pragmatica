// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.blueprint;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn3;
import org.pragmatica.lang.utils.Causes;

/// A blueprint `External` stream `source` the parser refuses (#1282).
public sealed interface StreamSourceError extends Cause {
    /// The source resolves to an engine key carrying a reserved stream-kind prefix
    /// (`StreamEngineKey.RESERVED_KIND_PREFIXES`). The slice's stream factories mint whatever key a
    /// binding resolves to, so accepting it would let a blueprint create — or, for an `entity:` key,
    /// exactly collide with — a stream only internal provisioning may create. No legitimate reference
    /// exists: spec §11.2 lets an External source name another blueprint's namespace or `system`, and a
    /// real durable-topic stream is not addressable in the three-part form.
    record ReservedKindSource(String alias, String source, String prefix, String message) implements StreamSourceError {
        static final Fn3<ReservedKindSource, String, String, String> FACTORY = Causes.forThreeValues("Stream resource '%s' names source '%s', whose stream name carries the reserved "
                                                                                                    + "stream-kind prefix '%s': those streams are provisioned only by the runtime "
                                                                                                    + "and cannot be referenced from a blueprint",
                                                                                                    ReservedKindSource::new);
    }

    record unused() implements StreamSourceError {
        @Override
        public String message() {
            return "";
        }
    }
}
