// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource;

import org.pragmatica.aether.slice.ProvisioningContext;

/// The runtime a resource is provisioned on, as far as backend availability is concerned (#3658).
///
/// A backend declares the runtimes it is available on; a backend-selection seam refuses, by name, a backend that
/// is not available on the runtime it is provisioned for (DHT has no cluster to live in on Terra).
///
/// - [#TERRA] — stamped by Terra into every [ProvisioningContext] its slices provision through
///   (`TerraContext.Scope.provide`), overriding anything the caller supplied.
/// - [#AETHER] — what an absent extension reads as. Aether stamps nothing: every resource factory was written
///   for it, and every bare-context caller (`ResourceFactory.provision(config)`, the factory unit tests) is
///   Aether-shaped. The cost of the default: a Terra provisioning path that bypassed the stamp would select as
///   Aether does, and a DHT backend would then fail on its missing DHT client extension, not on this refusal.
public enum BackendRuntime {
    AETHER,
    TERRA;

    /// The runtime `context` provisions for: its [BackendRuntime] extension, [#AETHER] when there is none.
    public static BackendRuntime backendRuntime(ProvisioningContext context) {
        return context.extension(BackendRuntime.class)
                      .or(AETHER);
    }
}
