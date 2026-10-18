package org.pragmatica.storage;

import java.util.Arrays;

import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// The content store's typed envelope around every value it stores under a name: a fixed marker, then one kind byte,
/// then the payload. Kind `D` is the caller's (possibly compressed) content, kind `M` a [ContentManifest].
///
/// F7: the kind byte is believed ONLY for a value stored under a trusted ref ([#trustedRef]), a ref name that no value
/// written before this scheme can carry. Every content-store write goes there, so the kind byte of a trusted value is
/// always one the store itself wrote, and an upload (whatever its bytes) is kind `D`, returned verbatim. A value under
/// a bare (legacy) name is never read as an envelope, so a legacy upload that happens to begin with the marker is
/// returned verbatim too. Unnamed chunk blocks carry no envelope; they are reached only through a manifest.
sealed interface ContentEnvelope {
    record unused() implements ContentEnvelope {}

    byte DIRECT = 'D';
    byte MANIFEST = 'M';
    String TRUSTED_REF_PREFIX = "|";

    /// The ref under which the content store keeps `name`'s value: `|` + name.
    ///
    /// Why no legacy value can be found under it: ref names reach disk only in metadata snapshots, and a snapshot
    /// written before the escaped ref-name encoding stored names raw and was read back by splitting each line at its
    /// FIRST `|` (see `DefaultSnapshotManager.parseRefLine`), so a name containing `|` never survived a restart (its
    /// line failed to parse, and its snapshot failed its integrity hash). An upgrade is a restart, so after it every
    /// ref whose name contains `|` was written by this code. A raw [StorageInstance] user can write any ref; the
    /// guarantee is about values that arrived through the content store.
    static String trustedRef(String name) {
        return TRUSTED_REF_PREFIX + name;
    }

    static byte[] marker() {
        return new byte[]{0x00, 'A', 'H', 'S', 'E', 0x02};
    }

    static byte[] wrap(byte kind, byte[] payload) {
        var marker = marker();
        var wrapped = new byte[marker.length + 1 + payload.length];

        System.arraycopy(marker, 0, wrapped, 0, marker.length);
        wrapped[marker.length] = kind;
        System.arraycopy(payload, 0, wrapped, marker.length + 1, payload.length);

        return wrapped;
    }

    static boolean isEnveloped(byte[] value) {
        var marker = marker();

        return value.length > marker.length && Arrays.equals(value, 0, marker.length, marker, 0, marker.length);
    }

    static Option<Byte> kindOf(byte[] value) {
        return isEnveloped(value)
               ? some(value[marker().length])
               : none();
    }

    /// The payload of an enveloped value of the given kind; empty for any other kind or an unenveloped value.
    static Option<byte[]> payloadOf(byte kind, byte[] value) {
        return kindOf(value).filter(actual -> actual == kind)
                     .map(_ -> Arrays.copyOfRange(value,
                                                  marker().length + 1,
                                                  value.length));
    }
}
