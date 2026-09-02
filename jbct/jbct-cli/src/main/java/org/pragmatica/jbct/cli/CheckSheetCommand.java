package org.pragmatica.jbct.cli;

import java.nio.file.Path;
import java.util.concurrent.Callable;

import org.pragmatica.jbct.derive.gate.EntryGate;
import org.pragmatica.jbct.derive.model.Verdict;
import org.pragmatica.jbct.derive.parse.SheetError;
import org.pragmatica.jbct.derive.parse.SheetParser;
import org.pragmatica.lang.Cause;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;


/// `jbct check-sheet <sheet.toml>` — the entry-gate linter for architecture answer sheets
/// (SPEC.md §5; issue #443 Phase A). Parses the sheet and runs the book's entry gate, reporting
/// gate errors with the book's vocabulary.
///
/// Exit codes: 0 clean · 1 gate errors (or an unparseable sheet). Codes 2 (halts/contradictions)
/// and 3 (judgment points pending) belong to the full `derive` pipeline — Phase B.
///
/// Machine JSON output is deferred to Phase B: the JSON payload (SPEC.md §4 emit) is the full
/// derive result — vector, pressure matrix, decision records — which does not exist yet, so
/// emitting gate-only JSON now would fix a format Phase B must break. The [Verdict] record is the
/// documented seam a Phase-B JSON emitter serializes.
@Command(name = "check-sheet",
         description = "Validate an architecture answer sheet against the entry gate (issue #443)",
         mixinStandardHelpOptions = true)
public class CheckSheetCommand implements Callable<Integer> {
    @Parameters(paramLabel = "<sheet.toml>", description = "Answer sheet (TOML) to check", arity = "1")
    Path sheet;

    @Override
    public Integer call() {
        return SheetParser.parse(sheet)
                          .map(EntryGate::verdict)
                          .fold(this::reportParseFailure, this::reportVerdict);
    }

    private Integer reportParseFailure(Cause cause) {
        var location = cause instanceof SheetError error && error.line() > 0
                       ? " (line " + error.line() + ")"
                       : "";

        System.err.println("check-sheet: cannot read sheet — " + cause.message() + location);

        return 1;
    }

    private Integer reportVerdict(Verdict verdict) {
        System.out.println(verdict.render());

        return verdict.exitCode();
    }
}
