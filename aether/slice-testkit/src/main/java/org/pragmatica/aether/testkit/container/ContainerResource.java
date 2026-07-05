// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.testkit.container;

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Promise;


/// A testcontainer-backed resource: starts a real backend, provisions the real
/// `ResourceFactory`-produced connector against it, and applies the slice's migrations. Registered
/// into the kit's resource map at build time, exactly like a fake (spec §4.1, §5.2).
///
/// @param <R> the resource interface the slice's generated factory asks for (e.g. `PgSqlConnector`)
public interface ContainerResource<R> {
    /// The runtime type the generated factory resolves — the map key's type component.
    Class<R> resourceType();
    /// Start the container and provision the real connector (with migrations applied).
    Promise<R> provision();

    /// Stop the container and release its resources.
    @Contract
    void stop();
}
