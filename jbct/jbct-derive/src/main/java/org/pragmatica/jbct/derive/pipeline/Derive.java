package org.pragmatica.jbct.derive.pipeline;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.pragmatica.jbct.derive.gate.EntryGate;
import org.pragmatica.jbct.derive.model.AnswerSheet;
import org.pragmatica.jbct.derive.parse.SheetParser;
import org.pragmatica.jbct.derive.pipeline.Press.PressResult;
import org.pragmatica.jbct.derive.pipeline.Resolve.ResolveResult;
import org.pragmatica.jbct.derive.pipeline.Verify.VerifyResult;
import org.pragmatica.jbct.derive.result.DeriveResult;
import org.pragmatica.jbct.derive.result.Halt;
import org.pragmatica.jbct.derive.result.Strike;
import org.pragmatica.lang.Result;

/// Derive — the full pipeline (SPEC.md §4): `parse → normalize → prune → press → resolve → verify
/// → emit`. This interface is the `emit`-less spine; the emitters render the [DeriveResult] it
/// produces.
///
/// The normalize stage is the entry gate: a sheet that fails it yields a gate-only result (the
/// fake-answer halt, ch. 8) and the pipeline never runs — the book does not derive from fake
/// answers. A clean sheet runs the four derivation stages in order.
public sealed interface Derive permits Derive.unused {
    record unused() implements Derive {}

    /// Derive from a sheet file. The path becomes the result's source.
    static Result<DeriveResult> derive(Path path) {
        return SheetParser.parse(path).map(Derive::derive);
    }

    /// Derive from raw TOML text with an explicit source label.
    static Result<DeriveResult> derive(String content, String source) {
        return SheetParser.parse(content, source).map(Derive::derive);
    }

    /// Derive from an already-parsed sheet.
    static DeriveResult derive(AnswerSheet sheet) {
        var gate = EntryGate.check(sheet);

        return gate.isEmpty()
               ? runPipeline(sheet)
               : DeriveResult.gated(sheet.source(), sheet.meta(), gate);
    }

    private static DeriveResult runPipeline(AnswerSheet sheet) {
        var strikes = Prune.prune(sheet);
        var press = Press.press(sheet);
        var resolved = Resolve.resolve(sheet, strikes, press);
        var verified = Verify.verify(sheet, resolved.vector(), resolved.recovery());

        return assemble(sheet, strikes, press, resolved, verified);
    }

    private static DeriveResult assemble(AnswerSheet sheet,
                                         List<Strike> strikes,
                                         PressResult press,
                                         ResolveResult resolved,
                                         VerifyResult verified) {
        return new DeriveResult(sheet.source(),
                                sheet.meta(),
                                List.of(),
                                strikes,
                                press.pressures(),
                                press.combinations(),
                                resolved.decisions(),
                                resolved.vector(),
                                resolved.recovery(),
                                verified.verifications(),
                                halts(resolved, verified),
                                resolved.judgmentPoints());
    }

    private static List<Halt> halts(ResolveResult resolved, VerifyResult verified) {
        return Stream.concat(resolved.halts().stream(), verified.halts().stream()).toList();
    }
}
