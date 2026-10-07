// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.jbct.slice.generator;

import java.io.PrintWriter;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.ProcessingEnvironment;
import javax.tools.Diagnostic;
import javax.tools.StandardLocation;

import org.pragmatica.jbct.slice.model.ResolvedTopicConstant;
import org.pragmatica.jbct.slice.model.SliceModel;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;


/// Target-specific descriptors; construction, validation of source contracts, and routes are shared.
public final class TerraDescriptorGenerator {
    private final ProcessingEnvironment environment;
    private final Set<String> services = new LinkedHashSet<>();

    public TerraDescriptorGenerator(ProcessingEnvironment environment) {
        this.environment = environment;
    }

    public boolean validate(SliceModel model) {
        var options = environment.getOptions();

        if (Set.of("slice.groupId", "slice.artifactId", "slice.version")
               .stream()
               .anyMatch(key -> options.getOrDefault(key, "")
                                       .isBlank())) {
            return refuse(model, "Terra requires slice.groupId, slice.artifactId and slice.version");
        }

        if (model.hasTransitiveAnnotatedMethods() || model.hasConfigUpdateSubscriptions()) {
            return refuse(model,
                          "Terra does not yet support transitive reactive methods or live configuration callbacks");
        }

        for (var dependency : model.dependencies()) {
            if (dependency.isStreamResource() || dependency.resourceQualifier()
                                                           .map(q -> q.resourceType()
                                                                      .toString()
                                                                      .contains("DurableEntity"))
                                                           .or(false)) {
                return refuse(model, "Terra does not support streams or durable entities");
            }
        }

        for (var method : model.methods()) {
            for (var binding : method.reactive()) {
                if (!binding.category().equals("subscription") || method.parameters().size() != 1) {
                    return refuse(model,
                                  "Terra supports only single-payload ephemeral topic subscriptions: " + method.name());
                }
            }
        }

        return true;
    }

    private boolean refuse(SliceModel model, String message) {
        environment.getMessager().printMessage(Diagnostic.Kind.ERROR, message, model.factoryMethod());

        return false;
    }

    public Result<Unit> generate(SliceModel model, Map<String, ResolvedTopicConstant> topics) {
        return Result.lift(Causes::fromThrowable,
                           () -> {
                               write(model, topics);

                               return Unit.unit();
                           });
    }

    // Filer is a throwing compiler API; generate() lifts this I/O boundary into Result.
    @org.pragmatica.lang.Contract
    private void write(SliceModel model, Map<String, ResolvedTopicConstant> topics) throws java.io.IOException {
        var name = model.simpleName() + "TerraFactory";
        var qualified = model.packageName() + "." + name;

        try (var out = new PrintWriter(environment.getFiler().createSourceFile(qualified).openWriter())) {
            out.println("package " + model.packageName() + ";");
            out.println("public final class " + name
                       + " implements org.pragmatica.terra.TerraFactory<" + model.simpleName()
                       + "> {");
            var options = environment.getOptions();
            var suffix = FactoryClassGenerator.toKebabCase(model.simpleName());
            var artifact = options.get("slice.groupId")
                         + ":" + options.get("slice.artifactId")
                         + "-" + suffix
                         + ":" + options.get("slice.version");

            out.println("public String artifact() { return " + quote(artifact) + "; }");
            out.println("public Class<" + model.simpleName()
                       + "> sliceType() { return " + model.simpleName()
                       + ".class; }");
            var dependencies = model.dependencies()
                                    .stream()
                                    .filter(d -> !d.isResource() && !d.isPlainInterface())
                                    .map(d -> d.interfaceQualifiedName() + ".class")
                                    .collect(java.util.stream.Collectors.joining(", "));

            out.println("public java.util.List<Class<?>> dependencies() { return java.util.List.of(" + dependencies
                       + "); }");
            var factoryMethod = FactoryClassGenerator.lowercaseFirst(model.simpleName());

            out.println("public org.pragmatica.lang.Promise<" + model.simpleName()
                       + "> create(org.pragmatica.terra.TerraContext ctx) {");
            out.println("return " + model.simpleName() + "Factory." + factoryMethod + "(ctx); }");
            out.println("public org.pragmatica.lang.Result<org.pragmatica.lang.Unit> bind(" + model.simpleName()
                       + " slice, org.pragmatica.terra.TerraContext ctx) {");
            out.println("return org.pragmatica.lang.Result.allOf(java.util.List.of(");
            var bindings = new java.util.ArrayList<String>();

            for (var method : model.methods()) {
                for (var binding : method.reactive()) {
                    var section = binding.qualifier().configSection();
                    var constant = topics.get(section);
                    var args = constant == null
                               ? quote(section)
                                + ", " + environment.getTypeUtils().erasure(method.parameters().getFirst().type())
                                + ".class"
                               : constant.holderQualifiedName() + "." + constant.fieldName();

                    bindings.add("ctx.subscribe(" + args + ", event -> slice." + method.name() + "(event).mapToUnit())");
                }
            }

            if (bindings.isEmpty()) {
                bindings.add("org.pragmatica.lang.Result.unitResult()");
            }

            out.println(String.join(",\n", bindings));
            out.println(")).map(_ -> org.pragmatica.lang.Unit.unit()); }");
            out.println("}");
        }

        services.add(qualified);
    }

    public Result<Unit> finish() {
        if (services.isEmpty()) {
            return Result.unitResult();
        }

        return Result.lift(Causes::fromThrowable,
                           () -> {
                               try (var writer = environment.getFiler()
                                                            .createResource(StandardLocation.CLASS_OUTPUT,
                                                                            "",
                                                                            "META-INF/services/org.pragmatica.terra.TerraFactory")
                                                            .openWriter()) {
                               writer.write(String.join("\n", services) + "\n");
                           }

                               return Unit.unit();
                           });
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\")
                           .replace("\"", "\\\"")
                           .replace("\n", "\\n")
                           .replace("\r", "\\r") + "\"";
    }
}
