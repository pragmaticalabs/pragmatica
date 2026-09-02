package org.pragmatica.jbct.derive.model;

/// The kind of a typed scope (SPEC.md §3): the prefix that makes a scope
/// machine-comparable for the scope test and the narrowest-containing-scope rule.
public enum ScopeKind {
    OPERATION("operation"),
    DATA_CLASS("data-class"),
    PATH("path"),
    POLICY("policy"),
    SYSTEM("system");

    private final String prefix;

    ScopeKind(String prefix) {
        this.prefix = prefix;
    }

    /// The literal prefix used in a sheet (e.g. `operation`, `data-class`).
    public String prefix() {
        return prefix;
    }
}
