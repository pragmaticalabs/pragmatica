package org.pragmatica.lang.io;

import java.io.InputStream;

import org.pragmatica.lang.Result;


/// Safe I/O operations for streams and classpath resources, returning `Result<T>` instead of
/// throwing `IOException` or returning null.
///
/// Eliminates the two recurring patterns:
///   - `BufferedReader.readLine() != null` loops
///   - `ClassLoader.getResourceAsStream() == null` checks
///
/// Example:
/// ```java
/// import static org.pragmatica.lang.io.StreamOps.*;
///
/// // Read classpath resource as string
/// var content = readResource(getClass().getClassLoader(), "META-INF/config.toml");
///
/// // Read InputStream as bytes
/// var bytes = readBytes(inputStream);
/// ```
public sealed interface StreamOps {
    /// Read entire InputStream as a string (UTF-8).
    static Result<String> readString(InputStream input) {
        return readBytes(input).map(bytes -> new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
    }

    /// Read entire InputStream as a byte array.
    static Result<byte[]> readBytes(InputStream input) {
        return Result.lift(e -> new StreamError.ReadFailed(e.getMessage()),
                           input::readAllBytes);
    }

    /// Load a classpath resource as a string (UTF-8).
    /// Returns `ResourceNotFound` if the resource does not exist.
    static Result<String> readResource(ClassLoader loader, String path) {
        return openResource(loader, path).flatMap(StreamOps::readString);
    }

    /// Load a classpath resource as a byte array.
    /// Returns `ResourceNotFound` if the resource does not exist.
    static Result<byte[]> readResourceBytes(ClassLoader loader, String path) {
        return openResource(loader, path).flatMap(StreamOps::readBytes);
    }

    /// Load a classpath resource relative to a class as a string (UTF-8).
    static Result<String> readResource(Class<?> anchor, String path) {
        return openResource(anchor, path).flatMap(StreamOps::readString);
    }

    /// Load a classpath resource relative to a class as a byte array.
    static Result<byte[]> readResourceBytes(Class<?> anchor, String path) {
        return openResource(anchor, path).flatMap(StreamOps::readBytes);
    }

    /// Open a classpath resource as an InputStream.
    /// Returns `ResourceNotFound` if the resource does not exist.
    static Result<InputStream> openResource(ClassLoader loader, String path) {
        var stream = loader.getResourceAsStream(path);

        return stream != null
               ? Result.success(stream)
               : new StreamError.ResourceNotFound(path).result();
    }

    /// Open a classpath resource relative to a class as an InputStream.
    static Result<InputStream> openResource(Class<?> anchor, String path) {
        var stream = anchor.getResourceAsStream(path);

        return stream != null
               ? Result.success(stream)
               : new StreamError.ResourceNotFound(path).result();
    }

    record unused() implements StreamOps {}
}
