// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.pragmatica.aether.resource.ResourceFactory;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.lang.Promise;

/// Test-only `ResourceFactory`, discovered by `SpiResourceProvider`'s `ServiceLoader` scan through
/// `src/test/resources/META-INF/services`. It hands back the `ProvisioningContext` it was provisioned
/// with -- AFTER `SpiResourceProvider.enrichWithRuntimeExtensions` layered the node-wide runtime
/// extensions onto it -- so a booted node's test can read exactly the context every real factory
/// (`ContentStoreFactory` included) receives, without a production accessor for the SPI's extension
/// map. The config section is `[context_capture]` with a single `enabled` key; the value is inert.
///
/// Registered for every `aether/node` test that builds an `SpiResourceProvider`; it answers only for
/// [CapturedProvisioningContext], which no production code provisions, so it is invisible elsewhere.
public final class ProvisioningContextCaptureFactory implements ResourceFactory<ProvisioningContextCaptureFactory.CapturedProvisioningContext,
                                                                                   ProvisioningContextCaptureFactory.CaptureConfig> {
    public record CaptureConfig(boolean enabled) {}

    public record CapturedProvisioningContext(ProvisioningContext context) {}

    @Override
    public Class<CapturedProvisioningContext> resourceType() {
        return CapturedProvisioningContext.class;
    }

    @Override
    public Class<CaptureConfig> configType() {
        return CaptureConfig.class;
    }

    @Override
    public Promise<CapturedProvisioningContext> provision(CaptureConfig config) {
        return Promise.success(new CapturedProvisioningContext(ProvisioningContext.provisioningContext()));
    }

    @Override
    public Promise<CapturedProvisioningContext> provision(CaptureConfig config, ProvisioningContext context) {
        return Promise.success(new CapturedProvisioningContext(context));
    }
}
