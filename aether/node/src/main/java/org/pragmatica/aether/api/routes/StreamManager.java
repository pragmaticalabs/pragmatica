// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.aether.slice.resource.ResourceVersion;
import org.pragmatica.lang.Result;


/// Catalog &lt;-&gt; engine identity mapping for streams (management-api-versioning-spec.md §3.2).
///
/// The catalog addresses every stream as `(namespace, stream, version)`. The engine
/// (`StreamPartitionManager`/`StreamReadRouter`/`StreamWriteRouter`) keys operator-created flat
/// streams — minted into the `system` namespace at the default version — by their bare name, and
/// keys app-blueprint streams by the full `namespace:name:version` address. This interface is the
/// single resolution point between the two, so no route handler encodes the distinction itself.
///
/// Not to be confused with the private `streamManager()` accessor on `StreamApiRoutes` /
/// `StreamRoutes`, which returns a `StreamPartitionManager` instance — an unrelated type. The name
/// collision is between a type and a method, which Java's namespaces keep syntactically distinct,
/// but readers should not assume the two refer to the same thing.
public sealed interface StreamManager {
    String SYSTEM_NAMESPACE = "system";

    /// The engine-level key for the given catalog address: the bare stream name for `system`
    /// streams, the full catalog address otherwise.
    static String engineKey(ResourceAddress address) {
        return isSystem(address)
               ? address.name()
                        .value()
               : address.asString();
    }

    /// The catalog address a flat, operator-created stream is minted under.
    static Result<ResourceAddress> systemAddress(String name) {
        return ResourceAddress.resourceAddress(SYSTEM_NAMESPACE, name, ResourceVersion.defaultVersion());
    }

    private static boolean isSystem(ResourceAddress address) {
        return SYSTEM_NAMESPACE.equals(address.namespace().value());
    }

    record unused() implements StreamManager {}
}
