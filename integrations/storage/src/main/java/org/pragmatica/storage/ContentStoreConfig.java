package org.pragmatica.storage;

/// Configuration for content store chunking and compression.
public record ContentStoreConfig(int chunkSizeBytes, Compression compression) {

    private static final int DEFAULT_CHUNK_SIZE = 4 * 1024 * 1024; // 4 MB

    public static ContentStoreConfig contentStoreConfig() {
        return new ContentStoreConfig(DEFAULT_CHUNK_SIZE, Compression.NONE);
    }

    public static ContentStoreConfig contentStoreConfig(int chunkSizeBytes, Compression compression) {
        return new ContentStoreConfig(chunkSizeBytes, compression);
    }
}
