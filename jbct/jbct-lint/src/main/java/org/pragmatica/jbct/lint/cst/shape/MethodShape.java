package org.pragmatica.jbct.lint.cst.shape;


/// The single JBCT structural pattern a method's body implements (issue #448, phase 1 census).
///
/// The book's "single pattern per function" rule holds that every method realises exactly one of
/// six composition patterns. [MethodShapeClassifier] assigns each concrete method one of those six
/// shapes from the syntax of its returned expression alone — no cross-file type resolution — or one
/// of the two residual verdicts when no single shape fits ([#MIXED]) or the body is imperative with
/// no composition root ([#UNCLASSIFIED]).
///
/// The six pattern shapes mirror the BPMN mapping in the book: [#LEAF] (Task), [#SEQUENCER]
/// (Sequence Flow), [#FORK_JOIN] (Parallel Gateway), [#CONDITION] (Exclusive Gateway), [#ITERATION]
/// (Multi-Instance), [#ASPECT] (Event Sub-Process). The two residual verdicts are the census
/// surfacing targets: [#MIXED] is emitted as `JBCT-SHAPE-01`, [#UNCLASSIFIED] as `JBCT-SHAPE-02`
/// (both INFO). The six pattern shapes are never diagnosed — they are the conformant census body.
public enum MethodShape {
    /// A single atomic operation — a plain call, reference, literal, or one-combinator transform
    /// with no further composition (BPMN Task).
    LEAF,
    /// Two or more dependent combinator steps threaded through one carrier (BPMN Sequence Flow).
    SEQUENCER,
    /// Parallel independent operations joined by `Result.all` / `Promise.all` / `Option.all` and a
    /// single combine step (BPMN Parallel Gateway).
    FORK_JOIN,
    /// Value-level routing — a root ternary or switch-expression that selects a branch without
    /// transforming (BPMN Exclusive Gateway).
    CONDITION,
    /// Functional collection processing — a stream source through a `collect`/`toList` terminal, or
    /// a `Result.allOf` / `Promise.allOf` collection aggregation (BPMN Multi-Instance).
    ITERATION,
    /// A cross-cutting wrapper — a `with*` decorator returning a lambda, or a body that applies an
    /// injected functional parameter and decorates the result (BPMN Event Sub-Process).
    ASPECT,
    /// Two pattern features blended at the same altitude of the returned expression, so no single
    /// shape fits (the census-surfaced JBCT-SHAPE-01 target).
    MIXED,
    /// Imperative residue — a multi-statement body, a loop / `if` / `try` / `throw` statement, or an
    /// expression with no recognisable composition root (the census-surfaced JBCT-SHAPE-02 target).
    UNCLASSIFIED
}
