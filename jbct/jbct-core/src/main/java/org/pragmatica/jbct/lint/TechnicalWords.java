package org.pragmatica.jbct.lint;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;


/// The curated technology and pattern words that JBCT-NAM-06 (type name) and JBCT-NAM-07 (package segment) flag (#2529).
///
/// Words are held lower-case and compared lower-case: a type name matches when any of its camel-case parts is a word,
/// a package segment matches when it is a word. `typeWords` is the list for type names; `packageWords` is the same
/// list plus the words that only mislead in a package name (`persistence`, the retired layer words).
///
/// The `[lint.naming]` keys `add` and `remove` adjust both lists with one entry: a word added is flagged in type names
/// and in package segments, a word removed is no longer flagged in either.
public record TechnicalWords(Set<String> typeWords, Set<String> packageWords) {
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
