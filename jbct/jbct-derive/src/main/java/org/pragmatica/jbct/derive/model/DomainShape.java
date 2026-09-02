package org.pragmatica.jbct.derive.model;

import java.util.List;

/// A domain-shape fact (SPEC.md §3, `[[domain_shape]]`): a fact about an effectful operation
/// the nine questions do not ask for directly but the derivation still needs (recovery cannot
/// be derived without it).
///
///   - `inverse`     — the domain inverse, `none`, or notes.
///   - `decays`      — whether the operation's data decays over time.
///   - `reshapeable` — how it can be reshaped: `idempotent`/`commutative`/`append-only`/`none`.
public record DomainShape(String operation, String inverse, boolean decays, List<String> reshapeable, int line) {
    public DomainShape {
        reshapeable = List.copyOf(reshapeable);
    }
}
