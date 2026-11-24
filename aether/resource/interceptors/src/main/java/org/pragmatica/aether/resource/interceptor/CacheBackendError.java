// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.interceptor;

import org.pragmatica.aether.resource.BackendRuntime;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.Functions.Fn3;
import org.pragmatica.lang.utils.Causes;

/// Refusals of the cache/idempotency backend-selection seam ([CacheBackends], #3658). Each names the store and
/// the backend; the config section is named by the provisioning wrapper around it (`CreationFailed` on Aether,
/// `SliceLoadingFailure.ResourceCreationFailed` on Terra). All are terminal: they come from configuration, and no
/// retry changes it.
public sealed interface CacheBackendError extends Cause {
    /// `backend` names no backend the seam knows.
    record UnknownBackend(String backend, String store, String message) implements CacheBackendError, Cause.Terminal {
        static final Fn2<UnknownBackend, String, String> FACTORY = Causes.forTwoValues("backend \"%s\" of store \"%s\" is unknown: the backends are memory and dht (#3658)",
                                                                                       UnknownBackend::new);

        static UnknownBackend unknownBackend(String backend, String store) {
            return FACTORY.apply(backend, store);
        }
    }

    /// `backend` does not fit `mode`: `LOCAL` needs a per-instance backend, `DISTRIBUTED`/`TIERED` a shared one.
    record ModeConflict(String store, CacheMode mode, String backend, String message) implements CacheBackendError, Cause.Terminal {
        static final Fn3<ModeConflict, String, CacheMode, String> FACTORY = Causes.forThreeValues("store \"%s\" sets mode %s with backend \"%s\": LOCAL needs the per-instance backend memory, DISTRIBUTED and TIERED need a shared backend (#3658)",
                                                                                                  ModeConflict::new);

        static ModeConflict modeConflict(String store, CacheMode mode, String backend) {
            return FACTORY.apply(store, mode, backend);
        }
    }

    /// `backend` is known but not available on the runtime the store is provisioned on (`dht` on Terra).
    record BackendUnavailable(String backend, String store, BackendRuntime runtime, String message) implements CacheBackendError, Cause.Terminal {
        static final Fn3<BackendUnavailable, String, String, BackendRuntime> FACTORY = Causes.forThreeValues("backend \"%s\" of store \"%s\" is not available on runtime %s (#3658)",
                                                                                                             BackendUnavailable::new);

        static BackendUnavailable backendUnavailable(String backend, String store, BackendRuntime runtime) {
            return FACTORY.apply(backend, store, runtime);
        }
    }
}
