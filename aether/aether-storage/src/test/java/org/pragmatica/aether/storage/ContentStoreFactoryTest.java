// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.

package org.pragmatica.aether.storage;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.storage.ByteRange;
import org.pragmatica.storage.ContentStore;
import org.pragmatica.storage.ObjectName;
import org.pragmatica.storage.ObjectSpec;
import org.pragmatica.storage.Precondition;
import org.pragmatica.storage.Compression;
import org.pragmatica.storage.ContentStoreConfig;
import org.pragmatica.storage.ContentStoreError;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.StorageInstance;

import java.nio.ByteBuffer;
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
            .flatMap(store -> storeAndReadBack(store, SAMPLE_CONTENT))
            .await()
            .onFailure(cause -> fail("blob round-trip should succeed: " + cause.message()))
            .onSuccess(ContentStoreFactoryTest::assertContentMatches);
    }

    @Test
    void provision_refusesAChunkSizeWhoseBlocksTheInstanceWouldRefuse_namingBothValues() {
        provisionOnDefaultInstance(ContentStoreConfig.contentStoreConfig(16 * 1024 * 1024, Compression.NONE))
            .await()
            .onSuccess(_ -> fail("a 16 MiB chunk makes blocks over the default 16 MiB cap"))
            .onFailure(cause -> {
                assertThat(cause).isInstanceOf(ContentStoreError.ChunkSizeExceedsBlockLimit.class);
                assertThat(cause.message()).contains("16777216").contains("16777223");
            });
    }

    @Test
    void provision_acceptsTheLargestChunkSizeTheInstanceTakes_andStoresAFullChunk() {
        var chunk = 16 * 1024 * 1024 - 7;
        var content = new byte[chunk];

        provisionOnDefaultInstance(ContentStoreConfig.contentStoreConfig(chunk, Compression.NONE))
            .flatMap(store -> storeAndReadBack(store, content))
            .await()
            .onFailure(cause -> fail("a chunk of cap minus the envelope must store: " + cause.message()))
            .onSuccess(stored -> assertThat(stored.isPresent()).isTrue());
    }

    @Test
    void provision_refusesANamespaceThatIsNotValid_namingTheSetting() {
        provisionOnDefaultInstance(ContentStoreConfig.contentStoreConfig(1024, Compression.NONE, "Not/Valid", 4, 2))
            .await()
            .onSuccess(_ -> fail("a namespace with a slash and capitals must be refused when the store is provisioned"))
            .onFailure(cause -> {
                assertThat(cause).isInstanceOf(ContentStoreError.InvalidConfig.class);
                assertThat(cause.message()).contains("namespace").contains("Not/Valid");
            });
    }

    @Test
    void provision_refusesAWriteWindowOfZero_andAcceptsAValidNamespace() {
        provisionOnDefaultInstance(ContentStoreConfig.contentStoreConfig(1024, Compression.NONE, "tenant-1", 0, 2))
            .await()
            .onSuccess(_ -> fail("a write window of 0 admits no block in flight"))
            .onFailure(cause -> assertThat(cause.message()).contains("writeWindow"));
        provisionOnDefaultInstance(ContentStoreConfig.contentStoreConfig(1024, Compression.NONE, "tenant-1", 4, 2))
            .await()
            .onFailure(cause -> fail("a valid namespace must provision: " + cause.message()));
    }

    @Test
    void provision_judgesAgainstTheInstancesOwnCap_notTheDefault() {
        var smallCap = StorageInstance.storageInstance("small",
                                                       List.of(MemoryTier.memoryTier(MEMORY_BYTES)),
                                                       org.pragmatica.storage.MetadataStore.inMemoryMetadataStore("small"),
                                                       org.pragmatica.storage.WritePolicy.WRITE_THROUGH,
                                                       Option.none(),
                                                       org.pragmatica.storage.AppendLog.TornTailSink.logOnly(),
                                                       _ -> {},
                                                       1024 * 1024);
        var context = ProvisioningContext.provisioningContext().withExtension(StorageInstance.class, smallCap);

        factory.provision(ContentStoreConfig.contentStoreConfig(), context)
               .await()
               .onSuccess(_ -> fail("the default 4 MiB chunk is over this instance's 1 MiB cap"))
               .onFailure(cause -> assertThat(cause.message()).contains("chunkSizeBytes 4194304").contains("maximum block size of 1048576"));
    }

    private Promise<ContentStore> provisionOnDefaultInstance(ContentStoreConfig config) {
        var storage = StorageInstance.storageInstance("content", List.of(MemoryTier.memoryTier(MEMORY_BYTES * 8)));
        var context = ProvisioningContext.provisioningContext().withExtension(StorageInstance.class, storage);

        return factory.provision(config, context);
    }

    /// Writes `content` as one object bound to `CONTENT_NAME`, looks it up by that name and reads its first piece.
    private static Promise<Option<byte[]>> storeAndReadBack(ContentStore store, byte[] content) {
        var name = ObjectName.objectName(CONTENT_NAME).unwrap();

        return store.create(ObjectSpec.objectSpec("text/plain"))
                    .flatMap(writer -> writer.write(ByteBuffer.wrap(content)).flatMap(_ -> writer.commit(Option.none())))
                    .flatMap(info -> store.bind(name, info.ref().id(), Precondition.nameFree()))
                    .flatMap(_ -> store.lookup(name, Option.none()))
                    .flatMap(found -> found.async(ContentStoreError.General.CONTENT_NOT_FOUND))
                    .flatMap(info -> store.open(info.ref(), ByteRange.whole()))
                    .flatMap(stream -> stream.next())
                    .map(next -> next.map(buffer -> {
                                              var bytes = new byte[buffer.remaining()];

                                              buffer.get(bytes);

                                              return bytes;
                                          }));
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
