// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.jbct.slice.generator;

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

import org.pragmatica.jbct.slice.model.DependencyModel;
import org.pragmatica.jbct.slice.model.MethodModel;
import org.pragmatica.jbct.slice.model.MethodModel.ReactiveMethodBinding;
import org.pragmatica.jbct.slice.model.ResolvedTopicConstant;
import org.pragmatica.jbct.slice.model.ResourceQualifierModel;
import org.pragmatica.jbct.slice.model.SliceModel;
import org.pragmatica.jbct.slice.model.SliceModel.TransitiveMethod;
import org.pragmatica.jbct.slice.routing.RouteConfig;
import org.pragmatica.jbct.slice.routing.RouteDsl;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;


public class ManifestGenerator {
    /// Envelope bumped 1006 -> 1007: value-object HTTP path/query segments now bind through the VO's
    /// `ValueMapping` (`static ValueMapping<Self, P> valueMapping()`), so generated `*Routes` compose
    /// the framework `String -> P` parser with the VO's `lift` (`PathParameter.aXxx().mapped(...)` /
    /// `QueryParameter.aXxx(name).mapped(...)`) and a lift failure yields a typed 400 (#397).
    static final int ENVELOPE_FORMAT_VERSION = 1007;

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
        return generateSliceManifest(model, Option.none(), Option.none(), Map.of());
    }

    /// Generate per-slice manifest with optional Routes class.
    /// Written to META-INF/slice/{SliceName}.manifest
    public Result<Unit> generateSliceManifest(SliceModel model, Option<String> routesClass) {
        return generateSliceManifest(model, routesClass, Option.none(), Map.of());
    }

    /// Generate per-slice manifest with optional Routes class and route configuration.
    /// Written to META-INF/slice/{SliceName}.manifest
    public Result<Unit> generateSliceManifest(SliceModel model,
                                              Option<String> routesClass,
                                              Option<RouteConfig> routeConfig) {
        return generateSliceManifest(model, routesClass, routeConfig, Map.of());
    }

    /// Generate per-slice manifest, threading the resolved single-source `Topic<T>` constants (#396)
    /// so typed pub/sub blocks carry a `topicName` derived from the constant rather than a
    /// resources.toml section.
    public Result<Unit> generateSliceManifest(SliceModel model,
                                              Option<String> routesClass,
                                              Option<RouteConfig> routeConfig,
                                              Map<String, ResolvedTopicConstant> topicBindings) {
        try {
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
            var dependencies = model.dependencies().stream().filter(dep -> !dep.isResource()).toList();

            props.setProperty("dependencies.count",
                              String.valueOf(dependencies.size()));
            int index = 0;

            for (var dep : dependencies) {
                var prefix = "dependency." + index + ".";

                props.setProperty(prefix + "interface", dep.interfaceQualifiedName());
                var resolved = versionResolver.resolve(dep);

                props.setProperty(prefix + "artifact",
                                  resolved.sliceArtifact().or(() -> ""));
                props.setProperty(prefix + "version",
                                  resolved.version().or(() -> "UNRESOLVED"));
                index++;
            }
            // Resources (exclude publishers and stream resources — they get their own sections)
            var resources = model.dependencies()
                                 .stream()
                                 .filter(dep -> dep.isResource()
                                                && !dep.isPublisher()
                                                && !dep.isStreamResource())
                                 .toList();

            props.setProperty("resources.count",
                              String.valueOf(resources.size()));
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
            writeReactiveBindings(props, model, topicBindings);
            // Publisher message types (for serializer registration)
            var publishMessageTypes = model.dependencies()
                                           .stream()
                                           .filter(dep -> dep.isPublisher())
                                           .flatMap(dep -> dep.publisherMessageType()
                                                              .stream())
                                           .filter(name2 -> !isStandardType(name2))
                                           .distinct()
                                           .collect(Collectors.toList());

            if (!publishMessageTypes.isEmpty()) {
                props.setProperty("publish.message.classes", String.join(",", publishMessageTypes));
            }
            // Publisher topics (enriched - config section + message type)
            var publishers = model.dependencies().stream().filter(DependencyModel::isPublisher).toList();

            props.setProperty("publish.topics.count",
                              String.valueOf(publishers.size()));
            for (int pubIndex = 0; pubIndex < publishers.size(); pubIndex++) {
                var pubPrefix = "publish.topic." + pubIndex + ".";

                publishers.get(pubIndex)
                          .resourceQualifier()
                          .onPresent(rq -> writeTopicConfig(props,
                                                            pubPrefix,
                                                            rq.configSection(),
                                                            topicBindings));
                publishers.get(pubIndex)
                          .publisherMessageType()
                          .onPresent(mt -> props.setProperty(pubPrefix + "messageType", mt));
            }
            // Stream publisher metadata
            var streamPublishers = model.dependencies().stream().filter(DependencyModel::isStreamPublisher).toList();

            props.setProperty("stream.publishers.count",
                              String.valueOf(streamPublishers.size()));
            for (int spIndex = 0; spIndex < streamPublishers.size(); spIndex++) {
                var spPrefix = "stream.publisher." + spIndex + ".";
                var sp = streamPublishers.get(spIndex);

                sp.resourceQualifier().onPresent(rq -> props.setProperty(spPrefix + "config", rq.configSection()));
                sp.streamEventType().onPresent(et -> props.setProperty(spPrefix + "eventType", et));
            }
            // Stream access metadata
            var streamAccessDeps = model.dependencies().stream().filter(DependencyModel::isStreamAccess).toList();

            props.setProperty("stream.access.count",
                              String.valueOf(streamAccessDeps.size()));
            for (int saIndex = 0; saIndex < streamAccessDeps.size(); saIndex++) {
                var saPrefix = "stream.access." + saIndex + ".";
                var sa = streamAccessDeps.get(saIndex);

                sa.resourceQualifier().onPresent(rq -> props.setProperty(saPrefix + "config", rq.configSection()));
                sa.streamEventType().onPresent(et -> props.setProperty(saPrefix + "eventType", et));
            }
            // Stream event codec classes (union of all stream event types)
            var streamEventTypes = collectStreamEventTypes(model);

            if (!streamEventTypes.isEmpty()) {
                props.setProperty("stream.event.classes", String.join(",", streamEventTypes));
            }
            // Metadata
            props.setProperty("generated.timestamp",
                              Instant.now().toString());
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
                                                                         .getSimpleName()
                               + ": " + e.getMessage()).result();
        }
    }

    private record ReactiveEntry(String category, String methodName, String config, Map<String, String> metadata) {}

    private void writeReactiveBindings(Properties props,
                                       SliceModel model,
                                       Map<String, ResolvedTopicConstant> topicBindings) {
        var allReactive = new ArrayList<ReactiveEntry>();
        // Direct methods
        for (var method : model.methods()) {
            for (var binding : method.reactive()) {
                allReactive.add(new ReactiveEntry(binding.category(),
                                                  method.name(),
                                                  binding.qualifier().configSection(),
                                                  extractMetadata(method, binding)));
            }
        }
        // Transitive methods
        for (var tm : model.transitiveReactiveMethods()) {
            for (var binding : tm.method().reactive()) {
                allReactive.add(new ReactiveEntry(binding.category(),
                                                  tm.qualifiedMethodName(),
                                                  binding.qualifier().configSection(),
                                                  extractMetadata(tm.method(), binding)));
            }
        }
        // Write properties
        props.setProperty("reactive.count",
                          String.valueOf(allReactive.size()));
        for (int i = 0; i < allReactive.size(); i++) {
            var entry = allReactive.get(i);
            var prefix = "reactive." + i + ".";

            props.setProperty(prefix + "category", entry.category());
            props.setProperty(prefix + "method", entry.methodName());
            writeTopicConfig(props, prefix, entry.config(), topicBindings);
            // Category-specific metadata
            entry.metadata().forEach((k, v) -> props.setProperty(prefix + k, v));
        }
    }

    /// Write the topic `config` and, for a typed-topic reference resolved to a single-source
    /// `Topic<T>` constant (#396), the derived `topicName`. For a typed topic the `config` written is
    /// the resolved topic name (so pub/sub orphan-matching and topic-address grammar validation
    /// operate on the name); legacy lowercase section configs are written verbatim.
    private static void writeTopicConfig(Properties props,
                                         String prefix,
                                         String configSection,
                                         Map<String, ResolvedTopicConstant> topicBindings) {
        var topicName = Option.option(topicBindings.get(configSection)).flatMap(ResolvedTopicConstant::topicName);

        props.setProperty(prefix + "config", topicName.or(configSection));
        topicName.onPresent(name -> props.setProperty(prefix + "topicName", name));
    }

    private Map<String, String> extractMetadata(MethodModel method, ReactiveMethodBinding binding) {
        var metadata = new LinkedHashMap<String, String>();

        switch (binding.category()) {
            case "subscription" -> {
                if (method.hasSingleParam()) {
                    metadata.put("messageType",
                                 getQualifiedTypeName(method.parameters().getFirst().type()));
                }
            }
            case "stream" -> {
                method.streamConsumerEventType().onPresent(et -> metadata.put("eventType", et));
                metadata.put("batch",
                             String.valueOf(method.isBatchStreamConsumer()));
            }
            case "config-update" -> {
                if (method.hasSingleParam()) {
                    metadata.put("paramType",
                                 getQualifiedTypeName(method.parameters().getFirst().type()));
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
        var adapterName = Character.toLowerCase(model.simpleName().charAt(0)) + model.simpleName().substring(1)
                        + "Slice";

        classes.add(model.packageName() + "." + model.simpleName() + "Factory$" + adapterName);
        // Proxy records for all dependencies
        for (var dep : model.dependencies()) {
            classes.add(model.packageName() + "." + model.simpleName() + "Factory$" + dep.localRecordName());
        }
        // For resource deps where the interface type differs from the resource type
        // (e.g., @PgSql persistence interfaces), include the interface and its generated factory
        for (var dep : model.dependencies()) {
            if (dep.isResource()) {
                dep.resourceQualifier()
                   .onPresent(qualifier -> {
                                  var resourceTypeName = qualifier.resourceType()
                                                                  .toString();

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

        props.setProperty("routes.count",
                          String.valueOf(routeEntries.size()));
        for (int rtIndex = 0; rtIndex < routeEntries.size(); rtIndex++) {
            var rtPrefix = "route." + rtIndex + ".";
            var entry = routeEntries.get(rtIndex);
            var version = config.routeVersion(entry.getKey());

            props.setProperty(rtPrefix + "method",
                              entry.getValue().method());
            props.setProperty(rtPrefix + "path",
                              manifestRoutePath(config, entry.getKey(), entry.getValue()));
            props.setProperty(rtPrefix + "handler", entry.getKey());
            props.setProperty(rtPrefix + "version", String.valueOf(version));
            props.setProperty(rtPrefix + "security",
                              config.effectiveSecurity(entry.getKey()).toConfigString());
        }

        writeVersionProperties(props, config);
    }

    /// The route's mounted path for the manifest (consumed by the topology/visualization layer).
    /// Versioned routes (#198 §6.4) compose `{apiPrefix}/v{N}/{path}` — the same path mounted at
    /// registration time — so the manifest path stays identical to the pre-refactor baked form;
    /// unversioned routes compose `{prefix}/{path}`.
    private String manifestRoutePath(RouteConfig config, String handlerName, RouteDsl routeDsl) {
        var version = config.routeVersion(handlerName);

        if (version > 0) {
            return config.apiPrefix() + "/v" + version + routeDsl.pathTemplate();
        }

        return config.prefix()
                     .isEmpty()
               ? routeDsl.pathTemplate()
               : config.prefix() + routeDsl.pathTemplate();
    }

    /// Write the #198 versioning metadata to the manifest. Unversioned slices emit
    /// `versions.count = 0`. For versioned slices the version-agnostic `[api]` fields and per-version
    /// `deprecated`/`sunset`/`defaultIfMissing` metadata are persisted for later phases (header
    /// emission, version-registry endpoint).
    private void writeVersionProperties(Properties props, RouteConfig config) {
        props.setProperty("versions.count",
                          String.valueOf(config.versions().size()));
        if (config.versions().isEmpty()) {
            return;
        }

        props.setProperty("api.prefix", config.apiPrefix());
        props.setProperty("api.requireVersionHeader",
                          String.valueOf(config.requireVersionHeader()));
        var versions = new ArrayList<>(config.versions().values());

        for (int i = 0; i < versions.size(); i++) {
            var vPrefix = "version." + i + ".";
            var version = versions.get(i);

            props.setProperty(vPrefix + "number",
                              String.valueOf(version.version()));
            props.setProperty(vPrefix + "deprecated",
                              String.valueOf(version.deprecated()));
            props.setProperty(vPrefix + "defaultIfMissing",
                              String.valueOf(version.defaultIfMissing()));
            version.sunset().onPresent(sunset -> props.setProperty(vPrefix + "sunset", sunset));
        }
    }

    private List<String> collectRequestTypes(SliceModel model) {
        return model.methods()
                    .stream()
                    .flatMap(m -> m.parameters()
                                   .stream()
                                   .map(MethodModel.MethodParameterInfo::type))
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
             .flatMap(dep -> dep.streamEventType()
                                .stream())
             .forEach(types::add);
        // From stream subscription methods
        model.streamSubscriptionMethods()
             .stream()
             .flatMap(m -> m.streamConsumerEventType()
                            .stream())
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
