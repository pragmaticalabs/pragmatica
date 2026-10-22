// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.pg.codegen.processor;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.pragmatica.aether.pg.codegen.NamingConvention;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.serialization.json.codegen.JsonCodecGenerator;

import static org.pragmatica.lang.utils.Causes.cause;


/// The generated JSON codecs of one persistence interface's `@Jsonb` types, in the holder `<Interface>JsonCodecs`
/// beside its factory. The codecs come from the same generator the slice processor uses for route types, with its
/// fixed defaults: a stored document is a durable contract, so no deploy-time JSON option changes its bytes.
final class JsonbCodecs {
    private static final String GENERATED_BY = "pg-codegen";

    private final JsonCodecGenerator generator;
    private final String holderName;
    private final Map<String, CodecField> fields = new LinkedHashMap<>();
    private final Set<String> fieldNames = new HashSet<>();

    /// A factory constant holding the codec of `typeName` (qualified) under `name`.
    record CodecField(String name, String typeName) {}

    JsonbCodecs(ProcessingEnvironment env, String packageName, String interfaceName) {
        this.generator = new JsonCodecGenerator(env, packageName);
        this.holderName = interfaceName + "JsonCodecs";
    }

    /// The factory constant holding the codec of `type`, or why `type` cannot be stored as a document.
    Result<String> field(TypeMirror type) {
        // A generic record is refused by the generator itself, with its reason.
        if (! (type instanceof DeclaredType declared) || !(declared.asElement() instanceof TypeElement element) || !isRecordOrEnum(element)) {
            return cause(type + " is not a record or enum type").result();
        }

        var qualified = element.getQualifiedName().toString();
        var known = fields.get(qualified);

        if (known != null) {
            return Result.success(known.name());
        }

        return generator.addRoot(type)
                        .fold(() -> Result.success(register(qualified, element)),
                              reason -> cause(reason).result());
    }

    List<CodecField> fields() {
        return List.copyOf(fields.values());
    }

    String holderName() {
        return holderName;
    }

    /// Writes the holder when any `@Jsonb` type was registered.
    Result<Unit> write(TypeElement owner) {
        return fields.isEmpty()
               ? Result.unitResult()
               : generator.write(holderName, GENERATED_BY, owner);
    }

    private String register(String qualified, TypeElement element) {
        var base = NamingConvention.toSnakeCase(element.getSimpleName().toString()).toUpperCase() + "_JSON";
        var name = base;

        for (var n = 2; !fieldNames.add(name); n++) {
            name = base + n;
        }

        fields.put(qualified, new CodecField(name, qualified));

        return name;
    }

    private static boolean isRecordOrEnum(TypeElement element) {
        return element.getKind() == ElementKind.RECORD || element.getKind() == ElementKind.ENUM;
    }
}
