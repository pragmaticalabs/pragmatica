package org.pragmatica.serialization.codec;

import javax.annotation.processing.Filer;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;

/// Generates per-type codec source files and module registries for @Codec-annotated types.
public class CodecClassGenerator {
    private final Filer filer;
    private final Elements elements;
    private final Types types;

    CodecClassGenerator(Filer filer, Elements elements, Types types) {
        this.filer = filer;
        this.elements = elements;
        this.types = types;
    }

    boolean generateRecordCodec(TypeElement recordElement, int tag) {
        var packageName = elements.getPackageOf(recordElement).getQualifiedName().toString();
        var simpleName = recordElement.getSimpleName().toString();
        var typeRef = typeReference(recordElement, packageName);
        var nestedPrefix = nestedPrefix(recordElement, packageName);
        var codecName = nestedPrefix + simpleName + "Codec";
        var qualifiedName = packageName + "." + codecName;
        var qualifiedTypeName = recordElement.getQualifiedName().toString();
        var components = recordElement.getRecordComponents();
        var tagExpression = tagExpression(tag, qualifiedTypeName);
        var hasTypeVars = !recordElement.getTypeParameters().isEmpty();

        try {
            var sourceFile = filer.createSourceFile(qualifiedName, recordElement);
            try (var writer = new PrintWriter(sourceFile.openWriter())) {
                writePackageAndImports(writer, packageName);
                if (hasTypeVars) {
                    writer.println("@SuppressWarnings({\"unchecked\", \"rawtypes\"})");
                }
                writer.println("public interface " + codecName + " {");
                writeTagConstant(writer, tagExpression);
                writeCodecField(writer, typeRef, codecName);
                writer.println();
                writeRecordWriteBody(writer, typeRef, codecName, components);
                writer.println();
                writeRecordReadBody(writer, typeRef, codecName, components);
                writer.println("}");
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    boolean generateEnumCodec(TypeElement enumElement, int tag) {
        var packageName = elements.getPackageOf(enumElement).getQualifiedName().toString();
        var simpleName = enumElement.getSimpleName().toString();
        var typeRef = typeReference(enumElement, packageName);
        var nestedPrefix = nestedPrefix(enumElement, packageName);
        var codecName = nestedPrefix + simpleName + "Codec";
        var qualifiedName = packageName + "." + codecName;
        var qualifiedTypeName = enumElement.getQualifiedName().toString();
        var tagExpression = tagExpression(tag, qualifiedTypeName);

        try {
            var sourceFile = filer.createSourceFile(qualifiedName, enumElement);
            try (var writer = new PrintWriter(sourceFile.openWriter())) {
                writePackageAndImports(writer, packageName);
                writer.println("public interface " + codecName + " {");
                writeTagConstant(writer, tagExpression);
                writeCodecField(writer, typeRef, codecName);
                writer.println();
                writeEnumWriteBody(writer, typeRef, codecName);
                writer.println();
                writeEnumReadBody(writer, typeRef, codecName);
                writer.println("}");
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    boolean generateRegistry(String packageName, String registryName, List<String> codecNames) {
        var qualifiedName = packageName + "." + registryName;

        try {
            var sourceFile = filer.createSourceFile(qualifiedName);
            try (var writer = new PrintWriter(sourceFile.openWriter())) {
                writer.println("package " + packageName + ";");
                writer.println();
                writer.println("import java.util.List;");
                writer.println("import org.pragmatica.serialization.SliceCodec.TypeCodec;");
                writer.println();
                writer.println("public interface " + registryName + " {");
                writer.print("    List<TypeCodec<?>> CODECS = List.of(");

                for (int i = 0; i < codecNames.size(); i++) {
                    if (i > 0) {
                        writer.print(",");
                    }
                    writer.println();
                    writer.print("        " + codecNames.get(i) + ".CODEC");
                }

                writer.println();
                writer.println("    );");
                writer.println("}");
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static void writePackageAndImports(PrintWriter writer, String packageName) {
        writer.println("package " + packageName + ";");
        writer.println();
        writer.println("import io.netty.buffer.ByteBuf;");
        writer.println("import org.pragmatica.serialization.SliceCodec;");
        writer.println("import org.pragmatica.serialization.SliceCodec.TypeCodec;");
        writer.println();
    }

    private static void writeTagConstant(PrintWriter writer, String tagExpression) {
        writer.println("    int TAG = " + tagExpression + ";");
    }

    private static void writeCodecField(PrintWriter writer, String typeRef, String codecName) {
        writer.println("    TypeCodec<" + typeRef + "> CODEC = new TypeCodec<>(" + typeRef + ".class, TAG, _ -> TAG,");
        writer.println("        " + codecName + "::writeBody, " + codecName + "::readBody);");
    }

    private static void writeRecordWriteBody(PrintWriter writer, String typeRef, String codecName,
                                             List<? extends RecordComponentElement> components) {
        writer.println("    static void writeBody(SliceCodec codec, ByteBuf buf, " + typeRef + " value) {");
        for (var component : components) {
            writer.println("        codec.write(buf, value." + component.getSimpleName() + "());");
        }
        writer.println("    }");
    }

    private void writeRecordReadBody(PrintWriter writer, String typeRef, String codecName,
                                      List<? extends RecordComponentElement> components) {
        writer.println("    static " + typeRef + " readBody(SliceCodec codec, ByteBuf buf) {");
        for (var component : components) {
            var typeName = erasedTypeName(component);
            writer.println("        var " + component.getSimpleName() + " = (" + typeName + ") codec.read(buf);");
        }
        writer.print("        return new " + typeRef + "(");
        for (int i = 0; i < components.size(); i++) {
            if (i > 0) {
                writer.print(", ");
            }
            writer.print(components.get(i).getSimpleName());
        }
        writer.println(");");
        writer.println("    }");
    }

    private static void writeEnumWriteBody(PrintWriter writer, String typeRef, String codecName) {
        writer.println("    static void writeBody(SliceCodec codec, ByteBuf buf, " + typeRef + " value) {");
        writer.println("        SliceCodec.writeCompact(buf, value.ordinal());");
        writer.println("    }");
    }

    private static void writeEnumReadBody(PrintWriter writer, String typeRef, String codecName) {
        writer.println("    static " + typeRef + " readBody(SliceCodec codec, ByteBuf buf) {");
        writer.println("        return " + typeRef + ".values()[SliceCodec.readCompact(buf)];");
        writer.println("    }");
    }

    private static String tagExpression(int tag, String qualifiedTypeName) {
        if (tag >= 0) {
            return String.valueOf(tag);
        }
        return "SliceCodec.deterministicTag(\"" + qualifiedTypeName + "\")";
    }

    /// Returns the source-level type reference for use in generated code.
    /// For top-level types, returns simple name. For `Outer.Inner`, returns "Outer.Inner".
    private String typeReference(TypeElement element, String packageName) {
        var qualifiedName = element.getQualifiedName().toString();
        return qualifiedName.substring(packageName.length() + 1);
    }

    /// Returns a prefix for codec names of nested types.
    /// For top-level types, returns "". For `Outer.Inner`, returns "Outer_".
    private String nestedPrefix(TypeElement element, String packageName) {
        var qualifiedName = element.getQualifiedName().toString();
        var afterPackage = qualifiedName.substring(packageName.length() + 1);
        var lastDot = afterPackage.lastIndexOf('.');

        if (lastDot < 0) {
            return "";
        }

        return afterPackage.substring(0, lastDot).replace('.', '_') + "_";
    }

    /// Returns the erased type name for a record component, suitable for use in casts.
    /// If the component type contains type variables, returns the erasure.
    /// e.g., `List<Batch<C>>` → `java.util.List`, `Phase` → `org.pragmatica...Phase`
    private String erasedTypeName(RecordComponentElement component) {
        var type = component.asType();

        if (containsTypeVariable(type)) {
            return types.erasure(type).toString();
        }

        return type.toString();
    }

    /// Check if a type mirror contains any type variables (e.g., `C` in `List<C>`).
    private boolean containsTypeVariable(javax.lang.model.type.TypeMirror type) {
        if (type.getKind() == TypeKind.TYPEVAR) {
            return true;
        }

        if (type instanceof javax.lang.model.type.DeclaredType declared) {
            for (var arg : declared.getTypeArguments()) {
                if (containsTypeVariable(arg)) {
                    return true;
                }
            }
        }

        if (type instanceof javax.lang.model.type.ArrayType array) {
            return containsTypeVariable(array.getComponentType());
        }

        return false;
    }
}
