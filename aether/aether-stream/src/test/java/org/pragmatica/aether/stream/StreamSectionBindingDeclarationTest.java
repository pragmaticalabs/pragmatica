// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.aether.slice.ReplicationContext;
import org.pragmatica.aether.slice.blueprint.StreamDeclarationError;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.config.source.TomlConfigSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// C-4 (#3334, S2a1-1 finding): a `declaration` read from `<section>.declaration` is not a stream key. The activation
/// binder ([StreamSectionBinding#bindStreamSection]) runs the same parse as `[streams.X]` deploy validation, which refuses
/// an unknown key naming it, so no `declaration` supplied through a bound section can reach the stream's config. A guess
/// (refuse rather than accept) that this pins. The control section without the key binds, so the refusal is the key's.
class StreamSectionBindingDeclarationTest {
    private static final ProvisioningContext CONTEXT = ProvisioningContext.provisioningContext()
                                                                          .withExtension(ReplicationContext.Source.class,
                                                                                         ReplicationContext.Source.fixed(ReplicationContext.BUILT_IN));
    private static final String SECTION = "streams.orders";
    private static final String CONTROL = """
            [streams.orders]
            version = "1.0.0"
            role = "producer"
            partitions = 3
            """;

    private static ConfigurationProvider providerOf(String toml) {
        return ConfigurationProvider.builder()
                                    .withSource(TomlConfigSource.tomlConfigSource(toml).unwrap())
                                    .build();
    }

    @Test
    void sectionWithoutDeclaration_binds() {
        assertThat(StreamSectionBinding.bindStreamSection(providerOf(CONTROL), SECTION, CONTEXT).isSuccess()).isTrue();
    }

    @Test
    void sectionWithDeclaration_isRefusedNamingTheKey_andBindsNoConfig() {
        StreamSectionBinding.bindStreamSection(providerOf(CONTROL + "declaration = \"max_age = 2h\"\n"), SECTION, CONTEXT)
                            .onSuccess(config -> fail("`declaration` is not a stream key and must be refused, yet bound: " + config))
                            .onFailure(cause -> assertThat(cause).isInstanceOf(StreamDeclarationError.UnknownStreamKeys.class))
                            .onFailure(cause -> assertThat(cause.message()).contains("declaration"));
    }
}
