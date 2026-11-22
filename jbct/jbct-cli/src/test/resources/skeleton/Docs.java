package fixtures;

/**
 * A class with a block doc comment.
 */
public class Docs {
    /// One markdown doc line.
    int one;

    /// First line of three.
    /// Second line.
    /// Third line.
    void three() {}

    /** A block doc on one line. */
    void block() {}

    // A plain comment is not a doc comment.
    void plain() {}

    /**
     * Block doc, then an annotation.
     */
    @Deprecated
    void annotated() {}

    /// Doc of a type.
    record R(int x) {}
}
