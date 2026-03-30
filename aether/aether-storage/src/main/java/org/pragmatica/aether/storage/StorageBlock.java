package org.pragmatica.aether.storage;

/// Immutable content-addressed storage block.
///
/// @param id content-addressed block identifier (SHA-256 of content)
/// @param content block payload bytes (may be compressed)
/// @param metadata block properties
public record StorageBlock(BlockId id, byte[] content, BlockMetadata metadata) {

    /// Defensive copy of mutable byte array.
    public StorageBlock {
        content = content.clone();
    }

    public static StorageBlock storageBlock(BlockId id, byte[] content, BlockMetadata metadata) {
        return new StorageBlock(id, content, metadata);
    }
}
