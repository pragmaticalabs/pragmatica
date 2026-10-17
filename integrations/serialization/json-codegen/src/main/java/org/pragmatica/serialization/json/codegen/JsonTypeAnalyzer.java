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
import java.util.Optional;
import java.util.Set;

/// Classifies Java types into [JsonType]s and collects the record and enum definitions a codec needs.
/// A type the generated codecs cannot handle (an interface, a type variable, a nested generic record, an
/// unsupported JDK type) is reported with a reason, and so is every type that contains one; callers then
/// leave that root to their fallback codec instead of failing the build.
final class JsonTypeAnalyzer {
    private static final String OPTION = "org.pragmatica.lang.Option";
    private static final String RESULT = "org.pragmatica.lang.Result";

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

    /// The type's model, or empty when unsupported ([#reason] then says why).
    Optional<JsonType> analyze(TypeMirror type) {
        return switch (type.getKind()) {
            case INT -> Optional.of(new JsonType.Scalar(JsonType.ScalarKind.INT));
            case LONG -> Optional.of(new JsonType.Scalar(JsonType.ScalarKind.LONG));
            case DOUBLE -> Optional.of(new JsonType.Scalar(JsonType.ScalarKind.DOUBLE));
            case BOOLEAN -> Optional.of(new JsonType.Scalar(JsonType.ScalarKind.BOOLEAN));
            case DECLARED -> analyzeDeclared((DeclaredType) type);
            default -> fail(type.toString(), "unsupported type kind " + type.getKind());
        };
    }

    String reason(TypeMirror type) {
        return unsupported.getOrDefault(erasedName(type), "unsupported type " + type);
    }

    private Optional<JsonType> analyzeDeclared(DeclaredType type) {
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
            case OPTION -> single(args, name).flatMap(this::analyze)
                                              .map(JsonType.OptionalOf::new);
            case "java.util.List" -> single(args, name).flatMap(this::analyze)
                                                         .map(element1 -> new JsonType.ListOf(element1, false));
            case "java.util.Set" -> single(args, name).flatMap(this::analyze)
                                                        .map(element1 -> new JsonType.ListOf(element1, true));
            case "java.util.Map" -> analyzeMap(args);
            default -> analyzeUserType(element);
        };
    }

    private Optional<JsonType> analyzeMap(List<? extends TypeMirror> args) {
        if (args.size() != 2) {
            return fail("java.util.Map", "raw Map");
        }
        var key = analyze(args.get(0));
        var value = analyze(args.get(1));

        if (key.isEmpty() || value.isEmpty()) {
            return Optional.empty();
        }
        if (!isKeyType(key.get())) {
            return fail("java.util.Map", "map key must be a String, an enum or a value object over a String: " + args.get(0));
        }
        return Optional.of(new JsonType.MapOf(key.get(), value.get()));
    }

    private boolean isKeyType(JsonType key) {
        return switch (key) {
            case JsonType.Scalar scalar -> scalar.kind() == JsonType.ScalarKind.STRING;
            case JsonType.EnumRef _ -> true;
            case JsonType.RecordRef ref -> {
                var definition = records.get(ref.element().getQualifiedName().toString());
                yield definition != null && definition.isValueObject()
                      && ((JsonType.Scalar) definition.components().getFirst().type()).kind() == JsonType.ScalarKind.STRING;
            }
            default -> false;
        };
    }

    private Optional<JsonType> analyzeUserType(TypeElement element) {
        var name = element.getQualifiedName().toString();

        if (unsupported.containsKey(name)) {
            return Optional.empty();
        }
        if (!isAccessible(element)) {
            return fail(name, "not accessible from package '" + holderPackage + "' where its codec is generated");
        }
        if (element.getKind() == ElementKind.ENUM) {
            enums.computeIfAbsent(name, _ -> enumDefinition(element));
            return Optional.of(new JsonType.EnumRef(element));
        }
        if (element.getKind() != ElementKind.RECORD) {
            return fail(name, "neither a record nor an enum (interfaces and sealed hierarchies are left to the fallback)");
        }
        if (!element.getTypeParameters().isEmpty()) {
            return fail(name, "generic record");
        }
        if (records.containsKey(name) || inProgress.contains(name)) {
            return Optional.of(new JsonType.RecordRef(element));
        }
        inProgress.add(name);
        var components = new ArrayList<JsonType.Component>();

        for (var component : element.getRecordComponents()) {
            var type = analyze(component.asType());

            if (type.isEmpty()) {
                inProgress.remove(name);
                return fail(name, "component " + component.getSimpleName() + ": " + reason(component.asType()));
            }
            components.add(new JsonType.Component(component.getSimpleName().toString(), type.get()));
        }
        inProgress.remove(name);
        records.put(name, new JsonType.RecordDefinition(element, List.copyOf(components), findFactory(element)));
        return Optional.of(new JsonType.RecordRef(element));
    }

    private JsonType.EnumDefinition enumDefinition(TypeElement element) {
        var constants = element.getEnclosedElements()
                               .stream()
                               .filter(enclosed -> enclosed.getKind() == ElementKind.ENUM_CONSTANT)
                               .map(enclosed -> enclosed.getSimpleName().toString())
                               .toList();

        return new JsonType.EnumDefinition(element, constants, constants.indexOf("UNKNOWN"));
    }

    // The JBCT factory: static, public, named after the type in lower camel case, taking exactly the
    // record's components in order, returning Result<ThisRecord>.
    private ExecutableElement findFactory(TypeElement element) {
        var simple = element.getSimpleName().toString();
        var factoryName = Character.toLowerCase(simple.charAt(0)) + simple.substring(1);
        var components = element.getRecordComponents();

        for (var enclosed : element.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.METHOD || !enclosed.getSimpleName().contentEquals(factoryName)) {
                continue;
            }
            var method = (ExecutableElement) enclosed;

            var samePackage = elements.getPackageOf(element).getQualifiedName().contentEquals(holderPackage);

            if (!method.getModifiers().contains(Modifier.STATIC) || !isAccessible(method.getModifiers(), samePackage)
                || method.getParameters().size() != components.size() || !returnsResultOf(method, element)) {
                continue;
            }
            var matches = true;

            for (int i = 0; i < components.size() && matches; i++) {
                matches = types.isSameType(method.getParameters().get(i).asType(), components.get(i).asType());
            }
            if (matches) {
                return method;
            }
        }
        return null;
    }

    private boolean returnsResultOf(ExecutableElement method, TypeElement element) {
        if (!(method.getReturnType() instanceof DeclaredType declared)) {
            return false;
        }
        var raw = (TypeElement) declared.asElement();

        return raw.getQualifiedName().contentEquals(RESULT) && declared.getTypeArguments().size() == 1
               && types.isSameType(declared.getTypeArguments().getFirst(), element.asType());
    }

    private boolean isAccessible(TypeElement element) {
        var samePackage = elements.getPackageOf(element).getQualifiedName().contentEquals(holderPackage);

        for (Element current = element; current instanceof TypeElement type; current = current.getEnclosingElement()) {
            if (!isAccessible(type.getModifiers(), samePackage)
                && !(type.getNestingKind() == NestingKind.MEMBER && type.getEnclosingElement().getKind().isInterface())) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAccessible(Set<Modifier> modifiers, boolean samePackage) {
        return modifiers.contains(Modifier.PUBLIC) || (samePackage && !modifiers.contains(Modifier.PRIVATE));
    }

    private Optional<TypeMirror> single(List<? extends TypeMirror> args, String owner) {
        if (args.size() != 1 || args.getFirst().getKind() == TypeKind.WILDCARD || args.getFirst().getKind() == TypeKind.TYPEVAR) {
            return fail(owner, "needs one concrete type argument");
        }
        return Optional.of((TypeMirror) args.getFirst());
    }

    private static Optional<JsonType> scalar(JsonType.ScalarKind kind) {
        return Optional.of(new JsonType.Scalar(kind));
    }

    private <T> Optional<T> fail(String name, String reason) {
        unsupported.putIfAbsent(name, reason);
        return Optional.empty();
    }

    private String erasedName(TypeMirror type) {
        return type instanceof DeclaredType declared
               ? ((TypeElement) declared.asElement()).getQualifiedName().toString()
               : type.toString();
    }
}
