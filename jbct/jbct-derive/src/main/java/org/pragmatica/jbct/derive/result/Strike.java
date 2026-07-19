package org.pragmatica.jbct.derive.result;

import org.pragmatica.jbct.derive.model.AnswerRow;
import org.pragmatica.jbct.derive.model.Axis;
import org.pragmatica.lang.Option;

/// A mandate strike (SPEC.md §4 prune): a Q6 mandate row removes an `axis:value` pair from the
/// menu. Binary, no weights — the value simply leaves, with the striking answer recorded.
///
/// `axis` is present when the strike's label resolves to a known [Axis]; an unresolved label is
/// still recorded verbatim (a finding, not a silent drop).
public record Strike(Option<Axis> axis, String axisLabel, String value, AnswerRow struckBy) {
    /// Parse one `axis:value` strike token against its striking mandate row.
    public static Option<Strike> strike(String token, AnswerRow struckBy) {
        var trimmed = token.trim();
        var colon = trimmed.indexOf(':');

        return colon <= 0 || colon == trimmed.length() - 1
               ? Option.none()
               : Option.some(build(trimmed.substring(0, colon), trimmed.substring(colon + 1), struckBy));
    }

    private static Strike build(String label, String value, AnswerRow struckBy) {
        return new Strike(Axis.byLabel(label), label, value, struckBy);
    }

    /// The struck pair rendered back to its `axis:value` form.
    public String display() {
        return axisLabel + ":" + value;
    }
}
