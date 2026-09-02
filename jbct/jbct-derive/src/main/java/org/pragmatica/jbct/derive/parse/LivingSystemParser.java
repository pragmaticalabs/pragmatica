package org.pragmatica.jbct.derive.parse;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.jbct.derive.model.CurrentVector;
import org.pragmatica.jbct.derive.model.CurrentVector.AxisPosition;
import org.pragmatica.jbct.derive.model.CurrentVector.RecoveryPosition;
import org.pragmatica.jbct.derive.model.Floor;
import org.pragmatica.jbct.derive.model.Scope;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

/// Parses the living-system inputs of a sheet: the optional `[current_vector]` and the optional
/// `[[floors]]` (SPEC.md §3). These feed Phase-B audit and verification; Phase A only parses and
/// carries them, so the entry gate ignores both.
public sealed interface LivingSystemParser {
    record unused() implements LivingSystemParser {}

    /// The current vector, present only for living-mode sheets that declare `[current_vector]`.
    static Result<Option<CurrentVector>> currentVector(TomlDocument doc) {
        return doc.hasSection("current_vector")
               ? present(doc).map(Option::some)
               : Result.success(Option.none());
    }

    /// The verification floors, one per `[[floors]]` block (empty when none are supplied).
    static Result<List<Floor>> floors(TomlDocument doc, SheetLines lines) {
        var arr = doc.getTableArray("floors").or(List.of());
        var results = IntStream.range(0, arr.size())
                               .mapToObj(i -> floor(i, lines.lineFor("floors", i), arr.get(i)))
                               .toList();

        return Result.allOf(results);
    }

    private static Result<CurrentVector> present(TomlDocument doc) {
        return Result.all(axis(doc, "topology"),
                          axis(doc, "substrate"),
                          axis(doc, "read_write"),
                          axis(doc, "state"),
                          axis(doc, "persistence"),
                          recovery(doc))
                     .map(CurrentVector::new);
    }

    private static Result<List<AxisPosition>> axis(TomlDocument doc, String key) {
        var entries = TomlAccess.sectionValue(doc, "current_vector", key).flatMap(TomlAccess::asList).or(List.of());

        return Result.allOf(entries.stream().map(entry -> axisPosition(key, entry)).toList());
    }

    private static Result<AxisPosition> axisPosition(String axisKey, Object entry) {
        return TomlAccess.asMap(entry)
                         .toResult(Causes.cause("current_vector." + axisKey + " entry is not a table"))
                         .flatMap(map -> axisFromMap(axisKey, map));
    }

    private static Result<AxisPosition> axisFromMap(String axisKey, Map<String, Object> map) {
        var where = "current_vector." + axisKey;

        return Result.all(required(map, "value", where), scopeOf(map, where)).map(AxisPosition::new);
    }

    private static Result<List<RecoveryPosition>> recovery(TomlDocument doc) {
        var entries = TomlAccess.sectionValue(doc, "current_vector", "recovery")
                                .flatMap(TomlAccess::asList)
                                .or(List.of());

        return Result.allOf(entries.stream().map(LivingSystemParser::recoveryPosition).toList());
    }

    private static Result<RecoveryPosition> recoveryPosition(Object entry) {
        return TomlAccess.asMap(entry)
                         .toResult(Causes.cause("current_vector.recovery entry is not a table"))
                         .flatMap(LivingSystemParser::recoveryFromMap);
    }

    private static Result<RecoveryPosition> recoveryFromMap(Map<String, Object> map) {
        return Result.all(required(map, "operation", "current_vector.recovery"),
                          required(map, "class", "current_vector.recovery"))
                     .map(RecoveryPosition::new);
    }

    private static Result<Floor> floor(int index, int line, Map<String, Object> row) {
        return TomlAccess.str(row, "path")
                         .toResult(Causes.cause("missing 'path'"))
                         .flatMap(Scope::scope)
                         .mapError(cause -> new SheetError.MalformedRow("floors", index, line, cause.message()))
                         .flatMap(path -> hopsOf(row, path));
    }

    private static Result<Floor> hopsOf(Map<String, Object> row, Scope path) {
        var entries = TomlAccess.asList(row.get("hops")).or(List.of());

        return Result.allOf(entries.stream().map(LivingSystemParser::hop).toList()).map(hops -> new Floor(path, hops));
    }

    private static Result<Floor.Hop> hop(Object entry) {
        return TomlAccess.asMap(entry)
                         .toResult(Causes.cause("floor hop is not a table"))
                         .flatMap(LivingSystemParser::hopFromMap);
    }

    private static Result<Floor.Hop> hopFromMap(Map<String, Object> map) {
        return TomlAccess.str(map, "name")
                         .toResult(Causes.cause("floor hop missing 'name'"))
                         .map(name -> assembleHop(map, name));
    }

    private static Floor.Hop assembleHop(Map<String, Object> map, String name) {
        return new Floor.Hop(name, TomlAccess.longVal(map, "p50_ms"));
    }

    private static Result<String> required(Map<String, Object> map, String key, String where) {
        return TomlAccess.str(map, key).toResult(new SheetError.MissingField(where + "." + key));
    }

    private static Result<Scope> scopeOf(Map<String, Object> map, String where) {
        return required(map, "scope", where).flatMap(Scope::scope);
    }
}
