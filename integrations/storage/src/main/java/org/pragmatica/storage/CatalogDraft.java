package org.pragmatica.storage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// A cluster catalog being changed: a root, the mutations applied to it so far and their outcomes, not yet written.
/// Immutable: [#apply] returns a new draft and leaves this one as it was, so a caller that groups several callers'
/// mutations into one commit keeps the draft from before a group that failed and goes on with the next.
///
/// Reads go to the changes made so far first, then to the root's trees. Applying is pure; [#write] builds the new pages and
/// the new root, versioned one above the base, and stores nothing.
///
/// Invariants every applied mutation keeps: a name binding points at an object that has an entry, and an entry records
/// the one name bound to it, if any. So a rename is one step: the old name, the new name and the entry change together.
record CatalogDraft(CatalogRoot base,
                    String namespace,
                    PageTree.PageSource source,
                    Map<String, Option<ObjectEntry>> entries,
                    Map<String, Option<ObjectId>> names,
                    long committedBytes,
                    long objectCount,
                    List<Option<ObjectInfo>> outcomes) {
    public CatalogDraft {
        entries = Map.copyOf(entries);
        names = Map.copyOf(names);
        outcomes = List.copyOf(outcomes);
    }

    /// A draft on `base` for the catalog of `namespace`, reading `base`'s pages from `source`.
    static CatalogDraft catalogDraft(CatalogRoot base, String namespace, PageTree.PageSource source) {
        return new CatalogDraft(base,
                                namespace,
                                source,
                                Map.of(),
                                Map.of(),
                                base.committedBytes(),
                                base.objectCount(),
                                List.of());
    }

    /// The version the draft writes: one above its base.
    long version() {
        return base.version() + 1;
    }

    /// The draft with all of `mutations` applied, in order, or the first failure, with none of them applied. Each applied
    /// mutation appends its outcome: the object as it is after it, or for [CatalogMutation.Remove] the object removed, if
    /// any.
    Promise<CatalogDraft> apply(List<CatalogMutation> mutations) {
        return applyFrom(mutations, 0);
    }

    private Promise<CatalogDraft> applyFrom(List<CatalogMutation> mutations, int index) {
        return index == mutations.size()
               ? Promise.success(this)
               : applyOne(mutations.get(index)).flatMap(next -> next.applyFrom(mutations, index + 1));
    }

    private Promise<CatalogDraft> applyOne(CatalogMutation mutation) {
        return switch (mutation) {
            case CatalogMutation.Create create -> created(create.id(), create.entry()).map(draft -> draft.withOutcomeOf(create.id()));
            case CatalogMutation.Patch patch -> existing(patch.id()).map(entry -> patched(patch.id(),
                                                                                          entry.patched(patch.patch()))).map(draft -> draft.withOutcomeOf(patch.id()));
            case CatalogMutation.Bind bind -> existing(bind.id()).flatMap(entry -> bound(bind, entry)).map(draft -> draft.withOutcomeOf(bind.id()));
            case CatalogMutation.Remove remove -> entry(remove.id()).map(entry -> entry.fold(() -> withOutcome(none()),
                                                                                             found -> removed(remove.id(),
                                                                                                              found)));
        };
    }

    // --- reads ---
    /// The object's entry as this draft has it.
    Promise<Option<ObjectEntry>> entry(ObjectId id) {
        var key = id.asString();

        return entries.containsKey(key)
               ? Promise.success(entries.get(key))
               : PageTree.get(base.idTree(),
                              key,
                              source)
                         .flatMap(found -> found.fold(() -> Promise.success(Option.<ObjectEntry> none()),
                                                      bytes -> ObjectEntry.fromBytes(bytes)
                                                                          .async()
                                                                          .map(Option::some)));
    }

    /// The object `name` is bound to, as this draft has it.
    Promise<Option<ObjectId>> holder(ObjectName name) {
        var key = name.value();

        return names.containsKey(key)
               ? Promise.success(names.get(key))
               : PageTree.get(base.nameTree(),
                              key,
                              source)
                         .flatMap(found -> found.fold(() -> Promise.success(Option.<ObjectId> none()),
                                                      bytes -> objectIdOf(bytes).async()
                                                                         .map(Option::some)));
    }

    private static Result<ObjectId> objectIdOf(byte[] bytes) {
        return bytes.length == ObjectId.LENGTH
               ? Result.success(new ObjectId(bytes))
               : ContentStoreError.General.CATALOG_PAGE_PARSE_FAILED.result();
    }

    private Promise<ObjectEntry> existing(ObjectId id) {
        return entry(id).flatMap(entry -> entry.async(ContentStoreError.ObjectNotFound.objectNotFound(id.asString())));
    }

    // --- mutations ---
    private Promise<CatalogDraft> created(ObjectId id, ObjectEntry entry) {
        return entry(id).flatMap(found -> found.isPresent()
                                          ? ContentStoreError.PreconditionFailed.preconditionFailed("object " + id.asString()
                                                                                                   + " already has an entry").<CatalogDraft> promise()
                                          : Promise.success(withEntry(id,
                                                                      some(stamped(entry.named(none(), none()))),
                                                                      entry.meta().size(),
                                                                      1)));
    }

    private CatalogDraft patched(ObjectId id, ObjectEntry entry) {
        return withEntry(id, some(stamped(entry)), 0, 0);
    }

    /// The precondition is checked against the name's binding in THIS draft, so a mutation applied to a newer root than
    /// the one it was prepared on is checked again there.
    private Promise<CatalogDraft> bound(CatalogMutation.Bind bind, ObjectEntry entry) {
        return holder(bind.name()).flatMap(holder -> LocalCatalog.checked(bind.precondition(),
                                                                          bind.name(),
                                                                          holder)
                                                                 .async()
                                                                 .flatMap(_ -> rebound(bind.name(),
                                                                                       bind.id(),
                                                                                       entry,
                                                                                       holder)));
    }

    /// One step: the name's previous object loses it, the object's previous name is released, the name points at the
    /// object and the object records the name.
    private Promise<CatalogDraft> rebound(ObjectName name, ObjectId id, ObjectEntry entry, Option<ObjectId> holder) {
        var former = entry.name().filter(current -> !current.equals(name));
        var previous = holder.filter(other -> !other.equals(id));

        return previous.fold(() -> Promise.success(this),
                             other -> unnamed(other))
                       .map(draft -> former.fold(() -> draft,
                                                 old -> draft.withName(old,
                                                                       none())))
                       .map(draft -> draft.withName(name,
                                                    some(id)))
                       .map(draft -> draft.withEntry(id,
                                                     some(draft.stamped(entry.named(some(name),
                                                                                    none()))),
                                                     0,
                                                     0));
    }

    /// The draft with `other`'s entry recording no name.
    private Promise<CatalogDraft> unnamed(ObjectId other) {
        return entry(other).map(found -> found.fold(() -> this,
                                                    entry -> withEntry(other,
                                                                       some(stamped(entry.named(none(), none()))),
                                                                       0,
                                                                       0)));
    }

    /// The entry and its name binding gone; the outcome is the object as it was.
    private CatalogDraft removed(ObjectId id, ObjectEntry entry) {
        var dropped = withEntry(id,
                                none(),
                                - entry.meta().size(),
                                - 1);

        return entry.name()
                    .fold(() -> dropped,
                          name -> dropped.withName(name,
                                                   none()))
                    .withOutcome(some(entry.info(namespace, id)));
    }

    /// `entry` at the version this draft writes.
    private ObjectEntry stamped(ObjectEntry entry) {
        return entry.withVersion(version());
    }

    // --- immutable updates ---
    private CatalogDraft withEntry(ObjectId id, Option<ObjectEntry> entry, long bytesDelta, long countDelta) {
        var changed = new HashMap<>(entries);

        changed.put(id.asString(), entry);

        return new CatalogDraft(base,
                                namespace,
                                source,
                                changed,
                                names,
                                committedBytes + bytesDelta,
                                objectCount + countDelta,
                                outcomes);
    }

    private CatalogDraft withName(ObjectName name, Option<ObjectId> holder) {
        var changed = new HashMap<>(names);

        changed.put(name.value(), holder);

        return new CatalogDraft(base, namespace, source, entries, changed, committedBytes, objectCount, outcomes);
    }

    private CatalogDraft withOutcome(Option<ObjectInfo> outcome) {
        return new CatalogDraft(base, namespace, source, entries, names, committedBytes, objectCount, appended(outcome));
    }

    /// The draft recording, as the outcome of the mutation just applied, the object `id` as it now is.
    private CatalogDraft withOutcomeOf(ObjectId id) {
        return withOutcome(Option.option(entries.get(id.asString()))
                                 .flatMap(entry -> entry)
                                 .map(entry -> entry.info(namespace, id)));
    }

    private List<Option<ObjectInfo>> appended(Option<ObjectInfo> outcome) {
        var all = new ArrayList<>(outcomes);

        all.add(outcome);

        return all;
    }

    // --- write ---
    /// The pages and root of the catalog this draft describes, at [#version], built on the base's trees with pages of at
    /// most `fanout` cells or children. Nothing is stored: the caller stores [CatalogWrite#blocks], then the root block,
    /// then moves the root pointer.
    Promise<CatalogWrite> write(int fanout) {
        return encodedEntries().async()
                             .flatMap(idEdits -> PageTree.write(base.idTree(),
                                                                idEdits,
                                                                fanout,
                                                                source))
                             .flatMap(idTree -> PageTree.write(base.nameTree(),
                                                               nameEdits(),
                                                               fanout,
                                                               source)
                                                        .flatMap(nameTree -> rooted(idTree, nameTree).async()));
    }

    private Result<NavigableMap<String, Option<byte[]>>> encodedEntries() {
        return Result.allOf(entries.entrySet()
                                   .stream()
                                   .map(change -> change.getValue()
                                                        .fold(() -> Result.success(Map.entry(change.getKey(),
                                                                                             Option.<byte[]> none())),
                                                              entry -> entry.toBytes()
                                                                            .map(bytes -> Map.entry(change.getKey(),
                                                                                                    some(bytes)))))).map(CatalogDraft::sorted);
    }

    private NavigableMap<String, Option<byte[]>> nameEdits() {
        return sorted(names.entrySet()
                           .stream()
                           .map(change -> Map.entry(change.getKey(),
                                                    change.getValue().map(ObjectId::bytes)))
                           .toList());
    }

    private static NavigableMap<String, Option<byte[]>> sorted(List<Map.Entry<String, Option<byte[]>>> changes) {
        var sorted = new TreeMap<String, Option<byte[]>>();

        changes.forEach(change -> sorted.put(change.getKey(), change.getValue()));

        return sorted;
    }

    private Result<CatalogWrite> rooted(PageTree.TreeWrite idTree, PageTree.TreeWrite nameTree) {
        var root = CatalogRoot.catalogRoot(version(), idTree.root(), nameTree.root(), committedBytes, objectCount);

        return root.toBytes()
                   .flatMap(bytes -> BlockId.blockId(bytes).map(rootId -> CatalogWrite.catalogWrite(base.version(),
                                                                                                    root,
                                                                                                    rootId,
                                                                                                    bytes,
                                                                                                    allBlocks(idTree,
                                                                                                              nameTree),
                                                                                                    outcomes)));
    }

    private static Map<BlockId, byte[]> allBlocks(PageTree.TreeWrite idTree, PageTree.TreeWrite nameTree) {
        var blocks = new HashMap<>(idTree.blocks());

        blocks.putAll(nameTree.blocks());

        return blocks;
    }

    /// A built commit: the version it was built on, the new root and its block, the new page blocks, and one outcome per
    /// applied mutation.
    record CatalogWrite(long baseVersion,
                        CatalogRoot root,
                        BlockId rootId,
                        byte[] rootBlock,
                        Map<BlockId, byte[]> blocks,
                        List<Option<ObjectInfo>> outcomes) {
        public CatalogWrite {
            blocks = Map.copyOf(blocks);
            outcomes = List.copyOf(outcomes);
        }

        static CatalogWrite catalogWrite(long baseVersion,
                                         CatalogRoot root,
                                         BlockId rootId,
                                         byte[] rootBlock,
                                         Map<BlockId, byte[]> blocks,
                                         List<Option<ObjectInfo>> outcomes) {
            return new CatalogWrite(baseVersion, root, rootId, rootBlock, blocks, outcomes);
        }
    }
}
