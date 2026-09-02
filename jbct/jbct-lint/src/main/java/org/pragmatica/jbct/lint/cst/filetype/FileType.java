package org.pragmatica.jbct.lint.cst.filetype;


/// The JBCT role a compilation unit plays, inferred from its own syntax (issue #453).
///
/// A file is routed to exactly one role by [FileTypeClassifier]. The roles mirror the book's
/// project-structure file kinds; the structural rules (JBCT-UC-02, JBCT-ORD-01, JBCT-INJ-01,
/// JBCT-VAL-01) gate on this verdict so a rule only fires on the file kind it governs. The verdict
/// is also exposed for reuse by the method-shape classifier (#448) and future score categories.
public enum FileType {
    /// A use-case interface: an interface exposing an `execute` Zone-1 entry, normally with nested
    /// Request/Response records and step interfaces.
    USE_CASE,
    /// A value object: a record with a static factory returning `Result<T>` (parse-don't-validate).
    VALUE_OBJECT,
    /// An error type: a type whose declaration extends (or implements) `Cause`.
    ERROR_TYPE,
    /// A step interface: a single-abstract-method interface (the injected orchestration step).
    STEP_INTERFACE,
    /// A utility interface: a `sealed interface` carrying a `record unused()` permit placeholder.
    UTILITY_INTERFACE,
    /// A test class: any type declaring a `@Test`-family method.
    TEST_CLASS,
    /// None of the recognised roles — deliberately left unclassified rather than guessed.
    UNCLASSIFIED
}
