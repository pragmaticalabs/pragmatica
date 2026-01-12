package org.pragmatica.jbct.slice;

import org.pragmatica.jbct.slice.generator.ApiInterfaceGenerator;
import org.pragmatica.jbct.slice.generator.DependencyVersionResolver;
import org.pragmatica.jbct.slice.generator.FactoryClassGenerator;
import org.pragmatica.jbct.slice.generator.ManifestGenerator;
import org.pragmatica.jbct.slice.model.SliceModel;
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
import java.util.Set;

import com.google.auto.service.AutoService;

@AutoService(Processor.class)
@SupportedAnnotationTypes("org.pragmatica.aether.slice.annotation.Slice")
@SupportedOptions({"slice.groupId", "slice.artifactId"})
@SupportedSourceVersion(SourceVersion.RELEASE_25)
public class SliceProcessor extends AbstractProcessor {
    private ApiInterfaceGenerator apiGenerator;
    private FactoryClassGenerator factoryGenerator;
    private ManifestGenerator manifestGenerator;
    private DependencyVersionResolver versionResolver;

    @Override
    public synchronized void init(javax.annotation.processing.ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        var filer = processingEnv.getFiler();
        var elements = processingEnv.getElementUtils();
        var types = processingEnv.getTypeUtils();
        var options = processingEnv.getOptions();
        this.versionResolver = new DependencyVersionResolver(processingEnv);
        this.apiGenerator = new ApiInterfaceGenerator(filer, elements, types);
        this.factoryGenerator = new FactoryClassGenerator(filer, elements, types, versionResolver);
        this.manifestGenerator = new ManifestGenerator(filer, versionResolver, options);
    }

    @Override
    public boolean process(Set< ? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        for (var annotation : annotations) {
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
        generateApiInterface(interfaceElement, sliceModel)
                            .flatMap(_ -> generateFactory(interfaceElement, sliceModel))
                            .flatMap(_ -> generateManifest(interfaceElement, sliceModel))
                            .flatMap(_ -> generateSliceManifest(interfaceElement, sliceModel))
                            .onFailure(cause -> error(interfaceElement,
                                                      cause.message()));
    }

    private Result<Unit> generateApiInterface(TypeElement interfaceElement, SliceModel sliceModel) {
        if (apiInterfaceExists(sliceModel)) {
            return Result.success(Unit.unit());
        }
        return apiGenerator.generate(sliceModel)
                           .onSuccess(_ -> note(interfaceElement,
                                                "Generated API interface: " + sliceModel.apiPackage() + "." + sliceModel.simpleName()));
    }

    private Result<Unit> generateFactory(TypeElement interfaceElement, SliceModel sliceModel) {
        return factoryGenerator.generate(sliceModel)
                               .onSuccess(_ -> note(interfaceElement,
                                                    "Generated factory: " + sliceModel.simpleName() + "Factory"));
    }

    private Result<Unit> generateManifest(TypeElement interfaceElement, SliceModel sliceModel) {
        return manifestGenerator.generate(sliceModel)
                                .onSuccess(_ -> note(interfaceElement,
                                                     "Generated manifest: META-INF/slice-api.properties"));
    }

    private Result<Unit> generateSliceManifest(TypeElement interfaceElement, SliceModel sliceModel) {
        return manifestGenerator.generateSliceManifest(sliceModel)
                                .onSuccess(_ -> note(interfaceElement,
                                                     "Generated slice manifest: META-INF/slice/" + sliceModel.simpleName() + ".manifest"));
    }

    private boolean apiInterfaceExists(SliceModel model) {
        var apiClassName = model.apiPackage() + "." + model.simpleName();
        return processingEnv.getElementUtils()
                            .getTypeElement(apiClassName) != null;
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
