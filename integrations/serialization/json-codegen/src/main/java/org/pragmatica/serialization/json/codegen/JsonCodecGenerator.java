/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.pragmatica.serialization.json.codegen;

import javax.annotation.processing.Filer;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import java.io.PrintWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.utils.Causes.cause;


/// Generates reflection-free JSON codecs for a set of root types. Shared by the codec processor (one `@Json`
/// type per entry point, [#writeEntryPoint]) and the slice processor (HTTP route request and response types in
/// one holder, [#write]), so both emit identical codecs. A root the generator cannot handle is reported, not
/// generated; its users fall back.
public final class JsonCodecGenerator {
    /// The marker annotation every generated JSON class carries, naming what it was generated for.
    public static final String MARKER = "org.pragmatica.serialization.json.spi.GeneratedFor";

    private final Filer filer;
    private final Elements elements;
    private final JsonTypeAnalyzer analyzer;
    private final List<TypeMirror> roots = new ArrayList<>();
    private final List<JsonType.ListOf> containerRoots = new ArrayList<>();
    private final String packageName;

    /// `packageName` is where the holder will be written; it decides which types and factories are reachable.
    public JsonCodecGenerator(ProcessingEnvironment env, String packageName) {
        this(env.getFiler(), env.getElementUtils(), env.getTypeUtils(), packageName);
    }

    public JsonCodecGenerator(Filer filer, Elements elements, Types types, String packageName) {
        this.filer = filer;
        this.elements = elements;
        this.packageName = packageName;
        this.analyzer = new JsonTypeAnalyzer(elements, types, packageName);
    }

    /// Adds a root type; empty when supported, otherwise why it is not.
    public Option<String> addRoot(TypeMirror type) {
        return addRoot(type, false);
    }

    /// Adds a root that is decoded by declared type (a request body); empty when supported, otherwise why it is
    /// not. A `List` or `Set` body root also gets a codec of its own in the holder (see [#source]). Only a body
    /// gets one: an encode sees the runtime value, never the declared type, so a response root's would be dead code.
    public Option<String> addBodyRoot(TypeMirror type) {
        return addRoot(type, true);
    }

    private Option<String> addRoot(TypeMirror type, boolean body) {
        var analyzed = analyzer.analyzeRoot(type);

        if (analyzed.isPresent()) {
            roots.add(type);
            analyzed.filter(_ -> body)
                    .filter(JsonType.ListOf.class::isInstance)
                    .map(JsonType.ListOf.class::cast)
                    .onPresent(containerRoots::add);

            return none();
        }

        return some(analyzer.reason(type));
    }

    public boolean hasCodecs() {
        return ! analyzer.records()
                         .isEmpty() || !analyzer.enums()
                                                .isEmpty();
    }

    public List<TypeMirror> roots() {
        return List.copyOf(roots);
    }

    /// The holder: a codec per record and enum (`CODECS`), and a codec per distinct `List` or `Set` body root
    /// (`CONTAINERS`, keyed by the root's reflective generic type), generated with the same container code as a
    /// component of that type.
    /// `generatedFor` names the source the holder is generated for (a slice), in its marker.
    public String source(String holderName, String generatedBy, String generatedFor) {
        return new JsonCodecEmitter(analyzer).emit(packageName, holderName, generatedBy, generatedFor, containerRoots);
    }

    /// The entry point of `root` (the only root added), named `holderName`.
    public String entryPointSource(String holderName, TypeElement root, String generatedBy) {
        return new JsonCodecEmitter(analyzer).emitEntryPoint(packageName, holderName, root, generatedBy);
    }

    /// Writes the holder `packageName.holderName` through the Filer, for `owner` (the type the holder is generated
    /// for, named in a name-clash failure).
    public Result<Unit> write(String holderName, String generatedBy, TypeElement owner) {
        return writeSource(holderName,
                           source(holderName,
                                  generatedBy,
                                  owner.getQualifiedName().toString()),
                           owner);
    }

    /// Writes the entry point `packageName.holderName` of `root` (the only root added) through the Filer.
    public Result<Unit> writeEntryPoint(String holderName, TypeElement root, String generatedBy) {
        return writeSource(holderName, entryPointSource(holderName, root, generatedBy), root);
    }

    /// The qualified name of the generated class `simpleName` in `packageName`.
    public static String qualifiedName(String packageName, String simpleName) {
        return packageName.isEmpty()
               ? simpleName
               : packageName + "." + simpleName;
    }

    private Result<Unit> writeSource(String holderName, String source, TypeElement owner) {
        var qualified = qualifiedName(packageName, holderName);

        return ensureFree(elements,
                          qualified,
                          owner.getQualifiedName().toString()).flatMap(_ -> Result.lift(Causes::fromThrowable,
                                                                                        () -> filer.createSourceFile(qualified,
                                                                                                                     owner)))
                         .flatMap(file -> Result.lift(Causes::fromThrowable, file::openWriter))
                         .map(writer -> print(writer, source));
    }

    /// Fails when `qualified`, the class about to be generated for `owner`, already names a type that is not the
    /// same source's own generated output, with one message naming both and how to resolve it, instead of the Filer's
    /// "attempt to recreate" error with a stack trace, a duplicate-class error inside generated source, or one source's
    /// codec silently replacing another's. Generated output carries a [GeneratedFor][#MARKER] marker naming its source:
    /// a class whose marker names `owner` (a previous build's class still in the output directory, or the same
    /// artifact on the class path) may be regenerated; a class generated for another source, or a user type without
    /// the marker, is refused.
    public static Result<Unit> ensureFree(Elements elements, String qualified, String owner) {
        return option(elements.getTypeElement(qualified)).flatMap(existing -> clash(existing, qualified, owner))
                     .map(Cause::<Unit> result)
                     .or(Result::unitResult);
    }

    private static Option<Cause> clash(TypeElement existing, String qualified, String owner) {
        return generatedFor(existing).map(source -> otherSource(source, qualified, owner))
                           .or(() -> some(notGenerated(existing, qualified, owner)));
    }

    private static Cause notGenerated(TypeElement existing, String qualified, String owner) {
        return cause("Cannot generate " + qualified
                    + " for " + owner
                    + ": the type " + existing.getQualifiedName()
                    + " already exists and is not generated JSON codec code."
                    + " Rename " + existing.getQualifiedName()
                    + ", or rename or move " + owner
                    + ".");
    }

    private static Option<Cause> otherSource(String source, String qualified, String owner) {
        return source.equals(owner)
               ? none()
               : some(cause("Cannot generate " + qualified
                           + " for " + owner
                           + ": it already exists, generated for " + source
                           + " (a class on the class path or in the output directory)."
                           + " Rename one of the two, or rebuild without the stale class."));
    }

    /// The marker annotation line for a class generated for `source`, which [#ensureFree] reads back from a class of the
    /// same name already on the class path: only the same source may regenerate it. `source` is qualified names and
    /// prose, never a quote or backslash, so it is a valid string literal as is.
    public static String generatedForMarker(String source) {
        return "@" + MARKER + "(\"" + source + "\")";
    }

    /// The source `existing` was generated for, read from its marker; empty for a class without one.
    public static Option<String> generatedFor(TypeElement existing) {
        return Option.from(existing.getAnnotationMirrors()
                                   .stream()
                                   .filter(mirror -> mirror.getAnnotationType()
                                                           .toString()
                                                           .equals(MARKER))
                                   .flatMap(mirror -> mirror.getElementValues()
                                                            .values()
                                                            .stream())
                                   .map(value -> String.valueOf(value.getValue()))
                                   .findFirst());
    }

    private static Unit print(Writer target, String source) {
        try (var writer = new PrintWriter(target)) {
            writer.print(source);
        }

        return Unit.unit();
    }
}
