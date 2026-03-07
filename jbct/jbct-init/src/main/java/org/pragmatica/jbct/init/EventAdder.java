package org.pragmatica.jbct.init;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

/// Adds pub-sub event annotation files to an existing Aether slice project.
public final class EventAdder {
    private static final Pattern KEBAB_CASE = Pattern.compile("[a-z][a-z0-9]*(-[a-z0-9]+)*");

    private final Path projectDir;
    private final String eventName;
    private final String eventCamelCase;
    private final String annotationPackage;
    private final String configKey;

    private EventAdder(Path projectDir,
                       String eventName,
                       String eventCamelCase,
                       String annotationPackage,
                       String configKey) {
        this.projectDir = projectDir;
        this.eventName = eventName;
        this.eventCamelCase = eventCamelCase;
        this.annotationPackage = annotationPackage;
        this.configKey = configKey;
    }

    /// Create an EventAdder by reading the existing project configuration.
    public static Result<EventAdder> eventAdder(Path projectDir, String eventName) {
        return eventAdder(projectDir, eventName, null, null);
    }

    /// Create an EventAdder with optional package and config key overrides.
    public static Result<EventAdder> eventAdder(Path projectDir,
                                                  String eventName,
                                                  String packageOverride,
                                                  String configKeyOverride) {
        return validateEventName(eventName)
                  .flatMap(_ -> ProjectConfig.projectConfig(projectDir))
                  .flatMap(config -> buildEventAdder(projectDir, eventName, packageOverride, configKeyOverride, config));
    }

    /// Add the event annotation files to the project.
    public Result<List<Path>> addEvent() {
        return createDirectories().flatMap(_ -> createAnnotationFiles());
    }

    public String eventName() {
        return eventName;
    }

    public String eventCamelCase() {
        return eventCamelCase;
    }

    public String annotationPackage() {
        return annotationPackage;
    }

    public String configKey() {
        return configKey;
    }

    private Result<Unit> createDirectories() {
        try {
            var packagePath = annotationPackage.replace(".", "/");
            var srcMainJava = projectDir.resolve("src/main/java");
            Files.createDirectories(srcMainJava.resolve(packagePath));
            return Result.success(Unit.unit());
        } catch (Exception e) {
            return Causes.cause("Failed to create directories: " + e.getMessage())
                         .result();
        }
    }

    private Result<List<Path>> createAnnotationFiles() {
        var packagePath = annotationPackage.replace(".", "/");
        var srcMainJava = projectDir.resolve("src/main/java");
        var publisherPath = srcMainJava.resolve(packagePath).resolve(eventCamelCase + "Publisher.java");
        var subscriptionPath = srcMainJava.resolve(packagePath).resolve(eventCamelCase + "Subscription.java");
        return Result.allOf(ProjectFiles.writeNewFile(publisherPath, substituteVariables(PUBLISHER_TEMPLATE)),
                            ProjectFiles.writeNewFile(subscriptionPath, substituteVariables(SUBSCRIPTION_TEMPLATE)));
    }

    private static Result<Unit> validateEventName(String eventName) {
        if (eventName == null || eventName.isBlank()) {
            return Causes.cause("Event name must not be null or empty")
                         .result();
        }
        if (!KEBAB_CASE.matcher(eventName).matches()) {
            return Causes.cause("Event name must be kebab-case (e.g., 'expensive-order'): " + eventName)
                         .result();
        }
        return Result.success(Unit.unit());
    }

    private static Result<EventAdder> buildEventAdder(Path projectDir,
                                                        String eventName,
                                                        String packageOverride,
                                                        String configKeyOverride,
                                                        ProjectConfig config) {
        var camelCase = ProjectFiles.toCamelCase(eventName);
        var annotationPackage = config.resolvePackage(packageOverride);
        var configKey = (configKeyOverride != null && !configKeyOverride.isBlank())
                        ? configKeyOverride
                        : "messaging." + eventName;
        return Result.success(new EventAdder(projectDir, eventName, camelCase, annotationPackage, configKey));
    }

    private String substituteVariables(String template) {
        var eventDescription = eventName.replace("-", " ");
        return template.replace("{{annotationPackage}}", annotationPackage)
                       .replace("{{eventCamelCase}}", eventCamelCase)
                       .replace("{{configKey}}", configKey)
                       .replace("{{eventDescription}}", eventDescription);
    }

    // Templates

    private static final String PUBLISHER_TEMPLATE = """
        package {{annotationPackage}};

        import org.pragmatica.aether.slice.Publisher;
        import org.pragmatica.aether.slice.annotation.ResourceQualifier;

        import java.lang.annotation.ElementType;
        import java.lang.annotation.Retention;
        import java.lang.annotation.RetentionPolicy;
        import java.lang.annotation.Target;

        /// Publisher qualifier for {{eventDescription}} events.
        @ResourceQualifier(type = Publisher.class, config = "{{configKey}}")
        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.PARAMETER)
        public @interface {{eventCamelCase}}Publisher {}
        """;

    private static final String SUBSCRIPTION_TEMPLATE = """
        package {{annotationPackage}};

        import org.pragmatica.aether.slice.Subscriber;
        import org.pragmatica.aether.slice.annotation.ResourceQualifier;

        import java.lang.annotation.ElementType;
        import java.lang.annotation.Retention;
        import java.lang.annotation.RetentionPolicy;
        import java.lang.annotation.Target;

        /// Subscription qualifier for {{eventDescription}} events.
        @ResourceQualifier(type = Subscriber.class, config = "{{configKey}}")
        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.METHOD)
        public @interface {{eventCamelCase}}Subscription {}
        """;
}
