package org.pragmatica.storage;

import java.nio.ByteBuffer;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


/// Streams one object into a store. Calls must not overlap: await each `write` before the next call.
///
/// `write` copies what it is given before it returns, so the caller may reuse its buffer at once; it resolves only when
/// fewer than `write_window` blocks are still being stored, which is the backpressure seam: a producer that awaits each
/// write never has more than `write_window + 1` blocks of the object on the heap.
///
/// Nothing is visible to readers until `commit` resolves. A writer that fails, or is aborted, gives back every credit it
/// took.
public interface ObjectWriter {
    Promise<Unit> write(ByteBuffer bytes);

    /// Not available in local mode: resolves to [ContentStoreError.NotSupported].
    Promise<WriteCheckpoint> checkpoint();

    /// Seals the object. `expected`, when present, is compared with the SHA-256 of the bytes received, and a mismatch
    /// fails the commit and releases everything the writer stored.
    Promise<ObjectInfo> commit(Option<Sha256> expected);

    /// Gives back what the writer stored. A writer already committed or aborted is left as it is.
    Promise<Unit> abort();
}
