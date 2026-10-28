package org.pragmatica.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.SplittableRandom;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// Writes an object of the given size through a disk-only v2 store, reads it back whole and by range, and prints what it
/// saw. Run by [LargeObjectRun] in a child JVM with a small heap, so an implementation that holds the object (or its
/// blocks) on the heap dies here with an `OutOfMemoryError`, not in the test's own JVM.
///
/// The bytes are non-repeating: each 1 MiB piece is seeded by its index. Content addressing would collapse repeated or
/// zero-filled blocks into one stored block and hide exactly the defect this guards against.
public final class LargeObjectProbe {
    private static final int PIECE = 1024 * 1024;
    private static final long SEED = 0x5DEECE66DL;
    private static final HexFormat HEX = HexFormat.of();

    private LargeObjectProbe() {}

    /// Piece `index` of the generated stream, `length` bytes long.
    private static byte[] piece(long index, int length) {
        var bytes = new byte[length];

        new SplittableRandom(SEED + index).nextBytes(bytes);

        return bytes;
    }

    /// The generated stream from `first` to `last` inclusive, regenerated piece by piece (for ranges that are small).
    private static byte[] generated(long first, long last) {
        var out = new byte[(int) (last - first + 1)];
        var written = 0;

        for (var at = first; at <= last; ) {
            var index = at / PIECE;
            var inPiece = (int) (at % PIECE);
            var count = (int) Math.min(PIECE - inPiece, last - at + 1);

            System.arraycopy(piece(index, PIECE), inPiece, out, written, count);
            written += count;
            at += count;
        }

        return out;
    }

    public static void main(String[] args) throws Exception {
        var size = Long.parseLong(args[0]);
        var dir = Path.of(args[1]);
        var tier = LocalDiskTier.localDiskTier(dir, size + 256L * 1024 * 1024).unwrap();
        var storage = StorageInstance.storageInstance("probe", List.of(tier));
        var store = ContentStore.contentStore(storage, ContentStoreConfig.contentStoreConfig()).unwrap();
        var written = MessageDigest.getInstance("SHA-256");
        var writer = store.create(ObjectSpec.objectSpec("application/octet-stream")).await().unwrap();
        var started = System.nanoTime();

        for (long index = 0, done = 0; done < size; index++) {
            var length = (int) Math.min(PIECE, size - done);
            var bytes = piece(index, length);

            written.update(bytes);
            writer.write(ByteBuffer.wrap(bytes)).await().unwrap();
            done += length;
        }

        var info = writer.commit(none()).await().unwrap();
        var writeMillis = (System.nanoTime() - started) / 1_000_000;
        var read = MessageDigest.getInstance("SHA-256");
        var stream = store.open(info.ref(), ByteRange.whole()).await().unwrap();
        var bytesRead = 0L;

        started = System.nanoTime();

        for (var next = stream.next().await().unwrap(); next.isPresent(); next = stream.next().await().unwrap()) {
            var buffer = next.or((ByteBuffer) null);

            bytesRead += buffer.remaining();
            read.update(buffer);
        }

        var readMillis = (System.nanoTime() - started) / 1_000_000;
        var chunk = ContentStoreConfig.contentStoreConfig().chunkSizeBytes();
        var rangesOk = rangeMatches(store, info, chunk - 3L, chunk + 5L)
                       && rangeMatches(store, info, size - 10, size - 1)
                       && rangeMatches(store, info, size / 2, size / 2 + 3L * PIECE)
                       && rangeMatches(store, info, 0, 0);

        System.out.println("MAX_HEAP_MB=" + Runtime.getRuntime().maxMemory() / (1024 * 1024));
        System.out.println("WRITE_SHA=" + HEX.formatHex(written.digest()));
        System.out.println("READ_SHA=" + HEX.formatHex(read.digest()));
        System.out.println("STORED_SHA=" + info.meta().digest().hexString());
        System.out.println("BYTES_READ=" + bytesRead);
        System.out.println("RANGES_OK=" + rangesOk);
        System.out.println("BLOCK_FILES=" + countFiles(dir));
        System.out.println("WRITE_MS=" + writeMillis);
        System.out.println("READ_MS=" + readMillis);
        System.out.println("DONE");
    }

    private static boolean rangeMatches(ContentStore store, ObjectInfo info, long first, long last) {
        var stream = store.open(info.ref(), ByteRange.byteRange(first, some(last))).await().unwrap();
        var out = new byte[(int) (last - first + 1)];
        var filled = 0;

        for (var next = stream.next().await().unwrap(); next.isPresent(); next = stream.next().await().unwrap()) {
            var buffer = next.or((ByteBuffer) null);
            var count = buffer.remaining();

            buffer.get(out, filled, count);
            filled += count;
        }

        return filled == out.length && java.util.Arrays.equals(out, generated(first, last));
    }

    private static long countFiles(Path dir) throws IOException {
        try (var files = Files.walk(dir)) {
            return files.filter(Files::isRegularFile).count();
        }
    }
}
