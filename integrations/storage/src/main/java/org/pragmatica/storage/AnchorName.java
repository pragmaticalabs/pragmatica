package org.pragmatica.storage;

import java.util.Arrays;
import java.util.regex.Pattern;

import org.pragmatica.lang.Result;


/// The name of a durable anchor ([StorageTier#putAnchor]): one to three segments joined by `/`, each 1 to 128 of
/// `[a-z0-9._-]` and neither `.` nor `..`. Built by the content store, never from caller text, and validated here so
/// no name reaches a tier key or a disk path unchecked.
///
/// A tier stores a name under [#storageSegment], where `/` becomes `~`: a character no segment can hold, so two names
/// never share a storage segment, and no name's segment is a prefix of another's followed by `/`. That is what lets
/// one S3 LIST of `anchors/<segment>/` return only that anchor's versions.
public record AnchorName(String value) {
    private static final Pattern SEGMENT = Pattern.compile("[a-z0-9._-]{1,128}");
    private static final int MAX_SEGMENTS = 3;

    public static Result<AnchorName> anchorName(String value) {
        var segments = value.split("/", -1);

        return segments.length <= MAX_SEGMENTS && Arrays.stream(segments).allMatch(AnchorName::isSegment)
               ? Result.success(new AnchorName(value))
               : AnchorError.InvalidAnchorName.invalidAnchorName(value).result();
    }

    /// The single path segment this name is stored under on a tier.
    String storageSegment() {
        return value.replace('/', '~');
    }

    private static boolean isSegment(String segment) {
        return SEGMENT.matcher(segment).matches()
               && !segment.equals(".")
               && !segment.equals("..");
    }
}
