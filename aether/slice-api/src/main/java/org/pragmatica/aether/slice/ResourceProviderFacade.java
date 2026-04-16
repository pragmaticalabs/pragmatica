// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


/// Facade interface for resource provisioning within slice context.
///
/// This interface is a simplified view of ResourceProvider for use
/// in slice factory methods. The actual implementation delegates to
/// the full ResourceProvider.
///
/// Example usage:
/// ```{@code
/// ctx.resources().provide(SqlConnector.class, "database.primary")
/// }```
public interface ResourceProviderFacade {
    <T> Promise<T> provide(Class<T> resourceType, String configSection);
    <T> Promise<T> provide(Class<T> resourceType, String configSection, ProvisioningContext context);

    default Promise<Unit> releaseAll(String sliceId) {
        return Promise.unitPromise();
    }
}
