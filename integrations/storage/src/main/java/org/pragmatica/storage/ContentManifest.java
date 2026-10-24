package org.pragmatica.storage;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.parse.Number;
import org.pragmatica.lang.utils.Causes;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// Manifest linking chunks of large content, in the binary v2 framing.
///
/// Serialized inside the content store's typed envelope ([ContentEnvelope]) as kind `M`: a version byte
/// ([#FORMAT_VERSION]), the chunk size, the total size, the SHA-256 of the whole content (32 bytes), the chunk count,
/// then each chunk id as length-prefixed bytes. The header carries the fields the object manifest will keep, so its
/// layout does not change again when they are first read; nothing reads `chunkSize` or `sha256` yet. Two-level
/// segments above 4096 chunks and the `SEGMENT` and `CATALOG_PAGE` kinds are not here: they come later, under a
/// version bump. The manifest names
/// no object: it is reached only from the ref the store wrote under a TRUSTED name (see [ContentEnvelope#trustedRef]),
/// so there is no name to escape and no separator. Whether a value is a manifest is decided out of band, by that ref and
/// the envelope kind the store itself wrote; content is never sniffed, so an upload whose bytes happen to be a valid
/// manifest of any format is returned verbatim. A version this build does not know is refused, never guessed at.
///
/// The pre-envelope text format, pipe-delimited behind the magic `AHSE-MANIFEST:`, is still READ for a LEGACY name (a bare
/// ref, which only a store from before the trusted namespace can have written), so content stored before the upgrade
/// stays readable and its delete still releases its chunks. A legacy value is ambiguous by construction: a pre-upgrade
/// upload that began with that magic is indistinguishable from a pre-upgrade manifest, so that one hazard remains for
/// legacy data and ends when the name is rewritten (which moves it to the trusted ref) or deleted. A legacy value is never
/// read as an envelope. Legacy manifests are parsed from the right, so a legacy name containing `|` is read too.
///
/// `chunkSize` is 0 and `sha256` is empty on a manifest read from the legacy text format, which recorded neither; such
/// a manifest is read, never written back.
public record ContentManifest(int chunkSize,
                              long totalSize,
                              String sha256,
                              int chunkCount,
                              List<String> chunkBlockIds) {
    static final String MAGIC_HEADER = "AHSE-MANIFEST:";
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

    private static ContentManifest legacyManifest(long totalSize, List<String> chunkBlockIds) {
        return new ContentManifest(0, totalSize, "", chunkBlockIds.size(), chunkBlockIds);
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

    /// Parse an enveloped manifest, the form the content store writes under its trusted names. Returns None for
    /// anything else, enveloped direct content, an unknown version and legacy text manifests included (a legacy text
    /// manifest is read only for a legacy name, through [#fromLegacyBytes]).
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
        var ids = new ArrayList<String>(Math.max(0,
                                                 Math.min(count, buffer.remaining() / Integer.BYTES)));

        for (int i = 0; i < count; i++) {
            ids.add(readId(buffer));
        }

        return buffer.hasRemaining() || count < 1
               ? none()
               : some(contentManifest(chunkSize, totalSize, sha256, ids));
    }

    private static String readSha256(ByteBuffer buffer) {
        var bytes = new byte[SHA256_BYTES];

        buffer.get(bytes);

        return HexFormat.of().formatHex(bytes);
    }

    private static String readId(ByteBuffer buffer) {
        var bytes = new byte[buffer.getInt()];

        buffer.get(bytes);

        return HexFormat.of().formatHex(bytes);
    }

    /// The pre-envelope text format, `AHSE-MANIFEST:name|size|count|id...`.
    static Option<ContentManifest> fromLegacyBytes(byte[] data) {
        var headerBytes = MAGIC_HEADER.getBytes(StandardCharsets.UTF_8);

        if (data.length < headerBytes.length || !Arrays.equals(data,
                                                               0,
                                                               headerBytes.length,
                                                               headerBytes,
                                                               0,
                                                               headerBytes.length)) {
            return none();
        }

        return parseLegacyBody(new String(data, StandardCharsets.UTF_8).substring(MAGIC_HEADER.length()));
    }

    /// Parsed from the right: the last `count` fields are the chunk ids, the two before them size and count, and
    /// everything before those is the name, `|` and all.
    private static Option<ContentManifest> parseLegacyBody(String body) {
        var parts = body.split("\\|", -1);

        for (int count = parts.length - 3; count >= 1; count--) {
            var countIndex = parts.length - count - 1;
            var candidate = legacyCandidate(parts, countIndex, count);

            if (candidate.isPresent()) {
                return candidate;
            }
        }

        return none();
    }

    private static Option<ContentManifest> matchingCount(long totalSize, int declared, int count, List<String> ids) {
        return declared == count
               ? some(legacyManifest(totalSize, ids))
               : none();
    }

    private static Option<ContentManifest> legacyCandidate(String[] parts, int countIndex, int count) {
        var ids = Arrays.asList(parts).subList(countIndex + 1, parts.length);

        return Result.all(Number.parseLong(parts[countIndex - 1]),
                          Number.parseInt(parts[countIndex]))
                     .map((totalSize, declared) -> matchingCount(totalSize, declared, count, ids))
                     .option()
                     .flatMap(opt -> opt);
    }
}
