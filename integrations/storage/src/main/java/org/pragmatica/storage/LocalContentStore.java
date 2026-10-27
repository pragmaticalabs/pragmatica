package org.pragmatica.storage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.storage.ContentStoreError.General.CONTENT_NOT_FOUND;
import static org.pragmatica.storage.ContentStoreError.General.ENTRY_PARSE_FAILED;
import static org.pragmatica.storage.ContentStoreError.General.MANIFEST_PARSE_FAILED;


/// [ContentStoreV2] over one [StorageInstance] on one node.
///
/// Keys, all inside the instance's ref table and all carrying the namespace, so a store can address its own namespace and
/// no other:
/// - `o/<namespace>/<object id>` -> the object-entry block (envelope kind `E`, see [ObjectEntry]): metadata and manifest id.
/// - `n/<namespace>/<name>` -> a name-binding block (envelope kind `N`): the id of the object the name points at.
///
/// Credits, which delete gives back exactly once: each chunk block and the manifest block hold the credit their `put`
/// took; the entry and the name binding hold the credit of the ref that names them. The chunks' and the manifest's
/// credits are released with the object, so two objects with identical content share blocks and each frees only its own.
final class LocalContentStore implements ContentStoreV2 {
    private static final String ENTRY_PREFIX = "o/";
    private static final String NAME_PREFIX = "n/";
    private static final int MAX_PAGE = 1000;

    private final StorageInstance storage;
    private final ContentStoreConfig config;
    private final Namespace namespace;
    private final ChunkFraming framing;

    LocalContentStore(StorageInstance storage, ContentStoreConfig config, Namespace namespace) {
        this.storage = storage;
        this.config = config;
        this.namespace = namespace;
        this.framing = ChunkFraming.chunkFraming(config.compression());
    }

    String namespaceValue() {
        return namespace.value();
    }

    /// The owner stamped on every object: the namespace the store is bound to, the only identity a local store has. The
    /// principal behind an upload arrives with the ingress that calls this store.
    String owner() {
        return namespace.value();
    }

    String entryRef(ObjectId id) {
        return entryPrefix() + id.asString();
    }

    private String entryPrefix() {
        return ENTRY_PREFIX + namespace.value() + "/";
    }

    private String nameRef(ObjectName name) {
        return NAME_PREFIX + namespace.value() + "/" + name.value();
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

    /// The name an entry records is a hint: the name binding is the authority (see [#bind]). A recorded name that does not
    /// point back at the object is not reported.
    @Override
    public Promise<Option<ObjectInfo>> stat(ObjectId id) {
        return readEntry(id).flatMap(entry -> entry.fold(() -> Promise.success(Option.<ObjectInfo> none()),
                                                         found -> confirmedName(id, found).map(confirmed -> some(confirmed.info(namespace.value(),
                                                                                                                                id)))));
    }

    private Promise<ObjectEntry> confirmedName(ObjectId id, ObjectEntry entry) {
        return entry.name()
                    .fold(() -> Promise.success(entry),
                          name -> holderOf(name).map(holder -> holder.filter(id::equals)
                                                                     .isPresent()
                                                               ? entry
                                                               : entry.named(none(),
                                                                             entry.alsoBound())
                                                                      .withVersion(entry.catalogVersion())));
    }

    /// The object a name points at, resolved through that name's own binding: during a rename both the old and the new
    /// name find the object, each reported under the name it was looked up by.
    @Override
    public Promise<Option<ObjectInfo>> lookup(ObjectName name, Option<Long> minVersion) {
        return holderOf(name).flatMap(holder -> holder.fold(() -> Promise.success(Option.<ObjectInfo> none()),
                                                            id -> readEntry(id).map(entry -> entry.map(found -> found.named(some(name),
                                                                                                                            found.alsoBound())
                                                                                                                     .withVersion(found.catalogVersion())
                                                                                                                     .info(namespace.value(),
                                                                                                                           id)))));
    }

    private Promise<Option<ObjectEntry>> readEntry(ObjectId id) {
        return storage.resolveRef(entryRef(id))
                      .fold(() -> Promise.success(Option.<ObjectEntry> none()),
                            blockId -> readBlock(blockId).flatMap(bytes -> ObjectEntry.fromBytes(bytes).async())
                                                .map(Option::some));
    }

    private Promise<byte[]> readBlock(BlockId blockId) {
        return storage.get(blockId)
                      .flatMap(block -> block.async(CONTENT_NOT_FOUND));
    }

    /// The object `name` is bound to, if any.
    private Promise<Option<ObjectId>> holderOf(ObjectName name) {
        return storage.resolveRef(nameRef(name))
                      .fold(() -> Promise.success(Option.<ObjectId> none()),
                            blockId -> readBlock(blockId).flatMap(this::objectIdOf)
                                                .map(Option::some));
    }

    /// The object a name points at, if that object still exists: a binding whose entry is gone (a delete raced the bind that
    /// wrote it) holds no name. Cleaned up lazily: the next bind of the name swaps it away.
    private Promise<Option<ObjectId>> liveHolderOf(ObjectName name) {
        return holderOf(name).flatMap(holder -> holder.fold(() -> Promise.success(Option.<ObjectId> none()),
                                                            id -> readEntry(id).map(entry -> entry.isPresent()
                                                                                             ? holder
                                                                                             : Option.<ObjectId> none())));
    }

    private Promise<ObjectId> objectIdOf(byte[] block) {
        return ContentEnvelope.payloadOf(ContentEnvelope.NAME, block)
                              .filter(payload -> payload.length == ObjectId.LENGTH)
                              .map(ObjectId::new)
                              .async(ENTRY_PARSE_FAILED);
    }

    // --- Names and metadata ---
    @Override
    public Promise<ObjectInfo> bind(ObjectName name, ObjectId id, Precondition precondition) {
        return readEntry(id).flatMap(entry -> entry.async(ContentStoreError.ObjectNotFound.objectNotFound(id.asString())))
                        .flatMap(entry -> liveHolderOf(name).flatMap(holder -> checked(precondition, name, holder).async()
                                                                                      .flatMap(_ -> rebind(name,
                                                                                                           id,
                                                                                                           entry,
                                                                                                           holder))));
    }

    private Result<Unit> checked(Precondition precondition, ObjectName name, Option<ObjectId> holder) {
        return switch (precondition) {
            case Precondition.Unconditional _ -> Result.success(unit());
            case Precondition.NameFree _ -> holder.isEmpty()
                                            ? Result.success(unit())
                                            : ContentStoreError.PreconditionFailed.preconditionFailed("name '" + name.value() + "' is already bound").result();
            case Precondition.BoundTo bound -> holder.filter(bound.id()::equals).isPresent()
                                               ? Result.success(unit())
                                               : ContentStoreError.PreconditionFailed.preconditionFailed("name '" + name.value() + "' is not bound to " + bound.id().asString()).result();
        };
    }

    /// Local-mode rename is never unnamed; it may briefly carry both names. In this order:
    /// 0. a second name left by an interrupted earlier rename, if any, is retired;
    /// 1. the entry records the new name as `alsoBound` (the intent), so a delete, or a rename, after a crash knows both names;
    /// 2. `n/<namespace>/<name>` is swapped to point at the object -- from here the new name finds it, and the name's previous
    ///    object (if another) no longer does;
    /// 3. the entry records the new name as its name and the old one as `alsoBound`;
    /// 4. the binding of the name the object had before, if any, is retired;
    /// 5. the entry stops recording a second name.
    /// The call returns after the last step. A concurrent reader finds the object under the old name until step 4 and under
    /// the new one from step 2. A crash after step 2 leaves both names resolving to the one object (the entry reports the
    /// name last recorded); both bindings hold their own credit, so nothing is reclaimed, and [#delete] retires both. A
    /// later cluster catalog makes the rename one atomic step.
    private Promise<ObjectInfo> rebind(ObjectName name, ObjectId id, ObjectEntry entry, Option<ObjectId> holder) {
        var former = entry.name().filter(current -> !current.equals(name));
        var intent = entry.named(entry.name(), some(name));
        var renamed = intent.named(some(name), former);
        var settled = renamed.named(some(name), none());

        return retireLeftover(name, id, entry).flatMap(_ -> writeEntry(id, intent))
                             .flatMap(_ -> storage.swapRef(nameRef(name),
                                                           ContentEnvelope.wrap(ContentEnvelope.NAME,
                                                                                id.bytes())))
                             .flatMap(_ -> writeEntry(id, renamed))
                             .flatMap(_ -> dropFormerName(name, id, entry))
                             .flatMap(_ -> former.isEmpty()
                                           ? Promise.success(renamed.info(namespace.value(),
                                                                          id))
                                           : writeEntry(id, settled));
    }

    private Promise<Unit> retireLeftover(ObjectName name, ObjectId id, ObjectEntry entry) {
        return entry.alsoBound()
                    .filter(leftover -> !leftover.equals(name))
                    .fold(() -> Promise.success(unit()),
                          leftover -> dropNameIfBoundTo(leftover, id));
    }

    private Promise<Unit> dropFormerName(ObjectName name, ObjectId id, ObjectEntry entry) {
        return entry.name()
                    .filter(former -> !former.equals(name))
                    .fold(() -> Promise.success(unit()),
                          former -> dropNameIfBoundTo(former, id));
    }

    private Promise<Unit> dropNameIfBoundTo(ObjectName name, ObjectId id) {
        return holderOf(name).flatMap(holder -> holder.filter(id::equals)
                                                      .isPresent()
                                                ? storage.dropRef(nameRef(name))
                                                         .map(_ -> unit())
                                                : Promise.success(unit()));
    }

    @Override
    public Promise<ObjectInfo> update(ObjectId id, MetaPatch patch) {
        return readEntry(id).flatMap(entry -> entry.async(ContentStoreError.ObjectNotFound.objectNotFound(id.asString())))
                        .flatMap(entry -> writeEntry(id,
                                                     entry.patched(patch)));
    }

    /// Replaces the entry of an object that exists. An entry swapped in where none was (the object was deleted since it
    /// was read) is dropped again and reported as not found: the delete had already released its blocks, so the new entry
    /// would name blocks nothing holds.
    private Promise<ObjectInfo> writeEntry(ObjectId id, ObjectEntry entry) {
        return entry.toBytes()
                    .async()
                    .flatMap(bytes -> storage.swapRef(entryRef(id),
                                                      bytes))
                    .flatMap(swap -> swap.displaced()
                                         .isPresent()
                                     ? Promise.success(entry.info(namespace.value(),
                                                                  id))
                                     : storage.dropRef(entryRef(id))
                                              .flatMap(_ -> ContentStoreError.ObjectNotFound.objectNotFound(id.asString()).<ObjectInfo> promise()));
    }

    // --- Delete ---
    /// Releases, never deletes: the entry is dropped (its own credit given back), then the credits the object's chunks and
    /// manifest took at write time. The manifest is read BEFORE anything is dropped, so an object whose manifest cannot be
    /// read fails the delete with nothing changed. [StorageInstance#dropRef] reports what it removed exactly once, so two
    /// deletes of one object release its blocks once.
    @Override
    public Promise<Unit> delete(ObjectId id) {
        return readEntry(id).flatMap(entry -> entry.fold(() -> Promise.success(unit()), found -> deleteEntry(id, found)));
    }

    private Promise<Unit> deleteEntry(ObjectId id, ObjectEntry entry) {
        return readBlock(entry.manifest()).flatMap(bytes -> ContentManifest.fromBytes(bytes).async(MANIFEST_PARSE_FAILED))
                        .flatMap(manifest -> storage.dropRef(entryRef(id))
                                                    .flatMap(displaced -> displaced.fold(() -> Promise.success(unit()),
                                                                                         _ -> dropName(id, entry).flatMap(_ -> releaseObject(entry.manifest(),
                                                                                                                                             manifest)))));
    }

    private Promise<Unit> dropName(ObjectId id, ObjectEntry entry) {
        return entry.name()
                    .fold(() -> Promise.success(unit()),
                          name -> dropNameIfBoundTo(name, id))
                    .flatMap(_ -> entry.alsoBound()
                                       .fold(() -> Promise.success(unit()),
                                             other -> dropNameIfBoundTo(other, id)));
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
    /// Objects in order of (name, id), unnamed objects first. Local mode reads every entry of the namespace to order and
    /// filter them, so a page costs the namespace's object count.
    @Override
    public Promise<Page<ObjectInfo>> list(Option<String> namePrefix, Option<Cursor> after, int limit) {
        if (limit < 1 || limit > MAX_PAGE) {
            return ContentStoreError.InvalidRange.invalidRange("limit " + limit + " is not between 1 and " + MAX_PAGE).promise();
        }

        return allObjects().map(objects -> pageOf(objects, namePrefix, after, limit));
    }

    private Promise<List<ObjectInfo>> allObjects() {
        var prefix = entryPrefix();
        var reads = storage.listRefs(prefix)
                           .stream()
                           .map(ref -> ObjectId.objectId(ref.substring(prefix.length())).fold(_ -> Promise.success(Option.<ObjectInfo> none()),
                                                                                              this::stat))
                           .toList();

        return gather(reads).map(found -> found.stream()
                                               .flatMap(info -> info.fold(java.util.stream.Stream::<ObjectInfo> empty,
                                                                          java.util.stream.Stream::of))
                                               .toList());
    }

    private static Page<ObjectInfo> pageOf(List<ObjectInfo> objects,
                                           Option<String> namePrefix,
                                           Option<Cursor> after,
                                           int limit) {
        var ordered = objects.stream()
                             .filter(info -> namePrefix.fold(() -> true,
                                                             wanted -> info.name()
                                                                           .filter(name -> name.value()
                                                                                               .startsWith(wanted))
                                                                           .isPresent()))
                             .sorted(Comparator.comparing(LocalContentStore::sortKey))
                             .filter(info -> after.fold(() -> true,
                                                        cursor -> sortKey(info).compareTo(cursor.value()) > 0))
                             .limit(limit + 1L)
                             .toList();
        var items = ordered.subList(0,
                                    Math.min(limit, ordered.size()));
        var next = ordered.size() > limit
                   ? some(Cursor.cursor(sortKey(items.getLast())))
                   : Option.<Cursor> none();

        return Page.page(items, next);
    }

    private static String sortKey(ObjectInfo info) {
        return info.name()
                   .map(ObjectName::value)
                   .or("") + "\u0000" + info.ref()
                                            .id()
                                            .asString();
    }
}
