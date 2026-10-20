package org.pragmatica.http.routing;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.option;


/// Parsed multipart request providing access to form fields and file uploads.
///
/// A field name may repeat (multi-select, checkbox groups): [#fieldValues(String)] returns every value in body
/// order, and [#field(String)] the FIRST, matching [#file(String)], which returns the first file of a name.
///
/// @param fieldValues form field values keyed by field name, each name's values in body order
/// @param files       uploaded files in order
public record MultipartRequest(Map<String, List<String>> fieldValues, List<FileUpload> files) {
    public MultipartRequest {
        fieldValues = copyOfValues(fieldValues);
        files = List.copyOf(files);
    }

    /// Factory method for creating a multipart request with one value per field.
    public static MultipartRequest multipartRequest(Map<String, String> fields, List<FileUpload> files) {
        var values = new LinkedHashMap<String, List<String>>();

        fields.forEach((name, value) -> values.put(name, List.of(value)));

        return new MultipartRequest(values, files);
    }

    /// Form field values by name, the first value of each name.
    public Map<String, String> fields() {
        var first = new LinkedHashMap<String, String>();

        fieldValues.forEach((name, values) -> first.put(name, values.getFirst()));

        return Map.copyOf(first);
    }

    /// Find a file upload by field name.
    public Option<FileUpload> file(String fieldName) {
        return Option.from(files.stream().filter(f -> f.fieldName()
                                                       .equals(fieldName)).findFirst());
    }

    /// Find all file uploads matching a field name.
    public List<FileUpload> allFiles(String fieldName) {
        return files.stream()
                    .filter(f -> f.fieldName()
                                  .equals(fieldName))
                    .toList();
    }

    /// Get a form field value by name: the first one, when the name repeats.
    public Option<String> field(String name) {
        return option(fieldValues.get(name)).flatMap(values -> Option.from(values.stream().findFirst()));
    }

    /// Every value of a form field, in body order; empty when the field is absent.
    public List<String> fieldValues(String name) {
        return option(fieldValues.get(name)).or(List.of());
    }

    /// Check if this request contains any files.
    public boolean hasFiles() {
        return ! files.isEmpty();
    }

    /// Check if this request contains any form fields.
    public boolean hasFields() {
        return ! fieldValues.isEmpty();
    }

    private static Map<String, List<String>> copyOfValues(Map<String, List<String>> values) {
        var copy = new LinkedHashMap<String, List<String>>();

        values.forEach((name, list) -> putNonEmpty(copy, name, list));

        return Map.copyOf(copy);
    }

    private static void putNonEmpty(Map<String, List<String>> target, String name, List<String> values) {
        if (!values.isEmpty()) {
            target.put(name, List.copyOf(values));
        }
    }
}
