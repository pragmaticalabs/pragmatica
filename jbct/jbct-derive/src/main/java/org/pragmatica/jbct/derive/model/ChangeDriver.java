package org.pragmatica.jbct.derive.model;

import org.pragmatica.lang.Option;

/// A change-driver fact (SPEC.md §3, `[[change_drivers]]`): the PFD seam — what varies, on what
/// cycle, from what source.
public record ChangeDriver(Scope scope, String volatility, Option<String> source, int line) {}
