package org.pragmatica.jbct.slice;

import org.pragmatica.jbct.slice.generator.DependencyVersionResolver;
import org.pragmatica.jbct.slice.generator.FactoryClassGenerator;
import org.pragmatica.jbct.slice.generator.ManifestGenerator;
import org.pragmatica.jbct.slice.model.SliceModel;
import org.pragmatica.jbct.slice.routing.ErrorPatternConfig;
import org.pragmatica.jbct.slice.routing.ErrorTypeDiscovery;
import org.pragmatica.jbct.slice.routing.RouteConfig;
import org.pragmatica.jbct.slice.routing.RouteConfigLoader;
import org.pragmatica.jbct.slice.routing.RouteSourceGenerator;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Path;
import java.util.Set;

import com.google.auto.service.AutoService;

@AutoService(Processor.class)
@SupportedAnnotationTypes({"org.pragmatica.aether.slice.annotation.Slice",
 "org.pragmatica.aether.infra.aspect.Aspect",
 "org.pragmatica.aether.infra.aspect.Key"})
@SupportedOptions({"slice.groupId", "slice.artifactId"})
@SupportedSourceVersion(SourceVersion.RELEASE_25)
public class SliceProcessor extends AbstractProcessor {
    private FactoryClassGenerator factoryGenerator;
    private ManifestGenerator manifestGenerator;
    private DependencyVersionResolver versionResolver;
    private RouteSourceGenerator routeGenerator;
    private ErrorTypeDiscovery errorDiscovery;

    @Override
    public synchronized void init(javax.annotation.processing.ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        var filer = processingEnv.getFiler();
        var elements = processingEnv.getElementUtils();
        var types = processingEnv.getTypeUtils();
        var options = processingEnv.getOptions();
        this.versionResolver = new DependencyVersionResolver(processingEnv);
        this.factoryGenerator = new FactoryClassGenerator(filer, elements, types, versionResolver);
        this.manifestGenerator = new ManifestGenerator(filer, versionResolver, options);
        this.errorDiscovery = new ErrorTypeDiscovery(processingEnv);
        this.routeGenerator = new RouteSourceGenerator(filer, processingEnv.getMessager());
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        // Only process @Slice annotations; @Aspect and @Key are handled during method extraction
        for (var annotation : annotations) {
            if (!annotation.getQualifiedName()
                           .toString()
                           .equals("org.pragmatica.aether.slice.annotation.Slice")) {
                continue;
            }
            for (var element : roundEnv.getElementsAnnotatedWith(annotation)) {
                if (element.getKind() != ElementKind.INTERFACE) {
                    error(element, "@Slice can only be applied to interfaces, found: " + element.getKind());
                    continue;
                }
                var interfaceElement = (TypeElement) element;
                processSliceInterface(interfaceElement);
            }
        }
        return true;
    }

    private void processSliceInterface(TypeElement interfaceElement) {
        SliceModel.sliceModel(interfaceElement, processingEnv)
                  .onFailure(cause -> error(interfaceElement,
                                            cause.message()))
                  .onSuccess(sliceModel -> generateArtifacts(interfaceElement, sliceModel));
    }

    private void generateArtifacts(TypeElement interfaceElement, SliceModel sliceModel) {
        Result.all(generateFactory(interfaceElement, sliceModel),
                   generateSliceManifest(interfaceElement, sliceModel),
                   generateRoutes(interfaceElement, sliceModel))
              .map((_, _, _) -> Unit.unit())
              .onFailure(cause -> error(interfaceElement,
                                        cause.message()));
    }

    private Result<Unit> generateFactory(TypeElement interfaceElement, SliceModel sliceModel) {
        return factoryGenerator.generate(sliceModel)
                               .onSuccess(_ -> note(interfaceElement,
                                                    "Generated factory: " + sliceModel.simpleName() + "Factory"));
    }

    private Result<Unit> generateSliceManifest(TypeElement interfaceElement, SliceModel sliceModel) {
        return manifestGenerator.generateSliceManifest(sliceModel)
                                .onSuccess(_ -> note(interfaceElement,
                                                     "Generated slice manifest: META-INF/slice/" + sliceModel.simpleName()
                                                     + ".manifest"));
    }

    private Result<Unit> generateRoutes(TypeElement interfaceElement, SliceModel sliceModel) {
        var packageName = sliceModel.packageName();
        return loadRouteConfig(packageName)
        .flatMap(configOpt -> configOpt.fold(() -> Result.success(Unit.unit()),
                                             config -> generateRoutesFromConfig(interfaceElement, sliceModel, config)));
    }

    private Result<Option<RouteConfig>> loadRouteConfig(String packageName) {
        try{
            var packagePath = packageName.replace('.', '/');
            var resource = processingEnv.getFiler()
                                        .getResource(StandardLocation.CLASS_OUTPUT,
                                                     "",
                                                     packagePath + "/" + RouteConfigLoader.CONFIG_FILE);
            var configPath = Path.of(resource.toUri());
            var packageDir = configPath.getParent();
            // Use loadMerged to support routes-base.toml inheritance
            return RouteConfigLoader.loadMerged(packageDir)
                                    .map(config -> config.hasRoutes()
                                                   ? Option.some(config)
                                                   : Option.none());
        } catch (IOException | IllegalArgumentException | UnsupportedOperationException | FileSystemNotFoundException _) {
            // routes.toml not found or not accessible (e.g., in test environment) - routes are optional
            return Result.success(Option.none());
        }
    }

    private Result<Unit> generateRoutesFromConfig(TypeElement interfaceElement,
                                                  SliceModel sliceModel,
                                                  RouteConfig config) {
        var packageName = sliceModel.packageName();
        return errorDiscovery.discover(packageName,
                                       config.errors())
                             .flatMap(errorMappings -> routeGenerator.generate(sliceModel, config, errorMappings))
                             .onSuccess(_ -> note(interfaceElement,
                                                  "Generated routes: " + sliceModel.simpleName() + "Routes"));
    }

    private void error(Element element, String message) {
        processingEnv.getMessager()
                     .printMessage(Diagnostic.Kind.ERROR, message, element);
    }

    private void note(Element element, String message) {
        processingEnv.getMessager()
                     .printMessage(Diagnostic.Kind.NOTE, message, element);
    }
}
