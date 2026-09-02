package org.pragmatica.jbct.cli;

import java.nio.file.Path;
import java.util.concurrent.Callable;

import org.pragmatica.jbct.derive.emit.JsonReport;
import org.pragmatica.jbct.derive.emit.MarkdownReport;
import org.pragmatica.jbct.derive.parse.SheetError;
import org.pragmatica.jbct.derive.pipeline.Derive;
import org.pragmatica.jbct.derive.result.DeriveResult;
import org.pragmatica.lang.Cause;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;


/// `jbct derive <sheet.toml>` — the full derivation pipeline for architecture answer sheets
/// (SPEC.md §5; issue #443 Phase B). Parses the sheet, runs the entry gate, then prune → press →
/// resolve → verify, and emits the derived vector, pressure matrix, decision records, halts and
/// emitted judgment points as markdown (default) or JSON (`--json`).
///
/// Exit codes (SPEC.md §5): 0 clean · 1 gate errors (or an unparseable sheet) · 2 halts /
/// contradictions · 3 judgment points pending. The engine emits judgment points; it never resolves
/// them (SPEC.md §1).
@Command(name = "derive",
         description = "Derive an architecture vector from an answer sheet (issue #443)",
         mixinStandardHelpOptions = true)
public class DeriveCommand implements Callable<Integer> {
    @Parameters(paramLabel = "<sheet.toml>", description = "Answer sheet (TOML) to derive", arity = "1")
    Path sheet;

    @Option(names = "--json", description = "Emit machine-readable JSON instead of the markdown report")
    boolean json;

    @Override
    public Integer call() {
        return Derive.derive(sheet)
                     .fold(this::reportParseFailure, this::reportResult);
    }

    private Integer reportParseFailure(Cause cause) {
        var location = cause instanceof SheetError error && error.line() > 0
                       ? " (line " + error.line() + ")"
                       : "";

        System.err.println("derive: cannot read sheet — " + cause.message() + location);

        return 1;
    }

    private Integer reportResult(DeriveResult result) {
        System.out.println(json
                           ? JsonReport.render(result)
                           : MarkdownReport.render(result));

        return result.exitCode();
    }
}
