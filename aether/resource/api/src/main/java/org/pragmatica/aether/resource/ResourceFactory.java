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

    default Promise<Unit> close(T resource) {
        if (resource instanceof AutoCloseable closeable) {return Promise.promise(promise -> {
                                                                                     try {
                                                                                         closeable.close();
                                                                                         promise.succeed(Unit.unit());
                                                                                     } catch (Exception e) {
                                                                                         promise.succeed(Unit.unit());
                                                                                     }
                                                                                 });}
        return Promise.unitPromise();
    }
}
