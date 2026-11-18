package org.pragmatica.jbct.cli.skeleton;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.pragmatica.jbct.cli.skeleton.SkeletonFixtures.cut;
import static org.pragmatica.jbct.cli.skeleton.SkeletonFixtures.declarations;
import static org.pragmatica.jbct.cli.skeleton.SkeletonFixtures.source;
import static org.assertj.core.api.Assertions.assertThat;


/// The property that makes the coordinates trustworthy: cutting the source at a reported range yields the declaration
/// itself, from its first annotation, modifier or token to its closing brace or semicolon, and the cut reads again
/// as the same declaration. The cut is computed in [SkeletonFixtures] from the line structure alone.
class SkeletonRangePropertyTest {
    private static final Pattern DECLARATION_START = Pattern.compile("^(@|[A-Za-z_$<{])");

    @ParameterizedTest
    @MethodSource("org.pragmatica.jbct.cli.skeleton.SkeletonFixtures#names")
    void everyRange_startsAtTheFirstTokenOfTheDeclaration_notAtItsComments(String fixture) {
        var source = source(fixture);
        var failures = new ArrayList<String>();

        every(source,
              (declaration, text) -> {
                  if (!DECLARATION_START.matcher(text).find()) {
                      failures.add(declaration.label() + " starts with " + first(text));
                  }
              });
        assertThat(failures).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("org.pragmatica.jbct.cli.skeleton.SkeletonFixtures#names")
    void everyRange_endsAtTheClosingBraceOrSemicolon_notAtTheTrailingTrivia(String fixture) {
        var source = source(fixture);
        var failures = new ArrayList<String>();

        every(source,
              (declaration, text) -> {
                  if (!endsProperly(declaration, text)) {
                      failures.add(declaration.label() + " ends with " + text.substring(Math.max(0, text.length() - 12)));
                  }
              });
        assertThat(failures).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("org.pragmatica.jbct.cli.skeleton.SkeletonFixtures#names")
    void everyRange_ofACallable_containsItsName(String fixture) {
        var source = source(fixture);
        var failures = new ArrayList<String>();

        every(source,
              (declaration, text) -> {
                  if (declaration.kind() != Kind.INITIALIZER && !text.contains(declaration.name())) {
                      failures.add(declaration.label() + " is not in " + first(text));
                  }
              });
        assertThat(failures).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("org.pragmatica.jbct.cli.skeleton.SkeletonFixtures#names")
    void everyRange_readsAgainAsTheSameDeclaration(String fixture) {
        var source = source(fixture);
        var failures = new ArrayList<String>();

        every(source, (declaration, text) -> reread(declaration, text).ifPresent(failures::add));
        assertThat(failures).isEmpty();
    }

    private static java.util.Optional<String> reread(Declaration declaration, String text) {
        var found = switch (declaration.kind()) {
            case CLASS, INTERFACE, RECORD, ENUM, ANNOTATION -> declarations(text, false);
            case METHOD, CONSTRUCTOR, INITIALIZER, FIELD -> children(wrap(declaration, text));
            case ENUM_CONSTANT -> List.of(declaration);
        };
        // one field declaration with several variables (`int a, b;`) is one range and one line per variable
        return (found.size() == 1 || declaration.kind() == Kind.FIELD) && found.stream()
                                                                               .anyMatch(each -> each.kind() == declaration.kind() && each.label()
                                                                                                                                          .equals(declaration.label()))
               ? java.util.Optional.empty()
               : java.util.Optional.of(declaration.label() + " re-read as " + found);
    }

    private static List<Declaration> children(String wrapped) {
        return declarations(wrapped, true).getFirst()
                           .children();
    }

    private static String wrap(Declaration declaration, String text) {
        return declaration.signature()
                          .equals("(compact)")
               ? "record " + declaration.name() + "(int x) {\n" + text + "\n}\n"
               : "class W {\n" + text + "\n}\n";
    }

    private static boolean endsProperly(Declaration declaration, String text) {
        return declaration.kind() == Kind.ENUM_CONSTANT
               ? !Character.isWhitespace(text.charAt(text.length() - 1))
               : text.endsWith("}") || text.endsWith(";");
    }

    private static String first(String text) {
        return text.substring(0,
                              Math.min(20, text.length()));
    }

    private static void every(String source, java.util.function.BiConsumer<Declaration, String> check) {
        var all = declarations(source, true).stream().flatMap(SkeletonFixtures::flatten).toList();

        assertThat(all).isNotEmpty();
        all.forEach(declaration -> check.accept(declaration, cut(source, declaration.range())));
    }
}
