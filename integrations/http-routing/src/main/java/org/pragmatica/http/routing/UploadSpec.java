package org.pragmatica.http.routing;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.pragmatica.lang.Option;

/// The ingest limits a route declares in `routes.toml` (`max_size`, `max_duration`, and the multipart part limits
/// `max_files`, `max_file_size`, `max_fields`, `max_field_size`), carried from the slice to the node that accepts the
/// connection, so an ingress that does not host the slice enforces them too.
///
/// `upload` marks a route that receives an upload body (`consumes` is binary or multipart, or the route declares a limit
/// key): such a route gets the upload idle, minimum-rate and duration bounds instead of the listener's general ones.
/// A limit of zero is "not declared": the listener's default applies. The part limits are checked on the decoded body,
/// which `max_size` has already bounded.
///
/// @param upload            the route receives an upload body
/// @param maxBodyBytes      `max_size` in bytes, zero when not declared
/// @param maxDurationMillis `max_duration` in milliseconds, zero when not declared
/// @param maxFiles          `max_files`: the most file parts, zero when not declared
/// @param maxFileBytes      `max_file_size`: the most bytes in one file part, zero when not declared
/// @param maxFields         `max_fields`: the most text fields, zero when not declared
/// @param maxFieldBytes     `max_field_size`: the most bytes in one text field value, zero when not declared
public record UploadSpec(boolean upload,
                         long maxBodyBytes,
                         long maxDurationMillis,
                         long maxFiles,
                         long maxFileBytes,
                         long maxFields,
                         long maxFieldBytes) {
    /// A route with no upload declaration: the listener's defaults apply.
    public static final UploadSpec NONE = new UploadSpec(false, 0, 0, 0, 0, 0, 0);

    public UploadSpec {
        maxBodyBytes = Math.max(0, maxBodyBytes);
        maxDurationMillis = Math.max(0, maxDurationMillis);
        maxFiles = Math.max(0, maxFiles);
        maxFileBytes = Math.max(0, maxFileBytes);
        maxFields = Math.max(0, maxFields);
        maxFieldBytes = Math.max(0, maxFieldBytes);
    }

    public static UploadSpec uploadSpec(boolean upload, long maxBodyBytes, long maxDurationMillis) {
        return new UploadSpec(upload, maxBodyBytes, maxDurationMillis, 0, 0, 0, 0);
    }

    public static UploadSpec uploadSpec(boolean upload,
                                        long maxBodyBytes,
                                        long maxDurationMillis,
                                        long maxFiles,
                                        long maxFileBytes,
                                        long maxFields,
                                        long maxFieldBytes) {
        return new UploadSpec(upload, maxBodyBytes, maxDurationMillis, maxFiles, maxFileBytes, maxFields, maxFieldBytes);
    }

    /// Whether this route declares nothing the listener's defaults do not already say.
    public boolean isNone() {
        return ! upload
               && maxBodyBytes == 0
               && maxDurationMillis == 0
               && ! hasPartLimits();
    }

    /// Whether any of the four multipart part limits is declared.
    public boolean hasPartLimits() {
        return maxFiles > 0 || maxFileBytes > 0 || maxFields > 0 || maxFieldBytes > 0;
    }

    /// The first part limit the decoded multipart body exceeds, or empty when it is within all of them.
    public Option<MultipartError.LimitExceeded> partBreach(MultipartRequest request) {
        return breach(request.files().size() > limitOrMax(maxFiles),
                      MultipartError.LimitExceeded.FILES.apply(maxFiles)).orElse(() -> fileSizeBreach(request))
                                                                         .orElse(() -> fieldCountBreach(request))
                                                                         .orElse(() -> fieldSizeBreach(request));
    }

    private Option<MultipartError.LimitExceeded> fileSizeBreach(MultipartRequest request) {
        return breach(request.files()
                             .stream()
                             .anyMatch(file -> file.content().length > limitOrMax(maxFileBytes)),
                      MultipartError.LimitExceeded.FILE_SIZE.apply(maxFileBytes));
    }

    private Option<MultipartError.LimitExceeded> fieldCountBreach(MultipartRequest request) {
        return breach(request.fieldValues().values().stream().mapToLong(List::size).sum() > limitOrMax(maxFields),
                      MultipartError.LimitExceeded.FIELDS.apply(maxFields));
    }

    private Option<MultipartError.LimitExceeded> fieldSizeBreach(MultipartRequest request) {
        return breach(request.fieldValues()
                             .values()
                             .stream()
                             .flatMap(List::stream)
                             .anyMatch(value -> value.getBytes(StandardCharsets.UTF_8).length > limitOrMax(maxFieldBytes)),
                      MultipartError.LimitExceeded.FIELD_SIZE.apply(maxFieldBytes));
    }

    private static Option<MultipartError.LimitExceeded> breach(boolean exceeded, MultipartError.LimitExceeded error) {
        return exceeded
               ? Option.some(error)
               : Option.none();
    }

    private static long limitOrMax(long limit) {
        return limit == 0
               ? Long.MAX_VALUE
               : limit;
    }

    /// The stricter of two specs published for one route by different nodes (they agree unless versions differ): an
    /// upload route stays an upload route, and the smaller declared limit wins, so a route is never admitted past what
    /// any serving node declared.
    public UploadSpec stricter(UploadSpec other) {
        return new UploadSpec(upload || other.upload,
                              smallerDeclared(maxBodyBytes, other.maxBodyBytes),
                              smallerDeclared(maxDurationMillis, other.maxDurationMillis),
                              smallerDeclared(maxFiles, other.maxFiles),
                              smallerDeclared(maxFileBytes, other.maxFileBytes),
                              smallerDeclared(maxFields, other.maxFields),
                              smallerDeclared(maxFieldBytes, other.maxFieldBytes));
    }

    private static long smallerDeclared(long left, long right) {
        return left == 0
               ? right
               : right == 0
                 ? left
                 : Math.min(left, right);
    }
}
