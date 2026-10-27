package org.pragmatica.storage;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;


/// Streaming, ranged object storage bound to one [Namespace], local mode. The only content store: the whole-`byte[]`
/// API it replaced is gone.
///
/// Per-operation guarantees in local mode (one node, one [StorageInstance]):
/// - `create`/`commit`: the bytes are in the instance before the entry that names them, so a reader that finds the entry
///   finds the bytes. A writer that fails or is aborted releases every block credit it took. Not atomic across a crash
///   between the manifest write and the entry write: the blocks already stored keep the credits their writes took, so the
///   instance's collector, which reclaims a block only when its last credit is released, never reclaims them. They stay
///   until a sweep of credited but unreferenced blocks exists. A crash inside `delete`, between dropping the entry and
///   releasing its blocks, leaves the same.
/// - `open`: every block is integrity-checked by the instance as it is read; a failed block fails the `next` that reaches
///   it. A stream holds at most `read_ahead + 1` blocks.
/// - `stat`, `lookup`, `list`, `delete`, `update`, `bind`: confined to this store's namespace. Each is a sequence of atomic
///   ref swaps, not one transaction; concurrent writers to the same object or name are last-writer-wins on each step.
/// - `lookup` and `list` see this node only.
/// - `resume` and `link` answer [ContentStoreError.NotSupported].
public interface ContentStore {
    Promise<ObjectWriter> create(ObjectSpec spec);
    Promise<ObjectWriter> resume(WriteSessionId id);
    /// Reads by reference, across namespaces.
    Promise<ChunkStream> open(ObjectRef ref, ByteRange range);
    Promise<Option<ObjectInfo>> stat(ObjectId id);
    /// `minVersion` is a read-your-writes bound for clustered catalogs; local mode always reads the current entry.
    Promise<Option<ObjectInfo>> lookup(ObjectName name, Option<Long> minVersion);
    /// Binds `name` to `id`, replacing the binding the precondition allows. Outside a rename window or crash recovery an
    /// object has one name: binding a second name moves it, and rebinding a name takes it from its previous object. In local
    /// mode a rename is never unnamed and may briefly carry both names: the new name is bound first, the old one retired
    /// after, and the call returns after both. A crash between the steps leaves both names resolving to the object. A
    /// later cluster catalog makes the rename atomic.
    Promise<ObjectInfo> bind(ObjectName name, ObjectId id, Precondition precondition);
    Promise<ObjectInfo> update(ObjectId id, MetaPatch patch);
    Promise<ObjectInfo> link(ObjectRef foreign);
    /// Idempotent: deleting an id this namespace does not hold succeeds and changes nothing.
    Promise<Unit> delete(ObjectId id);
    Promise<Page<ObjectInfo>> list(Option<String> namePrefix, Option<Cursor> after, int limit);

    /// A store over `storage` bound to the namespace and tuned by the settings of `config`, which are validated against
    /// the instance ([ContentStoreConfig#validatedAgainst]).
    static Result<ContentStore> contentStore(StorageInstance storage, ContentStoreConfig config) {
        return config.validatedAgainst(storage.maxBlockBytes())
                     .flatMap(valid -> Namespace.namespace(valid.namespace()).map(namespace -> new LocalContentStore(storage,
                                                                                                                     valid,
                                                                                                                     namespace)));
    }
}
