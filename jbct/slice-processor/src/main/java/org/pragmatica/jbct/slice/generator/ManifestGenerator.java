package org.pragmatica.jbct.slice.generator;

import org.pragmatica.jbct.slice.model.DependencyModel;
import org.pragmatica.jbct.slice.model.MethodModel;
import org.pragmatica.jbct.slice.model.SliceModel;
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
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

public class ManifestGenerator {
    static final int ENVELOPE_FORMAT_VERSION = 3;

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
        return generateSliceManifest(model, Option.none());
    }

    /// Generate per-slice manifest with optional Routes class.
    /// Written to META-INF/slice/{SliceName}.manifest
    public Result<Unit> generateSliceManifest(SliceModel model, Option<String> routesClass) {
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
            // Slice config file path (for blueprint generator to read)
            props.setProperty("config.file", "slices/" + sliceName + ".toml");
            // Topic subscription metadata
            var subscriptionMethods = model.subscriptionMethods();
            props.setProperty("topic.subscriptions.count", String.valueOf(subscriptionMethods.size()));
            int subIndex = 0;
            for (var method : subscriptionMethods) {
                for (var subscription : method.subscriptions()) {
                    var subPrefix = "topic.subscription." + subIndex + ".";
                    props.setProperty(subPrefix + "config", subscription.configSection());
                    props.setProperty(subPrefix + "method", method.name());
                    // Message type from the single parameter
                    if (method.hasSingleParam()) {
                        props.setProperty(subPrefix + "messageType",
                                          getQualifiedTypeName(method.parameters().getFirst().type()));
                    }
                    subIndex++;
                }
            }
            // Update count to actual number of subscription entries
            props.setProperty("topic.subscriptions.count", String.valueOf(subIndex));
            // Scheduled task metadata
            var scheduledMethods = model.scheduledMethods();
            int schedIndex = 0;
            for (var method : scheduledMethods) {
                for (var schedule : method.scheduled()) {
                    var schedPrefix = "scheduled.task." + schedIndex + ".";
                    props.setProperty(schedPrefix + "config", schedule.configSection());
                    props.setProperty(schedPrefix + "method", method.name());
                    schedIndex++;
                }
            }
            props.setProperty("scheduled.tasks.count", String.valueOf(schedIndex));
            // Publisher message types (for serializer registration)
            var publishMessageTypes = model.dependencies()
                                           .stream()
                                           .filter(dep -> dep.isPublisher())
                                           .flatMap(dep -> dep.publisherMessageType().stream())
                                           .filter(name -> !isStandardType(name))
                                           .distinct()
                                           .collect(Collectors.toList());
            if (!publishMessageTypes.isEmpty()) {
                props.setProperty("publish.message.classes", String.join(",", publishMessageTypes));
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
            return Result.success(Unit.unit());
        } catch (Exception e) {
            return Causes.cause("Failed to generate slice manifest: " + e.getClass()
                                                                         .getSimpleName() + ": " + e.getMessage())
                         .result();
        }
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
        // Add Routes class if generated
        routesClass.onPresent(classes::add);
        return classes;
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
