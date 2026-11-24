// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionStateValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionStateValue.Election;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionStateValue.PartitionState;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.testlogging.SourceWalk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionStateValue.streamPartitionStateValue;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// #3303: the partition-state row's dormant last component `activationGeneration` is carried by every copy method (a write
/// derived from the committed row cannot drop it), no factory or constructor overload defaults it, and no main source writes a
/// non-zero value yet.
///
/// The tripwire scans AetherValue.java too, minus the row's factory and four copy methods (skipped by name; their bodies are
/// covered by the carry arms, so a literal injected inside one of them is NOT seen by the scan). It does not see method references
/// or reflection. A read of `activationGeneration()` counts as a carry only when unqualified or on `this`.
///
/// The carry arms run on a row whose component is 7, built here: a carry test on 0 cannot tell a carry from a default.
class StreamPartitionStateValueTest {
    private static final long CARRIED = 7L;
    private static final NodeId NODE_A = NodeId.nodeId("node-a").unwrap();
    private static final NodeId NODE_B = NodeId.nodeId("node-b").unwrap();
    private static final Election ELECTION = Election.election(Epoch.epoch(2L, 6L, 1L), NODE_B, false, List.of(NODE_A, NODE_B));
    private static final String TRIPWIRE = "SP3b-1 writes this in S4: delete this half";
    private static final Pattern WRITE_CALL = Pattern.compile("\\bstreamPartitionStateValue\\(|\\bnew\\s+StreamPartitionStateValue\\(");
    /// The carry sites: the unqualified accessor, or `this.`'s. A read through any other receiver (a Put's, another row's) is
    /// not a carry and is flagged.
    private static final Pattern CARRY_READ = Pattern.compile("(?:this\\s*\\.\\s*)?activationGeneration\\(\\s*\\)");
    /// The methods of the row's record inside AetherValue.java that build it from its own components: the factory and the four
    /// copy methods. Their bodies are carried by the arms above, so the scan of AetherValue.java skips them by name.
    private static final Set<String> OWN_BUILDERS = Set.of("streamPartitionStateValue", "withElection", "closed", "released", "newLife");

    // ---- (2) carry, one arm per copy method ----

    @Test
    void withElection_set_carriesActivationGeneration() {
        var row = openRow(none());

        var result = row.withElection(some(ELECTION));

        assertThat(result.activationGeneration()).as("activationGeneration after withElection(some)").isEqualTo(CARRIED);
        assertThat(result).isEqualTo(streamPartitionStateValue(3L,
                                                               2L,
                                                               100L,
                                                               PartitionState.OPEN,
                                                               10L,
                                                               none(),
                                                               some(ELECTION),
                                                               List.of(1),
                                                               List.of(2, 3),
                                                               CARRIED));
    }

    @Test
    void withElection_cleared_carriesActivationGeneration() {
        var row = openRow(some(ELECTION));

        var result = row.withElection(none());

        assertThat(result.activationGeneration()).as("activationGeneration after withElection(none)").isEqualTo(CARRIED);
        assertThat(result).isEqualTo(openRow(none()));
    }

    @Test
    void closed_carriesActivationGenerationAndElection() {
        var row = openRow(some(ELECTION));

        var result = row.closed(900L);

        assertThat(result.activationGeneration()).as("activationGeneration after closed").isEqualTo(CARRIED);
        assertThat(result).isEqualTo(streamPartitionStateValue(3L,
                                                               2L,
                                                               100L,
                                                               PartitionState.CLOSED,
                                                               10L,
                                                               some(900L),
                                                               some(ELECTION),
                                                               List.of(1),
                                                               List.of(2, 3),
                                                               CARRIED));
    }

    @Test
    void released_carriesActivationGenerationAndEveryOtherComponent() {
        var row = openRow(some(ELECTION)).closed(900L);

        var result = row.released();

        assertThat(result.activationGeneration()).as("activationGeneration after released").isEqualTo(CARRIED);
        assertThat(result).isEqualTo(streamPartitionStateValue(3L,
                                                               2L,
                                                               100L,
                                                               PartitionState.RELEASED,
                                                               10L,
                                                               some(900L),
                                                               some(ELECTION),
                                                               List.of(1),
                                                               List.of(2, 3),
                                                               CARRIED));
    }

    @Test
    void newLife_onALiveElection_bumpsGenerationClearsElectionAndCarriesActivationGeneration() {
        var row = openRow(some(ELECTION));

        var result = row.newLife(500L);

        assertThat(result.activationGeneration()).as("activationGeneration after newLife").isEqualTo(CARRIED);
        assertThat(result.generation()).as("generation + 1").isEqualTo(3L);
        assertThat(result.lifeStart()).as("the new lifeStart").isEqualTo(500L);
        assertThat(result.election()).as("election cleared").isEqualTo(none());
        assertThat(result).isEqualTo(streamPartitionStateValue(3L,
                                                               3L,
                                                               500L,
                                                               PartitionState.OPEN,
                                                               10L,
                                                               none(),
                                                               none(),
                                                               List.of(1),
                                                               List.of(2, 3),
                                                               CARRIED));
    }

    @Test
    void newLife_onAClosedRowWithLastOffset_keepsStateLastOffsetParentsAndChildren() {
        var closed = openRow(some(ELECTION)).closed(900L);

        var result = closed.newLife(500L);

        assertThat(result.state()).as("state kept (a new life keeps state)").isEqualTo(PartitionState.CLOSED);
        assertThat(result.lastOffset()).as("lastOffset kept").isEqualTo(some(900L));
        assertThat(result.parents()).as("parents kept").isEqualTo(List.of(1));
        assertThat(result.children()).as("children kept").isEqualTo(List.of(2, 3));
        assertThat(result.generation()).as("generation + 1").isEqualTo(3L);
        assertThat(result.lifeStart()).as("the new lifeStart").isEqualTo(500L);
        assertThat(result.election()).as("election cleared").isEqualTo(none());
    }

    @Test
    void withElection_onAClosedRowWithLastOffset_keepsStateAndLastOffset() {
        var closed = openRow(none()).closed(900L);

        var set = closed.withElection(some(ELECTION));
        var cleared = set.withElection(none());

        assertThat(set.state()).as("state after withElection(some)").isEqualTo(PartitionState.CLOSED);
        assertThat(set.lastOffset()).as("lastOffset after withElection(some)").isEqualTo(some(900L));
        assertThat(set.election()).as("election set").isEqualTo(some(ELECTION));
        assertThat(cleared.state()).as("state after withElection(none)").isEqualTo(PartitionState.CLOSED);
        assertThat(cleared.lastOffset()).as("lastOffset after withElection(none)").isEqualTo(some(900L));
        assertThat(cleared.election()).as("election cleared").isEqualTo(none());
    }

    // ---- factory and constructor pass-through, against the INPUTS (not a second factory call) ----

    @Test
    void factory_passesEveryComponentThrough_distinctInputsComparedToAccessors() {
        var election = Election.election(Epoch.epoch(4L, 8L, 2L), NODE_A, true, List.of(NODE_B, NODE_A));
        var parents = List.of(4, 5);
        var children = List.of(6, 7, 8);

        var row = streamPartitionStateValue(11L, 12L, 13L, PartitionState.CLOSED, 14L, some(15L), some(election), parents, children, 9L);

        assertThat(row.incarnation()).as("incarnation").isEqualTo(11L);
        assertThat(row.generation()).as("generation").isEqualTo(12L);
        assertThat(row.lifeStart()).as("lifeStart").isEqualTo(13L);
        assertThat(row.state()).as("state").isEqualTo(PartitionState.CLOSED);
        assertThat(row.firstOffset()).as("firstOffset").isEqualTo(14L);
        assertThat(row.lastOffset()).as("lastOffset").isEqualTo(some(15L));
        assertThat(row.election()).as("election").isEqualTo(some(election));
        assertThat(row.parents()).as("parents, not swapped with children").isEqualTo(parents);
        assertThat(row.children()).as("children, not swapped with parents").isEqualTo(children);
        assertThat(row.activationGeneration()).as("activationGeneration").isEqualTo(9L);
    }

    @Test
    void electionFactory_passesEveryComponentThrough_inputsComparedToAccessors() {
        var epoch = Epoch.epoch(4L, 8L, 2L);
        var isr = List.of(NODE_B, NODE_A);

        var election = Election.election(epoch, NODE_A, true, isr);

        assertThat(election.epoch()).as("epoch").isEqualTo(epoch);
        assertThat(election.node()).as("node").isEqualTo(NODE_A);
        assertThat(election.entityLoss()).as("entityLoss, not negated").isTrue();
        assertThat(election.isr()).as("isr, not dropped").isEqualTo(isr);
        assertThat(Election.election(epoch, NODE_A, false, isr).entityLoss()).as("entityLoss false").isFalse();
    }

    @Test
    void election_copiesIsr_laterChangesToTheInputListAreNotSeen() {
        var isr = new ArrayList<>(List.of(NODE_A));

        var election = Election.election(Epoch.epoch(4L, 8L, 2L), NODE_A, false, isr);
        isr.add(NODE_B);

        assertThat(election.isr()).as("isr after the input list grew").isEqualTo(List.of(NODE_A));
        assertThatThrownBy(() -> election.isr().add(NODE_B)).as("isr is unmodifiable").isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void value_copiesParentsAndChildren_laterChangesToTheInputListsAreNotSeen() {
        var parents = new ArrayList<>(List.of(1));
        var children = new ArrayList<>(List.of(2, 3));

        var row = streamPartitionStateValue(3L, 2L, 100L, PartitionState.OPEN, 10L, none(), none(), parents, children, CARRIED);
        parents.add(9);
        children.add(9);

        assertThat(row.parents()).as("parents after the input list grew").isEqualTo(List.of(1));
        assertThat(row.children()).as("children after the input list grew").isEqualTo(List.of(2, 3));
        assertThatThrownBy(() -> row.parents().add(9)).as("parents unmodifiable").isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> row.children().add(9)).as("children unmodifiable").isInstanceOf(UnsupportedOperationException.class);
    }

    // ---- no defaulting overload ----

    @Test
    void value_hasExactlyOneFactoryAndOnePublicConstructor_eachWithTenParameters() {
        var factories = Stream.of(StreamPartitionStateValue.class.getDeclaredMethods())
                              .filter(method -> Modifier.isStatic(method.getModifiers()))
                              .filter(method -> method.getReturnType() == StreamPartitionStateValue.class)
                              .toList();
        var constructors = StreamPartitionStateValue.class.getConstructors();

        assertThat(factories).as("static factories of the value").hasSize(1);
        assertThat(factories.getFirst().getParameterCount()).as("factory parameters").isEqualTo(10);
        assertThat(constructors).as("public constructors of the value").hasSize(1);
        assertThat(constructors[0].getParameterCount()).as("constructor parameters").isEqualTo(10);
    }

    // ---- (3) tripwire: no main source writes a non-zero component ----

    @Test
    void mainSources_writeNoNonZeroActivationGeneration() {
        var root = repositoryRoot();
        var files = mainSources(root);
        var calls = files.stream()
                         .flatMap(file -> writeCalls(scanned(file)).stream().map(call -> file + ": " + call))
                         .toList();
        var offenders = calls.stream()
                             .filter(call -> !carriesOrPassesZero(call))
                             .toList();

        System.out.printf("activationGeneration tripwire: root %s, %d main source files, %d calls%n", root, files.size(), calls.size());

        assertThat(files).as("main source files scanned under %s", root).isNotEmpty();
        assertThat(offenders).as(TRIPWIRE).isEmpty();
        assertThat(calls).as("writes of the row outside the row's own factory and copy methods (none exist yet; the first writer updates this count). "
                             + TRIPWIRE)
                         .isEmpty();
    }

    @Test
    void tripwireScan_flagsANonZeroLastArgument_andPassesZeroOrACarry() {
        var fixture = String.join("\n",
                                  "var a = streamPartitionStateValue(1L, 1L, 0L, OPEN, 0L, none(), none(), List.of(1, 2), List.of(), 1);",
                                  "var b = new StreamPartitionStateValue(1L, 1L, 0L, OPEN, 0L, none(), none(), List.of(), List.of(), 0);",
                                  "var c = streamPartitionStateValue(1L, 1L, 0L, OPEN, 0L, none(), none(), List.of(), List.of(), activationGeneration());",
                                  "var d = streamPartitionStateValue(1L, 1L, 0L, OPEN, 0L, none(), none(), List.of(), List.of(), 0L);",
                                  "var e = streamPartitionStateValue(1L, 1L, 0L, OPEN, 0L, none(), none(), List.of(), List.of(), put.activationGeneration());",
                                  "var f = streamPartitionStateValue(1L, 1L, 0L, OPEN, 0L, none(), none(), List.of(), List.of(), this.activationGeneration());");

        var calls = writeCalls(fixture);

        assertThat(calls).as("calls found in the fixture").hasSize(6);
        assertThat(calls.stream().filter(call -> !carriesOrPassesZero(call)).toList())
            .as("the call ending in 1 and the call carrying another receiver's component (a Put's) are flagged")
            .containsExactly(calls.get(0), calls.get(4));
    }

    @Test
    void ownBuildersScan_skipsOnlyTheNamedMethods_andSeesAnInjectedWriterAnywhereElse() {
        var fixture = String.join("\n",
                                  "public static StreamPartitionStateValue streamPartitionStateValue(long a, long b) {",
                                  "    return new StreamPartitionStateValue(a, b, activationGeneration);",
                                  "}",
                                  "public StreamPartitionStateValue closed(long lastOffset) {",
                                  "    if (lastOffset > 0) { return new StreamPartitionStateValue(a, b, activationGeneration()); }",
                                  "    return this;",
                                  "}",
                                  "public StreamPartitionStateValue rogue() {",
                                  "    return streamPartitionStateValue(a, b, 5L);",
                                  "}",
                                  "public StreamPartitionStateValue released() {",
                                  "    return new StreamPartitionStateValue(a, b, activationGeneration());",
                                  "}",
                                  "public StreamPartitionStateValue rogueToo() {",
                                  "    return new StreamPartitionStateValue(a, b, 6);",
                                  "}");

        var remaining = writeCalls(withoutMethods(fixture, OWN_BUILDERS));

        assertThat(remaining).as("only the writers outside the named methods remain")
                             .containsExactly("streamPartitionStateValue(a, b, 5L)", "new StreamPartitionStateValue(a, b, 6)");
    }

    private static StreamPartitionStateValue openRow(Option<Election> election) {
        return streamPartitionStateValue(3L,
                                         2L,
                                         100L,
                                         PartitionState.OPEN,
                                         10L,
                                         none(),
                                         election,
                                         List.of(1),
                                         List.of(2, 3),
                                         CARRIED);
    }

    /// The text the tripwire scans in `file`: AetherValue.java without the row's own factory and copy methods (those are covered by
    /// the carry arms), every other main source whole.
    private static String scanned(Path file) {
        var text = read(file);

        return file.endsWith(Path.of("kvstore", "AetherValue.java")) ? withoutMethods(text, OWN_BUILDERS) : text;
    }

    /// `source` with every method that returns `StreamPartitionStateValue` and is named in `names` removed, signature through
    /// closing brace.
    private static String withoutMethods(String source, Set<String> names) {
        var matcher = Pattern.compile("\\bStreamPartitionStateValue\\s+(?:" + String.join("|", names) + ")\\s*\\(").matcher(source);
        var result = new StringBuilder();
        var from = 0;

        while (matcher.find(from)) {
            var body = source.indexOf('{', closing(source, matcher.end() - 1, '(', ')'));

            result.append(source, from, matcher.start());
            from = closing(source, body, '{', '}') + 1;
        }

        return result.append(source.substring(from)).toString();
    }

    private static boolean carriesOrPassesZero(String call) {
        var last = lastArgument(call);

        return last.equals("0") || last.equals("0L") || CARRY_READ.matcher(last).matches();
    }

    /// Every write call in `source`, as the text from the call's name through its closing parenthesis.
    private static List<String> writeCalls(String source) {
        var matcher = WRITE_CALL.matcher(source);
        var calls = new ArrayList<String>();

        while (matcher.find()) {
            calls.add(source.substring(matcher.start(), closing(source, matcher.end() - 1, '(', ')') + 1));
        }

        return calls;
    }

    private static int closing(String source, int open, char opening, char closing) {
        var depth = 0;

        for (var i = open; i < source.length(); i++) {
            var c = source.charAt(i);

            if (c == opening) {
                depth++;
            } else if (c == closing && --depth == 0) {
                return i;
            }
        }

        return source.length() - 1;
    }

    /// The last top-level argument of `call`, trimmed.
    private static String lastArgument(String call) {
        var open = call.indexOf('(');
        var depth = 0;
        var start = open + 1;

        for (var i = open + 1; i < call.length() - 1; i++) {
            var c = call.charAt(i);

            if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
            } else if (c == ',' && depth == 0) {
                start = i + 1;
            }
        }

        return call.substring(start, call.length() - 1).trim();
    }

    private static Path repositoryRoot() {
        var dir = Path.of("").toAbsolutePath();

        while (dir != null && !(Files.isDirectory(dir.resolve("aether")) && Files.isDirectory(dir.resolve("core"))
                                && Files.isRegularFile(dir.resolve("pom.xml")))) {
            dir = dir.getParent();
        }

        assertThat(dir).as("repository root above %s", Path.of("").toAbsolutePath()).isNotNull();

        return dir;
    }

    private static List<Path> mainSources(Path root) {
        var files = new ArrayList<Path>();

        try {
            for (var file : SourceWalk.files(root)) {
                if (file.toString().endsWith(".java") && isMainSource(root.relativize(file))) {
                    files.add(file);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        return files;
    }

    private static boolean isMainSource(Path relative) {
        var text = relative.toString().replace('\\', '/');

        return text.startsWith("src/main/java/") || text.contains("/src/main/java/");
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
