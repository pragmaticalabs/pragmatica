package org.pragmatica.storage;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.storage.ContentStoreError.General.WRITER_CLOSED;


/// Streams one object into the instance as it arrives. Bytes are cut into `chunkSize` chunks; each chunk is hashed into the
/// whole-object digest, framed and stored as one block, with at most `writeWindow` blocks being stored while the next
/// chunk fills. `write` holds on to the caller's buffer until it has consumed all of it, and resolves then: a producer that
/// awaits each write is held back as soon as the window is full, so the object never sits on the heap in more than
/// `writeWindow + 1` chunks.
///
/// Every block `put` credits is the writer's until the object is committed. A writer that fails, is aborted, or whose
/// commit fails after the blocks were stored gives back exactly the credits it took, and only those: a block shared with
/// another object by deduplication keeps that object's credit.
final class LocalObjectWriter implements ObjectWriter {
    private static final Logger log = LoggerFactory.getLogger(LocalObjectWriter.class);

    private final LocalContentStore store;
    private final StorageInstance storage;
    private final ContentStoreConfig config;
    private final ChunkFraming framing;
    private final ObjectSpec spec;
    private final ObjectId id;
    private final Object lock = new Object();
    private final IncrementalSha256 whole = IncrementalSha256.incrementalSha256();
    private final List<Promise<BlockId>> stored = new ArrayList<>();
    private byte[] buffer;
    private int fill;
    private long total;
    private int inFlight;
    private Cause failure;
    private boolean closed;
    private ByteBuffer input;
    private Promise<Unit> pendingWrite;
    private Promise<Unit> unwinding;
    private boolean pumping;

    LocalObjectWriter(LocalContentStore store,
                      StorageInstance storage,
                      ContentStoreConfig config,
                      ChunkFraming framing,
                      ObjectSpec spec,
                      ObjectId id) {
        this.store = store;
        this.storage = storage;
        this.config = config;
        this.framing = framing;
        this.spec = spec;
        this.id = id;
        this.buffer = new byte[config.chunkSizeBytes()];
    }

    @Override
    public Promise<Unit> write(ByteBuffer bytes) {
        Promise<Unit> done;
        Option<Release> release;

        synchronized (lock) {
            if (failure != null) {
                return failure.promise();
            }

            if (closed) {
                return WRITER_CLOSED.promise();
            }

            done = Promise.promise();
            pendingWrite = done;
            input = bytes;
            release = pump();
        }

        release.onPresent(Release::resolve);

        return done;
    }

    /// Moves input into the chunk buffer, dispatching each full chunk while the window has room; with the window full it
    /// stops, and the completion of a stored block calls it again. Releases the pending write when the input is consumed or a
    /// block failed; the caller resolves it after leaving the lock. Resolving runs the producer's continuation, which may call
    /// `write` at once: inside the pump that `write` would find the pump busy, and once the block that settled it was the
    /// last one being stored nothing would pump again. Called with the lock held.
    private Option<Release> pump() {
        if (pumping) {
            return Option.none();
        }

        pumping = true;
        try {
            return settleWrite();
        } finally {
            pumping = false;
        }
    }

    /// A block store that completes while `dispatch` is still on the stack calls [#pump] again from inside the loop; the
    /// outer loop is the one that continues, so the nested call returns at once.
    private Option<Release> settleWrite() {
        var settle = failure == null
                     ? consume()
                     : false;

        if (pendingWrite == null || (failure == null && !settle)) {
            return Option.none();
        }

        var release = new Release(pendingWrite, Option.option(failure));

        pendingWrite = null;
        input = null;

        return Option.some(release);
    }

    /// A pending write the pump has finished with, resolved outside the lock.
    private record Release(Promise<Unit> write, Option<Cause> failure) {
        Promise<Unit> resolve() {
            return failure.fold(() -> write.succeed(unit()), write::fail);
        }
    }

    private boolean consume() {
        while (input != null && input.hasRemaining()) {
            if (fill == buffer.length) {
                if (inFlight >= config.writeWindow()) {
                    return false;
                }

                dispatch();
                if (failure != null) {
                    return false;
                }
            }

            var count = Math.min(input.remaining(), buffer.length - fill);

            input.get(buffer, fill, count);
            fill += count;
        }

        return true;
    }

    /// Hashes, frames and starts storing the current chunk. Called with the lock held.
    private Unit dispatch() {
        var chunk = buffer;
        var length = fill;

        whole.update(chunk, 0, length);
        total += length;
        buffer = new byte[config.chunkSizeBytes()];
        fill = 0;

        return framing.frame(chunk, length)
                      .fold(this::markFailed, this::startStoring);
    }

    /// The first failure is the writer's. Unless a commit or abort already owns the cleanup, the writer unwinds itself: it
    /// is closed, and once every block store has settled it gives back the credits of those that succeeded. Called with the
    /// lock held.
    private Unit markFailed(Cause cause) {
        if (failure == null) {
            failure = cause;
        }

        if (!closed) {
            closed = true;
            unwinding = settled(List.copyOf(stored)).flatMap(results -> rollBack(creditedBy(results)));
        }

        return unit();
    }

    private Unit startStoring(byte[] block) {
        inFlight++;
        stored.add(storage.put(block).withResult(this::blockSettled));

        return unit();
    }

    private void blockSettled(Result<BlockId> result) {
        Option<Release> release;

        synchronized (lock) {
            inFlight--;
            result.fold(this::markFailed, _ -> unit());
            release = pump();
        }

        release.onPresent(Release::resolve);
    }

    @Override
    public Promise<WriteCheckpoint> checkpoint() {
        return ContentStoreError.NotSupported.notSupported("checkpoint").promise();
    }

    @Override
    public Promise<ObjectInfo> commit(Option<Sha256> expected) {
        List<Promise<BlockId>> blocks;
        Sha256 digest;
        long size;

        synchronized (lock) {
            if (closed) {
                return unwinding != null
                       ? unwinding.flatMap(_ -> failure.<ObjectInfo> promise())
                       : WRITER_CLOSED.promise();
            }

            closed = true;
            if (failure == null && (fill > 0 || stored.isEmpty())) {
                dispatch();
            }

            blocks = List.copyOf(stored);
            digest = whole.digest();
            size = total;
        }

        return settled(blocks).flatMap(results -> finish(results, digest, size, expected));
    }

    private Promise<ObjectInfo> finish(List<Result<BlockId>> results,
                                       Sha256 digest,
                                       long size,
                                       Option<Sha256> expected) {
        var credited = creditedBy(results);

        return problemWith(results, digest, size, expected).fold(() -> writeManifestAndEntry(credited, digest, size),
                                                                 cause -> rollBack(credited).flatMap(_ -> cause.promise()));
    }

    private static List<BlockId> creditedBy(List<Result<BlockId>> results) {
        var credited = new ArrayList<BlockId>();

        results.forEach(result -> result.onSuccess(credited::add));

        return credited;
    }

    /// Why this object cannot be committed, if it cannot: a block that failed, a length other than the declared one, or
    /// bytes whose digest is not the expected one.
    private Option<Cause> problemWith(List<Result<BlockId>> results,
                                      Sha256 digest,
                                      long size,
                                      Option<Sha256> expected) {
        synchronized (lock) {
            if (failure != null) {
                return Option.some(failure);
            }
        }

        for (var result : results) {
            var failed = result.fold(Option::some, _ -> Option.<Cause> none());

            if (failed.isPresent()) {
                return failed;
            }
        }

        return spec.declaredLength()
                   .filter(declared -> declared != size)
                   .<Cause> map(declared -> ContentStoreError.LengthMismatch.lengthMismatch(declared, size))
                   .orElse(() -> expected.filter(sha -> !sha.equals(digest))
                                         .map(sha -> ContentStoreError.DigestMismatch.digestMismatch(sha.hexString(),
                                                                                                     digest.hexString())));
    }

    /// The manifest first (its own credit, released with the object), then the entry, in one write-and-ref call: the pair
    /// `put` then `createRef` would credit the entry twice and it could never be collected (#812). A failure at either step
    /// gives back what the steps before it took, and the failure is reported only after that.
    private Promise<ObjectInfo> writeManifestAndEntry(List<BlockId> chunks, Sha256 digest, long size) {
        var manifest = ContentManifest.contentManifest(config.chunkSizeBytes(),
                                                       size,
                                                       digest.hexString(),
                                                       chunks.stream().map(BlockId::hexString).toList());

        return unwindOnFailure(storage.put(manifest.toBytes())
                                      .flatMap(manifestId -> unwindOnFailure(writeEntry(manifestId, digest, size),
                                                                             List.of(manifestId))),
                               chunks);
    }

    private Promise<ObjectInfo> writeEntry(BlockId manifestId, Sha256 digest, long size) {
        var meta = ObjectMeta.objectMeta(spec.displayName(),
                                         spec.contentType(),
                                         size,
                                         digest,
                                         store.owner(),
                                         Instant.now(),
                                         spec.expiresAt(),
                                         spec.attributes());
        var entry = ObjectEntry.objectEntry(manifestId, meta);

        return entry.toBytes()
                    .async()
                    .flatMap(bytes -> storage.swapRef(store.entryRef(id),
                                                      bytes))
                    .map(_ -> entry.info(store.namespaceValue(),
                                         id));
    }

    private <T> Promise<T> unwindOnFailure(Promise<T> attempt, List<BlockId> credited) {
        var outcome = Promise.<T> promise();

        attempt.onResult(result -> result.onSuccess(outcome::succeed)
                                         .onFailure(cause -> rollBack(credited).onResult(_ -> outcome.fail(cause))));

        return outcome;
    }

    @Override
    public Promise<Unit> abort() {
        List<Promise<BlockId>> blocks;
        Option<Promise<Unit>> held;

        synchronized (lock) {
            if (closed) {
                return unwinding != null
                       ? unwinding
                       : Promise.success(unit());
            }

            closed = true;
            blocks = List.copyOf(stored);
            held = Option.option(pendingWrite);
            pendingWrite = null;
            input = null;
        }
        // a write held back by the full window fails rather than waiting forever: an ingress that aborts on disconnect may
        // still be awaiting it
        held.onPresent(write -> write.fail(WRITER_CLOSED));

        return settled(blocks).flatMap(results -> rollBack(creditedBy(results)));
    }

    /// Waits for every block store to finish, successful or not: a rollback must know which credits were really taken.
    private static Promise<List<Result<BlockId>>> settled(List<Promise<BlockId>> blocks) {
        var all = Promise.<List<Result<BlockId>>> promise();
        var results = new ArrayList<Result<BlockId>>();

        for (var i = 0; i < blocks.size(); i++) {
            results.add(null);
        }

        if (blocks.isEmpty()) {
            return Promise.success(List.of());
        }

        var remaining = new AtomicInteger(blocks.size());

        for (var i = 0; i < blocks.size(); i++) {
            var index = i;
            // detached: `all` is what callers wait on and it resolves inside the last callback; nothing chains on one block
            blocks.get(index)
                  .onResult(result -> {
                                synchronized (results) {
                                    results.set(index, result);
                                }

                                if (remaining.decrementAndGet() == 0) {
                                    all.succeed(List.copyOf(results));
                                }
                            });
        }

        return all;
    }

    private Promise<Unit> rollBack(List<BlockId> credited) {
        return releaseAll(credited);
    }

    private Promise<Unit> releaseAll(List<BlockId> ids) {
        return LocalContentStore.gather(ids.stream().map(this::release).toList()).map(_ -> unit());
    }

    /// A release that fails is logged and does not mask the failure that caused the rollback.
    private Promise<Unit> release(BlockId id) {
        var done = Promise.<Unit> promise();

        storage.release(id)
               .onResult(result -> {
                             result.onFailure(cause -> log.warn("Rollback of a failed object write could not release {}: {}",
                                                                id,
                                                                cause.message()));
                             done.succeed(unit());
                         });

        return done;
    }
}
