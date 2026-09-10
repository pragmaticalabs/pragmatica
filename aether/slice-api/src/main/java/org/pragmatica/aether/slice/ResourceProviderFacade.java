// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


public interface ResourceProviderFacade {
    <T> Promise<T> provide(Class<T> resourceType, String configSection);
    <T> Promise<T> provide(Class<T> resourceType, String configSection, ProvisioningContext context);
    /// Release everything this facade provisioned for `sliceId`.
    ///
    /// ABSTRACT ON PURPOSE, and it used to have a `Promise.unitPromise()` default (#892). That
    /// default is indistinguishable from "released everything", so the node's own facade — an
    /// anonymous class implementing the two `provide` overloads and nothing else — inherited it and
    /// **no slice unload ever reached a provider on a deployed node.** A close that reports success
    /// while doing nothing is the same failure #891 fixed one layer down; here the compiler can
    /// refuse it outright, so it does.
    ///
    /// An implementation with genuinely nothing to release still has to say so, which is the point:
    /// the no-ops that remain are deliberate and greppable rather than inherited by accident.
    ///
    /// The `sliceId` a caller passes is NOT authoritative wherever
    /// `SliceLoadingContext.SliceAwareResourceProvider` sits in the chain — it substitutes the
    /// deployed `Artifact` it provisions under, and reports a caller id that names a different
    /// slice. See its `releaseAll`.
    Promise<Unit> releaseAll(String sliceId);
}
