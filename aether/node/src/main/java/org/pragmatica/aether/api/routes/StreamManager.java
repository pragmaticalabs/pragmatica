// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.aether.slice.resource.ResourceVersion;
import org.pragmatica.aether.slice.stream.StreamEngineKey;
import org.pragmatica.lang.Result;


/// Catalog &lt;-&gt; engine identity mapping for streams (management-api-versioning-spec.md §3.2).
///
/// The catalog addresses every stream as `(namespace, stream, version)`. The engine
/// (`StreamPartitionManager`/`StreamReadRouter`/`StreamWriteRouter`) keys every stream, `system` included, by
/// the full `namespace:name:version` address (#1338). This interface is the single resolution point from the catalog
/// address to that key, so no route handler computes it itself.
///
/// Not to be confused with the private `streamManager()` accessor on `StreamApiRoutes` /
/// `StreamRoutes`, which returns a `StreamPartitionManager` instance — an unrelated type. The name
/// collision is between a type and a method, which Java's namespaces keep syntactically distinct,
/// but readers should not assume the two refer to the same thing.
public sealed interface StreamManager {
    String SYSTEM_NAMESPACE = "system";

    /// The engine-level key for the given catalog address: the full catalog address, in every namespace.
    ///
    /// Delegates to [StreamEngineKey#engineKey] rather than recomputing (#1040). The reduction used
    /// to be written out here AND in `SystemStreams.isForbiddenEngineKey`, while the slice/engine
    /// materialization path applied neither — so a declared stream was addressable under two
    /// spellings at once. The route handlers keep calling this method; only the body moved.
    static String engineKey(ResourceAddress address) {
        return StreamEngineKey.engineKey(address);
    }

    /// The catalog address a flat, operator-created stream is minted under.
    static Result<ResourceAddress> systemAddress(String name) {
        return ResourceAddress.resourceAddress(SYSTEM_NAMESPACE, name, ResourceVersion.defaultVersion());
    }

    record unused() implements StreamManager {}
}
