package org.pragmatica.jbct.derive.model;

import java.util.List;

import org.pragmatica.lang.Option;

/// One scoped answer row from a `[[answers.qN]]` block (SPEC.md §3).
///
/// Common fields are typed; question-specific fields the entry gate keys on are optional and
/// carried alongside:
///
///   - `price`   — the number's cost / 53rd-minute consequence (Q1/Q2).
///   - `shape`   — triage: `system-clock`/`requester-clock` for Q1, one of
///                 `volume`/`contention`/`burst`/`deadline` for Q5 (Card 3).
///   - `basis`   — Q2 triage: `target` (a commitment) vs `observed` (a measurement).
///   - `kind`    — Q6 decomposition: `audit`/`replay`/`residency`/`mandate`.
///   - `strikes` — Q6 mandate rows only: the `axis:value` pairs a legal mandate removes from the
///                 menu (SPEC.md §4 prune). Empty for every other row.
///   - `contained` — a load answer (Q5) the author states is absorbed by an existing thin tier
///                 (cache / coalescer / load balancer). Such a tier is axis-invisible (F18): it is
///                 recorded as an inert pressure and never forces a scope-split.
///   - `source`  — provenance (a citation or `elicited:<who>`); survives into output.
///
/// `line` is the 1-based line of the row's `[[answers.qN]]` header in the source sheet, so a
/// gate finding can point at the offending row.
public record AnswerRow(QuestionId question,
                        int line,
                        Scope scope,
                        String statement,
                        RowStatus status,
                        Option<String> price,
                        Option<String> shape,
                        Option<String> basis,
                        Option<String> kind,
                        List<String> strikes,
                        boolean contained,
                        Option<String> source) {
    public AnswerRow {
        strikes = List.copyOf(strikes);
    }

    /// Whether this row carries a real answer (as opposed to an explicit `UNKNOWN`).
    public boolean isAnswered() {
        return status == RowStatus.ANSWERED;
    }

    /// A compact citation of this row for decision records and the pressure matrix, e.g.
    /// `Q5 path:company-search` (F10: every derived position names the answer that forced it).
    public String cite() {
        return question.name() + " " + scope.display();
    }
}
