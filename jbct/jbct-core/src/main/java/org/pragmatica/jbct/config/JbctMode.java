package org.pragmatica.jbct.config;

import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;


/// What kind of code a source root holds, and so which JBCT checks apply to it (#2525).
///
/// Written `[lint] mode = "business" | "infrastructure" | "format-only" | "disabled"` in `jbct.toml`, and accepted as the
/// same strings by the CLI `--mode` and the Maven user property `-Djbct.mode`. Unset, a root is [#BUSINESS].
///
/// `business` runs every rule, `infrastructure` the code-level rules only, `format-only` no rule but keeps formatting,
/// `disabled` neither. Until the lint and format entry points honour the last two, a load that names them is refused
/// as not yet supported ([#requireSupported()]), so no build accepts a mode it does not honour.
public enum JbctMode {
    BUSINESS("business"),
    INFRASTRUCTURE("infrastructure"),
    FORMAT_ONLY("format-only"),
    DISABLED("disabled");
    /// The accepted tokens, in the wording a refusal shows.
    public static final String ALLOWED = Stream.of(values())
                                               .map(JbctMode::token)
                                               .collect(Collectors.joining(", ", "one of: ", ""));
    /// The mode of a source root whose configuration does not set one.
    public static final JbctMode DEFAULT = BUSINESS;
    private final String token;
    JbctMode(String token) {
        this.token = token;
    }
    /// The token written in `jbct.toml` and accepted by `--mode` / `-Djbct.mode`.
    public String token() {
        return token;
    }
    /// Parse a mode token; an unrecognised token yields [Option#none()], which the TOML layer parser records as a
    /// mismatch naming the key, the value and [#ALLOWED].
    public static Option<JbctMode> fromToken(String raw) {
        var normalized = raw.toLowerCase(Locale.ROOT);

        return Option.from(Stream.of(values())
                                 .filter(mode -> mode.token.equals(normalized))
                                 .findFirst());
    }
    /// Parse an explicit override (`--mode`, `-Djbct.mode`): an unknown token is [ConfigError.ModeUnknown], a known
    /// one that is not yet honoured is [ConfigError.ModeNotYetSupported].
    public static Result<JbctMode> jbctMode(String raw) {
        return fromToken(raw).toResult(ConfigError.ModeUnknown.modeUnknown(raw))
                        .flatMap(JbctMode::requireSupported);
    }
    /// This mode when the entry points honour it; `format-only` and `disabled` are refused until they do.
    public Result<JbctMode> requireSupported() {
        return isSupported()
               ? Result.success(this)
               : ConfigError.ModeNotYetSupported.modeNotYetSupported(this)
                            .result();
    }
    private boolean isSupported() {
        return this == BUSINESS || this == INFRASTRUCTURE;
    }
}
