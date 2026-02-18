package org.pragmatica.aether.forge.load.pattern;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.Result.unitResult;

/// Processes templates containing pattern placeholders like `${type:args`}.
///
/// A template can contain multiple patterns intermixed with literal text.
/// Example: `{"sku": "${random:SKU-#####`", "qty": ${range:1-10}}}
public final class TemplateProcessor {
    private static final Pattern PATTERN_REGEX = Pattern.compile("(\\$\\{[a-z]+(?::[^}]*)?})");

    private final String template;
    private final List<Segment> segments;

    /// A segment is either literal text or a pattern generator.
    private sealed interface Segment {
        String render();

        record Literal(String text) implements Segment {
            static Result<Literal> literal(String text) {
                return success(new Literal(text));
            }

            @Override
            public String render() {
                return text;
            }
        }

        record Generator(PatternGenerator generator) implements Segment {
            static Result<Generator> generator(PatternGenerator generator) {
                return success(new Generator(generator));
            }

            @Override
            public String render() {
                return generator.generate();
            }
        }

        record unused() implements Segment {
            @Override
            public String render() {
                return "";
            }
        }
    }

    private TemplateProcessor(String template, List<Segment> segments) {
        this.template = template;
        this.segments = segments;
    }

    /// Compiles a template string into a processor.
    ///
    /// Parses all patterns at compile time and validates them.
    ///
    /// @param template the template string
    /// @return Result containing the processor or an error
    public static Result<TemplateProcessor> compile(String template) {
        return option(template).filter(s -> !s.isEmpty())
                     .map(TemplateProcessor::buildAndWrap)
                     .or(success(new TemplateProcessor("",
                                                       List.of())));
    }

    private static Result<TemplateProcessor> buildAndWrap(String t) {
        return buildSegments(t).map(segs -> new TemplateProcessor(t, segs));
    }

    private static Result<List<Segment>> buildSegments(String template) {
        var matches = PATTERN_REGEX.matcher(template)
                                   .results()
                                   .map(m -> new MatchInfo(m.start(),
                                                           m.end(),
                                                           m.group(1)))
                                   .toList();
        return toSegments(template, matches);
    }

    private record MatchInfo(int start, int end, String pattern) {
        static Result<MatchInfo> matchInfo(int start, int end, String pattern) {
            return success(new MatchInfo(start, end, pattern));
        }
    }

    private static Result<List<Segment>> toSegments(String template, List<MatchInfo> matches) {
        return matches.isEmpty()
               ? success(literalOnlySegments(template))
               : compileGenerators(template, matches);
    }

    private static Result<List<Segment>> compileGenerators(String template, List<MatchInfo> matches) {
        var generatorResults = matches.stream()
                                      .map(m -> PatternParser.parse(m.pattern()))
                                      .toList();
        return Result.allOf(generatorResults)
                     .map(gens -> assembleSegments(template, matches, gens));
    }

    private static List<Segment> literalOnlySegments(String template) {
        return template.isEmpty()
               ? List.of()
               : List.of(new Segment.Literal(template));
    }

    private static List<Segment> assembleSegments(String template,
                                                  List<MatchInfo> matches,
                                                  List<PatternGenerator> generators) {
        var segments = new ArrayList<Segment>();
        var lastEnd = new int[]{0};
        IntStream.range(0,
                        matches.size())
                 .forEach(i -> addSegment(segments,
                                          template,
                                          matches.get(i),
                                          generators.get(i),
                                          lastEnd));
        addTrailingLiteral(segments, template, lastEnd[0]);
        return List.copyOf(segments);
    }

    private static void addSegment(List<Segment> segments,
                                   String template,
                                   MatchInfo match,
                                   PatternGenerator generator,
                                   int[] lastEnd) {
        if (match.start() > lastEnd[0]) {
            segments.add(new Segment.Literal(template.substring(lastEnd[0], match.start())));
        }
        segments.add(new Segment.Generator(generator));
        lastEnd[0] = match.end();
    }

    private static void addTrailingLiteral(List<Segment> segments, String template, int lastEnd) {
        if (lastEnd < template.length()) {
            segments.add(new Segment.Literal(template.substring(lastEnd)));
        }
    }

    /// Processes the template, replacing all patterns with generated values.
    ///
    /// @return the processed string
    public String process() {
        return segments.isEmpty()
               ? template
               : renderSegments();
    }

    private String renderSegments() {
        return segments.stream()
                       .map(Segment::render)
                       .collect(StringBuilder::new, StringBuilder::append, StringBuilder::append)
                       .toString();
    }

    /// Returns the original template string.
    public String template() {
        return template;
    }

    /// Returns true if this template contains any patterns.
    public boolean hasPatterns() {
        return segments.stream()
                       .anyMatch(s -> s instanceof Segment.Generator);
    }

    /// Returns the number of pattern generators in this template.
    public int patternCount() {
        return (int) segments.stream()
                            .filter(s -> s instanceof Segment.Generator)
                            .count();
    }

    /// Resets all sequence generators in this template.
    public Result<Unit> resetSequences() {
        var sequenceGenerators = segments.stream()
                                         .filter(TemplateProcessor::isSequenceSegment)
                                         .map(TemplateProcessor::toSequenceGenerator);
        sequenceGenerators.forEach(SequenceGenerator::reset);
        return unitResult();
    }

    private static boolean isSequenceSegment(Segment s) {
        return s instanceof Segment.Generator gen && gen.generator() instanceof SequenceGenerator;
    }

    private static SequenceGenerator toSequenceGenerator(Segment s) {
        return (SequenceGenerator)((Segment.Generator) s).generator();
    }
}
