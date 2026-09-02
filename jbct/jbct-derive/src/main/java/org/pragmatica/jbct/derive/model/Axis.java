package org.pragmatica.jbct.derive.model;

import org.pragmatica.lang.Option;

/// The six vector axes the derivation positions a system on (SPEC.md §3 `[current_vector]`, and the
/// six columns every published run reports). Each axis carries its *null value* — the cheapest
/// position the greenfield derivation starts from and keeps unless an answer forces a move.
///
/// Recovery is per effectful operation rather than a single axis value (see
/// [org.pragmatica.jbct.derive.result.RecoveryAssignment]); its null value is rendered as a dash.
public enum Axis {
    TOPOLOGY("topology", "single deployable"),
    SUBSTRATE("substrate", "direct"),
    READ_WRITE("read-write", "unified"),
    STATE("state", "current-state"),
    PERSISTENCE("persistence", "single shared"),
    RECOVERY("recovery", "—");

    private final String label;
    private final String nullValue;

    Axis(String label, String nullValue) {
        this.label = label;
        this.nullValue = nullValue;
    }

    /// The axis label as written in a sheet's `[current_vector]` and in a strike (`substrate:...`).
    public String label() {
        return label;
    }

    /// The cheapest position on this axis — the greenfield starting value.
    public String nullValue() {
        return nullValue;
    }

    /// Resolve an axis by its sheet label (the prefix of a strike such as `substrate:private-only`).
    public static Option<Axis> byLabel(String raw) {
        return Option.option(raw)
                     .map(String::trim)
                     .flatMap(Axis::match);
    }

    private static Option<Axis> match(String trimmed) {
        for (var axis : values()) {
            if (axis.label.equals(trimmed)) {
                return Option.some(axis);
            }
        }

        return Option.none();
    }
}
