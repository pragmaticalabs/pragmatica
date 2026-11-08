package org.pragmatica.storage;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.storage.ContentStoreError.General.CONTENT_NOT_FOUND;
import static org.pragmatica.storage.ContentStoreError.General.MANIFEST_PARSE_FAILED;


/// [ContentStore] over one [StorageInstance] on one node. The store keeps the bytes; which objects exist and what they
/// are named is its [Catalog]'s ([LocalCatalog] here: the instance's ref table).
///
/// Credits, which delete gives back exactly once: each chunk block and the manifest block hold the credit their `put`
/// took; the entry and the name binding hold the credit of the ref that names them. The chunks' and the manifest's
/// credits are released with the object, so two objects with identical content share blocks and each frees only its own.
final class LocalContentStore implements ContentStore {
    private static final int MAX_PAGE = 1000;

    private final StorageInstance storage;
    private final ContentStoreConfig config;
    private final Namespace namespace;
    private final ChunkFraming framing;
    private final Catalog catalog;

    LocalContentStore(StorageInstance storage, ContentStoreConfig config, Namespace namespace) {
        this.storage = storage;
        this.config = config;
        this.namespace = namespace;
        this.framing = ChunkFraming.chunkFraming(config.compression());
        this.catalog = LocalCatalog.localCatalog(storage, namespace);
    }

    /// The owner stamped on every object: the namespace the store is bound to, the only identity a local store has. The
    /// principal behind an upload arrives with the ingress that calls this store.
    String owner() {
        return namespace.value();
    }

    String entryRef(ObjectId id) {
        return LocalCatalog.entryRef(namespace, id);
    }

    /// Records a written object's entry: the writer's last step.
    Promise<ObjectInfo> insert(ObjectId id, ObjectEntry entry) {
        return catalog.insert(id, entry);
    }

    // --- Write ---
    @Override
    public Promise<ObjectWriter> create(ObjectSpec spec) {
        return Promise.success(new LocalObjectWriter(this, storage, config, framing, spec, ObjectId.objectId()));
    }

    @Override
    public Promise<ObjectWriter> resume(WriteSessionId id) {
        return ContentStoreError.NotSupported.notSupported("resume").promise();
    }

    /// Linking another namespace's object at zero byte cost needs the shared blocks to stay alive after their first owner is
    /// deleted. A local instance counts references per block and has no mark to say so; crediting every chunk of the
    /// foreign object for the new entry would need a named ref per chunk. The clustered catalog's mark does it, so local
    /// mode refuses.
    @Override
    public Promise<ObjectInfo> link(ObjectRef foreign) {
        return ContentStoreError.NotSupported.notSupported("link").promise();
    }

    // --- Read ---
    @Override
    public Promise<ChunkStream> open(ObjectRef ref, ByteRange range) {
        return readBlock(ref.manifest()).flatMap(bytes -> ContentManifest.fromBytes(bytes).async(MANIFEST_PARSE_FAILED))
                        .flatMap(manifest -> LocalChunkStream.chunkStream(storage,
                                                                          framing,
                                                                          manifest,
                                                                          range,
                                                                          config.readAhead())
                                                             .async());
    }

    @Override
    public Promise<Option<ObjectInfo>> stat(ObjectId id) {
        return catalog.stat(id);
    }

    @Override
    public Promise<Option<ObjectInfo>> lookup(ObjectName name, Option<Long> minVersion) {
        return catalog.lookup(name, minVersion);
    }

    private Promise<byte[]> readBlock(BlockId blockId) {
        return storage.get(blockId)
                      .flatMap(block -> block.async(CONTENT_NOT_FOUND));
    }

    // --- Names and metadata ---
    @Override
    public Promise<ObjectInfo> bind(ObjectName name, ObjectId id, Precondition precondition) {
        return catalog.bind(name, id, precondition);
    }

    @Override
    public Promise<ObjectInfo> update(ObjectId id, MetaPatch patch) {
        return catalog.update(id, patch);
    }

    // --- Delete ---
    /// Releases, never deletes: the object is removed from the catalog (its entry and name give back their own credits),
    /// then the credits the object's chunks and manifest took at write time are released. The manifest is read BEFORE
    /// anything is removed, so an object whose manifest cannot be read fails the delete with nothing changed. The catalog
    /// reports a removed object exactly once ([Catalog#remove]), so two deletes of one object release its blocks once.
    @Override
    public Promise<Unit> delete(ObjectId id) {
        return catalog.stat(id)
                      .flatMap(info -> info.fold(() -> Promise.success(unit()),
                                                 found -> deleteObject(id,
                                                                       found.ref().manifest())));
    }

    private Promise<Unit> deleteObject(ObjectId id, BlockId manifestId) {
        return readBlock(manifestId).flatMap(bytes -> ContentManifest.fromBytes(bytes).async(MANIFEST_PARSE_FAILED))
                        .flatMap(manifest -> catalog.remove(id)
                                                    .flatMap(removed -> removed.fold(() -> Promise.success(unit()),
                                                                                     _ -> releaseObject(manifestId,
                                                                                                        manifest))));
    }

    private Promise<Unit> releaseObject(BlockId manifestId, ContentManifest manifest) {
        var ids = new ArrayList<>(manifest.chunkBlockIds()
                                          .stream()
                                          .flatMap(hex -> BlockId.fromHex(hex).fold(_ -> Stream.<BlockId> empty(),
                                                                                    Stream::of))
                                          .toList());

        ids.add(manifestId);

        return releaseAll(ids);
    }

    /// The releases are independent, so they are started together: no chain deeper than the object has blocks.
    private Promise<Unit> releaseAll(List<BlockId> ids) {
        return gather(ids.stream().map(storage::release).toList()).map(_ -> unit());
    }

    /// All of `promises`, in order, without chaining them: a namespace or an object of any size resolves in constant stack.
    static <T> Promise<List<T>> gather(List<Promise<T>> promises) {
        return gather(promises, Promise::onResult);
    }

    /// `subscribe` registers a handler on an input; the seam lets a test run the handlers in an order a real promise never
    /// produces on demand (a handler registered while its input is being resolved runs before the ones registered earlier).
    static <T> Promise<List<T>> gather(List<Promise<T>> promises,
                                       BiConsumer<Promise<T>, Consumer<Result<T>>> subscribe) {
        if (promises.isEmpty()) {
            return Promise.success(List.of());
        }

        var all = Promise.<List<T>> promise();
        var results = new java.util.concurrent.atomic.AtomicReferenceArray<T>(promises.size());
        var remaining = new java.util.concurrent.atomic.AtomicInteger(promises.size());
        var failed = new java.util.concurrent.atomic.AtomicBoolean();

        for (var i = 0; i < promises.size(); i++) {
            var index = i;
            // detached: `all` is what callers wait on and it resolves inside the last callback; nothing chains on one promise
            subscribe.accept(promises.get(index),
                             result -> {
                                 result.onSuccess(value -> results.set(index, value))
                                       .onFailure(cause -> {
                                     if (failed.compareAndSet(false, true)) {
                                         all.fail(cause);
                                     }
                                 });
                                 if (remaining.decrementAndGet() == 0 && !failed.get()) {
                                     var values = new ArrayList<T>(results.length());

                                     for (var j = 0; j < results.length(); j++) {
                                         values.add(results.get(j));
                                     }

                                     all.succeed(values);
                                 }
                             });
        }

        return all;
    }

    // --- List ---
    @Override
    public Promise<Page<ObjectInfo>> list(Option<String> namePrefix, Option<Cursor> after, int limit) {
        if (limit < 1 || limit > MAX_PAGE) {
            return ContentStoreError.InvalidRange.invalidRange("limit " + limit + " is not between 1 and " + MAX_PAGE).promise();
        }

        return catalog.list(namePrefix, after, limit);
    }
}
