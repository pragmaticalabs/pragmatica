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

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.pragmatica.lang.Option;


/// Classifies Java types into [JsonType]s and collects the record and enum definitions a codec needs.
/// A type the generated codecs cannot handle (an interface, a type variable, a nested generic record, an
/// unsupported JDK type) is reported with a reason, and so is every type that contains one; callers then
/// leave that root to their fallback codec instead of failing the build.
final class JsonTypeAnalyzer {
    private static final String OPTION = "org.pragmatica.lang.Option";
    private static final String RESULT = "org.pragmatica.lang.Result";
    // Generated readers track seen and required fields as bits of one long: field i is `1L << i`, so bits
    // 0..63 exist and a 65th component would wrap onto bit 0.
    static final int MAX_COMPONENTS = Long.SIZE;

    // Packages whose annotations Jackson honours; a generated codec would silently ignore them.
    private static final List<String> JACKSON_ANNOTATION_PACKAGES = List.of("com.fasterxml.jackson.annotation.",
                                                                            "com.fasterxml.jackson.databind.annotation.",
                                                                            "tools.jackson.");

    private final Elements elements;
    private final Types types;
    private final Map<String, JsonType.RecordDefinition> records = new LinkedHashMap<>();
    private final Map<String, JsonType.EnumDefinition> enums = new LinkedHashMap<>();
    private final Map<String, String> unsupported = new LinkedHashMap<>();
    private final Set<String> inProgress = new HashSet<>();
    private final String holderPackage;

    /// `holderPackage` is where the generated codecs will live: types and factories there need only be
    /// non-private; anywhere else they must be public.
    JsonTypeAnalyzer(Elements elements, Types types, String holderPackage) {
        this.elements = elements;
        this.types = types;
        this.holderPackage = holderPackage;
    }

    Map<String, JsonType.RecordDefinition> records() {
        return records;
    }

    Map<String, JsonType.EnumDefinition> enums() {
        return enums;
    }

    /// A root's model, or empty when unsupported ([#reason] then says why). A refused root leaves no trace:
    /// every record and enum definition its analysis added is removed again, so no definition can refer to a
    /// record that never got one.
    Option<JsonType> analyzeRoot(TypeMirror type) {
        var knownRecords = Set.copyOf(records.keySet());
        var knownEnums = Set.copyOf(enums.keySet());

        return analyze(type).onEmpty(() -> rollback(knownRecords, knownEnums));
    }

    private void rollback(Set<String> knownRecords, Set<String> knownEnums) {
        records.keySet().retainAll(knownRecords);
        enums.keySet().retainAll(knownEnums);
    }

    /// The type's model, or empty when unsupported ([#reason] then says why).
    Option<JsonType> analyze(TypeMirror type) {
        return switch (type.getKind()) {
            case INT -> Option.some(new JsonType.Scalar(JsonType.ScalarKind.INT));
            case LONG -> Option.some(new JsonType.Scalar(JsonType.ScalarKind.LONG));
            case DOUBLE -> Option.some(new JsonType.Scalar(JsonType.ScalarKind.DOUBLE));
            case BOOLEAN -> Option.some(new JsonType.Scalar(JsonType.ScalarKind.BOOLEAN));
            case DECLARED -> analyzeDeclared((DeclaredType) type);
            default -> fail(type.toString(), "unsupported type kind " + type.getKind());
        };
    }

    /// Why `type` was refused. A container refused for its argument names the argument and the argument's own
    /// reason, down to the innermost refusal: `java.util.List<Inner>: Inner: <why Inner was refused>`.
    String reason(TypeMirror type) {
        return argumentReason(type).map(reason -> type + ": " + reason)
                                   .orElse(() -> recordedReason(type))
                                   .or("unsupported type " + type);
    }

    // The first type argument with a known refusal, as `<argument>: <its reason>`.
    private Option<String> argumentReason(TypeMirror type) {
        return type instanceof DeclaredType declared
               ? Option.from(declared.getTypeArguments()
                                     .stream()
                                     .flatMap(argument -> refusedArgument(argument).stream())
                                     .findFirst())
               : Option.none();
    }

    private Option<String> refusedArgument(TypeMirror argument) {
        return knownReason(argument).map(reason -> argument + ": " + reason);
    }

    private Option<String> knownReason(TypeMirror type) {
        return argumentReason(type).orElse(() -> recordedReason(type));
    }

    private Option<String> recordedReason(TypeMirror type) {
        return Option.option(unsupported.get(erasedName(type)));
    }

    private Option<JsonType> analyzeDeclared(DeclaredType type) {
        var element = (TypeElement) type.asElement();
        var name = element.getQualifiedName().toString();
        var args = type.getTypeArguments();

        return switch (name) {
            case "java.lang.Integer" -> scalar(JsonType.ScalarKind.INT);
            case "java.lang.Long" -> scalar(JsonType.ScalarKind.LONG);
            case "java.lang.Double" -> scalar(JsonType.ScalarKind.DOUBLE);
            case "java.lang.Boolean" -> scalar(JsonType.ScalarKind.BOOLEAN);
            case "java.lang.String" -> scalar(JsonType.ScalarKind.STRING);
            case "java.math.BigDecimal" -> scalar(JsonType.ScalarKind.DECIMAL);
            case "java.time.Instant" -> scalar(JsonType.ScalarKind.INSTANT);
            case "java.util.UUID" -> scalar(JsonType.ScalarKind.UUID);
            case "java.time.LocalDate" -> scalar(JsonType.ScalarKind.LOCAL_DATE);
            case "java.util.Currency" -> scalar(JsonType.ScalarKind.CURRENCY);
            case OPTION -> single(args, name).flatMap(this::analyze).map(JsonType.OptionalOf::new);
            case "java.util.List" -> single(args, name).flatMap(this::analyze).map(element1 -> new JsonType.ListOf(element1,
                                                                                                                   false));
            case "java.util.Set" -> single(args, name).flatMap(this::analyze).map(element1 -> new JsonType.ListOf(element1,
                                                                                                                  true));
            case "java.util.Map" -> analyzeMap(args);
            default -> analyzeUserType(element);
        };
    }

    private Option<JsonType> analyzeMap(List<? extends TypeMirror> args) {
        if (args.size() != 2) {
            return fail("java.util.Map", "raw Map");
        }

        var keyType = args.get(0);

        return Option.all(analyze(keyType),
                          analyze(args.get(1)))
                     .flatMap((key, value) -> mapOf(key, value, keyType));
    }

    private Option<JsonType> mapOf(JsonType key, JsonType value, TypeMirror keyType) {
        return isKeyType(key)
               ? Option.some(new JsonType.MapOf(key, value))
               : fail("java.util.Map", "map key must be a String, an enum or a value object over a String: " + keyType);
    }

    private boolean isKeyType(JsonType key) {
        return switch (key) {
            case JsonType.Scalar _ -> isString(key);
            case JsonType.EnumRef _ -> true;
            case JsonType.RecordRef ref -> Option.option(records.get(ref.element().getQualifiedName().toString())).filter(JsonType.RecordDefinition::isValueObject).filter(JsonTypeAnalyzer::overString).isPresent();
            default -> false;
        };
    }

    // A value object has exactly one component, so "any" is "its".
    private static boolean overString(JsonType.RecordDefinition definition) {
        return definition.components()
                         .stream()
                         .map(JsonType.Component::type)
                         .anyMatch(JsonTypeAnalyzer::isString);
    }

    private static boolean isString(JsonType type) {
        return type instanceof JsonType.Scalar scalar && scalar.kind() == JsonType.ScalarKind.STRING;
    }

    private Option<JsonType> analyzeUserType(TypeElement element) {
        var name = element.getQualifiedName().toString();

        if (unsupported.containsKey(name)) {
            return Option.none();
        }

        if (!isAccessible(element)) {
            return fail(name, "not accessible from package '" + holderPackage + "' where its codec is generated");
        }

        if (element.getKind() != ElementKind.ENUM && element.getKind() != ElementKind.RECORD) {
            return fail(name,
                        "neither a record nor an enum (interfaces and sealed hierarchies are left to the fallback)");
        }

        var jackson = jacksonAnnotation(element);

        if (jackson.isPresent()) {
            return jackson.flatMap(reason -> fail(name, reason));
        }

        if (element.getKind() == ElementKind.ENUM) {
            enums.computeIfAbsent(name, _ -> enumDefinition(element));

            return Option.some(new JsonType.EnumRef(element));
        }

        if (!element.getTypeParameters().isEmpty()) {
            return fail(name, "generic record");
        }

        if (element.getRecordComponents().size() > MAX_COMPONENTS) {
            return fail(name,
                        element.getRecordComponents()
                               .size()
                       + " components; generated codecs support at most " + MAX_COMPONENTS);
        }

        if (records.containsKey(name) || inProgress.contains(name)) {
            return Option.some(new JsonType.RecordRef(element));
        }

        inProgress.add(name);
        var components = new ArrayList<JsonType.Component>();

        for (var component : element.getRecordComponents()) {
            var type = analyze(component.asType());

            if (type.isEmpty()) {
                inProgress.remove(name);

                return fail(name,
                            "component " + component.getSimpleName() + ": " + reason(component.asType()));
            }

            type.onPresent(present -> components.add(new JsonType.Component(component.getSimpleName().toString(),
                                                                            present)));
        }

        inProgress.remove(name);
        var definition = new JsonType.RecordDefinition(element, List.copyOf(components), findFactory(element));
        // The value-object rule must not depend on where the holder lives (Jackson cannot know it): a value
        // object whose factory this holder cannot call is left to the fallback, never re-shaped into an object.
        if (definition.isValueObject() && !isAccessible(definition.factory())) {
            return fail(name,
                        "value object factory " + definition.factoryName()
                       + " is not accessible from package '" + holderPackage
                       + "' where its codec is generated");
        }

        records.put(name,
                    definition.isValueObject() || isAccessible(definition.factory())
                    ? definition
                    : new JsonType.RecordDefinition(element, definition.components(), Option.none()));

        return Option.some(new JsonType.RecordRef(element));
    }

    private boolean isAccessible(Option<ExecutableElement> factory) {
        return factory.map(method -> isAccessible(method.getModifiers(),
                                                  samePackage((TypeElement) method.getEnclosingElement())))
                      .or(true);
    }

    private boolean samePackage(TypeElement element) {
        return elements.getPackageOf(element)
                       .getQualifiedName()
                       .contentEquals(holderPackage);
    }

    // Jackson honours its annotations on the type, on every member and parameter, on every supertype, and
    // through meta-annotations; the first one found, with where it sits.
    private Option<String> jacksonAnnotation(TypeElement element) {
        return Option.from(supertypesAndSelf(element).stream()
                                            .flatMap(JsonTypeAnalyzer::annotationSites)
                                            .flatMap(site -> jacksonAnnotations(site).map(annotation -> "Jackson annotation @" + annotation
                                                                                                       + " on " + describe(site)))
                                            .findFirst());
    }

    private List<TypeElement> supertypesAndSelf(TypeElement element) {
        var found = new LinkedHashMap<String, TypeElement>();
        var pending = new ArrayList<TypeElement>(List.of(element));

        while (!pending.isEmpty()) {
            var current = pending.removeFirst();

            if (found.putIfAbsent(current.getQualifiedName().toString(),
                                  current) == null) {
                types.directSupertypes(current.asType())
                     .stream()
                     .filter(DeclaredType.class::isInstance)
                     .map(supertype -> (TypeElement)((DeclaredType) supertype).asElement())
                     .forEach(pending::add);
            }
        }

        return List.copyOf(found.values());
    }

    // The type and everything Jackson reads on it: fields, record components, methods (accessors included),
    // constructors, enum constants and every parameter. Nested types are types of their own.
    private static Stream<Element> annotationSites(TypeElement type) {
        var members = type.getEnclosedElements()
                          .stream()
                          .filter(member -> !(member instanceof TypeElement))
                          .map(Element.class::cast)
                          .toList();
        var parameters = members.stream()
                                .filter(ExecutableElement.class::isInstance)
                                .map(ExecutableElement.class::cast)
                                .flatMap(member -> member.getParameters()
                                                         .stream())
                                .map(Element.class::cast);
        var components = type.getRecordComponents().stream().map(Element.class::cast);

        return Stream.concat(Stream.concat(Stream.of(type), members.stream()),
                             Stream.concat(components, parameters));
    }

    private static Stream<String> jacksonAnnotations(Element site) {
        return site.getAnnotationMirrors()
                   .stream()
                   .map(mirror -> (TypeElement) mirror.getAnnotationType()
                                                      .asElement())
                   .filter(annotation -> isJackson(annotation) || annotation.getAnnotationMirrors()
                                                                            .stream()
                                                                            .map(meta -> (TypeElement) meta.getAnnotationType()
                                                                                                           .asElement())
                                                                            .anyMatch(JsonTypeAnalyzer::isJackson))
                   .map(annotation -> annotation.getQualifiedName()
                                                .toString());
    }

    private static boolean isJackson(TypeElement annotation) {
        var name = annotation.getQualifiedName().toString();

        return JACKSON_ANNOTATION_PACKAGES.stream().anyMatch(name::startsWith);
    }

    private static String describe(Element site) {
        return switch (site) {
            case TypeElement type -> type.getQualifiedName().toString();
            case VariableElement parameter when parameter.getKind() == ElementKind.PARAMETER -> "parameter " + parameter.getSimpleName() + " of " + describe(parameter.getEnclosingElement());
            default -> describe(site.getEnclosingElement()) + "." + site.getSimpleName();
        };
    }

    private JsonType.EnumDefinition enumDefinition(TypeElement element) {
        var constants = element.getEnclosedElements()
                               .stream()
                               .filter(enclosed -> enclosed.getKind() == ElementKind.ENUM_CONSTANT)
                               .map(enclosed -> enclosed.getSimpleName()
                                                        .toString())
                               .toList();

        return new JsonType.EnumDefinition(element, constants, constants.indexOf("UNKNOWN"));
    }

    // The JBCT factory: static, not private, named after the type in lower camel case, taking exactly the
    // record's components in order, returning Result<ThisRecord>. Whether this holder can call it is decided
    // by the caller, so the shape alone decides what is a value object.
    private Option<ExecutableElement> findFactory(TypeElement element) {
        var simple = element.getSimpleName().toString();
        var factoryName = Character.toLowerCase(simple.charAt(0)) + simple.substring(1);
        var components = element.getRecordComponents();

        for (var enclosed : element.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.METHOD || !enclosed.getSimpleName().contentEquals(factoryName)) {
                continue;
            }

            var method = (ExecutableElement) enclosed;

            if (!method.getModifiers().contains(Modifier.STATIC) || method.getModifiers().contains(Modifier.PRIVATE) || method.getParameters()
                                                                                                                              .size() != components.size() || !returnsResultOf(method,
                                                                                                                                                                               element)) {
                continue;
            }

            var matches = true;

            for (int i = 0; i < components.size() && matches; i++) {
                matches = types.isSameType(method.getParameters().get(i).asType(),
                                           components.get(i).asType());
            }

            if (matches) {
                return Option.some(method);
            }
        }

        return Option.none();
    }

    private boolean returnsResultOf(ExecutableElement method, TypeElement element) {
        if (! (method.getReturnType() instanceof DeclaredType declared)) {
            return false;
        }

        var raw = (TypeElement) declared.asElement();

        return raw.getQualifiedName()
                  .contentEquals(RESULT)
               && declared.getTypeArguments()
                          .size() == 1
               && types.isSameType(declared.getTypeArguments().getFirst(),
                                   element.asType());
    }

    private boolean isAccessible(TypeElement element) {
        var samePackage = samePackage(element);

        for (Element current = element; current instanceof TypeElement type; current = current.getEnclosingElement()) {
            if (!isAccessible(type.getModifiers(), samePackage) && !(type.getNestingKind() == NestingKind.MEMBER && type.getEnclosingElement()
                                                                                                                        .getKind()
                                                                                                                        .isInterface())) {
                return false;
            }
        }

        return true;
    }

    private static boolean isAccessible(Set<Modifier> modifiers, boolean samePackage) {
        return modifiers.contains(Modifier.PUBLIC) || (samePackage && !modifiers.contains(Modifier.PRIVATE));
    }

    private Option<TypeMirror> single(List<? extends TypeMirror> args, String owner) {
        if (args.size() != 1 || args.getFirst().getKind() == TypeKind.WILDCARD || args.getFirst().getKind() == TypeKind.TYPEVAR) {
            return fail(owner, "needs one concrete type argument");
        }

        return Option.some((TypeMirror) args.getFirst());
    }

    private static Option<JsonType> scalar(JsonType.ScalarKind kind) {
        return Option.some(new JsonType.Scalar(kind));
    }

    private <T> Option<T> fail(String name, String reason) {
        unsupported.putIfAbsent(name, reason);

        return Option.none();
    }

    private String erasedName(TypeMirror type) {
        return type instanceof DeclaredType declared
               ? ((TypeElement) declared.asElement()).getQualifiedName()
                                                     .toString()
               : type.toString();
    }
}
