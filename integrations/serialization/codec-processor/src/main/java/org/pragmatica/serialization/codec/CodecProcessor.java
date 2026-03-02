package org.pragmatica.serialization.codec;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@SupportedAnnotationTypes("org.pragmatica.serialization.Codec")
@SupportedSourceVersion(SourceVersion.RELEASE_25)
public class CodecProcessor extends AbstractProcessor {
    private CodecClassGenerator generator;

    @Override
    public synchronized void init(javax.annotation.processing.ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        this.generator = new CodecClassGenerator(processingEnv.getFiler(), processingEnv.getElementUtils(), processingEnv.getTypeUtils());
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        Map<String, List<String>> packageToCodecNames = new LinkedHashMap<>();

        for (var annotation : annotations) {
            for (var element : roundEnv.getElementsAnnotatedWith(annotation)) {
                processAnnotatedElement(element, packageToCodecNames);
            }
        }

        generateRegistries(packageToCodecNames);
        return true;
    }

    private void processAnnotatedElement(Element element, Map<String, List<String>> packageToCodecNames) {
        var kind = element.getKind();

        if (kind == ElementKind.RECORD) {
            processRecord((TypeElement) element, packageToCodecNames);
        } else if (kind == ElementKind.ENUM) {
            processEnum((TypeElement) element, packageToCodecNames);
        } else if (kind == ElementKind.INTERFACE) {
            processSealedInterface((TypeElement) element, packageToCodecNames);
        } else {
            error(element, "@Codec can only be applied to records, enums, or sealed interfaces");
        }
    }

    private void processRecord(TypeElement element, Map<String, List<String>> packageToCodecNames) {
        var tag = extractTag(element);
        var result = generator.generateRecordCodec(element, tag);

        if (result) {
            registerCodec(element, packageToCodecNames);
            note(element, "Generated codec: " + element.getSimpleName() + "Codec");
        }
    }

    private void processEnum(TypeElement element, Map<String, List<String>> packageToCodecNames) {
        var tag = extractTag(element);
        var result = generator.generateEnumCodec(element, tag);

        if (result) {
            registerCodec(element, packageToCodecNames);
            note(element, "Generated codec: " + element.getSimpleName() + "Codec");
        }
    }

    private void processSealedInterface(TypeElement element, Map<String, List<String>> packageToCodecNames) {
        var permittedSubclasses = element.getPermittedSubclasses();

        if (permittedSubclasses.isEmpty()) {
            error(element, "@Codec on interface requires a sealed interface with permitted subtypes");
            return;
        }

        var permittedNames = new java.util.HashSet<String>();

        for (var subtype : permittedSubclasses) {
            var subtypeElement = (TypeElement) processingEnv.getTypeUtils().asElement(subtype);

            if (subtypeElement == null) {
                continue;
            }

            permittedNames.add(subtypeElement.getQualifiedName().toString());
            var subtypeKind = subtypeElement.getKind();
            var tag = extractTag(subtypeElement);

            if (subtypeKind == ElementKind.RECORD) {
                if (generator.generateRecordCodec(subtypeElement, tag)) {
                    registerCodec(subtypeElement, packageToCodecNames);
                    note(subtypeElement, "Generated codec: " + subtypeElement.getSimpleName() + "Codec");
                }
            } else if (subtypeKind == ElementKind.ENUM) {
                if (generator.generateEnumCodec(subtypeElement, tag)) {
                    registerCodec(subtypeElement, packageToCodecNames);
                    note(subtypeElement, "Generated codec: " + subtypeElement.getSimpleName() + "Codec");
                }
            } else if (subtypeKind == ElementKind.INTERFACE) {
                processSealedInterface(subtypeElement, packageToCodecNames);
            }
        }

        // Process nested records/enums that are NOT permitted subclasses (helper types used as fields)
        for (var enclosed : element.getEnclosedElements()) {
            if (!(enclosed instanceof TypeElement nested)) {
                continue;
            }

            if (permittedNames.contains(nested.getQualifiedName().toString())) {
                continue;
            }

            var nestedKind = nested.getKind();
            var tag = extractTag(nested);

            if (nestedKind == ElementKind.RECORD) {
                if (generator.generateRecordCodec(nested, tag)) {
                    registerCodec(nested, packageToCodecNames);
                    note(nested, "Generated codec for nested type: " + nested.getSimpleName() + "Codec");
                }
            } else if (nestedKind == ElementKind.ENUM) {
                if (generator.generateEnumCodec(nested, tag)) {
                    registerCodec(nested, packageToCodecNames);
                    note(nested, "Generated codec for nested type: " + nested.getSimpleName() + "Codec");
                }
            }
        }
    }

    private void registerCodec(TypeElement element, Map<String, List<String>> packageToCodecNames) {
        var packageName = processingEnv.getElementUtils()
                                       .getPackageOf(element)
                                       .getQualifiedName()
                                       .toString();
        var codecName = nestedPrefix(element, packageName) + element.getSimpleName() + "Codec";
        packageToCodecNames.computeIfAbsent(packageName, _ -> new ArrayList<>()).add(codecName);
    }

    private static String nestedPrefix(TypeElement element, String packageName) {
        var qualifiedName = element.getQualifiedName().toString();
        var afterPackage = qualifiedName.substring(packageName.length() + 1);
        var lastDot = afterPackage.lastIndexOf('.');

        if (lastDot < 0) {
            return "";
        }

        return afterPackage.substring(0, lastDot).replace('.', '_') + "_";
    }

    private void generateRegistries(Map<String, List<String>> packageToCodecNames) {
        for (var entry : packageToCodecNames.entrySet()) {
            var packageName = entry.getKey();
            var codecNames = entry.getValue();
            var registryName = deriveRegistryName(packageName);

            if (generator.generateRegistry(packageName, registryName, codecNames)) {
                processingEnv.getMessager()
                             .printMessage(Diagnostic.Kind.NOTE, "Generated registry: " + registryName);
            }
        }
    }

    private int extractTag(TypeElement element) {
        for (var mirror : element.getAnnotationMirrors()) {
            var annotationType = mirror.getAnnotationType().toString();

            if (!"org.pragmatica.serialization.Codec".equals(annotationType)) {
                continue;
            }

            for (var entry : mirror.getElementValues().entrySet()) {
                if ("tag()".equals(entry.getKey().toString())) {
                    return (int) entry.getValue().getValue();
                }
            }
        }

        return -1;
    }

    private static String deriveRegistryName(String packageName) {
        var lastDot = packageName.lastIndexOf('.');
        var segment = lastDot >= 0 ? packageName.substring(lastDot + 1) : packageName;
        var capitalized = Character.toUpperCase(segment.charAt(0)) + segment.substring(1);
        return capitalized + "Codecs";
    }

    private void error(Element element, String message) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, element);
    }

    private void note(Element element, String message) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, message, element);
    }
}
