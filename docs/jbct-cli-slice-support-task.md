# JBCT-CLI Slice Support Task Description

## Document Purpose

This document provides detailed implementation requirements for adding Aether slice support to the jbct-cli project. It is intended for the agent or developer implementing these features.

---

## 1. Overview

### 1.1 Purpose and Scope

Extend jbct-cli to support Aether slice development with:

1. **Annotation Processor** (`slice-processor`): Generates API interfaces, proxy classes, and factory classes from `@Slice` annotated interfaces
2. **Maven Plugin Goals**: Collect dependencies and validate slice configuration
3. **CLI Commands**: Initialize and validate slice projects

### 1.2 Integration with Aether Runtime

The generated code integrates with Aether runtime:

- **SliceInvokerFacade**: Interface for inter-slice calls (defined in `slice-api`)
- **SliceRuntime**: Provides access to `SliceInvokerFacade`
- **Manifest files**: Used by `DependencyResolver` to map artifacts to classes

### 1.3 Module Structure in jbct-cli

```
jbct-cli/
├── pom.xml (parent)
├── jbct-core/              # Existing: formatting, linting
├── jbct-cli/               # Existing: CLI commands
├── jbct-maven-plugin/      # Existing: Maven goals
└── slice-processor/        # NEW: annotation processor module
    ├── pom.xml
    └── src/main/java/org/pragmatica/jbct/slice/
        ├── SliceProcessor.java
        ├── generator/
        │   ├── ApiInterfaceGenerator.java
        │   ├── ProxyClassGenerator.java
        │   ├── FactoryClassGenerator.java
        │   └── ManifestGenerator.java
        └── model/
            ├── SliceModel.java
            ├── MethodModel.java
            └── DependencyModel.java
```

---

## 2. New Module: slice-processor

### 2.1 Module Configuration

**pom.xml:**
```xml
<?xml version="1.0" encoding="UTF-8"?>
<project>
    <parent>
        <groupId>org.pragmatica-lite</groupId>
        <artifactId>jbct-parent</artifactId>
        <version>${revision}</version>
    </parent>

    <artifactId>slice-processor</artifactId>
    <packaging>jar</packaging>

    <dependencies>
        <!-- Annotation processing API -->
        <dependency>
            <groupId>com.google.auto.service</groupId>
            <artifactId>auto-service</artifactId>
            <version>1.1.1</version>
            <optional>true</optional>
        </dependency>

        <!-- Code generation -->
        <dependency>
            <groupId>com.squareup</groupId>
            <artifactId>javapoet</artifactId>
            <version>1.13.0</version>
        </dependency>

        <!-- Slice annotations (compile only) -->
        <dependency>
            <groupId>org.pragmatica-lite.aether</groupId>
            <artifactId>slice-annotations</artifactId>
            <version>${aether.version}</version>
        </dependency>
    </dependencies>
</project>
```

### 2.2 Annotation Processor Implementation

**SliceProcessor.java:**

```java
package org.pragmatica.jbct.slice;

import com.google.auto.service.AutoService;
import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import java.util.*;

@AutoService(Processor.class)
@SupportedAnnotationTypes("org.pragmatica.aether.slice.annotation.Slice")
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public class SliceProcessor extends AbstractProcessor {

    private ApiInterfaceGenerator apiGenerator;
    private ProxyClassGenerator proxyGenerator;
    private FactoryClassGenerator factoryGenerator;
    private ManifestGenerator manifestGenerator;
    private DependencyVersionResolver versionResolver;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);

        var filer = processingEnv.getFiler();
        var messager = processingEnv.getMessager();
        var elements = processingEnv.getElementUtils();
        var types = processingEnv.getTypeUtils();

        this.versionResolver = new DependencyVersionResolver(processingEnv);
        this.apiGenerator = new ApiInterfaceGenerator(filer, elements, types);
        this.proxyGenerator = new ProxyClassGenerator(filer, elements, types);
        this.factoryGenerator = new FactoryClassGenerator(filer, elements, types, versionResolver);
        this.manifestGenerator = new ManifestGenerator(filer);
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        for (var element : roundEnv.getElementsAnnotatedWith(Slice.class)) {
            if (element.getKind() != ElementKind.INTERFACE) {
                error(element, "@Slice can only be applied to interfaces");
                continue;
            }

            var interfaceElement = (TypeElement) element;
            processSliceInterface(interfaceElement);
        }
        return true;
    }

    private void processSliceInterface(TypeElement interfaceElement) {
        try {
            // 1. Build model from interface
            var sliceModel = SliceModel.from(interfaceElement, processingEnv);

            // 2. Generate API interface (if not exists)
            if (!apiInterfaceExists(sliceModel)) {
                apiGenerator.generate(sliceModel);
            }

            // 3. If this slice has dependencies, generate proxies and factory
            if (sliceModel.hasDependencies()) {
                for (var dependency : sliceModel.dependencies()) {
                    proxyGenerator.generate(dependency, sliceModel);
                }
                factoryGenerator.generate(sliceModel);
            }

            // 4. Generate manifest
            manifestGenerator.generate(sliceModel);

        } catch (Exception e) {
            error(interfaceElement, "Failed to process @Slice: " + e.getMessage());
        }
    }

    private boolean apiInterfaceExists(SliceModel model) {
        var apiClassName = model.apiPackage() + "." + model.simpleName();
        return processingEnv.getElementUtils().getTypeElement(apiClassName) != null;
    }

    private void error(Element element, String message) {
        processingEnv.getMessager().printMessage(
            Diagnostic.Kind.ERROR, message, element);
    }
}
```

### 2.3 Processing @Slice Annotations

**Input:** Interface annotated with `@Slice`

```java
@Slice
public interface InventoryService {
    Promise<StockAvailability> checkStock(CheckStockRequest request);
    Promise<StockReservation> reserveStock(ReserveStockRequest request);

    static InventoryService inventoryService(/*dependencies*/) {
        return new InventoryServiceImpl();
    }
}
```

**Processing Steps:**

1. **Validate annotation target**: Must be an interface
2. **Extract interface metadata**:
   - Package name
   - Simple name
   - Type parameters (if generic)
3. **Extract methods**:
   - Non-static, non-default methods become API methods
   - Static method returning the interface type is the factory method
4. **Extract dependencies** from factory method parameters
5. **Resolve dependency versions** from `slice-deps.properties`
6. **Generate code** (API, proxies, factory, manifest)

**SliceModel.java:**

```java
package org.pragmatica.jbct.slice.model;

import javax.lang.model.element.*;
import javax.lang.model.type.*;
import javax.annotation.processing.ProcessingEnvironment;
import java.util.*;

public record SliceModel(
    String packageName,
    String simpleName,
    String qualifiedName,
    String apiPackage,
    List<MethodModel> methods,
    List<DependencyModel> dependencies,
    ExecutableElement factoryMethod
) {
    public static SliceModel from(TypeElement element, ProcessingEnvironment env) {
        var packageName = env.getElementUtils()
            .getPackageOf(element).getQualifiedName().toString();
        var simpleName = element.getSimpleName().toString();
        var qualifiedName = element.getQualifiedName().toString();
        var apiPackage = packageName + ".api";

        var methods = extractMethods(element, env);
        var factoryMethod = findFactoryMethod(element, simpleName);
        var dependencies = extractDependencies(factoryMethod, env);

        return new SliceModel(
            packageName, simpleName, qualifiedName,
            apiPackage, methods, dependencies, factoryMethod
        );
    }

    private static List<MethodModel> extractMethods(TypeElement element, ProcessingEnvironment env) {
        return element.getEnclosedElements().stream()
            .filter(e -> e.getKind() == ElementKind.METHOD)
            .map(e -> (ExecutableElement) e)
            .filter(m -> !m.getModifiers().contains(Modifier.STATIC))
            .filter(m -> !m.getModifiers().contains(Modifier.DEFAULT))
            .map(MethodModel::from)
            .toList();
    }

    private static ExecutableElement findFactoryMethod(TypeElement element, String simpleName) {
        var expectedName = Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1);

        return element.getEnclosedElements().stream()
            .filter(e -> e.getKind() == ElementKind.METHOD)
            .map(e -> (ExecutableElement) e)
            .filter(m -> m.getModifiers().contains(Modifier.STATIC))
            .filter(m -> m.getSimpleName().toString().equals(expectedName))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "No factory method found: " + expectedName + "(...)"));
    }

    private static List<DependencyModel> extractDependencies(
            ExecutableElement factoryMethod, ProcessingEnvironment env) {
        return factoryMethod.getParameters().stream()
            .map(param -> DependencyModel.from(param, env))
            .toList();
    }

    public boolean hasDependencies() {
        return !dependencies.isEmpty();
    }
}
```

**MethodModel.java:**

```java
package org.pragmatica.jbct.slice.model;

import javax.lang.model.element.*;
import javax.lang.model.type.*;

public record MethodModel(
    String name,
    TypeMirror returnType,
    TypeMirror parameterType,
    String parameterName,
    boolean deprecated
) {
    public static MethodModel from(ExecutableElement method) {
        var name = method.getSimpleName().toString();
        var returnType = method.getReturnType();

        // Extract Promise<T> type argument
        var promiseArg = extractPromiseTypeArg(returnType);

        var params = method.getParameters();
        if (params.size() != 1) {
            throw new IllegalStateException(
                "Slice methods must have exactly one parameter: " + name);
        }

        var param = params.get(0);
        var deprecated = method.getAnnotation(Deprecated.class) != null;

        return new MethodModel(
            name,
            promiseArg,
            param.asType(),
            param.getSimpleName().toString(),
            deprecated
        );
    }

    private static TypeMirror extractPromiseTypeArg(TypeMirror returnType) {
        if (returnType instanceof DeclaredType dt) {
            var typeArgs = dt.getTypeArguments();
            if (!typeArgs.isEmpty()) {
                return typeArgs.get(0);
            }
        }
        return returnType;
    }
}
```

**DependencyModel.java:**

```java
package org.pragmatica.jbct.slice.model;

import javax.lang.model.element.*;
import javax.lang.model.type.*;
import javax.annotation.processing.ProcessingEnvironment;

public record DependencyModel(
    String parameterName,
    TypeMirror interfaceType,
    String interfaceQualifiedName,
    String interfaceSimpleName,
    String interfacePackage,
    String sliceArtifact,  // From manifest: org.example:inventory-service
    String version         // From slice-deps.properties: 1.0.0
) {
    public static DependencyModel from(VariableElement param, ProcessingEnvironment env) {
        var paramName = param.getSimpleName().toString();
        var type = param.asType();

        if (!(type instanceof DeclaredType dt)) {
            throw new IllegalStateException(
                "Dependency parameter must be an interface: " + paramName);
        }

        var typeElement = (TypeElement) dt.asElement();
        var qualifiedName = typeElement.getQualifiedName().toString();
        var simpleName = typeElement.getSimpleName().toString();
        var packageName = env.getElementUtils()
            .getPackageOf(typeElement).getQualifiedName().toString();

        // These will be resolved later by DependencyVersionResolver
        return new DependencyModel(
            paramName, type, qualifiedName, simpleName, packageName,
            null, null  // Resolved later
        );
    }

    public DependencyModel withResolved(String sliceArtifact, String version) {
        return new DependencyModel(
            parameterName, interfaceType, interfaceQualifiedName,
            interfaceSimpleName, interfacePackage,
            sliceArtifact, version
        );
    }

    public String fullArtifact() {
        return sliceArtifact + ":" + version;
    }

    public String proxyClassName() {
        return interfaceSimpleName + "Proxy";
    }
}
```

### 2.4 API Interface Generation Algorithm

**ApiInterfaceGenerator.java:**

```java
package org.pragmatica.jbct.slice.generator;

import com.squareup.javapoet.*;
import javax.annotation.processing.Filer;
import javax.lang.model.element.*;
import javax.lang.model.util.*;
import java.io.IOException;

public class ApiInterfaceGenerator {

    private final Filer filer;
    private final Elements elements;
    private final Types types;

    public ApiInterfaceGenerator(Filer filer, Elements elements, Types types) {
        this.filer = filer;
        this.elements = elements;
        this.types = types;
    }

    public void generate(SliceModel model) throws IOException {
        var interfaceBuilder = TypeSpec.interfaceBuilder(model.simpleName())
            .addModifiers(Modifier.PUBLIC)
            .addJavadoc("API interface for $L slice.\n", model.simpleName())
            .addJavadoc("Generated by slice-processor - do not edit manually.\n\n")
            .addJavadoc("@see $L\n", model.qualifiedName());

        // Add methods
        for (var method : model.methods()) {
            var methodSpec = generateMethod(method);
            interfaceBuilder.addMethod(methodSpec);
        }

        var javaFile = JavaFile.builder(model.apiPackage(), interfaceBuilder.build())
            .indent("    ")
            .build();

        javaFile.writeTo(filer);
    }

    private MethodSpec generateMethod(MethodModel method) {
        var builder = MethodSpec.methodBuilder(method.name())
            .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
            .returns(TypeName.get(method.returnType()))
            .addParameter(TypeName.get(method.parameterType()), method.parameterName());

        if (method.deprecated()) {
            builder.addAnnotation(Deprecated.class);
        }

        return builder.build();
    }
}
```

**Generation Rules:**

1. **Package**: Original package + `.api` suffix
2. **Name**: Same as implementation interface
3. **Methods**: All non-static, non-default methods
4. **Annotations**: Only preserve `@Deprecated`
5. **Javadoc**: Add generated notice and link to implementation

### 2.5 Proxy Class Generation Algorithm

**ProxyClassGenerator.java:**

```java
package org.pragmatica.jbct.slice.generator;

import com.squareup.javapoet.*;
import javax.annotation.processing.Filer;
import javax.lang.model.element.*;
import javax.lang.model.util.*;
import java.io.IOException;

public class ProxyClassGenerator {

    private final Filer filer;
    private final Elements elements;
    private final Types types;

    // Type references
    private static final ClassName SLICE_INVOKER_FACADE = ClassName.get(
        "org.pragmatica.aether.slice", "SliceRuntime", "SliceInvokerFacade");
    private static final ClassName PROMISE = ClassName.get(
        "org.pragmatica.lang", "Promise");

    public void generate(DependencyModel dependency, SliceModel context) throws IOException {
        var proxyName = dependency.proxyClassName();
        var interfaceType = TypeName.get(dependency.interfaceType());

        var classBuilder = TypeSpec.classBuilder(proxyName)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addSuperinterface(interfaceType)
            .addJavadoc("Proxy implementation of $L.\n", dependency.interfaceSimpleName())
            .addJavadoc("Delegates all calls to SliceInvoker.\n")
            .addJavadoc("Generated by slice-processor - do not edit manually.\n");

        // Fields
        classBuilder.addField(SLICE_INVOKER_FACADE, "invoker", Modifier.PRIVATE, Modifier.FINAL);
        classBuilder.addField(String.class, "artifact", Modifier.PRIVATE, Modifier.FINAL);

        // Constructor
        classBuilder.addMethod(MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PUBLIC)
            .addParameter(SLICE_INVOKER_FACADE, "invoker")
            .addParameter(String.class, "artifact")
            .addStatement("this.invoker = invoker")
            .addStatement("this.artifact = artifact")
            .build());

        // Methods - need to load interface to get method details
        var interfaceElement = elements.getTypeElement(dependency.interfaceQualifiedName());
        for (var enclosed : interfaceElement.getEnclosedElements()) {
            if (enclosed.getKind() == ElementKind.METHOD) {
                var method = (ExecutableElement) enclosed;
                if (!method.getModifiers().contains(Modifier.STATIC) &&
                    !method.getModifiers().contains(Modifier.DEFAULT)) {
                    classBuilder.addMethod(generateProxyMethod(method));
                }
            }
        }

        // toString
        classBuilder.addMethod(MethodSpec.methodBuilder("toString")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .returns(String.class)
            .addStatement("return $S + artifact + $S", proxyName + "[", "]")
            .build());

        var javaFile = JavaFile.builder(context.apiPackage(), classBuilder.build())
            .indent("    ")
            .build();

        javaFile.writeTo(filer);
    }

    private MethodSpec generateProxyMethod(ExecutableElement method) {
        var methodName = method.getSimpleName().toString();
        var returnType = method.getReturnType();
        var params = method.getParameters();

        if (params.size() != 1) {
            throw new IllegalStateException("Proxy method must have exactly one parameter");
        }

        var param = params.get(0);
        var paramType = param.asType();
        var paramName = param.getSimpleName().toString();

        // Extract response type from Promise<T>
        var responseType = extractPromiseTypeArg(returnType);

        return MethodSpec.methodBuilder(methodName)
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .returns(TypeName.get(returnType))
            .addParameter(TypeName.get(paramType), paramName)
            .addStatement("return invoker.invokeAndWait(artifact, $S, $L, $T.class)",
                methodName, paramName, responseType)
            .build();
    }

    private TypeMirror extractPromiseTypeArg(TypeMirror type) {
        // Extract T from Promise<T>
        if (type instanceof DeclaredType dt) {
            var typeArgs = dt.getTypeArguments();
            if (!typeArgs.isEmpty()) {
                return typeArgs.get(0);
            }
        }
        throw new IllegalStateException("Return type must be Promise<T>");
    }
}
```

**Generation Rules:**

1. **Class name**: Interface name + `Proxy`
2. **Package**: Same as API interface (caller's api package)
3. **Fields**: `invoker` (SliceInvokerFacade), `artifact` (String)
4. **Constructor**: Takes invoker and artifact
5. **Methods**: Each method delegates to `invoker.invokeAndWait(...)`

### 2.6 Factory Class Generation Algorithm

**FactoryClassGenerator.java:**

```java
package org.pragmatica.jbct.slice.generator;

import com.squareup.javapoet.*;
import javax.annotation.processing.Filer;
import javax.lang.model.element.*;
import javax.lang.model.util.*;
import java.io.IOException;

public class FactoryClassGenerator {

    private final Filer filer;
    private final Elements elements;
    private final Types types;
    private final DependencyVersionResolver versionResolver;

    private static final ClassName SLICE_INVOKER_FACADE = ClassName.get(
        "org.pragmatica.aether.slice", "SliceRuntime", "SliceInvokerFacade");

    public void generate(SliceModel model) throws IOException {
        var factoryName = model.simpleName() + "Factory";
        var sliceType = ClassName.get(model.packageName(), model.simpleName());

        var classBuilder = TypeSpec.classBuilder(factoryName)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addJavadoc("Factory for $L slice.\n", model.simpleName())
            .addJavadoc("Creates proxy instances and calls the slice factory method.\n")
            .addJavadoc("Generated by slice-processor - do not edit manually.\n");

        // Private constructor
        classBuilder.addMethod(MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PRIVATE)
            .build());

        // Create method
        var createMethod = MethodSpec.methodBuilder("create")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(sliceType)
            .addParameter(SLICE_INVOKER_FACADE, "invoker")
            .addJavadoc("Create $L slice with all dependencies.\n\n", model.simpleName())
            .addJavadoc("@param invoker The slice invoker for remote calls\n")
            .addJavadoc("@return Configured $L instance\n", model.simpleName());

        // Create proxy for each dependency
        var factoryArgs = new StringBuilder();
        for (var dep : model.dependencies()) {
            var resolved = versionResolver.resolve(dep);
            var proxyType = ClassName.get(model.apiPackage(), resolved.proxyClassName());
            var varName = resolved.parameterName();

            createMethod.addStatement("$T $L = new $T(invoker, $S)",
                proxyType, varName, proxyType, resolved.fullArtifact());

            if (factoryArgs.length() > 0) {
                factoryArgs.append(", ");
            }
            factoryArgs.append(varName);
        }

        // Call original factory
        var factoryMethodName = Character.toLowerCase(model.simpleName().charAt(0))
            + model.simpleName().substring(1);
        createMethod.addStatement("return $T.$L($L)",
            sliceType, factoryMethodName, factoryArgs.toString());

        classBuilder.addMethod(createMethod.build());

        // Dependencies inner class
        var depsBuilder = TypeSpec.classBuilder("Dependencies")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .addJavadoc("Dependency information for this slice.\n");

        depsBuilder.addMethod(MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PRIVATE)
            .build());

        for (var dep : model.dependencies()) {
            var resolved = versionResolver.resolve(dep);
            var constName = toConstantName(resolved.parameterName());
            depsBuilder.addField(FieldSpec.builder(String.class, constName)
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .initializer("$S", resolved.fullArtifact())
                .build());
        }

        classBuilder.addType(depsBuilder.build());

        var javaFile = JavaFile.builder(model.packageName(), classBuilder.build())
            .indent("    ")
            .build();

        javaFile.writeTo(filer);
    }

    private String toConstantName(String name) {
        // camelCase -> SCREAMING_SNAKE_CASE
        var result = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isUpperCase(c) && i > 0) {
                result.append('_');
            }
            result.append(Character.toUpperCase(c));
        }
        return result.toString();
    }
}
```

### 2.7 Manifest Generation

**ManifestGenerator.java:**

```java
package org.pragmatica.jbct.slice.generator;

import javax.annotation.processing.Filer;
import javax.tools.StandardLocation;
import java.io.*;
import java.time.Instant;
import java.util.Properties;

public class ManifestGenerator {

    private final Filer filer;

    public ManifestGenerator(Filer filer) {
        this.filer = filer;
    }

    public void generate(SliceModel model) throws IOException {
        var props = new Properties();

        // API artifact (with classifier)
        props.setProperty("api.artifact",
            getArtifactFromEnv() + ":api");

        // Slice artifact (without classifier)
        props.setProperty("slice.artifact",
            getArtifactFromEnv());

        // API interface fully qualified name
        props.setProperty("api.interface",
            model.apiPackage() + "." + model.simpleName());

        // Implementation interface fully qualified name
        props.setProperty("impl.interface",
            model.qualifiedName());

        // Metadata
        props.setProperty("generated.timestamp",
            Instant.now().toString());

        props.setProperty("processor.version",
            getProcessorVersion());

        // Write to META-INF/slice-api.properties
        var resource = filer.createResource(
            StandardLocation.CLASS_OUTPUT,
            "",
            "META-INF/slice-api.properties"
        );

        try (var writer = new OutputStreamWriter(resource.openOutputStream())) {
            props.store(writer, "Slice API manifest - generated by slice-processor");
        }
    }

    private String getArtifactFromEnv() {
        // Read from Maven properties passed to annotation processor
        var groupId = System.getProperty("slice.groupId", "unknown");
        var artifactId = System.getProperty("slice.artifactId", "unknown");
        return groupId + ":" + artifactId;
    }

    private String getProcessorVersion() {
        return getClass().getPackage().getImplementationVersion();
    }
}
```

### 2.8 Error Reporting

The annotation processor should report clear, actionable errors:

```java
// Examples of error messages:

// Invalid annotation target
error(element, "@Slice can only be applied to interfaces, found: " + element.getKind());

// Missing factory method
error(element, "No factory method found. Expected: static " +
    simpleName + " " + factoryMethodName + "(...)");

// Invalid factory return type
error(method, "Factory method must return " + simpleName +
    ", found: " + method.getReturnType());

// Invalid method signature
error(method, "Slice methods must have exactly one parameter. " +
    "Method '" + methodName + "' has " + params.size());

// Non-Promise return type
error(method, "Slice methods must return Promise<T>. " +
    "Method '" + methodName + "' returns " + returnType);

// Missing dependency version
error(param, "Cannot resolve version for dependency: " +
    interfaceName + ". Ensure it's declared as a provided dependency.");

// Circular dependency warning
warning(element, "Potential circular dependency detected: " +
    model.simpleName() + " -> " + dep.interfaceSimpleName() + " -> ...");
```

---

## 3. Maven Plugin Goals

### 3.1 jbct:collect-slice-deps

**Purpose:** Collect provided dependencies and write to properties file.

**Mojo Implementation:**

```java
package org.pragmatica.jbct.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.*;
import org.apache.maven.project.MavenProject;
import org.apache.maven.artifact.Artifact;

import java.io.*;
import java.util.Properties;

@Mojo(name = "collect-slice-deps",
      defaultPhase = LifecyclePhase.GENERATE_SOURCES,
      requiresDependencyResolution = ResolutionScope.COMPILE)
public class CollectSliceDepsMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(property = "jbct.outputFile",
               defaultValue = "${project.build.directory}/slice-deps.properties")
    private File outputFile;

    @Parameter(property = "jbct.includeClassifiers",
               defaultValue = "api")
    private String includeClassifiers;

    @Override
    public void execute() throws MojoExecutionException {
        var props = new Properties();
        var classifiers = Set.of(includeClassifiers.split(","));

        for (var artifact : project.getArtifacts()) {
            if (!"provided".equals(artifact.getScope())) {
                continue;
            }

            var classifier = artifact.getClassifier();
            if (classifier == null || !classifiers.contains(classifier)) {
                continue;
            }

            // Key: groupId:artifactId:classifier (escaped)
            var key = escapeKey(artifact.getGroupId() + ":" +
                                artifact.getArtifactId() + ":" +
                                classifier);

            // Value: resolved version
            props.setProperty(key, artifact.getVersion());

            getLog().debug("Collected: " + key + "=" + artifact.getVersion());
        }

        // Ensure directory exists
        outputFile.getParentFile().mkdirs();

        try (var writer = new FileWriter(outputFile)) {
            props.store(writer, "Generated by jbct:collect-slice-deps");
            getLog().info("Wrote " + props.size() + " dependencies to " + outputFile);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to write dependencies", e);
        }
    }

    private String escapeKey(String key) {
        // Escape : for properties file format
        return key.replace(":", "\\:");
    }
}
```

**Configuration:**

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `outputFile` | File | `${project.build.directory}/slice-deps.properties` | Output file path |
| `includeClassifiers` | String | `api` | Comma-separated classifiers to include |

### 3.2 jbct:verify

**Purpose:** Validate slice configuration.

**Mojo Implementation:**

```java
package org.pragmatica.jbct.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.*;
import org.apache.maven.project.MavenProject;

import java.io.*;
import java.nio.file.*;
import java.util.*;

@Mojo(name = "verify",
      defaultPhase = LifecyclePhase.VERIFY,
      requiresDependencyResolution = ResolutionScope.COMPILE)
public class VerifySliceMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(property = "jbct.failOnWarning", defaultValue = "false")
    private boolean failOnWarning;

    private List<String> errors = new ArrayList<>();
    private List<String> warnings = new ArrayList<>();

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        getLog().info("Validating slice configuration...");

        checkManifestEntries();
        checkSliceApiProperties();
        checkGeneratedFactory();
        checkDependencyConsistency();

        // Report results
        for (var warning : warnings) {
            getLog().warn(warning);
        }
        for (var error : errors) {
            getLog().error(error);
        }

        if (!errors.isEmpty()) {
            throw new MojoFailureException(
                "Slice validation failed with " + errors.size() + " error(s)");
        }

        if (failOnWarning && !warnings.isEmpty()) {
            throw new MojoFailureException(
                "Slice validation failed with " + warnings.size() + " warning(s)");
        }

        getLog().info("Slice validation passed");
    }

    private void checkManifestEntries() {
        var manifestFile = new File(project.getBuild().getOutputDirectory(),
            "META-INF/MANIFEST.MF");

        if (!manifestFile.exists()) {
            warnings.add("MANIFEST.MF not found - will be created during packaging");
            return;
        }

        // Check for required entries
        try {
            var manifest = new java.util.jar.Manifest(
                new FileInputStream(manifestFile));
            var attrs = manifest.getMainAttributes();

            if (attrs.getValue("Slice-Artifact") == null) {
                warnings.add("Missing Slice-Artifact manifest entry");
            }
            if (attrs.getValue("Slice-Class") == null) {
                warnings.add("Missing Slice-Class manifest entry");
            }
        } catch (IOException e) {
            errors.add("Failed to read MANIFEST.MF: " + e.getMessage());
        }
    }

    private void checkSliceApiProperties() {
        var propsFile = new File(project.getBuild().getOutputDirectory(),
            "META-INF/slice-api.properties");

        if (!propsFile.exists()) {
            errors.add("slice-api.properties not found. " +
                "Ensure annotation processor is configured.");
            return;
        }

        try {
            var props = new Properties();
            props.load(new FileInputStream(propsFile));

            checkRequired(props, "api.artifact", "slice-api.properties");
            checkRequired(props, "slice.artifact", "slice-api.properties");
            checkRequired(props, "api.interface", "slice-api.properties");
            checkRequired(props, "impl.interface", "slice-api.properties");

        } catch (IOException e) {
            errors.add("Failed to read slice-api.properties: " + e.getMessage());
        }
    }

    private void checkRequired(Properties props, String key, String file) {
        if (props.getProperty(key) == null) {
            errors.add("Missing required property '" + key + "' in " + file);
        }
    }

    private void checkGeneratedFactory() {
        // Find @Slice annotated interfaces and check for corresponding Factory
        // This would scan compiled classes
    }

    private void checkDependencyConsistency() {
        // Check that all factory parameters have corresponding provided dependencies
    }
}
```

**Validation Checks:**

| Check | Severity | Description |
|-------|----------|-------------|
| Missing MANIFEST.MF | Warning | Created during packaging |
| Missing Slice-Artifact | Warning | Required for runtime |
| Missing Slice-Class | Warning | Required for runtime |
| Missing slice-api.properties | Error | Annotation processor not run |
| Missing factory class | Error | Generation failed |
| Orphan dependency | Warning | Declared but not used |
| Missing dependency | Error | Used but not declared |

---

## 4. CLI Commands

### 4.1 jbct init --slice

**Purpose:** Scaffold a new slice project.

**Command:**
```bash
jbct init --slice --groupId org.example --artifactId my-slice
```

**Implementation:**

```java
package org.pragmatica.jbct.cli;

import picocli.CommandLine.*;

@Command(name = "init", description = "Initialize a new project")
public class InitCommand implements Runnable {

    @Option(names = "--slice", description = "Create a slice project")
    private boolean slice;

    @Option(names = "--groupId", required = true, description = "Maven group ID")
    private String groupId;

    @Option(names = "--artifactId", required = true, description = "Maven artifact ID")
    private String artifactId;

    @Option(names = "--package", description = "Base package (defaults to groupId)")
    private String basePackage;

    @Option(names = "--output", defaultValue = ".", description = "Output directory")
    private File outputDir;

    @Override
    public void run() {
        if (slice) {
            initSliceProject();
        } else {
            initRegularProject();
        }
    }

    private void initSliceProject() {
        var pkg = basePackage != null ? basePackage : groupId;
        var sliceName = toCamelCase(artifactId);

        // Create directory structure
        var projectDir = new File(outputDir, artifactId);
        createDirectory(projectDir, "src/main/java/" + pkg.replace('.', '/'));
        createDirectory(projectDir, "src/main/resources/META-INF/dependencies");
        createDirectory(projectDir, "src/test/java/" + pkg.replace('.', '/'));

        // Generate POM
        generateSlicePom(projectDir, groupId, artifactId);

        // Generate slice interface
        generateSliceInterface(projectDir, pkg, sliceName);

        // Generate implementation
        generateSliceImpl(projectDir, pkg, sliceName);

        // Generate sample request/response
        generateSampleTypes(projectDir, pkg);

        System.out.println("Created slice project: " + projectDir.getAbsolutePath());
        System.out.println("\nNext steps:");
        System.out.println("  cd " + artifactId);
        System.out.println("  mvn compile");
    }

    private void generateSlicePom(File projectDir, String groupId, String artifactId) {
        var pom = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0"
                     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                     xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                                         http://maven.apache.org/xsd/maven-4.0.0.xsd">
                <modelVersion>4.0.0</modelVersion>

                <parent>
                    <groupId>org.pragmatica-lite.aether</groupId>
                    <artifactId>slice-parent</artifactId>
                    <version>${aether.version}</version>
                </parent>

                <groupId>%s</groupId>
                <artifactId>%s</artifactId>
                <version>1.0.0-SNAPSHOT</version>

                <properties>
                    <slice.class>%s.%s</slice.class>
                </properties>

                <dependencies>
                    <!-- Add slice API dependencies here -->
                </dependencies>
            </project>
            """.formatted(groupId, artifactId, basePackage, toCamelCase(artifactId));

        writeFile(new File(projectDir, "pom.xml"), pom);
    }
}
```

**Generated Structure:**

```
my-slice/
├── pom.xml
└── src/
    ├── main/
    │   ├── java/org/example/myslice/
    │   │   ├── MySlice.java
    │   │   ├── MySliceImpl.java
    │   │   ├── MyRequest.java
    │   │   └── MyResponse.java
    │   └── resources/
    │       └── META-INF/
    │           └── dependencies/
    │               └── org.example.myslice.MySlice
    └── test/
        └── java/org/example/myslice/
            └── MySliceTest.java
```

### 4.2 jbct verify

**Purpose:** Validate slice project configuration from CLI.

**Command:**
```bash
jbct verify [directory]
```

**Implementation:**

```java
@Command(name = "verify", description = "Verify slice project configuration")
public class VerifyCommand implements Runnable {

    @Parameters(index = "0", defaultValue = ".",
                description = "Project directory")
    private File projectDir;

    @Option(names = "--strict", description = "Fail on warnings")
    private boolean strict;

    @Override
    public void run() {
        var validator = new SliceProjectValidator(projectDir);
        var result = validator.validate();

        for (var warning : result.warnings()) {
            System.out.println("WARNING: " + warning);
        }
        for (var error : result.errors()) {
            System.out.println("ERROR: " + error);
        }

        if (!result.errors().isEmpty()) {
            System.exit(1);
        }
        if (strict && !result.warnings().isEmpty()) {
            System.exit(1);
        }

        System.out.println("Validation passed");
    }
}
```

---

## 5. File Format Specifications

### 5.1 slice-deps.properties Format

**Location:** `target/slice-deps.properties`
**Generated by:** `jbct:collect-slice-deps`

```properties
# Generated by jbct:collect-slice-deps
# Format: groupId\:artifactId\:classifier=version

org.example\:inventory-service\:api=1.0.0
org.example\:pricing-service\:api=1.2.0
org.pragmatica-lite.aether.demo\:order-domain\:api=0.1.0
```

**Parsing:**
```java
var props = new Properties();
props.load(new FileInputStream("target/slice-deps.properties"));

// Key format: groupId:artifactId:classifier (unescaped)
var key = "org.example:inventory-service:api";
var version = props.getProperty(key.replace(":", "\\:"));
```

### 5.2 slice-api.properties Format

**Location:** `META-INF/slice-api.properties` (in API JAR)
**Generated by:** Annotation processor

```properties
# Slice API manifest - generated by slice-processor

# API artifact (group:artifact:classifier)
api.artifact=org.example:inventory-service:api

# Slice artifact (group:artifact, no classifier)
slice.artifact=org.example:inventory-service

# Fully qualified API interface name
api.interface=org.example.inventory.api.InventoryService

# Fully qualified implementation interface name
impl.interface=org.example.inventory.InventoryService

# Generation metadata
generated.timestamp=2025-01-15T10:30:00Z
processor.version=0.6.1
```

### 5.3 Generated Code Templates

**API Interface Template:**

```java
package ${apiPackage};

/**
 * API interface for ${simpleName} slice.
 * Generated by slice-processor - do not edit manually.
 *
 * @see ${qualifiedName}
 */
public interface ${simpleName} {
${methods}
}
```

**Proxy Class Template:**

```java
package ${apiPackage};

import org.pragmatica.aether.slice.SliceRuntime.SliceInvokerFacade;

/**
 * Proxy implementation of ${interfaceName}.
 * Generated by slice-processor - do not edit manually.
 */
public final class ${interfaceName}Proxy implements ${interfaceName} {

    private final SliceInvokerFacade invoker;
    private final String artifact;

    public ${interfaceName}Proxy(SliceInvokerFacade invoker, String artifact) {
        this.invoker = invoker;
        this.artifact = artifact;
    }

${proxyMethods}

    @Override
    public String toString() {
        return "${interfaceName}Proxy[" + artifact + "]";
    }
}
```

**Factory Class Template:**

```java
package ${packageName};

import org.pragmatica.aether.slice.SliceRuntime.SliceInvokerFacade;
${imports}

/**
 * Factory for ${simpleName} slice.
 * Generated by slice-processor - do not edit manually.
 */
public final class ${simpleName}Factory {

    private ${simpleName}Factory() {}

    public static ${simpleName} create(SliceInvokerFacade invoker) {
${proxyCreations}
        return ${simpleName}.${factoryMethodName}(${factoryArgs});
    }

    public static final class Dependencies {
${dependencyConstants}
        private Dependencies() {}
    }
}
```

---

## 6. Testing Requirements

### 6.1 Unit Tests for Annotation Processor

```java
class SliceProcessorTest {

    @Test
    void should_generate_api_interface() {
        // Given
        var source = """
            @Slice
            public interface TestService {
                Promise<Response> doSomething(Request request);
                static TestService testService() { return null; }
            }
            """;

        // When
        var compilation = compile(source);

        // Then
        assertThat(compilation).succeededWithoutWarnings();
        assertThat(compilation).generatedSourceFile("test.api.TestService")
            .hasSourceEquivalentTo(expectedApiInterface());
    }

    @Test
    void should_generate_proxy_for_dependency() {
        // Given
        var source = """
            @Slice
            public interface OrderService {
                Promise<Order> createOrder(CreateOrderRequest request);
                static OrderService orderService(InventoryService inv) { return null; }
            }
            """;

        // When
        var compilation = compile(source);

        // Then
        assertThat(compilation).generatedSourceFile("test.api.InventoryServiceProxy")
            .containsText("invoker.invokeAndWait");
    }

    @Test
    void should_fail_on_non_interface() {
        // Given
        var source = """
            @Slice
            public class NotAnInterface {}
            """;

        // When
        var compilation = compile(source);

        // Then
        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("can only be applied to interfaces");
    }
}
```

### 6.2 Integration Tests with Sample Slices

```java
class SliceIntegrationTest {

    @Test
    void should_compile_slice_with_dependencies() {
        // Given - multi-module project
        var inventoryService = createSliceModule("inventory-service");
        var orderService = createSliceModule("order-service",
            dependency("inventory-service", "api", "provided"));

        // When
        var result = maven(inventoryService, orderService)
            .goals("compile")
            .execute();

        // Then
        assertThat(result).isSuccessful();
        assertThat(orderService).hasGeneratedClass("OrderServiceFactory");
        assertThat(orderService).hasGeneratedClass("api.InventoryServiceProxy");
    }

    @Test
    void should_create_api_jar() {
        // Given
        var slice = createSliceModule("test-slice");

        // When
        var result = maven(slice)
            .goals("package")
            .execute();

        // Then
        assertThat(result).isSuccessful();
        assertThat(slice).hasArtifact("test-slice-1.0.0.jar");
        assertThat(slice).hasArtifact("test-slice-1.0.0-api.jar");
        assertThat(slice.apiJar()).containsEntry("META-INF/slice-api.properties");
        assertThat(slice.apiJar()).containsEntry("test/api/TestSlice.class");
    }
}
```

### 6.3 Maven Plugin Tests

```java
class CollectSliceDepsMojoTest {

    @Test
    void should_collect_provided_api_dependencies() {
        // Given
        var project = createProject()
            .dependency("org.example", "dep1", "1.0.0", "api", "provided")
            .dependency("org.example", "dep2", "2.0.0", "api", "provided")
            .dependency("org.example", "runtime", "1.0.0", null, "runtime")
            .build();

        // When
        var mojo = new CollectSliceDepsMojo();
        mojo.setProject(project);
        mojo.execute();

        // Then
        var props = loadProperties(project, "slice-deps.properties");
        assertThat(props).containsEntry("org.example\\:dep1\\:api", "1.0.0");
        assertThat(props).containsEntry("org.example\\:dep2\\:api", "2.0.0");
        assertThat(props).doesNotContainKey("org.example\\:runtime");
    }
}
```

---

## 7. Dependencies

### 7.1 Required Libraries

| Library | Version | Purpose |
|---------|---------|---------|
| auto-service | 1.1.1 | Annotation processor registration |
| javapoet | 1.13.0 | Java code generation |
| maven-plugin-api | 3.9.9 | Maven plugin development |
| maven-plugin-annotations | 3.9.9 | Maven mojo annotations |
| picocli | 4.7.6 | CLI framework |

### 7.2 Java Version Requirements

- **Minimum:** Java 21
- **Target:** Java 25+ (for latest language features in generated code)

### 7.3 Maven Version Requirements

- **Minimum:** Maven 3.9.0
- **Recommended:** Maven 3.9.9+

---

## 8. Implementation Checklist

### Phase 1: Core Module Setup
- [ ] Create `slice-processor` module with POM
- [ ] Add dependencies (auto-service, javapoet)
- [ ] Create package structure

### Phase 2: Model Classes
- [ ] Implement `SliceModel`
- [ ] Implement `MethodModel`
- [ ] Implement `DependencyModel`

### Phase 3: Generators
- [ ] Implement `ApiInterfaceGenerator`
- [ ] Implement `ProxyClassGenerator`
- [ ] Implement `FactoryClassGenerator`
- [ ] Implement `ManifestGenerator`
- [ ] Implement `DependencyVersionResolver`

### Phase 4: Annotation Processor
- [ ] Implement `SliceProcessor`
- [ ] Register with auto-service
- [ ] Add comprehensive error reporting

### Phase 5: Maven Plugin Goals
- [ ] Implement `CollectSliceDepsMojo`
- [ ] Implement `VerifySliceMojo`
- [ ] Add to existing plugin descriptor

### Phase 6: CLI Commands
- [ ] Implement `jbct init --slice`
- [ ] Implement `jbct verify`
- [ ] Update CLI help/documentation

### Phase 7: Testing
- [ ] Unit tests for generators
- [ ] Unit tests for annotation processor
- [ ] Integration tests with sample projects
- [ ] Maven plugin tests

### Phase 8: Documentation
- [ ] Update jbct-cli README
- [ ] Add usage examples
- [ ] Document configuration options
