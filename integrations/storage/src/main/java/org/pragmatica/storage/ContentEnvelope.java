package org.pragmatica.storage;

import java.util.Arrays;

import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// The content store's typed envelope around every value it stores under a name: a fixed marker, then one kind byte,
/// then the payload. Kind `D` is the caller's (possibly compressed) content, kind `M` a [ContentManifest].
///
/// It is what keeps a manifest out of band: whether a value is a manifest is decided by the kind byte the store itself
/// wrote, never by what the value's payload begins with, so content a caller uploads — whatever its bytes — is always
/// kind `D` and is returned verbatim. Unnamed chunk blocks carry no envelope; they are reached only through a manifest.
///
/// The marker begins with a NUL byte, which a legacy text manifest (`AHSE-MANIFEST:`) never does. A value without the
/// marker was written before the envelope existed and is read the legacy way (see [ContentManifest]).
sealed interface ContentEnvelope {
    record unused() implements ContentEnvelope {}

    byte DIRECT = 'D';
    byte MANIFEST = 'M';

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
                            .map(_ -> Arrays.copyOfRange(value, marker().length + 1, value.length));
    }
}
