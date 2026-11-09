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
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.tools.JavaFileObject;
import java.io.PrintWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.serialization.json.NamingStrategy;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.utils.Causes.cause;


/// Generates reflection-free JSON codecs for a set of root types. Shared by the codec processor (one `@Json`
/// type per entry point, [#writeEntryPoint]) and the slice processor (HTTP route request and response types in
/// one holder, [#write]), so both emit identical codecs. A root the generator cannot handle is reported, not
/// generated; its users fall back.
///
/// JSON names are fixed here, at build time: a component's `@JsonName`, otherwise its name under the
/// [NamingStrategy] the generator was constructed with ([NamingStrategy#AS_DECLARED] by default). The codec processor
/// takes the strategy from `-Ajson.naming`; another processor passes its own to the constructors below.
public final class JsonCodecGenerator {
    /// The marker annotation every generated JSON class carries, naming what it was generated for.
    public static final String MARKER = "org.pragmatica.serialization.json.spi.GeneratedFor";
    private static final String RUNTIME_PACKAGE = "org.pragmatica.serialization.json.";
    private static final Set<String> GENERATED_FIELDS = Set.of("CODEC", "CODECS");

    private final Filer filer;
    private final Elements elements;
    private final JsonTypeAnalyzer analyzer;
    private final List<TypeMirror> roots = new ArrayList<>();
    private final List<JsonType.ListOf> containerRoots = new ArrayList<>();

    private static final String NESTED_REASON = "the record is also a component of another type the slice decodes or answers, and a codec shared with response decoding must not drop required fields";

    private static final String OTHER_USE_REASON = "another route of the slice decodes the record without binding that component from the path or query";

    private final Map<String, Set<String>> requestBound = new HashMap<>();
    private final String packageName;

    /// `packageName` is where the holder will be written; it decides which types and factories are reachable. Names
    /// are [NamingStrategy#AS_DECLARED].
    public JsonCodecGenerator(ProcessingEnvironment env, String packageName) {
        this(env, packageName, NamingStrategy.AS_DECLARED);
    }

    /// As [#JsonCodecGenerator(ProcessingEnvironment, String)], naming components with `naming`.
    public JsonCodecGenerator(ProcessingEnvironment env, String packageName, NamingStrategy naming) {
        this(env.getFiler(), env.getElementUtils(), env.getTypeUtils(), packageName, naming);
    }

    public JsonCodecGenerator(Filer filer, Elements elements, Types types, String packageName) {
        this(filer, elements, types, packageName, NamingStrategy.AS_DECLARED);
    }

    /// As [#JsonCodecGenerator(Filer, Elements, Types, String)], naming components with `naming`.
    public JsonCodecGenerator(Filer filer, Elements elements, Types types, String packageName, NamingStrategy naming) {
        this.filer = filer;
        this.elements = elements;
        this.packageName = packageName;
        this.analyzer = new JsonTypeAnalyzer(elements, types, packageName, naming);
    }

    /// Adds a root type; empty when supported, otherwise why it is not.
    public Option<String> addRoot(TypeMirror type) {
        return addRoot(type, false);
    }

    /// Adds a root that is decoded by declared type (a request body); empty when supported, otherwise why it is
    /// not. A `List` or `Set` body root also gets a codec of its own in the holder (see [#source]). Only a body
    /// gets one: an encode sees the runtime value, never the declared type, so a response root's would be dead code.
    public Option<String> addBodyRoot(TypeMirror type) {
        return addRoot(type, true, Set.of());
    }

    /// Adds a record body root whose components named in `requestBound` are filled from the request's path or query
    /// (#2957), never from the body: its codec does not require them and skips a body member of the same name
    /// unread, so the request value wins. The relaxation holds only while every use agrees: the names are
    /// intersected over all body uses of the record (a plain body use binds none), and dropped when any record or
    /// container root reaches the record as a nested value, whose decode must stay strict.
    public Option<String> addBodyRoot(TypeMirror type, Set<String> requestBound) {
        return addRoot(type, true, requestBound);
    }

    private Option<String> addRoot(TypeMirror type, boolean body) {
        return addRoot(type, body, Set.of());
    }

    private Option<String> addRoot(TypeMirror type, boolean body, Set<String> bound) {
        var analyzed = analyzer.analyzeRoot(type);

        if (analyzed.isPresent()) {
            roots.add(type);
            analyzed.filter(_ -> body)
                    .filter(JsonType.ListOf.class::isInstance)
                    .map(JsonType.ListOf.class::cast)
                    .onPresent(containerRoots::add);
            analyzed.filter(_ -> body).onPresent(root -> bindRequest(root, bound));

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
        return new JsonCodecEmitter(analyzer, requestBoundComponents()).emit(packageName,
                                                                             holderName,
                                                                             generatedBy,
                                                                             generatedFor,
                                                                             containerRoots);
    }

    /// The components of the record body root `type` that its route binds from the request (`bound`) but whose codec
    /// still requires them in the body, each with the reason: the relaxation fell off because another use of the
    /// record needs them (#2957). Asked after every root is added; empty for a relaxed record and for a type that
    /// is not a record.
    public Map<String, String> stillRequired(TypeMirror type, Set<String> bound) {
        return analyzer.analyzeRoot(type)
                       .filter(JsonType.RecordRef.class::isInstance)
                       .map(JsonType.RecordRef.class::cast)
                       .map(ref -> blockedComponents(qualifiedName(ref.element()),
                                                     bound))
                       .or(Map.of());
    }

    private Map<String, String> blockedComponents(String record, Set<String> bound) {
        var blocked = new TreeMap<String, String>();
        var relaxed = requestBoundComponents().getOrDefault(record, Set.of());
        var reason = reachedAsNested(record)
                     ? NESTED_REASON
                     : OTHER_USE_REASON;

        for (var component : analyzer.records().get(record).components()) {
            if (bound.contains(component.name()) && !JsonCodecEmitter.isOptionalMember(component.type()) && !relaxed.contains(component.name())) {
                blocked.put(component.name(), reason);
            }
        }

        return blocked;
    }

    // A record body root remembers the components its route binds from the request, intersected with earlier uses.
    private void bindRequest(JsonType root, Set<String> bound) {
        if (root instanceof JsonType.RecordRef ref) {
            requestBound.merge(qualifiedName(ref.element()), Set.copyOf(bound), JsonCodecGenerator::common);
        }
    }

    // The request-bound components per record, for the records no decode reaches as a nested value.
    private Map<String, Set<String>> requestBoundComponents() {
        return requestBound.entrySet()
                           .stream()
                           .filter(this::relaxable)
                           .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private boolean relaxable(Map.Entry<String, Set<String>> entry) {
        return ! reachedAsNested(entry.getKey());
    }

    private boolean reachedAsNested(String record) {
        return reachedByComponent(record) || containerRoots.stream()
                                                           .anyMatch(root -> reaches(root, record));
    }

    private boolean reachedByComponent(String record) {
        return analyzer.records()
                       .values()
                       .stream()
                       .flatMap(definition -> definition.components()
                                                        .stream())
                       .anyMatch(component -> reaches(component.type(),
                                                      record));
    }

    private static boolean reaches(JsonType type, String record) {
        return switch (type) {
            case JsonType.RecordRef ref -> qualifiedName(ref.element()).equals(record);
            case JsonType.OptionalOf optional -> reaches(optional.element(), record);
            case JsonType.PatchOf patch -> reaches(patch.element(), record);
            case JsonType.ListOf list -> reaches(list.element(), record);
            // A key record is a value object, whose one component a route cannot bind without binding the whole body.
            case JsonType.MapOf map -> reaches(map.value(), record);
            case JsonType.Scalar _, JsonType.EnumRef _ -> false;
        };
    }

    private static Set<String> common(Set<String> left, Set<String> right) {
        var both = new HashSet<>(left);

        both.retainAll(right);

        return Set.copyOf(both);
    }

    private static String qualifiedName(TypeElement element) {
        return element.getQualifiedName()
                      .toString();
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
    /// artifact on the class path) may be regenerated; a class generated for another source is refused. A class
    /// without the marker is refused too, and the message tells the two causes apart where it can: a compiled class
    /// shaped like generated JSON codec code (a `CODEC` or `CODECS` field typed by the JSON runtime) is stale output
    /// of an older processor version, to be removed by cleaning the build; anything else may be a user type or stale
    /// output, and the message names both ways out.
    public static Result<Unit> ensureFree(Elements elements, String qualified, String owner) {
        return option(elements.getTypeElement(qualified)).flatMap(existing -> clash(elements, existing, qualified, owner))
                     .map(Cause::<Unit> result)
                     .or(Result::unitResult);
    }

    private static Option<Cause> clash(Elements elements, TypeElement existing, String qualified, String owner) {
        return generatedFor(existing).map(source -> otherSource(source, qualified, owner))
                           .or(() -> some(unmarked(elements, existing, qualified, owner)));
    }

    private static Cause unmarked(Elements elements, TypeElement existing, String qualified, String owner) {
        return isCompiled(elements, existing) && looksGenerated(existing)
               ? staleOutput(qualified, owner)
               : notGenerated(existing, qualified, owner);
    }

    // FER: an element whose file cannot be told is treated as not compiled, which gives the message naming both ways
    // out rather than the confident "clean the build".
    private static boolean isCompiled(Elements elements, TypeElement existing) {
        return Option.lift(() -> elements.getFileObjectOf(existing))
                     .filter(file -> file.getKind() == JavaFileObject.Kind.CLASS)
                     .isPresent();
    }

    private static boolean looksGenerated(TypeElement existing) {
        return ElementFilter.fieldsIn(existing.getEnclosedElements())
                            .stream()
                            .anyMatch(JsonCodecGenerator::isGeneratedField);
    }

    // A `CODEC` or `CODECS` field typed by the JSON runtime: what every generated entry point and holder declares.
    private static boolean isGeneratedField(VariableElement field) {
        return GENERATED_FIELDS.contains(field.getSimpleName().toString()) && field.asType()
                                                                                   .toString()
                                                                                   .contains(RUNTIME_PACKAGE);
    }

    private static Cause staleOutput(String qualified, String owner) {
        return cause("Cannot generate " + qualified
                    + " for " + owner
                    + ": a class " + qualified
                    + " already exists without the GeneratedFor marker and looks like JSON codec code generated by an"
                    + " older processor version: stale generated output. Clean the build (for example `mvn clean`)"
                    + " and compile again.");
    }

    private static Cause notGenerated(TypeElement existing, String qualified, String owner) {
        return cause("Cannot generate " + qualified
                    + " for " + owner
                    + ": the type " + existing.getQualifiedName()
                    + " already exists and is not marked as generated JSON codec code."
                    + " If it is stale output of an older build, clean the build (for example `mvn clean`);"
                    + " otherwise rename " + existing.getQualifiedName()
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
