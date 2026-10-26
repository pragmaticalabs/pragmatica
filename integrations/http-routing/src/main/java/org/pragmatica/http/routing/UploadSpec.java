package org.pragmatica.http.routing;

import org.pragmatica.lang.Option;


/// The ingest limits a route declares in `routes.toml` (`max_size`, `max_duration`, and the multipart part limits
/// `max_files`, `max_file_size`, `max_fields`, `max_field_size`), carried from the slice to the node that accepts the
/// connection, so an ingress that does not host the slice enforces them too.
///
/// `upload` marks a route that receives an upload body (`consumes` is binary or multipart, or the route declares a limit
/// key): such a route gets the upload idle, minimum-rate and duration bounds instead of the listener's general ones.
/// A limit of zero is "not declared": the listener's default applies. The part limits are checked on the aggregated body,
/// which `max_size` has already bounded, from its multipart framing (see [#partBreach(byte[], String)]).
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
               && !hasPartLimits();
    }

    /// Whether any of the four multipart part limits is declared.
    public boolean hasPartLimits() {
        return maxFiles > 0 || maxFileBytes > 0 || maxFields > 0 || maxFieldBytes > 0;
    }

    /// The first part limit a multipart body exceeds, or empty when it is within all of them or the route declares none.
    ///
    /// Counts and sizes come from the framing scan alone ([MultipartScan]): the body is not decoded and no part content
    /// is copied, so the check costs one pass over the bytes and holds nothing. A body that is not multipart/form-data,
    /// has no boundary or is not closed is not a breach: whoever reads the body answers it as it always has. Blocking
    /// work proportional to the body: call it off the event loop.
    ///
    /// @param body        the aggregated request body
    /// @param contentType the Content-Type header value
    public Option<MultipartError.LimitExceeded> partBreach(byte[] body, String contentType) {
        return hasPartLimits() && MultipartParser.isMultipart(contentType)
               ? MultipartScan.framing(contentType).flatMap(framing -> partBreach(MultipartScan.scan(body,
                                                                                                     framing.boundary())))
               : Option.none();
    }

    Option<MultipartError.LimitExceeded> partBreach(MultipartScan.Parts scanned) {
        return scanned.closed()
               ? tooManyParts(scanned).orElse(() -> fileCountBreach(scanned))
                             .orElse(() -> fileSizeBreach(scanned))
                             .orElse(() -> fieldCountBreach(scanned))
                             .orElse(() -> fieldSizeBreach(scanned))
               : Option.none();
    }

    /// More parts than [MultipartParser#DEFAULT_MAX_PARTS] and the declared counts together allow: the slice's own
    /// decoder would refuse the body, so the gate refuses it first, with the same bound.
    private Option<MultipartError.LimitExceeded> tooManyParts(MultipartScan.Parts scanned) {
        var cap = (int) Math.min(Integer.MAX_VALUE, Math.max(MultipartParser.DEFAULT_MAX_PARTS, maxFiles + maxFields));

        return breach(scanned.parts().size() > cap,
                      MultipartError.LimitExceeded.PARTS.apply((long) cap));
    }

    private Option<MultipartError.LimitExceeded> fileCountBreach(MultipartScan.Parts scanned) {
        return breach(count(scanned, true) > limitOrMax(maxFiles), MultipartError.LimitExceeded.FILES.apply(maxFiles));
    }

    private Option<MultipartError.LimitExceeded> fileSizeBreach(MultipartScan.Parts scanned) {
        return breach(largest(scanned, true) > limitOrMax(maxFileBytes),
                      MultipartError.LimitExceeded.FILE_SIZE.apply(maxFileBytes));
    }

    private Option<MultipartError.LimitExceeded> fieldCountBreach(MultipartScan.Parts scanned) {
        return breach(count(scanned, false) > limitOrMax(maxFields),
                      MultipartError.LimitExceeded.FIELDS.apply(maxFields));
    }

    private Option<MultipartError.LimitExceeded> fieldSizeBreach(MultipartScan.Parts scanned) {
        return breach(largest(scanned, false) > limitOrMax(maxFieldBytes),
                      MultipartError.LimitExceeded.FIELD_SIZE.apply(maxFieldBytes));
    }

    private static long count(MultipartScan.Parts scanned, boolean file) {
        return scanned.parts()
                      .stream()
                      .filter(part -> part.file() == file)
                      .count();
    }

    private static long largest(MultipartScan.Parts scanned, boolean file) {
        return scanned.parts()
                      .stream()
                      .filter(part -> part.file() == file)
                      .mapToLong(MultipartScan.Part::size)
                      .max()
                      .orElse(0);
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
