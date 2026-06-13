package org.pragmatica.jbct.doc;

import org.pragmatica.lang.Result;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.pragmatica.lang.Result.success;

/// Extracts the class-level markdown header (`///` block) from Java source content.
///
/// The header is the contiguous run of `///` lines immediately preceding the first top-level
/// type declaration (for `package-info.java`, the run preceding the `package` statement).
/// The leading `/* ... */` license block, `package`/`import` lines, blank lines, and any
/// annotations between the header and the declaration are skipped.
///
/// The `/// ` prefix is stripped from each line (a lone `///` becomes an empty line), yielding
/// clean markdown.
///
/// With the API flag, a `## Public API` section is appended listing lines that look like
/// top-level method signatures: four-space-indented lines starting with `static `, `default `,
/// or `public ` and containing `(`, with a trailing `{` stripped. This is a textual heuristic,
/// not a parse, and may miss or over-report signatures in unusual formatting.
public sealed interface HeaderExtractor {
    record unused() implements HeaderExtractor {}

    Pattern TYPE_DECLARATION = Pattern.compile(
        "^(public\\s+|sealed\\s+|abstract\\s+|final\\s+|non-sealed\\s+)*"
        + "(class|interface|record|enum|@interface)\\b.*");

    Pattern API_SIGNATURE = Pattern.compile(
        "^ {4}(public |static |default )[^=;]*\\(.*");

    /// Extract the header markdown. Works for both regular types (header precedes the type
    /// declaration) and `package-info.java` (header precedes the `package` statement).
    static Result<String> extract(String content, String name) {
        return header(splitLines(content), name);
    }

    /// Extract the header and append a `## Public API` section of detected signatures.
    static Result<String> extractWithApi(String content, String name) {
        return header(splitLines(content), name).map(md -> appendApi(md, splitLines(content)));
    }

    private static Result<String> header(List<String> lines, String name) {
        var block = collectHeaderBlock(lines);
        return block.isEmpty() ? DocError.HeaderMissing.headerMissing(name)
                                          .result()
                              : success(stripPrefixes(block));
    }

    private static List<String> collectHeaderBlock(List<String> lines) {
        var index = skipLicense(lines, 0);
        var block = new ArrayList<String>();
        for (var i = index; i < lines.size(); i++) {
            var trimmed = lines.get(i)
                               .strip();
            if (trimmed.startsWith("///")) {
                block.add(trimmed);
            } else if (endsHeaderBlock(trimmed, block)) {
                return block;
            } else if (resetsBlock(trimmed, block)) {
                block.clear();
            }
        }
        return List.of();
    }

    private static boolean endsHeaderBlock(String trimmed, List<String> block) {
        return !block.isEmpty() && isDeclaration(trimmed);
    }

    private static boolean resetsBlock(String trimmed, List<String> block) {
        return !block.isEmpty() && !trimmed.isEmpty() && !trimmed.startsWith("@");
    }

    private static boolean isDeclaration(String trimmed) {
        return trimmed.startsWith("package ") || TYPE_DECLARATION.matcher(trimmed)
                                                                 .matches();
    }

    private static int skipLicense(List<String> lines, int start) {
        if (start < lines.size() && lines.get(start)
                                         .strip()
                                         .startsWith("/*")) {
            return endOfBlockComment(lines, start) + 1;
        }
        return start;
    }

    private static int endOfBlockComment(List<String> lines, int start) {
        for (var i = start; i < lines.size(); i++) {
            if (lines.get(i)
                     .contains("*/")) {
                return i;
            }
        }
        return lines.size() - 1;
    }

    private static String stripPrefixes(List<String> block) {
        return String.join("\n",
                           block.stream()
                                .map(HeaderExtractor::stripPrefix)
                                .toList());
    }

    private static String stripPrefix(String line) {
        var withoutSlashes = line.substring(3);
        return withoutSlashes.startsWith(" ") ? withoutSlashes.substring(1) : withoutSlashes;
    }

    private static String appendApi(String markdown, List<String> lines) {
        var signatures = collectSignatures(lines);
        return signatures.isEmpty() ? markdown : markdown + "\n\n## Public API\n\n" + renderSignatures(signatures);
    }

    private static List<String> collectSignatures(List<String> lines) {
        return lines.stream()
                    .filter(line -> !line.strip()
                                         .startsWith("///"))
                    .filter(line -> API_SIGNATURE.matcher(line)
                                                 .matches())
                    .map(HeaderExtractor::toSignature)
                    .toList();
    }

    private static String toSignature(String line) {
        var trimmed = line.strip();
        return trimmed.endsWith("{") ? trimmed.substring(0, trimmed.length() - 1)
                                              .strip()
                                    : trimmed;
    }

    private static String renderSignatures(List<String> signatures) {
        return String.join("\n",
                           signatures.stream()
                                     .map(signature -> "- `" + signature + "`")
                                     .toList());
    }

    private static List<String> splitLines(String content) {
        return List.of(content.split("\n", -1));
    }
}
