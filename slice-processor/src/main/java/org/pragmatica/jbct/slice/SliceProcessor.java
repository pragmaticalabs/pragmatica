package org.pragmatica.jbct.slice;

import org.pragmatica.jbct.slice.generator.ApiInterfaceGenerator;
import org.pragmatica.jbct.slice.generator.DependencyVersionResolver;
import org.pragmatica.jbct.slice.generator.FactoryClassGenerator;
import org.pragmatica.jbct.slice.generator.ManifestGenerator;
import org.pragmatica.jbct.slice.generator.ProxyClassGenerator;
import org.pragmatica.jbct.slice.model.SliceModel;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
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
@SupportedSourceVersion(SourceVersion.RELEASE_25)
public class SliceProcessor extends AbstractProcessor {
    private ApiInterfaceGenerator apiGenerator;
    private ProxyClassGenerator proxyGenerator;
    private FactoryClassGenerator factoryGenerator;
    private ManifestGenerator manifestGenerator;
    private DependencyVersionResolver versionResolver;

    @Override
    public synchronized void init(javax.annotation.processing.ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        var filer = processingEnv.getFiler();
        var elements = processingEnv.getElementUtils();
        var types = processingEnv.getTypeUtils();
        this.versionResolver = new DependencyVersionResolver(processingEnv);
        this.apiGenerator = new ApiInterfaceGenerator(filer, elements, types);
        this.proxyGenerator = new ProxyClassGenerator(filer, elements, types);
        this.factoryGenerator = new FactoryClassGenerator(filer, elements, types, versionResolver);
        this.manifestGenerator = new ManifestGenerator(filer);
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
        try{
            // 1. Build model from interface
            var sliceModel = SliceModel.from(interfaceElement, processingEnv);
            // 2. Generate API interface (if not exists)
            if (!apiInterfaceExists(sliceModel)) {
                apiGenerator.generate(sliceModel);
                note(interfaceElement,
                     "Generated API interface: " + sliceModel.apiPackage() + "." + sliceModel.simpleName());
            }
            // 3. If this slice has dependencies, generate proxies and factory
            if (sliceModel.hasDependencies()) {
                for (var dependency : sliceModel.dependencies()) {
                    var resolved = versionResolver.resolve(dependency);
                    proxyGenerator.generate(resolved, sliceModel);
                    note(interfaceElement, "Generated proxy: " + resolved.proxyClassName());
                }
                factoryGenerator.generate(sliceModel);
                note(interfaceElement, "Generated factory: " + sliceModel.simpleName() + "Factory");
            }
            // 4. Generate manifest
            manifestGenerator.generate(sliceModel);
            note(interfaceElement, "Generated manifest: META-INF/slice-api.properties");
        } catch (IllegalStateException e) {
            error(interfaceElement, e.getMessage());
        } catch (Exception e) {
            error(interfaceElement, "Failed to process @Slice: " + e.getMessage());
        }
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
