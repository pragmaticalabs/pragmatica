package org.pragmatica.storage;

import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;


/// How a tier lays out and frames anchor versions; shared by [LocalDiskTier] and [RemoteTier] so both hold the same
/// bytes under the same keys.
///
/// **Keys.** Version `v` of anchor `n` is stored as `anchors/<n.storageSegment()>/<key(v)>`, where `key(v)` is `v`
/// followed by `Long.MAX_VALUE - v` zero-padded to 19 digits. Fixed width makes lexicographic order numeric order, and
/// the inversion puts the HIGHEST version first: S3 lists keys in ascending order, so the newest version is the first
/// key of a one-key LIST however many versions exist, and on disk it is the smallest file name. The `v` marker is what an
/// S3 LIST names as its prefix ([#KEY_MARKER]), so an object that is no version key of the anchor -- the empty
/// "folder" object an S3 console creates at `anchors/<segment>/`, for one -- is never the key a one-key LIST returns
/// unless it starts with the marker, and then it fails the read closed ([AnchorError.AnchorKeyMalformed]).
///
/// **Frame.** `format (1 byte, = 1) | version (8) | value length (4) | value | CRC32C of everything before it (4)`.
/// The version is repeated inside the frame so a file renamed or copied onto another version's key is refused.
final class AnchorFrame {
    static final String DIRECTORY = "anchors";
    /// The first character of every version key.
    static final String KEY_MARKER = "v";
    private static final String MAX_DIGITS = Long.toString(Long.MAX_VALUE);
    private static final byte FORMAT = 1;
    private static final int KEY_DIGITS = 19;
    private static final int HEADER_BYTES = 1 + Long.BYTES + Integer.BYTES;
    private static final int TRAILER_BYTES = Integer.BYTES;

    private AnchorFrame() {}

    /// The key of `version`; `version` must be at least 0.
    static String key(long version) {
        return KEY_MARKER + String.format("%0" + KEY_DIGITS + "d", Long.MAX_VALUE - version);
    }

    /// The version a key names; none for anything that is not a key: a partial file, a stray name, or 19 digits above
    /// `Long.MAX_VALUE`, which no version maps to.
    static Option<Long> versionOf(String key) {
        return Option.some(key)
                     .filter(candidate -> candidate.startsWith(KEY_MARKER))
                     .map(candidate -> candidate.substring(KEY_MARKER.length()))
                     .filter(AnchorFrame::isInvertedVersion)
                     .map(digits -> Long.MAX_VALUE - Long.parseLong(digits));
    }

    private static boolean isInvertedVersion(String digits) {
        return digits.length() == KEY_DIGITS
               && digits.chars()
                        .allMatch(Character::isDigit)
               && digits.compareTo(MAX_DIGITS) <= 0;
    }

    static byte[] encode(long version, byte[] value) {
        var buffer = ByteBuffer.allocate(HEADER_BYTES + value.length + TRAILER_BYTES)
                               .put(FORMAT)
                               .putLong(version)
                               .putInt(value.length)
                               .put(value);

        return buffer.putInt(crcOf(buffer.array(),
                                   HEADER_BYTES + value.length))
                     .array();
    }

    /// The value of `frame`, which was read from the key of `version` on `tier`; [AnchorError.AnchorCorrupt] when the
    /// frame is damaged or names another version.
    static Result<Anchor> decode(TierLevel tier, AnchorName name, long version, byte[] frame) {
        return problemOf(version, frame).fold(() -> Result.success(Anchor.anchor(version, valueOf(frame))),
                                              detail -> AnchorError.AnchorCorrupt.anchorCorrupt(tier,
                                                                                                name,
                                                                                                version,
                                                                                                detail).result());
    }

    private static Option<String> problemOf(long version, byte[] frame) {
        if (frame.length < HEADER_BYTES + TRAILER_BYTES) {
            return Option.some("truncated: " + frame.length + " bytes");
        }

        var buffer = ByteBuffer.wrap(frame);
        var format = buffer.get();
        var framedVersion = buffer.getLong();
        var length = buffer.getInt();

        if (format != FORMAT) {
            return Option.some("unknown format " + (format & 0xFF));
        }

        if (length < 0 || length != frame.length - HEADER_BYTES - TRAILER_BYTES) {
            return Option.some("value length " + length + " does not match the " + frame.length + "-byte frame");
        }

        if (crcOf(frame, frame.length - TRAILER_BYTES) != ByteBuffer.wrap(frame,
                                                                          frame.length - TRAILER_BYTES,
                                                                          TRAILER_BYTES).getInt()) {
            return Option.some("checksum mismatch");
        }

        return framedVersion == version
               ? Option.none()
               : Option.some("frame holds version " + framedVersion);
    }

    private static byte[] valueOf(byte[] frame) {
        var value = new byte[frame.length - HEADER_BYTES - TRAILER_BYTES];

        System.arraycopy(frame, HEADER_BYTES, value, 0, value.length);

        return value;
    }

    private static int crcOf(byte[] bytes, int length) {
        var crc = new CRC32C();

        crc.update(bytes, 0, length);

        return (int) crc.getValue();
    }
}
