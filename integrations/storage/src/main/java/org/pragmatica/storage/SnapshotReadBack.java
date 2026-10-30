package org.pragmatica.storage;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.none;


/// Write-time read-back of a snapshot file (#2257): ONE pass over the encoded text, comparing what each
/// line DECODES to with the in-memory value it was rendered from -- epoch, node id, hash, every lifecycle
/// field, every ref name and target -- in the order they were written. Nothing is parsed into a collection
/// first and nothing is compared through the encoder, so an encoder that loses information is seen.
/// The decoders are the codec's own ([SnapshotNameCodec#decodesTo]) and the JDK's number and hex parsers;
/// restore keeps its own line parsers, and the tests restore every file this class accepts.
/// A malformed number or tier name throws, which the caller lifts into a failure.
sealed interface SnapshotReadBack {
    HexFormat HEX = HexFormat.of();

    /// True when `content` reads back as `captured`.
    static boolean matches(MetadataSnapshot captured, String content) {
        var headerEnd = content.indexOf("\n\n");

        return headerEnd >= 0
               && headerMatches(captured,
                                DefaultSnapshotManager.parseHeaders(content.substring(0, headerEnd).split("\n")))
               && bodyMatches(captured, new LineCursor(content, headerEnd));
    }

    private static boolean headerMatches(MetadataSnapshot captured, Map<String, String> headers) {
        return DefaultSnapshotManager.FORMAT_VERSION.equals(headers.get("format"))
               && String.valueOf(captured.epoch()).equals(headers.get("epoch"))
               && captured.nodeId()
                          .equals(headers.get("nodeId"))
               && HEX.formatHex(captured.contentHash()).equals(headers.get("contentHash"));
    }

    private static boolean bodyMatches(MetadataSnapshot captured, LineCursor cursor) {
        return cursor.next()
                     .filter("# Lifecycles"::equals)
                     .isPresent()
               && lifecyclesMatch(captured, cursor)
               && cursor.next()
                        .filter("# Refs"::equals)
                        .isPresent()
               && refsMatch(captured, cursor)
               && cursor.next()
                        .isEmpty();
    }

    private static boolean lifecyclesMatch(MetadataSnapshot captured, LineCursor cursor) {
        for (var lifecycle : captured.lifecycles()) {
            if (cursor.next().filter(line -> lifecycleReadsBack(line, lifecycle)).isEmpty()) {
                return false;
            }
        }

        return true;
    }

    private static boolean refsMatch(MetadataSnapshot captured, LineCursor cursor) {
        for (var ref : captured.refs().entrySet()) {
            if (cursor.next().filter(line -> refReadsBack(line, ref)).isEmpty()) {
                return false;
            }
        }

        return true;
    }

    /// `<64 hex>|<tiers>|<refCount>|<lastAccessedAt>|<createdAt>|<accessCount>|<orphanedAt>`, plus
    /// `|<flags>` when -- and only when -- the record carries a flag (a cache-only marker, a shared-delete authority).
    /// Nothing else follows.
    private static boolean lifecycleReadsBack(String line, BlockLifecycle expected) {
        var bar1 = line.indexOf('|');
        var bar2 = line.indexOf('|', bar1 + 1);
        var bar3 = line.indexOf('|', bar2 + 1);
        var bar4 = line.indexOf('|', bar3 + 1);
        var bar5 = line.indexOf('|', bar4 + 1);
        var bar6 = line.indexOf('|', bar5 + 1);
        var bar7 = bar6 > 0
                   ? line.indexOf('|', bar6 + 1)
                   : -1;
        var orphanedEnd = bar7 < 0
                          ? line.length()
                          : bar7;

        return bar1 == 64
               && bar2 > 0
               && bar3 > 0
               && bar4 > 0
               && bar5 > 0
               && bar6 > 0
               && flagReadsBack(line, bar7, expected)
               && idReadsBack(line, 0, expected.blockId())
               && tiersRead(line.substring(bar1 + 1, bar2)).equals(expected.presentIn())
               && Integer.parseInt(line, bar2 + 1, bar3, 10) == expected.refCount()
               && Long.parseLong(line, bar3 + 1, bar4, 10) == expected.lastAccessedAt()
               && Long.parseLong(line, bar4 + 1, bar5, 10) == expected.createdAt()
               && Integer.parseInt(line, bar5 + 1, bar6, 10) == expected.accessCount()
               && Long.parseLong(line, bar6 + 1, orphanedEnd, 10) == expected.orphanedAt();
    }

    /// No 8th field for a record without flags; exactly its flags ([MetadataSnapshot#lineFlags]: the cache-only marker and
    /// the shared-delete authority, #2258), and nothing after them, for one with any.
    private static boolean flagReadsBack(String line, int bar7, BlockLifecycle expected) {
        var flags = MetadataSnapshot.lineFlags(expected);

        return flags.isEmpty()
               ? bar7 < 0
               : bar7 > 0 && line.substring(bar7 + 1)
                                 .equals(String.join(",", flags));
    }

    /// `<percent-encoded name>|<64 hex>`.
    private static boolean refReadsBack(String line, Map.Entry<String, BlockId> expected) {
        var bar = line.indexOf('|');

        return bar >= 0
               && line.length() - bar - 1 == 64
               && SnapshotNameCodec.decodesTo(line.substring(0, bar), expected.getKey())
               && idReadsBack(line, bar + 1, expected.getValue());
    }

    private static boolean idReadsBack(String line, int from, BlockId expected) {
        return Arrays.equals(HEX.parseHex(line, from, from + 64), expected.hash());
    }

    private static Set<TierLevel> tiersRead(String tiers) {
        var read = EnumSet.noneOf(TierLevel.class);

        if (!tiers.isEmpty()) {
            Arrays.stream(tiers.split(",")).map(TierLevel::valueOf).forEach(read::add);
        }

        return read;
    }

    /// The non-empty lines of `content` from `from` on, one at a time, without splitting the whole text.
    final class LineCursor {
        private final String content;
        private int position;

        LineCursor(String content, int from) {
            this.content = content;
            this.position = from;
        }

        Option<String> next() {
            while (position < content.length() && content.charAt(position) == '\n') {
                position++;
            }

            if (position >= content.length()) {
                return none();
            }

            var end = content.indexOf('\n', position);
            var line = content.substring(position,
                                         end < 0
                                         ? content.length()
                                         : end);

            position += line.length();

            return Option.some(line);
        }
    }

    record unused() implements SnapshotReadBack {}
}
