// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.blueprint.StreamConfigParser;
import org.pragmatica.aether.slice.blueprint.StreamSection;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.lang.Result;


/// The section binder both stream resource factories share (#1549): a `[streams.X]` section provisioned at
/// slice activation is parsed by [StreamConfigParser#parseStreamConfig], the parse deploy validation runs
/// over the same declaration. Before #1549 the generic record binder read snake_case component names and
/// resolved every key it did not find from `StreamConfig.DEFAULT`, so the documented dashed keys
/// (`min-sync-replicas`, `max-event-size`, `retention*`, `auto-offset-reset`, `encryption-key-id`) never
/// reached the runtime.
public sealed interface StreamSectionBinding {
    static Result<StreamConfig> bindStreamSection(ConfigurationProvider provider, String section) {
        return StreamConfigParser.parseStreamConfig(StreamSection.providerSection(provider, section));
    }

    record unused() implements StreamSectionBinding {}
}
