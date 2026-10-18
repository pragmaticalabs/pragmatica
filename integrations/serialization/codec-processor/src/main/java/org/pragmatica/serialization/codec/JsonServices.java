package org.pragmatica.serialization.codec;

import javax.annotation.processing.Filer;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.tools.StandardLocation;
import java.io.PrintWriter;
import java.io.Writer;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import static org.pragmatica.lang.Option.option;


/// The services file that makes every `@Json` entry point its type's `ServiceLoader` provider:
/// `META-INF/services/org.pragmatica.serialization.json.spi.JsonCodecProvider`, one entry point binary name per line,
/// sorted, so the file is reproducible. It is a resource, written once, in the last round, and only by a compilation
/// that generated at least one entry point; its originating elements are the entry points' source types.
///
/// Incremental compilation: an entry a previous compilation left in the file (read from the class output) is kept
/// while its class still resolves, so recompiling some `@Json` types into the same output keeps the others, and
/// dropped once its class is gone, so a deleted type's entry does not linger. An entry point whose type lost `@Json`
/// still resolves while its class file remains, so its entry stays until the build tool removes the outputs of the
/// changed source (incremental IDE builds track the originating elements) or the output is cleaned.
final class JsonServices {
    static final String SERVICE_FILE = "META-INF/services/org.pragmatica.serialization.json.spi.JsonCodecProvider";

    private final Filer filer;
    private final Elements elements;

    private final List<String> previous;

    // entry point binary name -> the @Json type it was generated for
    private final Map<String, TypeElement> entryPoints = new TreeMap<>();

    JsonServices(Filer filer, Elements elements) {
        this.filer = filer;
        this.elements = elements;
        this.previous = previousEntries(filer);
    }

    /// Records the entry point `qualified` generated for `type`; returns this collector.
    JsonServices add(String qualified, TypeElement type) {
        entryPoints.put(qualified, type);

        return this;
    }

    /// Writes the services file: this compilation's entry points and the previous entries that still resolve, sorted.
    /// Nothing is written when this compilation generated no entry point.
    Result<Unit> write() {
        return entryPoints.isEmpty()
               ? Result.unitResult()
               : writeResource(listing());
    }

    // This compilation's entry points and the previous entries that still resolve, sorted by name.
    private SortedMap<String, Element> listing() {
        var all = new TreeMap<String, Element>(entryPoints);

        previous.stream()
                .flatMap(name -> resolved(name).stream())
                .forEach(resolved -> all.putIfAbsent(resolved.getKey(),
                                                     resolved.getValue()));

        return all;
    }

    // A stale entry (its class deleted since) would make the ServiceLoader report a broken provider, so it is dropped.
    private Option<Map.Entry<String, Element>> resolved(String name) {
        return option(elements.getTypeElement(name)).map(element -> Map.<String, Element> entry(name, element));
    }

    private Result<Unit> writeResource(SortedMap<String, Element> listing) {
        return Result.lift(Causes::fromThrowable,
                           () -> filer.createResource(StandardLocation.CLASS_OUTPUT,
                                                      "",
                                                      SERVICE_FILE,
                                                      listing.values().toArray(Element[]::new)))
                     .flatMap(resource -> Result.lift(Causes::fromThrowable, resource::openWriter))
                     .map(writer -> print(writer,
                                          List.copyOf(listing.keySet())));
    }

    // The services file a previous (incremental) compilation left in the class output, if any.
    private static List<String> previousEntries(Filer filer) {
        // Design-out: no readable previous file is the normal case of a clean build, and means nothing to keep, so
        // the read failure is replaced by an empty list rather than reported.
        return Result.lift(Causes::fromThrowable,
                           () -> filer.getResource(StandardLocation.CLASS_OUTPUT, "", SERVICE_FILE)
                                      .getCharContent(true))
                     .map(JsonServices::entryLines)
                     .or(List.of());
    }

    // Reads back what write() produces, one binary name per line, and skips blank and whole-line comment lines. Unlike
    // the runtime lookup's reader it does not strip trailing comments: this file is the processor's own output, which
    // carries none, so a hand-edited line with one is not kept as an entry (its class name would not resolve).
    private static List<String> entryLines(CharSequence text) {
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
