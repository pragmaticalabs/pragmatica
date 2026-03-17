package org.pragmatica.email.http.vendor;

import java.util.List;

/// Minimal JSON string builder for vendor request bodies.
/// Avoids Jackson dependency by hand-building JSON strings.
final class JsonBuilder {
    private final StringBuilder sb = new StringBuilder();
    private boolean first = true;

    private JsonBuilder() {
        sb.append('{');
    }

    static JsonBuilder jsonBuilder() {
        return new JsonBuilder();
    }

    JsonBuilder field(String key, String value) {
        separator();
        sb.append('"').append(escape(key)).append("\":\"").append(escape(value)).append('"');
        return this;
    }

    JsonBuilder rawField(String key, String rawValue) {
        separator();
        sb.append('"').append(escape(key)).append("\":").append(rawValue);
        return this;
    }

    JsonBuilder arrayField(String key, List<String> values) {
        separator();
        sb.append('"').append(escape(key)).append("\":[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(escape(values.get(i))).append('"');
        }
        sb.append(']');
        return this;
    }

    JsonBuilder objectArrayField(String key, List<String> values, String innerKey) {
        separator();
        sb.append('"').append(escape(key)).append("\":[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"").append(escape(innerKey)).append("\":\"").append(escape(values.get(i))).append("\"}");
        }
        sb.append(']');
        return this;
    }

    String build() {
        sb.append('}');
        return sb.toString();
    }

    private void separator() {
        if (first) {
            first = false;
        } else {
            sb.append(',');
        }
    }

    static String escape(String value) {
        return value.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");
    }
}
