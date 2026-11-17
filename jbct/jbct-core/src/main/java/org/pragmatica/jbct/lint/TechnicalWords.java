package org.pragmatica.jbct.lint;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.lang.Option;


/// The curated technology and pattern words that JBCT-NAM-06 (type name) and JBCT-NAM-07 (package segment) flag (#2529).
///
/// Words are held lower-case and compared lower-case: a type name matches when any of its camel-case parts is a word,
/// a package segment matches when it is a word. `typeWords` is the list for type names; `packageWords` is the same
/// list plus the words that only mislead in a package name (`persistence`, the retired layer words).
///
/// The `[lint.naming]` keys `add` and `remove` adjust both lists with one entry: a word added is flagged in type names
/// and in package segments, a word removed is no longer flagged in either.
///
/// A configured word is accepted only when [#tokens(String)], the tokenizer NAM-06 applies to type names, keeps it as
/// exactly one token (`PostgreSQL` splits into `Postgre` and `SQL`, so it could never equal a token and is refused):
/// see [#problem(String)].
public record TechnicalWords(Set<String> typeWords, Set<String> packageWords) {
    private static final Pattern CAMEL_BOUNDARY = Pattern.compile("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])");
    private static final Pattern WORD_SHAPE = Pattern.compile("[A-Za-z][A-Za-z0-9]*");

    private static final List<String> TYPE_DEFAULTS = List.of("repository",
                                                              "dao",
                                                              "controller",
                                                              "adapter",
                                                              "gateway",
                                                              "client",
                                                              "impl",
                                                              "service",
                                                              "manager",
                                                              "helper",
                                                              "util",
                                                              "jooq",
                                                              "jpa",
                                                              "jdbc",
                                                              "postgres",
                                                              "http",
                                                              "rest",
                                                              "kafka");

    private static final List<String> PACKAGE_ONLY_DEFAULTS = List.of("internal",
                                                                      "utils",
                                                                      "common",
                                                                      "persistence",
                                                                      "usecase",
                                                                      "domain",
                                                                      "application",
                                                                      "infra",
                                                                      "integration",
                                                                      "bootstrap",
                                                                      "config");

    public TechnicalWords {
        typeWords = Set.copyOf(typeWords);
        packageWords = Set.copyOf(packageWords);
    }

    /// The camel-case tokens of a type name: the split JBCT-NAM-06 compares to the words, and the one every configured
    /// word must survive whole.
    public static List<String> tokens(String name) {
        return CAMEL_BOUNDARY.splitAsStream(name).toList();
    }

    /// Why a configured word cannot be used, empty when it can: a word is made of letters and digits starting with a
    /// letter and stays exactly one token under [#tokens(String)], otherwise it could never match.
    public static Option<String> problem(String word) {
        return WORD_SHAPE.matcher(word).matches()
               ? splitProblem(word)
               : Option.some("'" + word + "' is not a word made of letters and digits starting with a letter");
    }

    private static Option<String> splitProblem(String word) {
        var tokens = tokens(word);

        return tokens.size() == 1
               ? Option.none()
               : Option.some("'" + word
                            + "' splits into the tokens " + String.join(", ", tokens)
                            + " and so can never equal one token of a type name; configure a single-token word");
    }

    /// The built-in word lists.
    public static final TechnicalWords DEFAULT = technicalWords(List.of(), List.of());

    /// The built-in lists extended by `add` and then narrowed by `remove` (remove wins over add and over the defaults).
    public static TechnicalWords technicalWords(List<String> add, List<String> remove) {
        return new TechnicalWords(adjusted(TYPE_DEFAULTS.stream(), add, remove),
                                  adjusted(Stream.concat(TYPE_DEFAULTS.stream(), PACKAGE_ONLY_DEFAULTS.stream()),
                                           add,
                                           remove));
    }

    private static Set<String> adjusted(Stream<String> defaults, List<String> add, List<String> remove) {
        var removed = lowerCased(remove);

        return Stream.concat(defaults,
                             add.stream())
                     .map(TechnicalWords::lowerCase)
                     .filter(word -> !removed.contains(word))
                     .collect(Collectors.toUnmodifiableSet());
    }

    private static Set<String> lowerCased(List<String> words) {
        return words.stream()
                    .map(TechnicalWords::lowerCase)
                    .collect(Collectors.toUnmodifiableSet());
    }

    private static String lowerCase(String word) {
        return word.toLowerCase(Locale.ROOT);
    }
}
