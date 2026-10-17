package org.pragmatica.serialization.codec;

import javax.annotation.processing.Filer;
import javax.lang.model.util.Elements;
import javax.tools.StandardLocation;
import java.io.PrintWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import static org.pragmatica.lang.Option.option;


/// The per-package JSON registries: one `<Segment>JsonCodecs<suffix>` class per package holding `@Json` types,
/// implementing `JsonCodecProvider` and listing each type's entry-point `CODEC`, plus the single
/// `META-INF/services` file naming every registry, so `JsonCodecs.codecFor` finds them through `ServiceLoader`.
///
/// Rounds: a package's registry is written in the first round that brings no new `@Json` type for it. The round
/// that generated its entry points always has a successor (generated sources start a round), so registries are
/// written before the last round and are compiled like any other source. A package whose `@Json` types arrive
/// after its registry was written (another processor generating one late) gets a second, numbered registry.
/// The services file is a resource and is written once, in the last round, with every registry; entries a
/// previous incremental compilation left in it are kept while their class still resolves.
final class JsonRegistries {
    static final String SERVICE = "org.pragmatica.serialization.json.spi.JsonCodecProvider";
    private static final String SERVICE_FILE = "META-INF/services/" + SERVICE;

    private final Filer filer;
    private final Elements elements;
    private final String suffix;
    // package -> (type qualified name -> entry-point simple name), in discovery order
    private final Map<String, Map<String, String>> pending = new LinkedHashMap<>();
    private final Map<String, Integer> written = new HashMap<>();
    private final Set<String> providers = new LinkedHashSet<>();

    JsonRegistries(Filer filer, Elements elements, String suffix) {
        this.filer = filer;
        this.elements = elements;
        this.suffix = suffix;
    }

    /// Records an entry point for its package's next registry; returns this collector.
    JsonRegistries add(String packageName, String typeName, String entryPoint) {
        pending.computeIfAbsent(packageName, _ -> new LinkedHashMap<>()).put(typeName, entryPoint);

        return this;
    }

    /// Writes the registries of pending packages that got no new `@Json` type this round; every failed write is
    /// reported, in one composite cause.
    Result<Unit> flush(Set<String> touchedThisRound) {
        var writes = new ArrayList<Result<Unit>>();

        for (var packageName : List.copyOf(pending.keySet())) {
            if (!touchedThisRound.contains(packageName)) {
                writes.add(writeRegistry(packageName, pending.remove(packageName)));
            }
        }

        return Result.allOf(writes).mapToUnit();
    }

    /// Last round: writes any registry still pending, then the services file.
    Result<Unit> finish() {
        return Result.all(flush(Set.of()),
                          writeServices())
                     .map((_, _) -> Unit.unit());
    }

    private Result<Unit> writeRegistry(String packageName, Map<String, String> entries) {
        var count = written.merge(packageName, 1, Integer::sum);
        var name = registryName(packageName) + (count == 1
                                                ? ""
                                                : String.valueOf(count));
        var qualified = packageName.isEmpty()
                        ? name
                        : packageName + "." + name;

        return Result.lift(Causes::fromThrowable,
                           () -> filer.createSourceFile(qualified))
                     .flatMap(file -> Result.lift(Causes::fromThrowable, file::openWriter))
                     .map(writer -> print(writer,
                                          registrySource(packageName, name, entries)))
                     .onSuccess(_ -> providers.add(qualified));
    }

    private String registryName(String packageName) {
        var segment = packageName.substring(packageName.lastIndexOf('.') + 1);
        var capitalized = segment.isEmpty()
                          ? "Default"
                          : Character.toUpperCase(segment.charAt(0)) + segment.substring(1);

        return capitalized + "JsonCodecs" + suffix;
    }

    private static List<String> registrySource(String packageName, String name, Map<String, String> entries) {
        var map = "java.util.Map<java.lang.Class<?>, org.pragmatica.serialization.json.JsonTypeCodec<?>>";
        var lines = new ArrayList<String>();

        if (!packageName.isEmpty()) {
            lines.add("package " + packageName + ";");
            lines.add("");
        }

        lines.add("/// GENERATED by CodecProcessor: the @Json codecs of this package, for JsonCodecs.codecFor. Do not edit.");
        lines.add("public final class " + name + " implements " + SERVICE + " {");
        lines.add("    public static final " + map + " CODECS = java.util.Map.ofEntries(");
        var rows = new ArrayList<String>();

        entries.forEach((type, entryPoint) -> rows.add("        java.util.Map.entry(" + type
                                                      + ".class, " + entryPoint
                                                      + ".CODEC)"));
        lines.add(String.join(",\n", rows));
        lines.add("    );");
        lines.add("");
        lines.add("    @Override");
        lines.add("    public " + map + " codecs() {");
        lines.add("        return CODECS;");
        lines.add("    }");
        lines.add("}");

        return lines;
    }

    private Result<Unit> writeServices() {
        if (providers.isEmpty()) {
            return Result.unitResult();
        }

        var all = new LinkedHashSet<String>();

        previousProviders().stream().filter(this::isResolvable).forEach(all::add);
        all.addAll(providers);

        return Result.lift(Causes::fromThrowable,
                           () -> filer.createResource(StandardLocation.CLASS_OUTPUT, "", SERVICE_FILE))
                     .flatMap(file -> Result.lift(Causes::fromThrowable, file::openWriter))
                     .map(writer -> print(writer,
                                          List.copyOf(all)));
    }

    // A stale entry (its registry deleted since) would make ServiceLoader fail every lookup, so it is dropped.
    private boolean isResolvable(String provider) {
        return option(elements.getTypeElement(provider)).isPresent();
    }

    // The services file a previous (incremental) compilation left in the class output, if any.
    private List<String> previousProviders() {
        // Design-out: no readable previous file is the normal case of a clean build, and means nothing to keep, so
        // the read failure is replaced by an empty list rather than reported.
        return Result.lift(Causes::fromThrowable,
                           () -> filer.getResource(StandardLocation.CLASS_OUTPUT, "", SERVICE_FILE)
                                      .getCharContent(true))
                     .map(JsonRegistries::providerLines)
                     .or(List.of());
    }

    private static List<String> providerLines(CharSequence text) {
        return text.toString()
                   .lines()
                   .map(String::strip)
                   .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                   .toList();
    }

    private static Unit print(Writer target, List<String> lines) {
        try (var writer = new PrintWriter(target)) {
            lines.forEach(writer::println);
        }

        return Unit.unit();
    }
}
