package org.pragmatica.storage;

import org.pragmatica.lang.Option;


/// A committed object as the catalog describes it. `catalogVersion` starts at 1 and grows by one with every change to the
/// object's entry (a name bound, a metadata patch).
public record ObjectInfo(ObjectRef ref, Option<ObjectName> name, ObjectMeta meta, long catalogVersion) {
    public static ObjectInfo objectInfo(ObjectRef ref, Option<ObjectName> name, ObjectMeta meta, long catalogVersion) {
        return new ObjectInfo(ref, name, meta, catalogVersion);
    }
}
