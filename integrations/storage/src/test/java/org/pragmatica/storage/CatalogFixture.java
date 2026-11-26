package org.pragmatica.storage;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.none;


/// Shared pieces of the catalog tests: entries that name no real blocks, and a page store in a map.
final class CatalogFixture {
    static final String NAMESPACE = "alpha";

    private CatalogFixture() {}

    /// An unnamed entry of an object of `size` bytes; its manifest id is derived from `seed` and names no stored block.
    static ObjectEntry entry(long size, int seed) {
        var manifest = new byte[Sha256.LENGTH];

        manifest[0] = (byte) seed;
        manifest[1] = (byte) (seed >> 8);
        manifest[2] = (byte) (seed >> 16);

        var meta = ObjectMeta.objectMeta(none(),
                                         "application/octet-stream",
                                         size,
                                         new Sha256(new byte[Sha256.LENGTH]),
                                         NAMESPACE,
                                         Instant.EPOCH,
                                         none(),
                                         Map.of());

        return ObjectEntry.objectEntry(new BlockId(manifest), meta);
    }

    static ObjectName name(String value) {
        return ObjectName.objectName(value).unwrap();
    }

    /// Pages in a map: the tier of the in-memory tree catalog and of the pure-core tests.
    static final class Pages {
        final Map<BlockId, byte[]> blocks = new ConcurrentHashMap<>();

        PageTree.PageSource source() {
            return id -> Option.option(blocks.get(id))
                               .async(ContentStoreError.General.CONTENT_NOT_FOUND)
                               .flatMap(bytes -> CatalogPage.fromBytes(bytes)
                                                            .async());
        }

        void store(Map<BlockId, byte[]> written) {
            blocks.putAll(written);
        }
    }
}
