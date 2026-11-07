package org.pragmatica.storage;

import java.util.Comparator;
import java.util.List;
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


/// [Catalog] over one [StorageInstance]'s ref table, on one node.
///
/// Keys, all inside the instance's ref table and all carrying the namespace, so a catalog can address its own namespace
/// and no other:
/// - `o/<namespace>/<object id>` -> the object-entry block (envelope kind `E`, see [ObjectEntry]): metadata and manifest id.
/// - `n/<namespace>/<name>` -> a name-binding block (envelope kind `N`): the id of the object the name points at.
///
/// The entry and the name binding hold the credit of the ref that names them. Every operation is a sequence of atomic ref
/// swaps, not one transaction: concurrent writers to the same object or name are last-writer-wins on each step, and a
/// rename is never unnamed but may briefly carry both names (see [#bind]).
final class LocalCatalog implements Catalog {
    private static final String ENTRY_PREFIX = "o/";
    private static final String NAME_PREFIX = "n/";

    private final StorageInstance storage;
    private final Namespace namespace;

    private LocalCatalog(StorageInstance storage, Namespace namespace) {
        this.storage = storage;
        this.namespace = namespace;
    }

    static LocalCatalog localCatalog(StorageInstance storage, Namespace namespace) {
        return new LocalCatalog(storage, namespace);
    }

    private String entryRef(ObjectId id) {
        return entryRef(namespace, id);
    }

    /// The ref of `id`'s entry in `namespace`.
    static String entryRef(Namespace namespace, ObjectId id) {
        return entryPrefix(namespace) + id.asString();
    }

    private static String entryPrefix(Namespace namespace) {
        return ENTRY_PREFIX + namespace.value() + "/";
    }

    private String nameRef(ObjectName name) {
        return NAME_PREFIX + namespace.value() + "/" + name.value();
    }

    @Override
    public Promise<ObjectInfo> insert(ObjectId id, ObjectEntry entry) {
        return entry.toBytes()
                    .async()
                    .flatMap(bytes -> storage.swapRef(entryRef(id),
                                                      bytes))
                    .map(_ -> entry.info(namespace.value(),
                                         id));
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
    /// name find the object, each reported under the name it was looked up by. `minVersion` is ignored: this node's ref
    /// table is always current.
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

    @Override
    public Promise<ObjectInfo> bind(ObjectName name, ObjectId id, Precondition precondition) {
        return readEntry(id).flatMap(entry -> entry.async(ContentStoreError.ObjectNotFound.objectNotFound(id.asString())))
                        .flatMap(entry -> liveHolderOf(name).flatMap(holder -> checked(precondition, name, holder).async()
                                                                                      .flatMap(_ -> rebind(name,
                                                                                                           id,
                                                                                                           entry,
                                                                                                           holder))));
    }

    /// Whether `holder`, the object `name` is bound to now, satisfies `precondition`. Shared with the cluster core
    /// ([CatalogDraft]), so both catalogs refuse a bind for the same reasons and with the same error.
    static Result<Unit> checked(Precondition precondition, ObjectName name, Option<ObjectId> holder) {
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
    /// name last recorded); both bindings hold their own credit, so nothing is reclaimed, and [#remove] retires both. The
    /// cluster catalog makes the rename one atomic step.
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

    /// Replaces the entry of an object that exists. An entry swapped in where none was (the object was removed since it
    /// was read) is dropped again and reported as not found: the removal had already released its blocks, so the new
    /// entry would name blocks nothing holds.
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

    /// The entry is dropped (its own credit given back), then the names it records, if they still point at the object.
    /// [StorageInstance#dropRef] reports what it removed exactly once, so of two removals of one object only one reports it.
    @Override
    public Promise<Option<ObjectInfo>> remove(ObjectId id) {
        return readEntry(id).flatMap(entry -> entry.fold(() -> Promise.success(Option.<ObjectInfo> none()),
                                                         found -> removeEntry(id, found)));
    }

    private Promise<Option<ObjectInfo>> removeEntry(ObjectId id, ObjectEntry entry) {
        return storage.dropRef(entryRef(id))
                      .flatMap(displaced -> displaced.fold(() -> Promise.success(Option.<ObjectInfo> none()),
                                                           _ -> dropNames(id, entry).map(_ -> some(entry.info(namespace.value(),
                                                                                                              id)))));
    }

    private Promise<Unit> dropNames(ObjectId id, ObjectEntry entry) {
        return entry.name()
                    .fold(() -> Promise.success(unit()),
                          name -> dropNameIfBoundTo(name, id))
                    .flatMap(_ -> entry.alsoBound()
                                       .fold(() -> Promise.success(unit()),
                                             other -> dropNameIfBoundTo(other, id)));
    }

    /// Local mode reads every entry of the namespace to order and filter them, found by filtering every ref of the
    /// instance, so a page costs the instance's ref count plus a read of each of the namespace's entries.
    @Override
    public Promise<Page<ObjectInfo>> list(Option<String> namePrefix, Option<Cursor> after, int limit) {
        return allObjects().map(objects -> pageOf(objects, namePrefix, after, limit));
    }

    private Promise<List<ObjectInfo>> allObjects() {
        var prefix = entryPrefix(namespace);
        var reads = storage.listRefs(prefix)
                           .stream()
                           .map(ref -> ObjectId.objectId(ref.substring(prefix.length())).fold(_ -> Promise.success(Option.<ObjectInfo> none()),
                                                                                              this::stat))
                           .toList();

        return LocalContentStore.gather(reads).map(found -> found.stream()
                                                                 .flatMap(info -> info.fold(Stream::<ObjectInfo> empty,
                                                                                            Stream::of))
                                                                 .toList());
    }

    /// One page of `objects` in order of (name, id), unnamed objects first. Shared with the cluster core, so both
    /// catalogs page the same way.
    static Page<ObjectInfo> pageOf(List<ObjectInfo> objects,
                                   Option<String> namePrefix,
                                   Option<Cursor> after,
                                   int limit) {
        var ordered = objects.stream()
                             .filter(info -> namePrefix.fold(() -> true,
                                                             wanted -> info.name()
                                                                           .filter(name -> name.value()
                                                                                               .startsWith(wanted))
                                                                           .isPresent()))
                             .sorted(Comparator.comparing(LocalCatalog::sortKey))
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
