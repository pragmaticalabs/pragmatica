// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.deadsurface;

import org.pragmatica.config.ConfigService;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.config.ProviderBasedConfigService;
import org.pragmatica.config.source.TomlConfigSource;
import org.pragmatica.storage.Compression;
import org.pragmatica.storage.ContentStoreConfig;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;


/// The streaming content store's three settings bind from the `[storage]` section's TOML, and an existing section that
/// names only the chunk size and the compression binds with their defaults: the new keys are optional, so an upgrade
/// needs no config change.
class ContentStoreConfigBindingTest {
    @Test
    void aSectionThatNamesOnlyChunkSizeAndCompression_bindsTheDefaultsOfTheStreamingSettings() {
        var config = serviceFrom("""
                                 [content]
                                 chunk_size_bytes = 1048576
                                 compression = "LZ4"
                                 """).config("content", ContentStoreConfig.class).unwrap();

        assertEquals(1048576, config.chunkSizeBytes());
        assertEquals(Compression.LZ4, config.compression());
        assertEquals("default", config.namespace());
        assertEquals(4, config.writeWindow());
        assertEquals(2, config.readAhead());
    }

    @Test
    void theStreamingSettings_bindFromTheirKeys() {
        var config = serviceFrom("""
                                 [content]
                                 chunk_size_bytes = 65536
                                 compression = "NONE"
                                 namespace = "tenant-a"
                                 write_window = 8
                                 read_ahead = 0
                                 """).config("content", ContentStoreConfig.class).unwrap();

        assertEquals("tenant-a", config.namespace());
        assertEquals(8, config.writeWindow());
        assertEquals(0, config.readAhead());
    }

    private static ConfigService serviceFrom(String toml) {
        var source = TomlConfigSource.tomlConfigSource(toml).unwrap();
        var provider = ConfigurationProvider.builder().withSource(source).build();

        return ProviderBasedConfigService.providerBasedConfigService(provider);
    }
}
