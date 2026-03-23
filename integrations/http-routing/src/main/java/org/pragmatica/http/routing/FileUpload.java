package org.pragmatica.http.routing;

import org.pragmatica.lang.Option;

import java.util.Objects;

import static org.pragmatica.lang.Option.option;

/// Represents an uploaded file from a multipart request.
///
/// @param fieldName   the form field name
/// @param filename    the original filename
/// @param contentType the MIME content type
/// @param content     the file content bytes
/// @param size        the file size in bytes
public record FileUpload(String fieldName,
                         String filename,
                         String contentType,
                         byte[] content,
                         long size) {
    public FileUpload {
        Objects.requireNonNull(fieldName, "fieldName");
        Objects.requireNonNull(filename, "filename");
        Objects.requireNonNull(contentType, "contentType");
        Objects.requireNonNull(content, "content");
    }

    /// Factory method for creating a file upload with size derived from content.
    public static FileUpload fileUpload(String fieldName, String filename, String contentType, byte[] content) {
        return new FileUpload(fieldName, filename, contentType, content, content.length);
    }

    /// Return filename as Option (empty if blank).
    public Option<String> optionalFilename() {
        return option(filename).filter(f -> !f.isBlank());
    }
}
