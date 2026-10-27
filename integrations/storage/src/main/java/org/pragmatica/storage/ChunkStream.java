package org.pragmatica.storage;

import java.nio.ByteBuffer;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


/// A pull-based read of a byte range. `next` resolves to the following slice of the range, in order, and to none at its
/// end. At most `read_ahead + 1` blocks are held on the heap at any moment. A block that fails its integrity check
/// fails the `next` that reaches it.
public interface ChunkStream {
    Promise<Option<ByteBuffer>> next();

    Promise<Unit> close();
}
