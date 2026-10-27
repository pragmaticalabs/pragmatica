package org.pragmatica.storage;


/// What a name must look like for a bind to go through.
public sealed interface Precondition {
    /// Whatever the name is bound to now, if anything, is replaced.
    record Unconditional() implements Precondition {}

    /// The name must not be bound.
    record NameFree() implements Precondition {}

    /// The name must be bound to exactly this object.
    record BoundTo(ObjectId id) implements Precondition {}

    static Precondition unconditional() {
        return new Unconditional();
    }

    static Precondition nameFree() {
        return new NameFree();
    }

    static Precondition boundTo(ObjectId id) {
        return new BoundTo(id);
    }
}
