package org.pragmatica.storage;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.SplittableRandom;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Unit.unit;


/// A [ContentStoreV2] over a memory-tier instance whose lifecycle records the test can read, plus the streaming helpers the
/// v2 tests share: upload in pieces, read a range back, observe credits.
final class V2Fixture {
    static final int CHUNK = 16;

    /// The metadata store the instance runs on, kept so the test reads lifecycle records through it.
    final MetadataStore metadata = MetadataStore.inMemoryMetadataStore("v2-test");
    final StorageInstance storage;

    V2Fixture(StorageTier tier) {
        storage = StorageInstance.storageInstance("v2-test", List.of(tier), metadata);
    }

    static V2Fixture v2Fixture() {
        return new V2Fixture(MemoryTier.memoryTier(16L * 1024 * 1024));
    }

    ContentStoreV2 store(String namespace) {
        return store(ContentStoreConfig.contentStoreConfig(CHUNK, Compression.NONE, namespace, 2, 2));
    }

    ContentStoreV2 store(ContentStoreConfig config) {
        return ContentStoreV2.contentStoreV2(storage, config).unwrap();
    }

    int refCountOf(BlockId id) {
        return metadata.getLifecycle(id).map(BlockLifecycle::refCount).or(-1);
    }

    /// Pseudo-random bytes, so no two blocks of a payload are equal.
    static byte[] payload(int length, long seed) {
        var bytes = new byte[length];

        new SplittableRandom(seed).nextBytes(bytes);

        return bytes;
    }

    static Promise<ObjectInfo> upload(ContentStoreV2 store, ObjectSpec spec, byte[] data, int piece) {
        return store.create(spec).flatMap(writer -> feed(writer, data, 0, piece).flatMap(_ -> writer.commit(none())));
    }

    static Promise<Unit> feed(ObjectWriter writer, byte[] data, int offset, int piece) {
        if (offset >= data.length) {
            return Promise.success(unit());
        }

        var count = Math.min(piece, data.length - offset);

        return writer.write(ByteBuffer.wrap(data, offset, count)).flatMap(_ -> feed(writer, data, offset + count, piece));
    }

    static ObjectInfo uploaded(ContentStoreV2 store, byte[] data) {
        return upload(store, ObjectSpec.objectSpec("application/octet-stream"), data, 7).await().unwrap();
    }

    static byte[] read(ContentStoreV2 store, ObjectRef ref, ByteRange range) {
        var out = new ByteArrayOutputStream();
        var stream = store.open(ref, range).await().unwrap();

        drain(stream, out);

        return out.toByteArray();
    }

    private static void drain(ChunkStream stream, ByteArrayOutputStream out) {
        for (Option<ByteBuffer> next = stream.next().await().unwrap(); next.isPresent(); next = stream.next().await().unwrap()) {
            next.onPresent(buffer -> {
                var bytes = new byte[buffer.remaining()];

                buffer.get(bytes);
                out.write(bytes, 0, bytes.length);
            });
        }
    }

    static <T> Cause failureOf(Promise<T> promise) {
        return promise.await().fold(cause -> cause, _ -> null);
    }

    List<BlockId> chunksOf(ObjectInfo info) {
        return ContentManifest.fromBytes(storage.get(info.ref().manifest()).await().unwrap().unwrap())
                              .unwrap()
                              .chunkBlockIds()
                              .stream()
                              .map(hex -> BlockId.fromHex(hex).unwrap())
                              .toList();
    }

    /// A tier that flips one byte of `target` whenever that block is read, and serves everything else as stored: the disk
    /// that rots one block.
    record TamperingTier(StorageTier backing, BlockId target) implements StorageTier {
        @Override
        public Promise<Option<byte[]>> get(BlockId id) {
            return backing.get(id).map(block -> id.equals(target)
                                                ? block.map(TamperingTier::flipped)
                                                : block);
        }

        private static byte[] flipped(byte[] block) {
            var copy = block.clone();

            copy[0] ^= 0x01;

            return copy;
        }

        @Override
        public Promise<Unit> put(BlockId id, byte[] content) {
            return backing.put(id, content);
        }

        @Override
        public Promise<Unit> delete(BlockId id) {
            return backing.delete(id);
        }

        @Override
        public Promise<Boolean> exists(BlockId id) {
            return backing.exists(id);
        }

        @Override
        public TierLevel level() {
            return backing.level();
        }

        @Override
        public long usedBytes() {
            return backing.usedBytes();
        }

        @Override
        public long maxBytes() {
            return backing.maxBytes();
        }
    }

    /// A tier that refuses every block `refuse` accepts and stores the rest; `refuse` can be switched by the test.
    record RefusingTier(StorageTier backing, java.util.function.Predicate<byte[]> refuse) implements StorageTier {
        @Override
        public Promise<Option<byte[]>> get(BlockId id) {
            return backing.get(id);
        }

        @Override
        public Promise<Unit> put(BlockId id, byte[] content) {
            return refuse.test(content)
                   ? org.pragmatica.lang.utils.Causes.cause("tier refused block " + id).promise()
                   : backing.put(id, content);
        }

        @Override
        public Promise<Unit> delete(BlockId id) {
            return backing.delete(id);
        }

        @Override
        public Promise<Boolean> exists(BlockId id) {
            return backing.exists(id);
        }

        @Override
        public TierLevel level() {
            return backing.level();
        }

        @Override
        public long usedBytes() {
            return backing.usedBytes();
        }

        @Override
        public long maxBytes() {
            return backing.maxBytes();
        }
    }
}
