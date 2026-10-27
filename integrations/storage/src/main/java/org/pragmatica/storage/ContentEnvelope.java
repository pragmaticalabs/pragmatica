package org.pragmatica.storage;

import java.util.Arrays;

import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// The content store's typed envelope around every value it keeps: a fixed marker, then one kind byte, then the payload.
/// Kind `M` is a [ContentManifest], `E` an object entry, `N` a name binding. The kind byte is believed only for a value
/// the store reached through a ref it wrote itself; caller bytes are chunk blocks, which carry no envelope and are reached
/// only through a manifest, so no upload is ever read as an envelope whatever its bytes begin with.
sealed interface ContentEnvelope {
    record unused() implements ContentEnvelope {}

    byte MANIFEST = 'M';
    /// An object entry of the v2 store: the object's metadata and the id of its manifest block.
    byte ENTRY = 'E';
    /// A name binding of the v2 store: the id of the object a name points at.
    byte NAME = 'N';

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
