package org.pragmatica.jbct.derive.model;

/// The nine questions of the architecture answer sheet (SPEC.md §3).
///
/// Each question sources a set of scoped rows in the TOML sheet under the
/// `[[answers.qN]]` array-of-tables key.
public enum QuestionId {
    Q1("Time budget"),
    Q2("Failure budget"),
    Q3("Loss budget"),
    Q4("Consistency contract"),
    Q5("Load"),
    Q6("External constraints"),
    Q7("Release structure"),
    Q8("Cost & capacity envelope"),
    Q9("Multi-X");

    private final String label;

    QuestionId(String label) {
        this.label = label;
    }

    /// Human-readable question title.
    public String label() {
        return label;
    }

    /// The TOML array-of-tables key that holds this question's rows (e.g. `answers.q1`).
    public String tableName() {
        return "answers." + name().toLowerCase();
    }
}
