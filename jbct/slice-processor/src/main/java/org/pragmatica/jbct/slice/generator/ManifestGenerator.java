// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.jbct.slice.generator;

import org.pragmatica.jbct.slice.model.DependencyModel;
import org.pragmatica.jbct.slice.model.MethodModel;
import org.pragmatica.jbct.slice.model.MethodModel.ReactiveMethodBinding;
import org.pragmatica.jbct.slice.model.ResourceQualifierModel;
import org.pragmatica.jbct.slice.model.SliceModel;
import org.pragmatica.jbct.slice.model.SliceModel.TransitiveMethod;
import org.pragmatica.jbct.slice.routing.RouteConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import javax.annotation.processing.Filer;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.tools.StandardLocation;
import java.io.OutputStreamWriter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

public class ManifestGenerator {
    static final int ENVELOPE_FORMAT_VERSION = 1003;

    private final Filer filer;
    private final DependencyVersionResolver versionResolver;
    private final Map<String, String> options;

    public ManifestGenerator(Filer filer, DependencyVersionResolver versionResolver, Map<String, String> options) {
        this.filer = filer;
        this.versionResolver = versionResolver;
        this.options = options;
    }

    private String getArtifactFromEnv() {
        var groupId = options.getOrDefault("slice.groupId", "unknown");
        var artifactId = options.getOrDefault("slice.artifactId", "unknown");
        return groupId + ":" + artifactId;
    }

    private String getSliceArtifact(String sliceName) {
        var groupId = options.getOrDefault("slice.groupId", "unknown");
        var artifactId = options.getOrDefault("slice.artifactId", "unknown");
        return groupId + ":" + artifactId + "-" + toKebabCase(sliceName);
    }

    /// Generate per-slice manifest with class listings for multi-artifact packaging.
    /// Written to META-INF/slice/{SliceName}.manifest
    public Result<Unit> generateSliceManifest(SliceModel model) {
        return generateSliceManifest(model, Option.none(), Option.none());
    }

    /// Generate per-slice manifest with optional Routes class.
    /// Written to META-INF/slice/{SliceName}.manifest
    public Result<Unit> generateSliceManifest(SliceModel model, Option<String> routesClass) {
        return generateSliceManifest(model, routesClass, Option.none());
    }

    /// Generate per-slice manifest with optional Routes class and route configuration.
    /// Written to META-INF/slice/{SliceName}.manifest
    public Result<Unit> generateSliceManifest(SliceModel model, Option<String> routesClass, Option<RouteConfig> routeConfig) {
        try{
            var props = new Properties();
            var sliceName = model.simpleName();
            // Set context for resolving local dependencies
            var groupId = options.getOrDefault("slice.groupId", "unknown");
            var artifactId = options.getOrDefault("slice.artifactId", "unknown");
            versionResolver.setSliceContext(model.packageName(), groupId, artifactId);
            // Slice identification
            props.setProperty("slice.name", sliceName);
            props.setProperty("slice.interface", model.qualifiedName());
            props.setProperty("slice.artifactSuffix", toKebabCase(sliceName));
            props.setProperty("slice.package", model.packageName());
            props.setProperty("slice.factory", model.packageName() + "." + sliceName + "Factory");
            // Implementation classes
            var implClasses = collectImplClasses(model, routesClass);
            props.setProperty("impl.classes", String.join(",", implClasses));
            // Request/Response types from methods
            var requestTypes = collectRequestTypes(model);
            var responseTypes = collectResponseTypes(model);
            props.setProperty("request.classes", String.join(",", requestTypes));
            props.setProperty("response.classes", String.join(",", responseTypes));
            // Artifact coordinates
            props.setProperty("base.artifact", getArtifactFromEnv());
            props.setProperty("slice.artifactId", getArtifactIdFromEnv() + "-" + toKebabCase(sliceName));
            // Dependencies for blueprint generation (exclude resource dependencies)
            var dependencies = model.dependencies()
                                    .stream()
                                    .filter(dep -> !dep.isResource())
                                    .toList();
            props.setProperty("dependencies.count",
                              String.valueOf(dependencies.size()));
            int index = 0;
            for (var dep : dependencies) {
                var prefix = "dependency." + index + ".";
                props.setProperty(prefix + "interface", dep.interfaceQualifiedName());
                var resolved = versionResolver.resolve(dep);
                props.setProperty(prefix + "artifact",
                                  resolved.sliceArtifact()
                                          .or(() -> ""));
                props.setProperty(prefix + "version",
                                  resolved.version()
                                          .or(() -> "UNRESOLVED"));
                index++;
            }
            // Resources (exclude publishers and stream resources — they get their own sections)
            var resources = model.dependencies()
                                 .stream()
                                 .filter(dep -> dep.isResource() && !dep.isPublisher() && !dep.isStreamResource())
                                 .toList();
            props.setProperty("resources.count", String.valueOf(resources.size()));
            for (int resIndex = 0; resIndex < resources.size(); resIndex++) {
                var resPrefix = "resource." + resIndex + ".";
                resources.get(resIndex)
                         .resourceQualifier()
                         .onPresent(rq -> writeResourceProperties(props, resPrefix, rq));
            }
            // HTTP routes
            routeConfig.onPresent(config -> writeRouteProperties(props, config));
            // Slice config file path (for blueprint generator to read)
            props.setProperty("config.file", "slices/" + sliceName + ".toml");
            // Reactive bindings (unified format for all reactive annotations)
            writeReactiveBindings(props, model);
            // Publisher message types (for serializer registration)
            var publishMessageTypes = model.dependencies()
                                           .stream()
                                           .filter(dep -> dep.isPublisher())
                                           .flatMap(dep -> dep.publisherMessageType().stream())
                                           .filter(name2 -> !isStandardType(name2))
                                           .distinct()
                                           .collect(Collectors.toList());
            if (!publishMessageTypes.isEmpty()) {
                props.setProperty("publish.message.classes", String.join(",", publishMessageTypes));
            }
            // Publisher topics (enriched - config section + message type)
            var publishers = model.dependencies()
                                  .stream()
                                  .filter(DependencyModel::isPublisher)
                                  .toList();
            props.setProperty("publish.topics.count", String.valueOf(publishers.size()));
            for (int pubIndex = 0; pubIndex < publishers.size(); pubIndex++) {
                var pubPrefix = "publish.topic." + pubIndex + ".";
                publishers.get(pubIndex)
                          .resourceQualifier()
                          .onPresent(rq -> props.setProperty(pubPrefix + "config", rq.configSection()));
                publishers.get(pubIndex)
                          .publisherMessageType()
                          .onPresent(mt -> props.setProperty(pubPrefix + "messageType", mt));
            }
            // Stream publisher metadata
            var streamPublishers = model.dependencies()
                                        .stream()
                                        .filter(DependencyModel::isStreamPublisher)
                                        .toList();
            props.setProperty("stream.publishers.count", String.valueOf(streamPublishers.size()));
            for (int spIndex = 0; spIndex < streamPublishers.size(); spIndex++) {
                var spPrefix = "stream.publisher." + spIndex + ".";
                var sp = streamPublishers.get(spIndex);
                sp.resourceQualifier()
                  .onPresent(rq -> props.setProperty(spPrefix + "config", rq.configSection()));
                sp.streamEventType()
                  .onPresent(et -> props.setProperty(spPrefix + "eventType", et));
            }
            // Stream access metadata
            var streamAccessDeps = model.dependencies()
                                        .stream()
                                        .filter(DependencyModel::isStreamAccess)
                                        .toList();
            props.setProperty("stream.access.count", String.valueOf(streamAccessDeps.size()));
            for (int saIndex = 0; saIndex < streamAccessDeps.size(); saIndex++) {
                var saPrefix = "stream.access." + saIndex + ".";
                var sa = streamAccessDeps.get(saIndex);
                sa.resourceQualifier()
                  .onPresent(rq -> props.setProperty(saPrefix + "config", rq.configSection()));
                sa.streamEventType()
                  .onPresent(et -> props.setProperty(saPrefix + "eventType", et));
            }
            // Stream event codec classes (union of all stream event types)
            var streamEventTypes = collectStreamEventTypes(model);
            if (!streamEventTypes.isEmpty()) {
                props.setProperty("stream.event.classes", String.join(",", streamEventTypes));
            }
            // Metadata
            props.setProperty("generated.timestamp",
                              Instant.now()
                                     .toString());
            props.setProperty("envelope.version", String.valueOf(ENVELOPE_FORMAT_VERSION));
            // Write to META-INF/slice/{SliceName}.manifest
            var resourcePath = "META-INF/slice/" + sliceName + ".manifest";
            var resource = filer.createResource(StandardLocation.CLASS_OUTPUT, "", resourcePath);
            try (var writer = new OutputStreamWriter(resource.openOutputStream())) {
                props.store(writer, "Slice manifest for " + sliceName + " - generated by slice-processor");
            }
            return Result.unitResult();
        } catch (Exception e) {
            return Causes.cause("Failed to generate slice manifest: " + e.getClass()
                                                                         .getSimpleName() + ": " + e.getMessage())
                         .result();
        }
    }

    private record ReactiveEntry(String category, String methodName, String config, Map<String, String> metadata) {}

    private void writeReactiveBindings(Properties props, SliceModel model) {
        var allReactive = new ArrayList<ReactiveEntry>();

        // Direct methods
        for (var method : model.methods()) {
            for (var binding : method.reactive()) {
                allReactive.add(new ReactiveEntry(binding.category(), method.name(),
                                                   binding.qualifier().configSection(),
                                                   extractMetadata(method, binding)));
            }
        }

        // Transitive methods
        for (var tm : model.transitiveReactiveMethods()) {
            for (var binding : tm.method().reactive()) {
                allReactive.add(new ReactiveEntry(binding.category(), tm.qualifiedMethodName(),
                                                   binding.qualifier().configSection(),
                                                   extractMetadata(tm.method(), binding)));
            }
        }

        // Write properties
        props.setProperty("reactive.count", String.valueOf(allReactive.size()));
        for (int i = 0; i < allReactive.size(); i++) {
            var entry = allReactive.get(i);
            var prefix = "reactive." + i + ".";
            props.setProperty(prefix + "category", entry.category());
            props.setProperty(prefix + "method", entry.methodName());
            props.setProperty(prefix + "config", entry.config());
            // Category-specific metadata
            entry.metadata().forEach((k, v) -> props.setProperty(prefix + k, v));
        }
    }

    private Map<String, String> extractMetadata(MethodModel method, ReactiveMethodBinding binding) {
        var metadata = new LinkedHashMap<String, String>();
        switch (binding.category()) {
            case "subscription" -> {
                if (method.hasSingleParam()) {
                    metadata.put("messageType", getQualifiedTypeName(method.parameters().getFirst().type()));
                }
            }
            case "stream" -> {
                method.streamConsumerEventType()
                      .onPresent(et -> metadata.put("eventType", et));
                metadata.put("batch", String.valueOf(method.isBatchStreamConsumer()));
            }
            case "config-update" -> {
                if (method.hasSingleParam()) {
                    metadata.put("paramType", getQualifiedTypeName(method.parameters().getFirst().type()));
                }
            }
            default -> {
                // scheduled, pg-notification, and custom categories have no extra metadata
            }
        }
        return metadata;
    }

    private List<String> collectImplClasses(SliceModel model, Option<String> routesClass) {
        var classes = new ArrayList<String>();
        // Original @Slice interface
        classes.add(model.qualifiedName());
        // Generated factory class
        classes.add(model.packageName() + "." + model.simpleName() + "Factory");
        // Factory inner classes (adapter record for createSlice)
        var adapterName = Character.toLowerCase(model.simpleName()
                                                     .charAt(0)) + model.simpleName()
                                                                        .substring(1) + "Slice";
        classes.add(model.packageName() + "." + model.simpleName() + "Factory$" + adapterName);
        // Proxy records for all dependencies
        for (var dep : model.dependencies()) {
            classes.add(model.packageName() + "." + model.simpleName() + "Factory$" + dep.localRecordName());
        }
        // For resource deps where the interface type differs from the resource type
        // (e.g., @PgSql persistence interfaces), include the interface and its generated factory
        for (var dep : model.dependencies()) {
            if (dep.isResource()) {
                dep.resourceQualifier().onPresent(qualifier -> {
                    var resourceTypeName = qualifier.resourceType().toString();
                    if (!resourceTypeName.equals(dep.interfaceQualifiedName())) {
                        classes.add(dep.interfaceQualifiedName());
                        classes.add(dep.interfaceQualifiedName() + "Factory");
                    }
                });
            }
        }
        // Add Routes class if generated
        routesClass.onPresent(classes::add);
        return classes;
    }

    private static void writeResourceProperties(Properties props, String prefix, ResourceQualifierModel rq) {
        props.setProperty(prefix + "type", rq.resourceTypeSimpleName());
        props.setProperty(prefix + "config", rq.configSection());
    }

    private void writeRouteProperties(Properties props, RouteConfig config) {
        var routeEntries = new ArrayList<>(config.routes().entrySet());
        props.setProperty("routes.count", String.valueOf(routeEntries.size()));
        for (int rtIndex = 0; rtIndex < routeEntries.size(); rtIndex++) {
            var rtPrefix = "route." + rtIndex + ".";
            var entry = routeEntries.get(rtIndex);
            props.setProperty(rtPrefix + "method", entry.getValue().method());
            props.setProperty(rtPrefix + "path", config.prefix().isEmpty()
                                                  ? entry.getValue().pathTemplate()
                                                  : config.prefix() + entry.getValue().pathTemplate());
            props.setProperty(rtPrefix + "handler", entry.getKey());
            props.setProperty(rtPrefix + "security", config.effectiveSecurity(entry.getKey()).toConfigString());
        }
    }

    private List<String> collectRequestTypes(SliceModel model) {
        return model.methods()
                    .stream()
                    .flatMap(m -> m.parameters().stream().map(MethodModel.MethodParameterInfo::type))
                    .map(this::getQualifiedTypeName)
                    .filter(name -> !isStandardType(name))
                    .distinct()
                    .collect(Collectors.toList());
    }

    private List<String> collectResponseTypes(SliceModel model) {
        return model.methods()
                    .stream()
                    .map(MethodModel::responseType)
                    .map(this::getQualifiedTypeName)
                    .filter(name -> !isStandardType(name))
                    .distinct()
                    .collect(Collectors.toList());
    }

    private String getQualifiedTypeName(TypeMirror type) {
        if (type instanceof DeclaredType dt) {
            var element = dt.asElement();
            return element.toString();
        }
        return type.toString();
    }

    private boolean isStandardType(String typeName) {
        return typeName.startsWith("java.lang.") || typeName.startsWith("java.util.") || typeName.equals("void") || typeName.equals("int") || typeName.equals("long") || typeName.equals("boolean") || typeName.equals("double") || typeName.equals("float");
    }

    /// Collect all distinct stream event types from publishers, subscribers, and access resources.
    private List<String> collectStreamEventTypes(SliceModel model) {
        var types = new ArrayList<String>();
        // From StreamPublisher and StreamAccess parameters
        model.dependencies()
             .stream()
             .filter(DependencyModel::isStreamResource)
             .flatMap(dep -> dep.streamEventType().stream())
             .forEach(types::add);
        // From stream subscription methods
        model.streamSubscriptionMethods()
             .stream()
             .flatMap(m -> m.streamConsumerEventType().stream())
             .forEach(types::add);
        return types.stream()
                    .filter(name -> !isStandardType(name))
                    .distinct()
                    .collect(Collectors.toList());
    }

    private String getArtifactIdFromEnv() {
        return options.getOrDefault("slice.artifactId", "unknown");
    }

    /// Convert PascalCase to kebab-case.
    /// Examples: OrderService -> order-service, PlaceOrder -> place-order
    private String toKebabCase(String pascalCase) {
        if (pascalCase == null || pascalCase.isEmpty()) {
            return pascalCase;
        }
        var result = new StringBuilder();
        for (int i = 0; i < pascalCase.length(); i++) {
            char c = pascalCase.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    result.append('-');
                }
                result.append(Character.toLowerCase(c));
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }
}
