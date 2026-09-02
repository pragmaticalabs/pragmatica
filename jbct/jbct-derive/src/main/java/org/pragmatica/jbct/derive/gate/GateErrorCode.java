package org.pragmatica.jbct.derive.gate;

/// The entry-gate error catalog (SPEC.md §4). Each code is a fake-answer form the book's entry
/// gate rejects, using the book's own vocabulary as the message. `UNKNOWN` rows are NOT here —
/// they pass the gate and propagate as UNKNOWN pressure.
///
/// `summary` is the book-vocabulary message shown to the author; `card` cites where the rule
/// lives in the method's reference cards.
public enum GateErrorCode {
    UNPRICED("unpriced answer — state the 53rd-minute consequence",
             "Card 5 · normalize"),
    UNSCOPED("unscoped answer — this question demands a per-operation / per-data-class / per-path answer, not 'system'",
             "Card 5 · normalize"),
    UNDECOMPOSED("undecomposed answer — decompose the bundle (audit vs replay; ownership vs release)",
                 "Card 5 · normalize"),
    UNTRIAGED("untriaged answer — triage it (requester-vs-system clock; observed-vs-target failure)",
              "Card 3 · triage (F22/F23)"),
    BARE_ILITY("bare ility — a bare quality word ('scalability', 'highly available') is not an answer",
               "Card 5 · normalize"),
    MISSING_SHAPE("missing load shape — name the shape: volume / contention / burst / deadline",
                  "Card 3 · triage"),
    MISSING_DOMAIN_SHAPE("missing domain shape — an effectful operation named without its domain-shape row; recovery cannot be derived",
                         "Card 5 · normalize");

    private final String summary;
    private final String card;

    GateErrorCode(String summary, String card) {
        this.summary = summary;
        this.card = card;
    }

    /// The book-vocabulary message for this fake-answer form.
    public String summary() {
        return summary;
    }

    /// The reference card this rule cites.
    public String card() {
        return card;
    }
}
