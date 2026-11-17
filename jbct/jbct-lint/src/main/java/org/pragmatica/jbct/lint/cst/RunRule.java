package org.pragmatica.jbct.lint.cst;


/// A lint rule that judges the whole run once every file has been seen, rather than one file at a time (#2525).
///
/// Declared empty: no rule of this kind exists yet, and the run-end entry point that evaluates them is not in place.
/// It is a catalog subtype now so that the first such rule enters the registry invariants like any other.
public non-sealed interface RunRule extends LintRule {}
