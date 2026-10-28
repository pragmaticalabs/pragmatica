package org.pragmatica.storage;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// Manifest linking the chunks of an object, in the binary v2 framing.
///
/// Serialized inside the content store's typed envelope ([ContentEnvelope]) as kind `M`: a version byte
/// ([#FORMAT_VERSION]), the chunk size, the total size, the SHA-256 of the whole content (32 bytes), the chunk count,
/// then each chunk id as length-prefixed bytes. Two-level segments above 4096 chunks and the `SEGMENT` and
/// `CATALOG_PAGE` kinds are not here: they come later, under a version bump. The manifest names no object: it is reached
/// only from an object entry the store wrote, so there is no name to escape and no separator. Content is never sniffed,
/// so an upload whose bytes happen to be a valid manifest of any format is stored as chunk blocks and returned verbatim.
/// A version this build does not know is refused, never guessed at.
public record ContentManifest(int chunkSize,
                              long totalSize,
                              String sha256,
                              int chunkCount,
                              List<String> chunkBlockIds) {
    static final byte FORMAT_VERSION = 2;
    private static final int SHA256_BYTES = 32;

    public ContentManifest {
        chunkBlockIds = List.copyOf(chunkBlockIds);
    }

    /// A manifest of `chunkSize` chunks of content of `totalSize` bytes whose SHA-256 is `sha256` (64 hex characters).
    public static ContentManifest contentManifest(int chunkSize,
                                                  long totalSize,
                                                  String sha256,
                                                  List<String> chunkBlockIds) {
        return new ContentManifest(chunkSize, totalSize, sha256, chunkBlockIds.size(), chunkBlockIds);
    }

    /// Serialize manifest to bytes for storage: envelope kind `M`, version byte, length-prefixed ids.
    public byte[] toBytes() {
        var idBytes = chunkBlockIds.stream().map(id -> HexFormat.of().parseHex(id)).toList();
        var size = Byte.BYTES + Integer.BYTES + Long.BYTES + SHA256_BYTES + Integer.BYTES + idBytes.stream()
                                                                                                   .mapToInt(id -> Integer.BYTES + id.length)
                                                                                                   .sum();
        var body = ByteBuffer.allocate(size)
                             .put(FORMAT_VERSION)
                             .putInt(chunkSize)
                             .putLong(totalSize)
                             .put(HexFormat.of().parseHex(sha256))
                             .putInt(chunkCount);

        idBytes.forEach(id -> body.putInt(id.length)
                                  .put(id));

        return ContentEnvelope.wrap(ContentEnvelope.MANIFEST, body.array());
    }

    /// Parse an enveloped manifest, the form the content store writes. Returns None for anything else: another envelope
    /// kind, an unknown version, unenveloped bytes.
    public static Option<ContentManifest> fromBytes(byte[] data) {
        return ContentEnvelope.payloadOf(ContentEnvelope.MANIFEST, data).flatMap(ContentManifest::parseEnveloped);
    }

    static Option<ContentManifest> parseEnveloped(byte[] payload) {
        return Result.lift(Causes::fromThrowable,
                           () -> decode(ByteBuffer.wrap(payload)))
                     .option()
                     .flatMap(manifest -> manifest);
    }

    private static Option<ContentManifest> decode(ByteBuffer buffer) {
        if (buffer.get() != FORMAT_VERSION) {
            return none();
        }

        var chunkSize = buffer.getInt();
        var totalSize = buffer.getLong();
        var sha256 = readSha256(buffer);
        var count = buffer.getInt();

        return readIds(buffer, count).filter(_ -> !buffer.hasRemaining() && count >= 1)
                      .map(ids -> contentManifest(chunkSize, totalSize, sha256, ids));
    }

    private static Option<List<String>> readIds(ByteBuffer buffer, int count) {
        var ids = new ArrayList<String>(Math.max(0,
                                                 Math.min(count, buffer.remaining() / Integer.BYTES)));

        for (int i = 0; i < count; i++) {
            var id = readId(buffer);

            if (id.isEmpty()) {
                return none();
            }

            id.onPresent(ids::add);
        }

        return some(ids);
    }

    private static String readSha256(ByteBuffer buffer) {
        return readHex(buffer, SHA256_BYTES);
    }

    /// An id whose stored length is negative or runs past the bytes left is refused before anything is allocated for
    /// it, so a corrupt length cannot make the decode allocate or fail with an error [Result#lift] does not catch.
    private static Option<String> readId(ByteBuffer buffer) {
        var length = buffer.getInt();

        return length < 0 || length > buffer.remaining()
               ? none()
               : some(readHex(buffer, length));
    }

    private static String readHex(ByteBuffer buffer, int length) {
        var bytes = new byte[length];

        buffer.get(bytes);

        return HexFormat.of().formatHex(bytes);
    }
}
