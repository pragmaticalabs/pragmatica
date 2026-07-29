// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.resource;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.resource.SpiResourceProvider.spiResourceProvider;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Runtime extensions are DEFAULTS, not overrides (#526).
///
/// The node registers a codec, a partition manager and a dozen other singletons as runtime
/// extensions. Before this fix they were applied unconditionally and last, so a slice that supplied
/// its OWN value — notably its own codec, the only one that knows application types — silently lost
/// it. These tests pin the precedence in both directions: a caller-supplied value survives, and a
/// type the caller did not supply is still injected.
class SpiResourceProviderExtensionTest {
    private static final TimeSpan TIMEOUT = timeSpan(5).seconds();

    private static final String SECTION = "recorded.section";

    private interface Codec {}

    private record NodeCodec() implements Codec {}

    private record SliceCodec() implements Codec {}

    private record PartitionManager() {}

    private static SpiResourceProvider providerWithExtensions(Codec codec, PartitionManager manager) {
        var provider = spiResourceProvider((_, _) -> Result.success(new RecordedResourceConfig(SECTION)));

        provider.registerExtension(Codec.class, codec);
        provider.registerExtension(PartitionManager.class, manager);

        return provider;
    }

    private static ProvisioningContext provisionAndCapture(SpiResourceProvider provider, ProvisioningContext context) {
        provider.provide(RecordedResource.class, SECTION, context).await(TIMEOUT).onFailure(cause -> {
                     throw new AssertionError("Provisioning failed: " + cause.message());
                 });

        return RecordingResourceFactory.lastContext();
    }

    @Test
    void provide_keepsCallerSuppliedExtension_whenRuntimeRegistersTheSameType() {
        var nodeCodec = new NodeCodec();
        var sliceCodec = new SliceCodec();
        var provider = providerWithExtensions(nodeCodec, new PartitionManager());
        var context = ProvisioningContext.provisioningContext().withExtension(Codec.class, sliceCodec);

        var captured = provisionAndCapture(provider, context);

        captured.extension(Codec.class).onSuccess(codec -> assertThat(codec).isSameAs(sliceCodec));
        assertThat(captured.extension(Codec.class).or((Codec) null)).isNotSameAs(nodeCodec);
    }

    @Test
    void provide_injectsRuntimeExtension_whenCallerSuppliedNothingForThatType() {
        var nodeCodec = new NodeCodec();
        var provider = providerWithExtensions(nodeCodec, new PartitionManager());

        var captured = provisionAndCapture(provider, ProvisioningContext.provisioningContext());

        captured.extension(Codec.class).onSuccess(codec -> assertThat(codec).isSameAs(nodeCodec));
    }

    @Test
    void provide_injectsOtherRuntimeExtensions_whenCallerOverridesOnlyOneType() {
        var manager = new PartitionManager();
        var sliceCodec = new SliceCodec();
        var provider = providerWithExtensions(new NodeCodec(), manager);
        var context = ProvisioningContext.provisioningContext().withExtension(Codec.class, sliceCodec);

        var captured = provisionAndCapture(provider, context);

        captured.extension(Codec.class).onSuccess(codec -> assertThat(codec).isSameAs(sliceCodec));
        captured.extension(PartitionManager.class).onSuccess(value -> assertThat(value).isSameAs(manager));
    }
}
