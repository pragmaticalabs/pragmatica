package conduit.domain;

import java.util.Locale;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.pragmatica.aether.slice.mapping.ValueMapping;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import static org.pragmatica.lang.Option.option;


/// An article's permanent URL key (B-22, B-24): `base + "-" + suffix`, generated once at creation, never changed.
///
/// `base` is the title lowercased with `Locale.ROOT`, every maximal run outside `[a-z0-9]` replaced by one `-`,
/// leading and trailing `-` removed, cut to 64 characters, trailing `-` removed again, `article` if empty. `suffix` is
/// 8 characters drawn uniformly from `[a-z0-9]`, so duplicate titles get distinct slugs with one INSERT and no
/// existence query.
public record Slug(String value) {
    private static final Pattern NON_ALNUM_RUN = Pattern.compile("[^a-z0-9]+");
    private static final Pattern EDGE_DASHES = Pattern.compile("^-+|-+$");
    private static final Pattern TRAILING_DASHES = Pattern.compile("-+$");
    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final int MAX_BASE = 64;
    private static final int SUFFIX_LENGTH = 8;
    private static final String EMPTY_BASE = "article";

    /// Infallible by construction: the base falls back to `article` when the title leaves nothing, and the suffix is
    /// `SUFFIX_LENGTH` characters, so the result is never blank (the minimal invariant `slug(String)` checks).
    public static Slug generate(Title title, RandomGenerator random) {
        return new Slug(base(title.value()) + "-" + suffix(random));
    }

    /// A slug taken from the path: empty when blank. Any non-blank text may name an article; the lookup decides whether
    /// one exists, and the caller maps empty to `article: not found`.
    public static Option<Slug> inPath(String raw) {
        return slug(raw).option();
    }

    static String base(String title) {
        var dashed = NON_ALNUM_RUN.matcher(title.toLowerCase(Locale.ROOT)).replaceAll("-");
        var trimmed = EDGE_DASHES.matcher(dashed).replaceAll("");
        var cut = trimmed.length() > MAX_BASE
                  ? trimmed.substring(0, MAX_BASE)
                  : trimmed;
        var base = TRAILING_DASHES.matcher(cut).replaceAll("");

        return base.isEmpty()
               ? EMPTY_BASE
               : base;
    }

    private static String suffix(RandomGenerator random) {
        return random.ints(SUFFIX_LENGTH,
                           0,
                           ALPHABET.length())
                     .mapToObj(ALPHABET::charAt)
                     .map(String::valueOf)
                     .collect(Collectors.joining());
    }

    /// The column representation (`ValueMapping`): `value()` out, `stored` back in.
    public static ValueMapping<Slug, String> valueMapping() {
        return ValueMapping.of(Slug::value, Slug::stored);
    }

    /// The minimal stable invariant: non-blank. Generated slugs are shaped by `generate`; a stored one may predate
    /// today's shape, so the shape is not checked here.
    public static Result<Slug> slug(String raw) {
        return option(raw).filter(Verify.Is::notBlank)
                     .toResult(Invalid.BLANK)
                     .map(Slug::new);
    }

    /// Rebuilds the value from its column through the same minimal check.
    public static Result<Slug> stored(String raw) {
        return slug(raw);
    }

    /// The value breaks the minimal invariant: for a stored value that is corrupt data, a 500, never a client error.
    public enum Invalid implements Cause {
        BLANK("Slug is blank");
        private final String message;
        Invalid(String message) {
            this.message = message;
        }
        @Override
        public String message() {
            return message;
        }
    }
}
