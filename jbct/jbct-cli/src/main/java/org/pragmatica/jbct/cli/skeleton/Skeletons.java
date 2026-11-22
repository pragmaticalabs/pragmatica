package org.pragmatica.jbct.cli.skeleton;

import org.pragmatica.jbct.parser.Java25Parser;
import org.pragmatica.jbct.parser.LineIndex;
import org.pragmatica.lang.Result;


/// Reads the skeleton of a Java source: its types and their members, each with the exact range it occupies.
///
/// A pure function of the source text, built on the JBCT parser tree. What is listed: classes, interfaces, records,
/// enums and annotation types, at any nesting depth, with their methods, constructors and initializers; with
/// `fields` also fields and enum constants. What is NOT listed: anonymous classes (an enum constant's body is one),
/// local classes and lambdas, which lie inside the range of the method that holds them.
///
/// **Range.** From the first annotation or modifier of the declaration to its last character, the closing brace or
/// semicolon, inclusive. The parser's node spans reach into the trivia after a declaration and, for a class member,
/// exclude its annotations and modifiers, so the range is computed from the first and the last real token of the
/// member wrapper instead. A doc comment is outside the range; its first row is reported as `docRow`.
public sealed interface Skeletons {
    /// The skeleton of `source`, or the position and text of its first parse error.
    static Result<Skeleton> skeleton(String source, boolean fields) {
        return new Java25Parser().parse(source)
                                 .mapError(cause -> SourceNotParsed.sourceNotParsed(cause, source))
                                 .map(root -> Skeleton.skeleton(SkeletonReader.skeletonReader(source,
                                                                                              root.cst().tokens(),
                                                                                              new LineIndex(source),
                                                                                              fields)
                                                                              .declarations(root)));
    }

    record unused() implements Skeletons {}
}
