// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
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
    record ReservedKindSource(String alias, String sourceAddress, String prefix, String message) implements StreamSourceError {
        static final Fn3<ReservedKindSource, String, String, String> FACTORY = Causes.forThreeValues("Stream resource '%s' names source '%s', whose stream name carries the reserved stream-kind prefix '%s': those streams are provisioned only by the runtime and cannot be referenced from a blueprint",
                                                                                                     ReservedKindSource::new);
    }

    /// A `system` source declared with a role that writes (`producer`, `both`) (#1338). System streams are read-only to
    /// blueprints and slices: only the node's own system publishers write them, so the declaration is refused at
    /// validation instead of surfacing as a refused publish later.
    record SystemSourceNotConsumer(String alias, String sourceAddress, String role, String message) implements StreamSourceError {
        static final Fn3<SystemSourceNotConsumer, String, String, String> FACTORY = Causes.forThreeValues("Stream resource '%s' names the system stream '%s' with role '%s': system streams are read-only to blueprints and slices; declare role = \"consumer\"",
                                                                                                          SystemSourceNotConsumer::new);
    }

    record unused() implements StreamSourceError {
        @Override
        public String message() {
            return "";
        }
    }
}
