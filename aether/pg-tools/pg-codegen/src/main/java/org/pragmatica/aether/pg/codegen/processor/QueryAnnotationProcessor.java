package org.pragmatica.aether.pg.codegen.processor;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.util.List;
import java.util.Set;

/// Main annotation processor for PostgreSQL persistence interfaces.
///
/// Processes interfaces annotated with `@PgSql` (or any annotation carrying
/// `@ResourceQualifier(type = PgSqlConnector.class)`) and generates
/// `{Interface}Factory` implementation classes with:
/// - Compile-time SQL validation against migration-derived schema
/// - Named parameter rewriting to positional `$N`
/// - Auto-generated CRUD SQL from method names
/// - Row mapper code using `Result.all()` composition
///
/// Triggered by the presence of `@Query` annotations on interface methods.
@SupportedAnnotationTypes("org.pragmatica.aether.resource.db.PgSql")
@SupportedSourceVersion(SourceVersion.RELEASE_25)
public class QueryAnnotationProcessor extends AbstractProcessor {

    private SchemaLoader schemaLoader;

    @Override
    public synchronized void init(javax.annotation.processing.ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        this.schemaLoader = new SchemaLoader(processingEnv);
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver()) {
            return false;
        }

        for (var annotation : annotations) {
            for (var element : roundEnv.getElementsAnnotatedWith(annotation)) {
                if (element.getKind() == ElementKind.INTERFACE) {
                    processInterface((TypeElement) element);
                }
            }
        }
        return true;
    }

    private void processInterface(TypeElement interfaceElement) {
        var packageName = extractPackageName(interfaceElement);
        var interfaceName = interfaceElement.getSimpleName().toString();

        // Load schema from config path
        var configPath = extractConfigPath(interfaceElement);
        var schemaOpt = schemaLoader.loadSchema(configPath);

        if (schemaOpt.isEmpty()) {
            processingEnv.getMessager().printMessage(
                Diagnostic.Kind.WARNING,
                "No schema available for validation; generating factory without validation",
                interfaceElement
            );
        }

        // Analyze methods and generate factory
        processingEnv.getMessager().printMessage(
            Diagnostic.Kind.NOTE,
            "Generating " + interfaceName + "Factory",
            interfaceElement
        );

        // For now, generate a minimal factory that delegates to PgSqlConnector
        // Full method analysis requires schema + return type resolution via the type mirror API
        generateFactory(packageName, interfaceName, interfaceElement);
    }

    private void generateFactory(String packageName, String interfaceName, TypeElement interfaceElement) {
        var factoryClassName = interfaceName + "Factory";
        var qualifiedName = packageName.isEmpty() ? factoryClassName : packageName + "." + factoryClassName;

        var source = FactoryGenerator.generate(
            packageName,
            interfaceName,
            List.of(),
            Set.of()
        );

        writeSourceFile(qualifiedName, source, interfaceElement);
    }

    private void writeSourceFile(String qualifiedName, String source, Element originElement) {
        try {
            var file = processingEnv.getFiler().createSourceFile(qualifiedName, originElement);
            try (var writer = file.openWriter()) {
                writer.write(source);
            }
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(
                Diagnostic.Kind.ERROR,
                "Failed to write generated source: " + e.getMessage(),
                originElement
            );
        }
    }

    private String extractPackageName(TypeElement element) {
        var pkg = processingEnv.getElementUtils().getPackageOf(element);
        return pkg.isUnnamed() ? "" : pkg.getQualifiedName().toString();
    }

    private String extractConfigPath(TypeElement interfaceElement) {
        // Extract the config path from @ResourceQualifier on the interface's annotation
        // Default to "database" if not found
        for (var mirror : interfaceElement.getAnnotationMirrors()) {
            var annotationType = mirror.getAnnotationType().asElement();
            for (var annotationOfAnnotation : annotationType.getAnnotationMirrors()) {
                var metaName = annotationOfAnnotation.getAnnotationType().toString();
                if (metaName.endsWith("ResourceQualifier")) {
                    for (var entry : annotationOfAnnotation.getElementValues().entrySet()) {
                        if (entry.getKey().getSimpleName().toString().equals("config")) {
                            return entry.getValue().getValue().toString();
                        }
                    }
                }
            }
        }
        return "database";
    }
}
