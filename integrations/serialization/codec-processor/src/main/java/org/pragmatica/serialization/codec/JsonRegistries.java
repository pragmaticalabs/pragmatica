package org.pragmatica.serialization.codec;

import javax.annotation.processing.Filer;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.ElementFilter;
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
import java.util.SortedMap;
import java.util.TreeMap;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.serialization.json.codegen.JsonCodecGenerator;
import org.pragmatica.serialization.json.codegen.JsonNames;

import static org.pragmatica.lang.Option.option;


/// The per-package JSON registries: one `<Segment>JsonCodecs<suffix>_<hash>` class per package holding `@Json` types,
/// implementing `JsonCodecProvider` and listing each type's entry-point `CODEC`, plus the single
/// `META-INF/services` file naming every registry, so `JsonCodecs.codecFor` finds them through `ServiceLoader`.
///
/// Names: `<hash>` is the first 16 hex digits (64 bits) of the SHA-256 of the sorted flat qualified names
/// (`JsonNames`, injective) of the types the registry lists, one per line. Two artifacts with `@Json` types in one
/// package (`src/main` and `src/test`, or two modules sharing a package) list different types, so their registries
/// get different names and both load; with one name per package the `ServiceLoader`, which loads a provider name
/// once, would silently drop one of them. The same type set always gets the same name, so a build is reproducible
/// and two copies of one registry are deduplicated by name. Two different type sets of one package collide with
/// probability about `2^-64` per pair (about `n^2 / 2^65` among `n` such artifacts); a collision would make the
/// `ServiceLoader` load only one of the two registries, so the other's types would look up as absent.
///
/// Rounds: a package's registry is written in the first round that brings no new `@Json` type for it. The round
/// that generated its entry points always has a successor (generated sources start a round), so registries are
/// written before the last round and are compiled like any other source. A package whose `@Json` types arrive
/// after its registry was written (another processor generating one late) gets a second registry, listing only the
/// types no registry of this compilation lists yet. The services file is a resource and is written once, in the last
/// round, with every registry; an entry a previous incremental compilation left in it is kept while its class still
/// resolves, unless this compilation wrote a registry for the same package (it supersedes the old one, whose type set
/// and so name may differ).
///
/// Incremental compilation: every registry is written with a manifest resource,
/// `META-INF/pragmatica/json/<registry>.types`, listing its types' qualified names, sorted. The first registry this
/// compilation writes for a package also lists the types a previous registry of that package listed (read from the
/// manifests of the previous services entries) when this compilation does not compile them and both the type and its
/// entry point, marked as generated for it, still resolve. Recompiling some of a package's types into the same output
/// therefore keeps the others; a type whose classes are gone, or that is recompiled without `@Json`, is dropped. The
/// merged set is what the registry lists and what its name is hashed over, so the superseding rule above stays exact.
final class JsonRegistries {
    static final String SERVICE = "org.pragmatica.serialization.json.spi.JsonCodecProvider";
    static final String MANIFEST_DIRECTORY = "META-INF/pragmatica/json/";
    private static final String SERVICE_FILE = "META-INF/services/" + SERVICE;

    private final Filer filer;
    private final Elements elements;
    private final String suffix;
    private final GeneratedNames generatedNames;
    private final List<String> previousProviders;
    // package -> (type qualified name -> flat name), in discovery order
    private final Map<String, Map<String, String>> pending = new LinkedHashMap<>();
    private final Set<String> writtenPackages = new HashSet<>();
    private final Set<String> providers = new LinkedHashSet<>();
    private final Set<String> listed = new HashSet<>();
    private final Set<String> compiled = new HashSet<>();

    JsonRegistries(Filer filer, Elements elements, String suffix, GeneratedNames generatedNames) {
        this.filer = filer;
        this.elements = elements;
        this.suffix = suffix;
        this.generatedNames = generatedNames;
        this.previousProviders = previousProviders(filer);
    }

    /// Records `type`'s entry point for its package's next registry; returns this collector.
    JsonRegistries add(String packageName, TypeElement type) {
        pending.computeIfAbsent(packageName,
                                _ -> new LinkedHashMap<>())
               .put(type.getQualifiedName().toString(),
                    JsonNames.flatName(type));

        return this;
    }

    /// Records the types this compilation compiles (a round's root elements and the types nested in them); returns
    /// this collector. A type a previous registry listed is carried over only when it is not one of them: a
    /// recompiled type that is still `@Json` is listed afresh, and one that is no longer `@Json` must not be listed.
    JsonRegistries compiling(Collection<? extends Element> roots) {
        roots.forEach(this::addCompiled);

        return this;
    }

    private void addCompiled(Element element) {
        if (element instanceof TypeElement type) {
            compiled.add(type.getQualifiedName().toString());
            ElementFilter.typesIn(type.getEnclosedElements()).forEach(this::addCompiled);
        }
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

    // The types to list, sorted by qualified name: this round's, plus (first registry of the package only) the ones
    // carried over from a previous compilation, minus any a registry of this compilation already lists. Nothing left
    // means no registry.
    private Result<Unit> writeRegistry(String packageName, Map<String, String> entries) {
        var listing = new TreeMap<>(entries);

        if (!writtenPackages.contains(packageName)) {
            retained(packageName, entries).forEach(listing::putIfAbsent);
        }

        listing.keySet().removeAll(listed);
        if (listing.isEmpty()) {
            return Result.unitResult();
        }

        return registryName(packageName, suffix, listing.values()).flatMap(name -> writeRegistry(packageName,
                                                                                                 name,
                                                                                                 listing));
    }

    private Result<Unit> writeRegistry(String packageName, String name, SortedMap<String, String> listing) {
        var qualified = JsonCodecGenerator.qualifiedName(packageName, name);
        var owner = "the @Json types " + String.join(", ", listing.keySet());

        return generatedNames.claim(qualified, owner)
                             .flatMap(_ -> JsonCodecGenerator.ensureFree(elements, qualified, owner))
                             .flatMap(_ -> writeSource(qualified,
                                                       registrySource(packageName, name, owner, listing)))
                             .flatMap(_ -> writeResource(manifestFile(qualified),
                                                         List.copyOf(listing.keySet())))
                             .onSuccess(_ -> recordWritten(packageName,
                                                           qualified,
                                                           listing.keySet()));
    }

    private Unit recordWritten(String packageName, String qualified, Set<String> types) {
        providers.add(qualified);
        writtenPackages.add(packageName);
        listed.addAll(types);

        return Unit.unit();
    }

    // The types of `packageName` the previous registries of that package listed (their manifests) that this
    // compilation neither lists nor compiles, while the type and its entry point, generated for it, still resolve.
    private Map<String, String> retained(String packageName, Map<String, String> entries) {
        var retained = new TreeMap<String, String>();

        previousProviders.stream()
                         .filter(provider -> packageOf(provider).equals(packageName))
                         .flatMap(provider -> manifest(provider).stream())
                         .filter(type -> !entries.containsKey(type) && !compiled.contains(type))
                         .forEach(type -> resolvedFlatName(packageName, type).onPresent(flat -> retained.put(type, flat)));

        return retained;
    }

    private Option<String> resolvedFlatName(String packageName, String type) {
        return option(elements.getTypeElement(type)).map(JsonNames::flatName)
                     .filter(flat -> isEntryPointOf(packageName, flat, type));
    }

    private boolean isEntryPointOf(String packageName, String flat, String type) {
        return option(elements.getTypeElement(JsonCodecGenerator.qualifiedName(packageName, flat + "Json"))).flatMap(JsonCodecGenerator::generatedFor)
                     .filter(type::equals)
                     .isPresent();
    }

    // The types a previous compilation's registry `provider` listed, from its manifest in the class output.
    private List<String> manifest(String provider) {
        // Design-out: a registry without a readable manifest (deleted, or written before manifests existed) has
        // nothing to carry over, so the read failure is replaced by an empty list rather than reported.
        return Result.lift(Causes::fromThrowable,
                           () -> filer.getResource(StandardLocation.CLASS_OUTPUT,
                                                   "",
                                                   manifestFile(provider))
                                      .getCharContent(true))
                     .map(JsonRegistries::providerLines)
                     .or(List.of());
    }

    static String manifestFile(String registry) {
        return MANIFEST_DIRECTORY + registry + ".types";
    }

    private static String packageOf(String qualified) {
        return qualified.substring(0,
                                   Math.max(0, qualified.lastIndexOf('.')));
    }

    private Result<Unit> writeSource(String qualified, List<String> lines) {
        return Result.lift(Causes::fromThrowable,
                           () -> filer.createSourceFile(qualified))
                     .flatMap(file -> Result.lift(Causes::fromThrowable, file::openWriter))
                     .map(writer -> print(writer, lines));
    }

    /// `<Segment>JsonCodecs<suffix>_<hash>`: `<hash>` is the first 16 hex digits of the SHA-256 of the qualified flat
    /// names of `flatNames` (types of `packageName`) sorted, one per line.
    static Result<String> registryName(String packageName, String suffix, Collection<String> flatNames) {
        var segment = packageName.substring(packageName.lastIndexOf('.') + 1);
        var capitalized = segment.isEmpty()
                          ? "Default"
                          : Character.toUpperCase(segment.charAt(0)) + segment.substring(1);

        return typeSetHash(packageName, flatNames).map(hash -> capitalized + "JsonCodecs" + suffix + "_" + hash);
    }

    // SHA-256 is required of every Java platform, so the lookup does not fail in practice; it is a Result only
    // because MessageDigest declares the checked exception.
    private static Result<String> typeSetHash(String packageName, Collection<String> flatNames) {
        var text = String.join("\n",
                               flatNames.stream()
                                        .map(flat -> JsonCodecGenerator.qualifiedName(packageName, flat))
                                        .sorted()
                                        .toList());

        return Result.lift(Causes::fromThrowable,
                           () -> MessageDigest.getInstance("SHA-256"))
                     .map(digest -> digest.digest(text.getBytes(StandardCharsets.UTF_8)))
                     .map(bytes -> HexFormat.of().formatHex(bytes, 0, 8));
    }

    private static List<String> registrySource(String packageName,
                                               String name,
                                               String owner,
                                               SortedMap<String, String> listing) {
        var map = "java.util.Map<java.lang.Class<?>, org.pragmatica.serialization.json.JsonTypeCodec<?>>";
        var lines = new ArrayList<String>();

        if (!packageName.isEmpty()) {
            lines.add("package " + packageName + ";");
            lines.add("");
        }

        lines.add("/// GENERATED by CodecProcessor: the @Json codecs of this package, for JsonCodecs.codecFor. Do not edit.");
        lines.add(JsonCodecGenerator.generatedForMarker(owner));
        lines.add("public final class " + name + " implements " + SERVICE + " {");
        lines.add("    public static final " + map + " CODECS = java.util.Map.ofEntries(");
        var rows = new ArrayList<String>();

        listing.forEach((type, flat) -> rows.add("        java.util.Map.entry(" + type
                                                + ".class, " + flat
                                                + "Json.CODEC)"));
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

        previousProviders.stream().filter(this::isResolvable).filter(this::isNotSuperseded).forEach(all::add);
        all.addAll(providers);

        return writeResource(SERVICE_FILE, List.copyOf(all));
    }

    private Result<Unit> writeResource(String file, List<String> lines) {
        return Result.lift(Causes::fromThrowable,
                           () -> filer.createResource(StandardLocation.CLASS_OUTPUT, "", file))
                     .flatMap(resource -> Result.lift(Causes::fromThrowable, resource::openWriter))
                     .map(writer -> print(writer, lines));
    }

    // A stale entry (its registry deleted since) would make ServiceLoader fail every lookup, so it is dropped.
    private boolean isResolvable(String provider) {
        return option(elements.getTypeElement(provider)).isPresent();
    }

    // A previous entry for a package this compilation wrote a registry for is the old copy of that registry (its
    // type set, and so its name, may differ): listing both would make every type they share a conflict.
    private boolean isNotSuperseded(String provider) {
        return ! writtenPackages.contains(packageOf(provider));
    }

    // The services file a previous (incremental) compilation left in the class output, if any.
    private static List<String> previousProviders(Filer filer) {
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
