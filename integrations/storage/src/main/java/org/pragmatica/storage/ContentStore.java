package org.pragmatica.storage;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

/// User-facing content storage with auto-chunking for large files.
/// Small content is stored as a single block. Large content is split
/// into chunks with a manifest block linking them.
public interface ContentStore {

    /// Store content under a name. Returns the content ID (BlockId hex).
    Promise<String> put(String name, byte[] content);

    /// Retrieve content by name.
    Promise<Option<byte[]>> get(String name);

    /// Check if content exists by name.
    Promise<Boolean> exists(String name);

    /// Delete content and its chunks by name.
    Promise<Unit> delete(String name);

    /// Create a content store backed by the given storage instance and config.
    static ContentStore contentStore(StorageInstance storage, ContentStoreConfig config) {
        return new DefaultContentStore(storage, config);
    }

    /// Create a content store with default config.
    static ContentStore contentStore(StorageInstance storage) {
        return contentStore(storage, ContentStoreConfig.contentStoreConfig());
    }
}
