package org.pragmatica.http.routing;

import org.pragmatica.lang.Option;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.pragmatica.lang.Option.option;

/// Parsed multipart request providing access to form fields and file uploads.
///
/// @param fields form field values keyed by field name
/// @param files  uploaded files in order
public record MultipartRequest(Map<String, String> fields,
                                List<FileUpload> files) {
    public MultipartRequest {
        Objects.requireNonNull(fields, "fields");
        Objects.requireNonNull(files, "files");
    }

    /// Factory method for creating a multipart request.
    public static MultipartRequest multipartRequest(Map<String, String> fields, List<FileUpload> files) {
        return new MultipartRequest(Map.copyOf(fields), List.copyOf(files));
    }

    /// Find a file upload by field name.
    public Option<FileUpload> file(String fieldName) {
        return Option.from(files.stream()
                                .filter(f -> f.fieldName().equals(fieldName))
                                .findFirst());
    }

    /// Find all file uploads matching a field name.
    public List<FileUpload> allFiles(String fieldName) {
        return files.stream()
                    .filter(f -> f.fieldName().equals(fieldName))
                    .toList();
    }

    /// Get a form field value by name.
    public Option<String> field(String name) {
        return option(fields.get(name));
    }

    /// Check if this request contains any files.
    public boolean hasFiles() {
        return !files.isEmpty();
    }

    /// Check if this request contains any form fields.
    public boolean hasFields() {
        return !fields.isEmpty();
    }
}
