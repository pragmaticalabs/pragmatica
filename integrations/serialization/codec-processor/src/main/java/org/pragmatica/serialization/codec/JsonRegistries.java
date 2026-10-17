package org.pragmatica.serialization.codec;

import javax.annotation.processing.Filer;
import javax.lang.model.util.Elements;
import javax.tools.StandardLocation;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.serialization.json.codegen.JsonCodecGenerator;

import static org.pragmatica.lang.Option.option;


/// The per-package JSON registries: one `<Segment>JsonCodecs<suffix>_<hash>` class per package holding `@Json` types,
/// implementing `JsonCodecProvider` and listing each type's entry-point `CODEC`, plus the single
/// `META-INF/services` file naming every registry, so `JsonCodecs.codecFor` finds them through `ServiceLoader`.
///
/// Names: `<hash>` is the first 8 hex digits of the SHA-256 of the sorted qualified names the registry lists. Two
/// artifacts with `@Json` types in one package (`src/main` and `src/test`, or two modules sharing a package) list
/// different types, so their registries get different names and both load; with one name per package the
/// `ServiceLoader`, which loads a provider name once, would silently drop one of them. The same type set always
/// gets the same name, so a build is reproducible and two copies of one registry are deduplicated by name.
///
/// Rounds: a package's registry is written in the first round that brings no new `@Json` type for it. The round
/// that generated its entry points always has a successor (generated sources start a round), so registries are
/// written before the last round and are compiled like any other source. A package whose `@Json` types arrive
/// after its registry was written (another processor generating one late) gets a second registry, named by its
/// own type set. The services file is a resource and is written once, in the last round, with every registry;
/// an entry a previous incremental compilation left in it is kept while its class still resolves, unless this
/// compilation wrote a registry for the same package (it supersedes the old one, whose type set and so name may
/// differ).
final class JsonRegistries {
    static final String SERVICE = "org.pragmatica.serialization.json.spi.JsonCodecProvider";
    private static final String SERVICE_FILE = "META-INF/services/" + SERVICE;

    private final Filer filer;
    private final Elements elements;
    private final String suffix;
    private final GeneratedNames generatedNames;
    // package -> (type qualified name -> entry-point simple name), in discovery order
    private final Map<String, Map<String, String>> pending = new LinkedHashMap<>();
    private final Set<String> writtenPackages = new HashSet<>();
    private final Set<String> providers = new LinkedHashSet<>();

    JsonRegistries(Filer filer, Elements elements, String suffix, GeneratedNames generatedNames) {
        this.filer = filer;
        this.elements = elements;
        this.suffix = suffix;
        this.generatedNames = generatedNames;
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
        return registryName(packageName, suffix, entries.keySet()).flatMap(name -> writeRegistry(packageName,
                                                                                                 name,
                                                                                                 entries));
    }

    private Result<Unit> writeRegistry(String packageName, String name, Map<String, String> entries) {
        var qualified = JsonCodecGenerator.qualifiedName(packageName, name);
        var owner = "the @Json types " + String.join(", ", entries.keySet());

        return generatedNames.claim(qualified, owner)
                             .flatMap(_ -> JsonCodecGenerator.ensureFree(elements, qualified, owner))
                             .flatMap(_ -> writeSource(qualified,
                                                       registrySource(packageName, name, entries)))
                             .onSuccess(_ -> providers.add(qualified))
                             .onSuccess(_ -> writtenPackages.add(packageName));
    }

    private Result<Unit> writeSource(String qualified, List<String> lines) {
        return Result.lift(Causes::fromThrowable,
                           () -> filer.createSourceFile(qualified))
                     .flatMap(file -> Result.lift(Causes::fromThrowable, file::openWriter))
                     .map(writer -> print(writer, lines));
    }

    /// `<Segment>JsonCodecs<suffix>_<hash>`: `<hash>` is the first 8 hex digits of the SHA-256 of `types` sorted,
    /// one qualified name per line.
    static Result<String> registryName(String packageName, String suffix, Collection<String> types) {
        var segment = packageName.substring(packageName.lastIndexOf('.') + 1);
        var capitalized = segment.isEmpty()
                          ? "Default"
                          : Character.toUpperCase(segment.charAt(0)) + segment.substring(1);

        return typeSetHash(types).map(hash -> capitalized + "JsonCodecs" + suffix + "_" + hash);
    }

    // SHA-256 is required of every Java platform, so the lookup does not fail in practice; it is a Result only
    // because MessageDigest declares the checked exception.
    private static Result<String> typeSetHash(Collection<String> types) {
        var text = String.join("\n",
                               types.stream().sorted().toList());

        return Result.lift(Causes::fromThrowable,
                           () -> MessageDigest.getInstance("SHA-256"))
                     .map(digest -> digest.digest(text.getBytes(StandardCharsets.UTF_8)))
                     .map(bytes -> HexFormat.of().formatHex(bytes, 0, 4));
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

        previousProviders().stream().filter(this::isResolvable).filter(this::isNotSuperseded).forEach(all::add);
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

    // A previous entry for a package this compilation wrote a registry for is the old copy of that registry (its
    // type set, and so its name, may differ): listing both would make every type they share a conflict.
    private boolean isNotSuperseded(String provider) {
        return ! writtenPackages.contains(provider.substring(0,
                                                             Math.max(0, provider.lastIndexOf('.'))));
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
