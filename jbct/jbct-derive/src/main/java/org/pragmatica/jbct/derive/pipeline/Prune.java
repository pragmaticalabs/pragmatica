package org.pragmatica.jbct.derive.pipeline;

import java.util.List;
import java.util.stream.Stream;

import org.pragmatica.jbct.derive.model.AnswerRow;
import org.pragmatica.jbct.derive.model.AnswerSheet;
import org.pragmatica.jbct.derive.model.QuestionId;
import org.pragmatica.jbct.derive.result.Strike;

/// Prune — the first derivation stage (SPEC.md §4). Mechanical and binary: a Q6 `mandate` row
/// carries explicit `strikes = ["axis:value", ...]`, and each struck pair leaves the menu with its
/// striking answer recorded. No weights, no judgment; a mandate that names no strike does not prune
/// (its containment is a later, non-mechanical concern).
public sealed interface Prune permits Prune.unused {
    record unused() implements Prune {}

    /// Collect every strike a sheet's mandate rows declare, in sheet order.
    static List<Strike> prune(AnswerSheet sheet) {
        return sheet.rows()
                    .stream()
                    .filter(AnswerRow::isAnswered)
                    .filter(Prune::isMandate)
                    .flatMap(Prune::strikesOf)
                    .toList();
    }

    private static boolean isMandate(AnswerRow row) {
        return row.question() == QuestionId.Q6
            && row.kind().map(String::toLowerCase).filter("mandate"::equals).isPresent();
    }

    private static Stream<Strike> strikesOf(AnswerRow row) {
        return row.strikes().stream().flatMap(token -> Strike.strike(token, row).stream());
    }
}
