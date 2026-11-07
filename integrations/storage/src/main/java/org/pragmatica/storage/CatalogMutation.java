package org.pragmatica.storage;

/// One change to a cluster catalog, stated as an intent: it is evaluated against the catalog it is applied to, never
/// against the one it was prepared on. Re-applying a set of mutations to a newer root is therefore the rebase, and every
/// precondition is checked again there ([CatalogCommit]).
sealed interface CatalogMutation {
    /// A newly written object's entry; the object is unnamed. Refused if the id already has an entry.
    record Create(ObjectId id, ObjectEntry entry) implements CatalogMutation {}

    /// The object's metadata patched; refused with [ContentStoreError.ObjectNotFound] if it has no entry.
    record Patch(ObjectId id, MetaPatch patch) implements CatalogMutation {}

    /// `name` bound to the object, if `precondition` holds for the name's current binding: the object's previous name and
    /// the name's previous object are both released in the same commit.
    record Bind(ObjectName name, ObjectId id, Precondition precondition) implements CatalogMutation {}

    /// The object's entry and its name binding removed; nothing changes if it has no entry.
    record Remove(ObjectId id) implements CatalogMutation {}

    static CatalogMutation create(ObjectId id, ObjectEntry entry) {
        return new Create(id, entry);
    }

    static CatalogMutation patch(ObjectId id, MetaPatch patch) {
        return new Patch(id, patch);
    }

    static CatalogMutation bind(ObjectName name, ObjectId id, Precondition precondition) {
        return new Bind(name, id, precondition);
    }

    static CatalogMutation remove(ObjectId id) {
        return new Remove(id);
    }
}
