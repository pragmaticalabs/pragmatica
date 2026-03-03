package org.pragmatica.postgres.conversion;

import org.pragmatica.postgres.Oid;

import java.lang.reflect.Array;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

// TODO: change internal value format from byte[] to PgValue(TEXT|BINARY)
@SuppressWarnings({"unchecked", "rawtypes"})
final class ArrayConversions {
    private ArrayConversions() {}
    static String fromArray(final Object elements, final Function<Object, String> printFn) {
        return appendArray(new StringBuilder(), elements, printFn).toString();
    }

    private static StringBuilder appendArray(StringBuilder sb, final Object elements, final Function<Object, String> printFn) {
        sb.append('{');

        int nElements = Array.getLength(elements);
        for (int i = 0; i < nElements; i++) {
            if (i > 0) {
                sb.append(',');
            }

            var o = Array.get(elements, i);
            if (o == null) {
                sb.append("NULL");
            } else if (o instanceof byte[] bytes) {
                sb.append(BlobConversions.fromBytes(bytes));
            } else if (o.getClass().isArray()) {
                sb = appendArray(sb, o, printFn);
            } else {
                sb = appendEscaped(sb, printFn.apply(o));
            }
        }

        return sb.append('}');
    }

    private static StringBuilder appendEscaped(final StringBuilder b, final String s) {
        b.append('"');

        for (int j = 0; j < s.length(); j++) {
            char c = s.charAt(j);
            if (c == '"' || c == '\\') {
                b.append('\\');
            }

            b.append(c);
        }
        return b.append('"');
    }

    static <T> T toArray(Class<T> arrayType, Oid oid, String value, BiFunction<Oid, String, Object> parse) {
        Class elementType = arrayType.getComponentType();

        while (elementType.getComponentType() != null && elementType != byte[].class) {
            elementType = elementType.getComponentType();
        }

        if (elementType.isPrimitive()) {
            throw new IllegalArgumentException("Primitive arrays are not supported due to possible NULL values");
        }

        if (value == null) {
            return null;
        }

        char[] text = value.toCharArray();
        var holder = new ArrayList<List<Object>>(1);

        if (readArray(text, skipBounds(text), (List) holder) != text.length) {
            throw new IllegalStateException("Failed to read array");
        }

        return (T) toNestedArrays(holder.getFirst(), elementType, getElementOid(oid), parse);
    }

    private static int skipBounds(final char[] text) {
        if (text[0] != '[') {
            return 0;
        }
        for (int end = 1; ; ) {
            if (text[end++] == '=') {
                return end;
            }
        }
    }

    private static int readArray(final char[] text, final int start, List<Object> result) {
        var values = new ArrayList<>();

        for (int i = start + 1; ; ) {
            final char c = text[i];

            if (c == '}') {
                result.add(values);
                return i + 1;
            } else if (c == ',' || Character.isWhitespace(c)) {
                i++;
            } else if (c == '"') {
                i = readString(text, i, values);
            } else if (c == '{') {
                i = readArray(text, i, values);
            } else if (isEncodedNull(text, i)) {
                i = readNull(i, values);
            } else {
                i = readValue(text, i, values);
            }
        }
    }

    private static boolean isEncodedNull(char[] text, int i) {
        return text[i] == 'N' && text.length > i + 4
               && text[i + 1] == 'U'
               && text[i + 2] == 'L'
               && text[i + 3] == 'L'
               && (text[i + 4] == ',' || text[i + 4] == '}' || Character.isWhitespace(text[i + 4]));
    }

    private static int readValue(final char[] text, final int start, List<Object> result) {
        var str = new StringBuilder();

        for (int i = start; ; i++) {
            char c = text[i];
            if (c == ',' || c == '}' || Character.isWhitespace(c)) {
                result.add(str.toString());
                return i;
            }
            str.append(c);
        }
    }

    private static int readNull(final int i, final List<Object> result) {
        result.add(null);
        return i + 4;
    }

    private static int readString(final char[] text, final int start, final List<Object> result) {
        var str = new StringBuilder();

        for (int i = start + 1; ; ) {
            char c = text[i++];
            if (c == '"') {
                result.add(str.toString());
                return i;
            }
            if (c == '\\') {
                c = text[i++];
            }
            str.append(c);
        }
    }

    private static Oid getElementOid(final Oid oid) {
        try {
            return Oid.valueOf(oid.name()
                                  .replaceFirst("_ARRAY", ""));
        } catch (IllegalArgumentException e) {
            return Oid.UNSPECIFIED;
        }
    }

    private static <T> T[] toNestedArrays(List<Object> result, Class<?> leafElementType, Oid oid, BiFunction<Oid, String, Object> parse) {
        var arr = (Object[]) Array.newInstance(leafElementType, getNestedDimensions(result, oid));

        for (int i = 0; i < result.size(); i++) {
            var elem = result.get(i);

            if (elem == null) {
                arr[i] = null;
            } else if (elem.getClass().equals(String.class)) {
                arr[i] = parse.apply(oid, (String) elem);
            } else {
                arr[i] = toNestedArrays((List<Object>) elem, leafElementType, oid, parse);
            }
        }
        return (T[]) arr;
    }

    private static int[] getNestedDimensions(List<Object> result, Oid oid) {
        if (result.isEmpty()) {
            return new int[]{0};
        }
        if (!(result.getFirst() instanceof List)) {
            return new int[]{result.size()};
        }

        var dimensions = new ArrayList<Integer>();
        dimensions.add(result.size());

        var value = result.getFirst();

        while (value instanceof List nested) {
            dimensions.add(nested.size());
            value = nested.isEmpty() ? null : nested.getFirst();
        }

        return toIntArray(dimensions);
    }

    private static int[] toIntArray(List<Integer> list) {
        var arr = new int[list.size()];

        for (int i = 0; i < arr.length; i++) {
            arr[i] = list.get(i);
        }
        return arr;
    }

    @SuppressWarnings("unchecked")
    static <T> T toBinaryArray(Class<T> arrayType, Oid oid, byte[] value) {
        if (value == null) {
            return null;
        }

        var buf = ByteBuffer.wrap(value);
        int ndim = buf.getInt();
        buf.getInt(); // has_null flag (we handle NULL via length == -1)
        int elemTypeOid = buf.getInt();

        if (ndim == 0) {
            return (T) Array.newInstance(resolveLeafType(arrayType), 0);
        }

        int[] dims = new int[ndim];
        for (int d = 0; d < ndim; d++) {
            dims[d] = buf.getInt();
            buf.getInt(); // lower_bound
        }

        var elementOid = Oid.valueOfId(elemTypeOid);
        var elementCodec = BinaryCodecs.forOid(elementOid);
        Class elementType = resolveLeafType(arrayType);

        if (ndim == 1) {
            var arr = (Object[]) Array.newInstance(elementType, dims[0]);
            for (int i = 0; i < dims[0]; i++) {
                arr[i] = readBinaryElement(buf, elementCodec);
            }
            return (T) arr;
        }

        return (T) readMultiDimArray(buf, dims, 0, elementType, elementCodec);
    }

    private static Class resolveLeafType(Class arrayType) {
        Class elementType = arrayType.getComponentType();

        while (elementType.getComponentType() != null && elementType != byte[].class) {
            elementType = elementType.getComponentType();
        }

        return elementType;
    }

    private static Object readBinaryElement(ByteBuffer buf, BinaryCodec<?> codec) {
        int len = buf.getInt();

        if (len == -1) {
            return null;
        }

        if (codec != null) {
            return codec.decode(buf, len);
        }

        var bytes = new byte[len];
        buf.get(bytes);
        return bytes;
    }

    private static Object[] readMultiDimArray(ByteBuffer buf, int[] dims, int dimIndex,
                                               Class<?> elementType, BinaryCodec<?> codec) {
        int size = dims[dimIndex];

        if (dimIndex == dims.length - 1) {
            var arr = (Object[]) Array.newInstance(elementType, size);
            for (int i = 0; i < size; i++) {
                arr[i] = readBinaryElement(buf, codec);
            }
            return arr;
        }

        int[] subDims = new int[dims.length - dimIndex - 1];
        System.arraycopy(dims, dimIndex + 1, subDims, 0, subDims.length);
        var subArrayType = Array.newInstance(elementType, subDims).getClass();
        var arr = (Object[]) Array.newInstance(subArrayType, size);
        for (int i = 0; i < size; i++) {
            arr[i] = readMultiDimArray(buf, dims, dimIndex + 1, elementType, codec);
        }
        return arr;
    }
}
