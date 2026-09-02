// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource;

import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


public interface ResourceFactory<T, C> {
    Class<T> resourceType();
    Class<C> configType();
    Promise<T> provision(C config);

    default Promise<T> provision(C config, ProvisioningContext context) {
        return provision(config);
    }

    default int priority() {
        return 0;
    }

    default boolean supports(C config) {
        return true;
    }

    /// Default unload: close an [AutoCloseable] resource, absorbing (but no longer discarding) a failed
    /// close. The promise still reports success either way — a resource that failed to close is released
    /// from the provider's cache regardless, and failing the whole `releaseAll` chain over one resource
    /// would block the release of every other. The throwable is LOGGED (JDK platform logging — this
    /// module deliberately carries no logging dependency) because a factory whose close-time work is
    /// load-bearing (e.g. the durable entity's registration retraction) would otherwise strand that work
    /// with nothing anywhere saying why; such factories should still override this with a properly
    /// reported close of their own.
    default Promise<Unit> close(T resource) {
        if (resource instanceof AutoCloseable closeable) {
            return Promise.promise(promise -> {
                try {
                    closeable.close();
                    promise.succeed(Unit.unit());
                } catch (Exception e) {
                    System.getLogger(ResourceFactory.class.getName()).log(System.Logger.Level.WARNING,
                                                                          "Resource close failed for " + resource.getClass()
                                                                                                                 .getName()
                                                                         + " — the resource is released from the cache anyway",
                                                                          e);
                    promise.succeed(Unit.unit());
                }
            });
        }

        return Promise.unitPromise();
    }
}
