package org.pragmatica.jbct.derive.model;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.utils.Causes;

/// A typed scope such as `operation:submit-filing`, `data-class:filings`, `path:search`,
/// `policy:filing-rules`, or the bare `system` (SPEC.md §3).
///
/// The prefix determines the [ScopeKind]; the remainder is the scope's name. The bare
/// `system` scope carries an empty name.
public record Scope(ScopeKind kind, String name) {
    private static final Fn1<Cause, String> INVALID_SCOPE =
        Causes.forOneValue("Invalid scope '%s' — expected one of operation:/data-class:/path:/policy: or bare 'system'");

    /// Parse a raw scope string into a typed [Scope].
    public static Result<Scope> scope(String raw) {
        return Verify.ensure(raw, Verify.Is::present, INVALID_SCOPE)
                     .map(String::trim)
                     .flatMap(Scope::parse);
    }

    private static Result<Scope> parse(String trimmed) {
        return trimmed.equals(ScopeKind.SYSTEM.prefix())
               ? Result.success(new Scope(ScopeKind.SYSTEM, ""))
               : parsePrefixed(trimmed);
    }

    private static Result<Scope> parsePrefixed(String trimmed) {
        int colon = trimmed.indexOf(':');

        return colon <= 0 || colon == trimmed.length() - 1
               ? INVALID_SCOPE.apply(trimmed).result()
               : forKind(trimmed, trimmed.substring(0, colon), trimmed.substring(colon + 1));
    }

    private static Result<Scope> forKind(String raw, String prefix, String name) {
        return switch (prefix) {
            case "operation" -> Result.success(new Scope(ScopeKind.OPERATION, name));
            case "data-class" -> Result.success(new Scope(ScopeKind.DATA_CLASS, name));
            case "path" -> Result.success(new Scope(ScopeKind.PATH, name));
            case "policy" -> Result.success(new Scope(ScopeKind.POLICY, name));
            default -> INVALID_SCOPE.apply(raw).result();
        };
    }

    /// Whether this scope is the bare `system` scope.
    public boolean isSystem() {
        return kind == ScopeKind.SYSTEM;
    }

    /// The scope rendered back to its canonical string form.
    public String display() {
        return isSystem()
               ? ScopeKind.SYSTEM.prefix()
               : kind.prefix() + ":" + name;
    }
}
