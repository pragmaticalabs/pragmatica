package org.pragmatica.serialization.codec;

import javax.annotation.processing.Filer;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
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

    private enum FieldKind {
        BYTE, SHORT, CHAR, INT, LONG, FLOAT, DOUBLE, BOOLEAN,
        STRING, CODEC_TYPE, DISPATCHED
    }

    private record ClassifiedField(RecordComponentElement component, FieldKind kind, String codecRef) {}

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
        var classified = classifyComponents(components, packageName);
        var hasDispatch = classified.stream().anyMatch(f -> f.kind() == FieldKind.DISPATCHED);

        try {
            var sourceFile = filer.createSourceFile(qualifiedName, recordElement);
            try (var writer = new PrintWriter(sourceFile.openWriter())) {
                writePackageAndImports(writer, packageName);
                if (hasTypeVars || hasDispatch) {
                    writer.println("@SuppressWarnings({\"unchecked\", \"rawtypes\"})");
                }
                writer.println("public interface " + codecName + " {");
                writeTagConstant(writer, tagExpression);
                writeCodecField(writer, typeRef, codecName);
                writer.println();
                writeRecordWriteBody(writer, typeRef, classified);
                writer.println();
                writeRecordReadBody(writer, typeRef, classified);
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
                writeEnumWriteBody(writer, typeRef);
                writer.println();
                writeEnumReadBody(writer, typeRef);
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

    // --- Field classification ---

    private List<ClassifiedField> classifyComponents(List<? extends RecordComponentElement> components,
                                                     String currentPackage) {
        return components.stream()
                         .map(c -> classifyField(c, currentPackage))
                         .toList();
    }

    private ClassifiedField classifyField(RecordComponentElement component, String currentPackage) {
        var typeMirror = component.asType();

        return switch (typeMirror.getKind()) {
            case BYTE -> new ClassifiedField(component, FieldKind.BYTE, null);
            case SHORT -> new ClassifiedField(component, FieldKind.SHORT, null);
            case CHAR -> new ClassifiedField(component, FieldKind.CHAR, null);
            case INT -> new ClassifiedField(component, FieldKind.INT, null);
            case LONG -> new ClassifiedField(component, FieldKind.LONG, null);
            case FLOAT -> new ClassifiedField(component, FieldKind.FLOAT, null);
            case DOUBLE -> new ClassifiedField(component, FieldKind.DOUBLE, null);
            case BOOLEAN -> new ClassifiedField(component, FieldKind.BOOLEAN, null);
            case DECLARED -> classifyDeclaredField(component, (DeclaredType) typeMirror, currentPackage);
            default -> new ClassifiedField(component, FieldKind.DISPATCHED, null);
        };
    }

    private ClassifiedField classifyDeclaredField(RecordComponentElement component, DeclaredType declared,
                                                  String currentPackage) {
        if (containsTypeVariable(declared)) {
            return new ClassifiedField(component, FieldKind.DISPATCHED, null);
        }

        var element = (TypeElement) declared.asElement();
        var qualifiedName = element.getQualifiedName().toString();

        if ("java.lang.String".equals(qualifiedName)) {
            return new ClassifiedField(component, FieldKind.STRING, null);
        }

        // Check for @Codec annotation (same compilation round)
        if (hasCodecAnnotation(element)) {
            var codecFqn = computeCodecFqn(element);
            return new ClassifiedField(component, FieldKind.CODEC_TYPE, codecReference(codecFqn, currentPackage));
        }

        // Check for companion codec class (cross-module dependency)
        var codecFqn = computeCodecFqn(element);

        if (elements.getTypeElement(codecFqn) != null) {
            return new ClassifiedField(component, FieldKind.CODEC_TYPE, codecReference(codecFqn, currentPackage));
        }

        return new ClassifiedField(component, FieldKind.DISPATCHED, null);
    }

    private static boolean hasCodecAnnotation(TypeElement element) {
        for (var mirror : element.getAnnotationMirrors()) {
            if ("org.pragmatica.serialization.Codec".equals(mirror.getAnnotationType().toString())) {
                return true;
            }
        }

        return false;
    }

    private String computeCodecFqn(TypeElement element) {
        var packageName = elements.getPackageOf(element).getQualifiedName().toString();
        return packageName + "." + nestedPrefix(element, packageName) + element.getSimpleName() + "Codec";
    }

    private static String codecReference(String codecFqn, String currentPackage) {
        var prefix = currentPackage + ".";

        if (codecFqn.startsWith(prefix) && codecFqn.indexOf('.', prefix.length()) < 0) {
            return codecFqn.substring(prefix.length());
        }

        return codecFqn;
    }

    // --- Source generation helpers ---

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

    // --- Record codec generation with field-level inlining ---

    private static void writeRecordWriteBody(PrintWriter writer, String typeRef,
                                             List<ClassifiedField> fields) {
        writer.println("    static void writeBody(SliceCodec codec, ByteBuf buf, " + typeRef + " value) {");

        for (var field : fields) {
            var accessor = "value." + field.component().getSimpleName() + "()";

            switch (field.kind()) {
                case BYTE -> {
                    writer.println("        buf.writeByte(SliceCodec.TAG_BYTE);");
                    writer.println("        buf.writeByte(" + accessor + ");");
                }
                case SHORT -> {
                    writer.println("        buf.writeByte(SliceCodec.TAG_SHORT);");
                    writer.println("        buf.writeShort(" + accessor + ");");
                }
                case CHAR -> {
                    writer.println("        buf.writeByte(SliceCodec.TAG_CHAR);");
                    writer.println("        buf.writeChar(" + accessor + ");");
                }
                case INT -> {
                    writer.println("        buf.writeByte(SliceCodec.TAG_INT);");
                    writer.println("        buf.writeInt(" + accessor + ");");
                }
                case LONG -> {
                    writer.println("        buf.writeByte(SliceCodec.TAG_LONG);");
                    writer.println("        buf.writeLong(" + accessor + ");");
                }
                case FLOAT -> {
                    writer.println("        buf.writeByte(SliceCodec.TAG_FLOAT);");
                    writer.println("        buf.writeFloat(" + accessor + ");");
                }
                case DOUBLE -> {
                    writer.println("        buf.writeByte(SliceCodec.TAG_DOUBLE);");
                    writer.println("        buf.writeDouble(" + accessor + ");");
                }
                case BOOLEAN -> writer.println("        buf.writeByte(" + accessor
                    + " ? SliceCodec.TAG_TRUE : SliceCodec.TAG_FALSE);");
                case STRING -> {
                    writer.println("        buf.writeByte(SliceCodec.TAG_STRING);");
                    writer.println("        SliceCodec.writeString(buf, " + accessor + ");");
                }
                case CODEC_TYPE -> {
                    writer.println("        SliceCodec.writeCompact(buf, " + field.codecRef() + ".TAG);");
                    writer.println("        " + field.codecRef() + ".writeBody(codec, buf, " + accessor + ");");
                }
                case DISPATCHED -> writer.println("        codec.write(buf, " + accessor + ");");
            }
        }

        writer.println("    }");
    }

    private void writeRecordReadBody(PrintWriter writer, String typeRef,
                                     List<ClassifiedField> fields) {
        writer.println("    static " + typeRef + " readBody(SliceCodec codec, ByteBuf buf) {");

        for (var field : fields) {
            var name = field.component().getSimpleName().toString();

            switch (field.kind()) {
                case BYTE -> {
                    writer.println("        buf.readByte();");
                    writer.println("        var " + name + " = buf.readByte();");
                }
                case SHORT -> {
                    writer.println("        buf.readByte();");
                    writer.println("        var " + name + " = buf.readShort();");
                }
                case CHAR -> {
                    writer.println("        buf.readByte();");
                    writer.println("        var " + name + " = buf.readChar();");
                }
                case INT -> {
                    writer.println("        buf.readByte();");
                    writer.println("        var " + name + " = buf.readInt();");
                }
                case LONG -> {
                    writer.println("        buf.readByte();");
                    writer.println("        var " + name + " = buf.readLong();");
                }
                case FLOAT -> {
                    writer.println("        buf.readByte();");
                    writer.println("        var " + name + " = buf.readFloat();");
                }
                case DOUBLE -> {
                    writer.println("        buf.readByte();");
                    writer.println("        var " + name + " = buf.readDouble();");
                }
                case BOOLEAN -> writer.println("        var " + name + " = buf.readByte() == SliceCodec.TAG_TRUE;");
                case STRING -> {
                    writer.println("        buf.readByte();");
                    writer.println("        var " + name + " = SliceCodec.readString(buf);");
                }
                case CODEC_TYPE -> {
                    writer.println("        SliceCodec.readCompact(buf);");
                    writer.println("        var " + name + " = " + field.codecRef() + ".readBody(codec, buf);");
                }
                case DISPATCHED -> {
                    var typeName = erasedTypeName(field.component());
                    writer.println("        var " + name + " = (" + typeName + ") codec.read(buf);");
                }
            }
        }

        writer.print("        return new " + typeRef + "(");

        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                writer.print(", ");
            }
            writer.print(fields.get(i).component().getSimpleName());
        }

        writer.println(");");
        writer.println("    }");
    }

    // --- Enum codec generation ---

    private static void writeEnumWriteBody(PrintWriter writer, String typeRef) {
        writer.println("    static void writeBody(SliceCodec codec, ByteBuf buf, " + typeRef + " value) {");
        writer.println("        SliceCodec.writeCompact(buf, value.ordinal());");
        writer.println("    }");
    }

    private static void writeEnumReadBody(PrintWriter writer, String typeRef) {
        writer.println("    static " + typeRef + " readBody(SliceCodec codec, ByteBuf buf) {");
        writer.println("        return " + typeRef + ".values()[SliceCodec.readCompact(buf)];");
        writer.println("    }");
    }

    // --- Utility methods ---

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

        if (type instanceof DeclaredType declared) {
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
