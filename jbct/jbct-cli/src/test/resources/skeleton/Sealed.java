package fixtures;

/// A sealed hierarchy.
public sealed interface Sealed permits Sealed.Leaf, Sealed.Pair, Sealed.Nothing {
    record Leaf(int value) implements Sealed {}

    /// A pair.
    record Pair(Sealed left, Sealed right) implements Sealed {
        public Pair {
            java.util.Objects.requireNonNull(left);
        }
    }

    enum Nothing implements Sealed {
        INSTANCE
    }

    default int size() {
        return switch (this) {
            case Leaf leaf -> 1;
            case Pair pair -> pair.left().size() + pair.right().size();
            case Nothing nothing -> 0;
        };
    }
}
