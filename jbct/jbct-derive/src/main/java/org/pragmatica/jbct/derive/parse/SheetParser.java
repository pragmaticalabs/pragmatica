package org.pragmatica.jbct.derive.parse;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.config.toml.TomlError;
import org.pragmatica.config.toml.TomlParser;
import org.pragmatica.jbct.derive.model.AnswerRow;
import org.pragmatica.jbct.derive.model.AnswerSheet;
import org.pragmatica.jbct.derive.model.ChangeDriver;
import org.pragmatica.jbct.derive.model.CurrentVector;
import org.pragmatica.jbct.derive.model.DomainShape;
import org.pragmatica.jbct.derive.model.Floor;
import org.pragmatica.jbct.derive.model.Meta;
import org.pragmatica.jbct.derive.model.Mode;
import org.pragmatica.jbct.derive.model.QuestionId;
import org.pragmatica.jbct.derive.model.RowStatus;
import org.pragmatica.jbct.derive.model.Scope;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.FileOps;
import org.pragmatica.lang.utils.Causes;

/// Parses a TOML answer sheet into the typed [AnswerSheet] model (SPEC.md §3), the first stage of
/// the pipeline (`parse → normalize → …`). This is parse-don't-validate: a returned [AnswerSheet]
/// is structurally sound and ready for the entry gate; anything malformed fails as a [SheetError]
/// carrying the sheet line where known.
///
/// Structural faults (bad TOML, missing `schema_version`/`[meta]`, malformed rows) fail here.
/// Semantic completeness — the book's entry gate — is a separate stage
/// ([org.pragmatica.jbct.derive.gate.EntryGate]).
public sealed interface SheetParser {
    record unused() implements SheetParser {}

    /// Parse a sheet from a file. The path becomes the sheet's `source` for diagnostics.
    static Result<AnswerSheet> parse(Path path) {
        return FileOps.readString(path)
                      .mapError(cause -> new SheetError.FileReadFailed(path.toString(), cause.message()))
                      .flatMap(content -> parse(content, path.toString()));
    }

    /// Parse a sheet from raw TOML text with an explicit source label.
    static Result<AnswerSheet> parse(String content, String source) {
        var lines = SheetLines.of(content);

        return TomlParser.parse(content)
                         .mapError(SheetParser::toSheetError)
                         .flatMap(doc -> fromDocument(doc, lines, source));
    }

    private static Cause toSheetError(Cause cause) {
        return cause instanceof TomlError toml
               ? SheetError.fromToml(toml)
               : new SheetError.Malformed(0, cause.message());
    }

    private static Result<AnswerSheet> fromDocument(TomlDocument doc, SheetLines lines, String source) {
        return Result.all(schemaVersion(doc),
                          meta(doc),
                          rows(doc, lines),
                          domainShapes(doc, lines),
                          changeDrivers(doc, lines),
                          LivingSystemParser.currentVector(doc),
                          LivingSystemParser.floors(doc, lines))
                     .map((version, meta, rows, shapes, drivers, vector, floors) ->
                              assemble(source, version, meta, rows, shapes, drivers, vector, floors));
    }

    private static AnswerSheet assemble(String source,
                                        String version,
                                        Meta meta,
                                        List<AnswerRow> rows,
                                        List<DomainShape> shapes,
                                        List<ChangeDriver> drivers,
                                        Option<CurrentVector> vector,
                                        List<Floor> floors) {
        return new AnswerSheet(source, version, meta, rows, shapes, drivers, vector, floors);
    }

    // ---- schema_version ----

    private static Result<String> schemaVersion(TomlDocument doc) {
        return doc.getString("", "schema_version")
                  .toResult(new SheetError.MissingSchemaVersion())
                  .flatMap(SheetParser::checkMajor);
    }

    private static Result<String> checkMajor(String version) {
        return version.startsWith("0.") || version.equals("0")
               ? Result.success(version)
               : new SheetError.UnsupportedSchemaVersion(version).result();
    }

    // ---- [meta] ----

    private static Result<Meta> meta(TomlDocument doc) {
        return Result.all(requiredMeta(doc, "system"), requiredMeta(doc, "era"), mode(doc))
                     .map((system, era, mode) -> assembleMeta(doc, system, era, mode));
    }

    private static Meta assembleMeta(TomlDocument doc, String system, String era, Mode mode) {
        return new Meta(system, era, doc.getString("meta", "author"), doc.getString("meta", "date"), mode);
    }

    private static Result<Mode> mode(TomlDocument doc) {
        return requiredMeta(doc, "mode").flatMap(Mode::mode);
    }

    private static Result<String> requiredMeta(TomlDocument doc, String key) {
        return doc.getString("meta", key).toResult(new SheetError.MissingField("meta." + key));
    }

    // ---- [[answers.qN]] ----

    private static Result<List<AnswerRow>> rows(TomlDocument doc, SheetLines lines) {
        var results = Stream.of(QuestionId.values())
                            .flatMap(question -> rowResults(doc, lines, question).stream())
                            .toList();

        return Result.allOf(results);
    }

    private static List<Result<AnswerRow>> rowResults(TomlDocument doc, SheetLines lines, QuestionId question) {
        var table = question.tableName();
        var arr = doc.getTableArray(table).or(List.of());

        return IntStream.range(0, arr.size())
                        .mapToObj(i -> answerRow(question, table, i, lines.lineFor(table, i), arr.get(i)))
                        .toList();
    }

    private static Result<AnswerRow> answerRow(QuestionId question, String table, int index, int line, Map<String, Object> row) {
        return Result.all(scopeField(row, table, index, line),
                          statement(row, table, index, line),
                          status(row, table, index, line))
                     .map((scope, statement, status) -> assembleRow(question, line, row, scope, statement, status));
    }

    private static AnswerRow assembleRow(QuestionId question,
                                         int line,
                                         Map<String, Object> row,
                                         Scope scope,
                                         String statement,
                                         RowStatus status) {
        return new AnswerRow(question,
                             line,
                             scope,
                             statement,
                             status,
                             TomlAccess.str(row, "price"),
                             TomlAccess.str(row, "shape"),
                             TomlAccess.str(row, "basis"),
                             TomlAccess.str(row, "kind"),
                             TomlAccess.strList(row, "strikes"),
                             TomlAccess.bool(row, "contained").or(false),
                             TomlAccess.str(row, "source"));
    }

    // ---- [[domain_shape]] ----

    private static Result<List<DomainShape>> domainShapes(TomlDocument doc, SheetLines lines) {
        var arr = doc.getTableArray("domain_shape").or(List.of());
        var results = IntStream.range(0, arr.size())
                               .mapToObj(i -> domainShape(i, lines.lineFor("domain_shape", i), arr.get(i)))
                               .toList();

        return Result.allOf(results);
    }

    private static Result<DomainShape> domainShape(int index, int line, Map<String, Object> row) {
        return TomlAccess.str(row, "operation")
                         .toResult(new SheetError.MalformedRow("domain_shape", index, line, "missing 'operation'"))
                         .map(operation -> assembleDomainShape(operation, row, line));
    }

    private static DomainShape assembleDomainShape(String operation, Map<String, Object> row, int line) {
        return new DomainShape(operation,
                               TomlAccess.str(row, "inverse").or("none"),
                               TomlAccess.bool(row, "decays").or(false),
                               TomlAccess.strList(row, "reshapeable"),
                               line);
    }

    // ---- [[change_drivers]] ----

    private static Result<List<ChangeDriver>> changeDrivers(TomlDocument doc, SheetLines lines) {
        var arr = doc.getTableArray("change_drivers").or(List.of());
        var results = IntStream.range(0, arr.size())
                               .mapToObj(i -> changeDriver(i, lines.lineFor("change_drivers", i), arr.get(i)))
                               .toList();

        return Result.allOf(results);
    }

    private static Result<ChangeDriver> changeDriver(int index, int line, Map<String, Object> row) {
        return Result.all(scopeField(row, "change_drivers", index, line),
                          requiredRow(row, "volatility", "change_drivers", index, line))
                     .map((scope, volatility) -> assembleChangeDriver(scope, volatility, row, line));
    }

    private static ChangeDriver assembleChangeDriver(Scope scope, String volatility, Map<String, Object> row, int line) {
        return new ChangeDriver(scope, volatility, TomlAccess.str(row, "source"), line);
    }

    // ---- shared row-field parsers ----

    private static Result<Scope> scopeField(Map<String, Object> row, String table, int index, int line) {
        return TomlAccess.str(row, "scope")
                         .toResult(Causes.cause("missing 'scope'"))
                         .flatMap(Scope::scope)
                         .mapError(cause -> new SheetError.MalformedRow(table, index, line, cause.message()));
    }

    private static Result<String> statement(Map<String, Object> row, String table, int index, int line) {
        return TomlAccess.str(row, "statement")
                         .filter(text -> !text.isEmpty())
                         .toResult(new SheetError.MalformedRow(table, index, line, "missing 'statement'"));
    }

    private static Result<RowStatus> status(Map<String, Object> row, String table, int index, int line) {
        return TomlAccess.str(row, "status")
                         .toResult(Causes.cause("missing 'status'"))
                         .flatMap(RowStatus::rowStatus)
                         .mapError(cause -> new SheetError.MalformedRow(table, index, line, cause.message()));
    }

    private static Result<String> requiredRow(Map<String, Object> row, String key, String table, int index, int line) {
        return TomlAccess.str(row, key).toResult(new SheetError.MalformedRow(table, index, line, "missing '" + key + "'"));
    }
}
