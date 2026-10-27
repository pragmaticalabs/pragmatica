package org.pragmatica.storage;

/// A durable reference to a committed object. Reading by reference works across namespaces: the 128-bit id together with
/// the manifest block id is the capability, and nothing checks the caller's own namespace.
public record ObjectRef(String namespace, ObjectId id, BlockId manifest, long size, Sha256 digest) {
    public static ObjectRef objectRef(String namespace, ObjectId id, BlockId manifest, long size, Sha256 digest) {
        return new ObjectRef(namespace, id, manifest, size, digest);
    }
}
