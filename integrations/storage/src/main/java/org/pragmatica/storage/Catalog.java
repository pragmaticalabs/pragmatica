package org.pragmatica.storage;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;


/// The naming half of a [ContentStore] for one namespace: which objects exist (their entries) and which names point at
/// them. The store keeps the bytes (writers, manifests, chunk reads, block credits); every question about an object's
/// existence, name or metadata goes through this seam.
///
/// Two implementations are planned:
/// - [LocalCatalog]: the instance's ref table on one node. Each operation is a sequence of atomic ref swaps, so a rename
///   briefly carries both names, and a crash between the steps can leave both bound.
/// - the cluster catalog (A3-2): a copy-on-write tree of pages committed by a CAS on a root pointer. Each operation is one
///   multi-mutation commit ([CatalogDraft]), so a rename is one atomic step.
///
/// Properties both implementations hold are pinned by the catalog contract suite; the ones only one of them holds are
/// stated in that implementation.
interface Catalog {
    /// Records the entry of a newly written object. The object is unnamed.
    Promise<ObjectInfo> insert(ObjectId id, ObjectEntry entry);
    /// The object, with the name that points at it, if any.
    Promise<Option<ObjectInfo>> stat(ObjectId id);
    /// The object `name` points at, reported under that name.
    Promise<Option<ObjectInfo>> lookup(ObjectName name, Option<Long> minVersion);
    /// Binds `name` to `id`: the object loses the name it had, and the name's previous object loses the name.
    /// [ContentStoreError.ObjectNotFound] when the object does not exist, [ContentStoreError.PreconditionFailed] when the
    /// name's current binding does not satisfy `precondition`.
    Promise<ObjectInfo> bind(ObjectName name, ObjectId id, Precondition precondition);
    /// Patches the object's metadata, keeping its name. [ContentStoreError.ObjectNotFound] when it does not exist.
    Promise<ObjectInfo> update(ObjectId id, MetaPatch patch);
    /// Removes the object and the name bound to it. The object removed is reported to exactly one of any number of
    /// concurrent callers; every other caller, and any caller after it, gets none.
    Promise<Option<ObjectInfo>> remove(ObjectId id);
    /// Objects in order of (name, id), unnamed objects first; `after` continues a previous page.
    Promise<Page<ObjectInfo>> list(Option<String> namePrefix, Option<Cursor> after, int limit);
}
