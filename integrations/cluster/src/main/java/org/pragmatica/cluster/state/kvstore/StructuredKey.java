package org.pragmatica.cluster.state.kvstore;

public interface StructuredKey {
    /// The key's canonical text. The worker replica ring sizes an effect by its length (R-07). A key family with a canonical text form
    /// re-declares this method abstract (`AetherKey` does), so every class of that family must implement it; the default, the record text, is
    /// reached only by keys outside such a family.
    default String asString() {
        return toString();
    }
}
