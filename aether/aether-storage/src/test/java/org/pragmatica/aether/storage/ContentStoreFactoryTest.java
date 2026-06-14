// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.storage;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.storage.ContentStore;
import org.pragmatica.storage.ContentStoreConfig;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.StorageInstance;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// Reproduces #251 (#99 regression): ContentStore provisioning requires a `StorageInstance`
/// extension on the `ProvisioningContext`. When the node registers that extension, provisioning
/// succeeds and a blob round-trips through the provisioned store.
class ContentStoreFactoryTest {
    private static final byte[] SAMPLE_CONTENT = "content-store round-trip".getBytes(StandardCharsets.UTF_8);
    private static final String CONTENT_NAME = "doc/readme.txt";
    private static final long MEMORY_BYTES = 8L * 1024 * 1024;

    private final ContentStoreFactory factory = new ContentStoreFactory();

    @Test
    void provision_succeeds_whenStorageInstanceExtensionPresent() {
        provisionContentStore()
            .await()
            .onFailure(cause -> fail("provision should succeed: " + cause.message()))
            .onSuccess(store -> assertThat(store).isNotNull());
    }

    @Test
    void provision_fails_whenStorageInstanceExtensionMissing() {
        factory.provision(ContentStoreConfig.contentStoreConfig(), ProvisioningContext.provisioningContext())
               .await()
               .onSuccess(_ -> fail("provision should fail without StorageInstance extension"));
    }

    @Test
    void provision_blobRoundTrips_throughProvisionedStore() {
        provisionContentStore()
            .flatMap(store -> store.put(CONTENT_NAME, SAMPLE_CONTENT).map(_ -> store))
            .flatMap(store -> store.get(CONTENT_NAME))
            .await()
            .onFailure(cause -> fail("blob round-trip should succeed: " + cause.message()))
            .onSuccess(ContentStoreFactoryTest::assertContentMatches);
    }

    private static void assertContentMatches(Option<byte[]> content) {
        assertThat(content.isPresent()).isTrue();
        content.onPresent(bytes -> assertThat(bytes).isEqualTo(SAMPLE_CONTENT));
    }

    private Promise<ContentStore> provisionContentStore() {
        var storage = StorageInstance.storageInstance("content", List.of(MemoryTier.memoryTier(MEMORY_BYTES)));
        var context = ProvisioningContext.provisioningContext().withExtension(StorageInstance.class, storage);

        return factory.provision(ContentStoreConfig.contentStoreConfig(), context);
    }
}
