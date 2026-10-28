package org.pragmatica.storage;

import org.pragmatica.lang.Option;


/// An inclusive byte range of an object: `first` through `last`, or through the end when `last` is absent. A `last` past
/// the end is clamped to it.
public record ByteRange(long first, Option<Long> last) {
    public static ByteRange byteRange(long first, Option<Long> last) {
        return new ByteRange(first, last);
    }

    /// The whole object.
    public static ByteRange whole() {
        return new ByteRange(0, Option.none());
    }
}
