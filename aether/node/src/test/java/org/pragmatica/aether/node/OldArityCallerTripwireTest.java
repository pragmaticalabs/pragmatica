// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeElement;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.constantpool.LoadableConstantEntry;
import java.lang.classfile.constantpool.MethodHandleEntry;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.invoke.MethodHandleInfo;
import java.lang.invoke.MethodType;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherValue.ConsumerAssignmentValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ConsumerAssignmentValue.AssignmentToken;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;

import static org.assertj.core.api.Assertions.assertThat;

/// #3334 S1a-1a: the callers of the OLD-arity shim factories (`AssignmentToken.assignmentToken(NodeId, Epoch)` and
/// `ConsumerAssignmentValue.consumerAssignmentValue(NodeId, Epoch, long, HlcTimestamp)`), read from COMPILED code.
///
/// The scan reads this module's class files and lists every `invokestatic` and every method-handle constant (`Type::factory`
/// loaded by `ldc` or passed to a bootstrap method) whose owner, name and descriptor are the old shim's. Source text does not
/// matter: a qualified call, a static import, an unqualified call and a method reference all compile to the same reference.
/// Two EXACT maps are pinned, production and test, each as `class#method` to call count (a lambda counts under its enclosing
/// method, so renumbering lambdas does not move the pin). A new caller in either, or a caller that disappeared, goes red.
///
/// SCOPE, two readers, whole repository: this module's `target/classes` and `target/test-classes` are read from compiled code
/// (above). Every other module is read from SOURCE, because a module downstream of this one (ember) is not compiled when this
/// test runs and aether-slice's classes are a dependency, not a scan root. The source scan matches the factory NAME, so a
/// qualified call, a static import and an unqualified call are all seen, and counts calls of the old argument count (2 and 4);
/// a method reference is counted whatever its arity, since source cannot tell which overload it binds. Declarations are not
/// callers. At the tip the callers outside this module are tests only (aether-slice, ember); they are pinned as an exact map.
///
/// Both pins are ENABLED tripwires on current behaviour. S1a-1b moves the production callers to the member-taking factory and
/// shrinks [#PRODUCTION_CALLERS]; S1a-4d removes the shim and deletes this test.
class OldArityCallerTripwireTest {
    private static final String OLD_FACTORY_REFERENCE = "an old-arity shim factory";
    private static final List<Target> OLD_ARITY = List.of(target(AssignmentToken.class,
                                                                 "assignmentToken",
                                                                 MethodType.methodType(AssignmentToken.class, NodeId.class, Epoch.class)),
                                                          target(ConsumerAssignmentValue.class,
                                                                 "consumerAssignmentValue",
                                                                 MethodType.methodType(ConsumerAssignmentValue.class,
                                                                                       NodeId.class,
                                                                                       Epoch.class,
                                                                                       long.class,
                                                                                       HlcTimestamp.class)));
    private static final Pattern LAMBDA = Pattern.compile("lambda\\$(.+)\\$\\d+");

    /// Production callers of the old arities at the tip (S1a-1a). S1a-1b is the lane that shrinks this set.
    private static final Map<String, Integer> PRODUCTION_CALLERS = Map.ofEntries(
        Map.entry("org.pragmatica.aether.node.stream.ClusterCursorStore#commit", 1),
        Map.entry("org.pragmatica.aether.node.stream.ConsumerAssignmentWriter#put", 1));

    /// Test callers of the old arities at the tip (S1a-1a).
    private static final Map<String, Integer> TEST_CALLERS = Map.ofEntries(
        Map.entry("org.pragmatica.aether.node.DispatchedSReaderReadYourWriteTest#commitWitness_seesTheCheckpointWhenTheWriteResolves", 1),
        Map.entry("org.pragmatica.aether.node.StreamCursorCheckpointPutCodecTest#checkpointPut_roundTrips_throughTheNodeCodec_withTheRewindEpoch", 1),
        Map.entry("org.pragmatica.aether.node.stream.ClusterCursorStoreTest#<clinit>", 2),
        Map.entry("org.pragmatica.aether.node.stream.ClusterCursorStoreTest#assignedToSelf", 1),
        Map.entry("org.pragmatica.aether.node.stream.ClusterCursorStoreTest#assignment", 1),
        Map.entry("org.pragmatica.aether.node.stream.ClusterCursorStoreTest$AssignmentFence#assign", 1),
        Map.entry("org.pragmatica.aether.node.stream.ClusterCursorStoreTest$AssignmentFence#commit_isFenced_whenTheRecordIsOneCounterAhead_forTheSameNode", 1),
        Map.entry("org.pragmatica.aether.node.stream.ClusterCursorStoreTest$RewindAndAssignmentFencesCompose#assign", 1),
        Map.entry("org.pragmatica.aether.node.stream.ConsensusBatchCountersTest#<clinit>", 1),
        Map.entry("org.pragmatica.aether.node.stream.ConsumerAssignmentWriterTest#commit", 1),
        Map.entry("org.pragmatica.aether.node.stream.CursorCheckpointAdvanceOnlyTest#<clinit>", 1),
        Map.entry("org.pragmatica.aether.node.stream.CursorCheckpointAdvanceOnlyTest#setUp", 1),
        Map.entry("org.pragmatica.aether.node.stream.CursorCheckpointRegressionTripwireTest#<clinit>", 1),
        Map.entry("org.pragmatica.aether.node.stream.CursorCheckpointRegressionTripwireTest#setUp", 1),
        Map.entry("org.pragmatica.aether.node.stream.StreamConsumerManagerTest#assignmentRecord", 1),
        Map.entry("org.pragmatica.aether.node.stream.StreamConsumerRuntimeClusterCursorTest#clusterStore", 1),
        Map.entry("org.pragmatica.aether.worker.metadata.WorkerMetadataIndexTest#consumerAssignmentTravelsWithItsStreamProjection", 1));

    /// Old-arity callers outside this module, by file relative to the repository root, at the tip (S1a-1a). aether-slice's codec
    /// test keeps three on purpose (it pins the shim's behaviour) and one value-factory use;
    /// the ember tripwire builds one assignment record.
    private static final Map<String, Integer> OUTSIDE_NODE_CALLERS = Map.ofEntries(
        Map.entry("aether/ember/src/test/java/org/pragmatica/aether/ember/EmberCursorCheckpointLoadTripwireTest.java", 1),
        Map.entry("aether/slice/src/test/java/org/pragmatica/aether/slice/kvstore/StreamCursorCheckpointValueCodecTest.java", 4));

    private static final Pattern SOURCE_REFERENCE = Pattern.compile("(::\\s*)?\\b(assignmentToken|consumerAssignmentValue)\\b(\\s*\\()?");
    private static final String NODE_MODULE = "aether/node/";

    record Target(String owner, String name, String descriptor) {
        boolean matches(String otherOwner, String otherName, String otherDescriptor) {
            return owner.equals(otherOwner) && name.equals(otherName) && descriptor.equals(otherDescriptor);
        }
    }

    @Test
    void productionCallers_ofTheOldArities_areExactlyThePinnedSet() {
        assertThat(oldArityCallers(classesDirectoryOf(NodeCodecs.class)))
            .as("production callers of %s changed. A NEW caller must use the member-taking factory; a REMOVED one is S1a-1b's"
                + " work and shrinks PRODUCTION_CALLERS", OLD_FACTORY_REFERENCE)
            .isEqualTo(PRODUCTION_CALLERS);
    }

    @Test
    void testCallers_ofTheOldArities_areExactlyThePinnedSet() {
        assertThat(oldArityCallers(classesDirectoryOf(OldArityCallerTripwireTest.class)))
            .as("test callers of %s changed. A NEW caller must use the member-taking factory; a REMOVED one shrinks"
                + " TEST_CALLERS (S1a-1b shrinks it as the production callers move)", OLD_FACTORY_REFERENCE)
            .isEqualTo(TEST_CALLERS);
    }

    @Test
    void outsideNodeCallers_ofTheOldArities_areExactlyThePinnedSet() {
        assertThat(oldArityCallersInSource(repositoryRoot()))
            .as("callers of %s outside aether/node changed. A NEW caller must use the member-taking factory; a REMOVED one"
                + " shrinks OUTSIDE_NODE_CALLERS", OLD_FACTORY_REFERENCE)
            .isEqualTo(OUTSIDE_NODE_CALLERS);
    }

    /// Positive control for the source scan: every form it claims to see is found, a declaration, a member-taking call and a
    /// string holding a comma are not callers, and a call spanning lines is counted.
    @Test
    void theSourceScan_findsEveryCallerFormAndSkipsDeclarations(@TempDir Path root) throws IOException {
        var file = root.resolve("aether/slice/src/test/java/Probe.java");

        Files.createDirectories(file.getParent());
        Files.writeString(file, SOURCE_PROBE);
        Files.createDirectories(root.resolve("aether/node/src/test/java"));
        Files.writeString(root.resolve("aether/node/src/test/java/Skipped.java"), SOURCE_PROBE);

        assertThat(oldArityCallersInSource(root)).containsExactlyInAnyOrderEntriesOf(Map.of("aether/slice/src/test/java/Probe.java", 6));
    }

    private static final String SOURCE_PROBE = """
        class Probe {
            static AssignmentToken assignmentToken(NodeId a, Epoch e) { return null; }
            Object qualified() { return AssignmentToken.assignmentToken(n, e); }
            Object unqualified() { return assignmentToken(n, e); }
            Object returned() { return assignmentToken(n(a, b), e); }
            Object reference() { return AssignmentToken::assignmentToken; }
            Object value() { return ConsumerAssignmentValue.consumerAssignmentValue(n, e, 1L, t); }
            Object spanning() {
                return ConsumerAssignmentValue.consumerAssignmentValue(n,
                                                                       e, 1L, t);
            }
            Object notOld() { return AssignmentToken.assignmentToken(n, e, some(",")); }
        }
        """;

    /// Positive control for the scan itself: a qualified call, a static-import call and a method reference, compiled from
    /// source, are all found, each under its own method, and an unrelated 3-arg call is not.
    @Test
    void theScan_findsQualifiedStaticImportAndMethodReferenceForms(@TempDir Path out) throws IOException {
        var source = out.resolve("src").resolve("Probe.java");

        Files.createDirectories(source.getParent());
        Files.writeString(source, PROBE_SOURCE);

        var classes = Files.createDirectories(out.resolve("classes"));
        var compiler = ToolProvider.getSystemJavaCompiler();
        var status = compiler.run(null, null, null, "-d", classes.toString(), "-cp", System.getProperty("java.class.path"), source.toString());

        assertThat(status).as("the control source must compile").isZero();
        assertThat(oldArityCallers(classes)).containsExactlyInAnyOrderEntriesOf(Map.of("Probe#qualified",
                                                                                       1,
                                                                                       "Probe#staticImport",
                                                                                       1,
                                                                                       "Probe#methodReference",
                                                                                       1,
                                                                                       "Probe#methodReferenceToValue",
                                                                                       1,
                                                                                       "Probe#valueQualified",
                                                                                       1));
    }

    private static final String PROBE_SOURCE = """
        import static org.pragmatica.aether.slice.kvstore.AetherValue.ConsumerAssignmentValue.AssignmentToken.assignmentToken;

        import java.util.function.BiFunction;
        import org.pragmatica.aether.slice.generation.Epoch;
        import org.pragmatica.aether.slice.kvstore.AetherValue.ConsumerAssignmentValue;
        import org.pragmatica.aether.slice.kvstore.AetherValue.ConsumerAssignmentValue.AssignmentToken;
        import org.pragmatica.consensus.NodeId;
        import org.pragmatica.hlc.HlcTimestamp;
        import org.pragmatica.lang.Option;

        class Probe {
            AssignmentToken qualified(NodeId n, Epoch e) {
                return AssignmentToken.assignmentToken(n, e);
            }

            AssignmentToken staticImport(NodeId n, Epoch e) {
                return assignmentToken(n, e);
            }

            BiFunction<NodeId, Epoch, AssignmentToken> methodReference() {
                return AssignmentToken::assignmentToken;
            }

            ConsumerAssignmentValue valueQualified(NodeId n, Epoch e, HlcTimestamp t) {
                return ConsumerAssignmentValue.consumerAssignmentValue(n, e, 1L, t);
            }

            Object methodReferenceToValue(NodeId n, Epoch e, HlcTimestamp t) {
                Runnable r = () -> ConsumerAssignmentValue.consumerAssignmentValue(n, e, 1L, t);

                return r;
            }

            AssignmentToken notOld(NodeId n, Epoch e) {
                return AssignmentToken.assignmentToken(n, e, Option.none());
            }
        }
        """;

    /// `file relative to root` to the number of old-arity callers in it, over every Java source outside `aether/node/`.
    static Map<String, Integer> oldArityCallersInSource(Path root) {
        var found = new TreeMap<String, Integer>();
        var scanned = new int[]{0};

        try (Stream<Path> files = Files.walk(root)) {
            files.filter(path -> isSourceUnderScan(root.relativize(path).toString().replace('\\', '/')))
                 .forEach(path -> scanSource(root, path, found, scanned));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertThat(scanned[0]).as("source files scanned under %s", root).isPositive();
        return found;
    }

    private static boolean isSourceUnderScan(String relative) {
        return relative.endsWith(".java")
               && !relative.startsWith(NODE_MODULE)
               && !relative.startsWith(".m2-local/")
               && !relative.startsWith(".git/")
               && !relative.startsWith(".bb/")
               && !relative.contains("/target/");
    }

    private static void scanSource(Path root, Path file, Map<String, Integer> found, int[] scanned) {
        try {
            var hits = oldArityCallsIn(Files.readString(file));

            scanned[0]++;
            if (hits > 0) {
                found.put(root.relativize(file).toString().replace('\\', '/'), hits);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static int oldArityCallsIn(String source) {
        var matcher = SOURCE_REFERENCE.matcher(source);
        var count = 0;

        while (matcher.find()) {
            if (matcher.group(1) != null) {
                count++;
            } else if (matcher.group(3) != null && !isDeclaration(source, matcher.start())
                       && argumentCount(source, matcher.end()) == oldArgumentCount(matcher.group(2))) {
                count++;
            }
        }
        return count;
    }

    private static int oldArgumentCount(String name) {
        return name.equals("assignmentToken") ? 2 : 4;
    }

    /// A declaration has a type (an identifier, or the `>` of a generic) right before the name; a call has `.`, `(`, `,`,
    /// `=`, `->` or the keyword `return`.
    private static boolean isDeclaration(String source, int nameStart) {
        var end = nameStart;

        while (end > 0 && Character.isWhitespace(source.charAt(end - 1))) {
            end--;
        }

        var start = end;

        while (start > 0 && Character.isJavaIdentifierPart(source.charAt(start - 1))) {
            start--;
        }
        return start < end && !source.substring(start, end).equals("return");
    }

    /// Top-level argument count of the call whose opening parenthesis ends just before `from`; string literals are skipped.
    private static int argumentCount(String source, int from) {
        var depth = 1;
        var commas = 0;
        var i = from;

        while (depth > 0) {
            var c = source.charAt(i);

            if (c == '"') {
                i = endOfString(source, i);
            } else if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
            } else if (c == ',' && depth == 1) {
                commas++;
            }
            i++;
        }
        return commas + 1;
    }

    private static int endOfString(String source, int open) {
        var i = open + 1;

        while (source.charAt(i) != '"') {
            i += source.charAt(i) == '\\' ? 2 : 1;
        }
        return i;
    }

    private static Path repositoryRoot() {
        var dir = Path.of("").toAbsolutePath();

        while (dir != null && !(Files.isDirectory(dir.resolve(".mvn")) && Files.isDirectory(dir.resolve("aether")))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IllegalStateException("repository root (a directory holding .mvn and aether) not found above the working directory");
        }
        return dir;
    }

    private static Target target(Class<?> owner, String name, MethodType type) {
        return new Target(owner.getName().replace('.', '/'), name, type.descriptorString());
    }

    /// The directory the class was loaded from. It must be a directory: a jar or a missing location would make the scan read
    /// nothing, and an empty read passes silently.
    private static Path classesDirectoryOf(Class<?> type) {
        try {
            var location = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());

            assertThat(location).as("%s must be loaded from a classes directory, or the scan reads nothing", type.getName()).isDirectory();
            return location;
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    /// `class#method` to the number of old-arity references in that method, over every class file under `root`.
    static Map<String, Integer> oldArityCallers(Path root) {
        var found = new TreeMap<String, Integer>();
        var scanned = new int[]{0};

        try (Stream<Path> files = Files.walk(root)) {
            files.filter(path -> path.toString().endsWith(".class"))
                 .forEach(path -> scanClass(path, found, scanned));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertThat(scanned[0]).as("class files scanned under %s", root).isPositive();
        return found;
    }

    private static void scanClass(Path file, Map<String, Integer> found, int[] scanned) {
        try {
            var model = ClassFile.of().parse(Files.readAllBytes(file));
            var owner = model.thisClass().asInternalName().replace('/', '.');

            scanned[0]++;
            for (var method : model.methods()) {
                scanMethod(owner, method, found);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void scanMethod(String owner, MethodModel method, Map<String, Integer> found) {
        var key = owner + "#" + enclosingName(method.methodName().stringValue());

        method.code().ifPresent(code -> {
            for (CodeElement element : code) {
                var hits = oldArityReferencesIn(element);

                if (hits > 0) {
                    found.merge(key, hits, Integer::sum);
                }
            }
        });
    }

    private static String enclosingName(String methodName) {
        var matcher = LAMBDA.matcher(methodName);

        return matcher.matches() ? matcher.group(1) : methodName;
    }

    private static int oldArityReferencesIn(CodeElement element) {
        return switch (element) {
            case InvokeInstruction call -> isOldInvokeStatic(call) ? 1 : 0;
            case ConstantInstruction.LoadConstantInstruction load -> isOldHandle(load.constantEntry()) ? 1 : 0;
            case InvokeDynamicInstruction dynamic -> (int) dynamic.invokedynamic().bootstrap().arguments().stream().filter(OldArityCallerTripwireTest::isOldHandle).count();
            default -> 0;
        };
    }

    private static boolean isOldInvokeStatic(InvokeInstruction call) {
        return call.opcode() == Opcode.INVOKESTATIC && isOld(call.owner().asInternalName(), call.name().stringValue(), call.type().stringValue());
    }

    private static boolean isOldHandle(LoadableConstantEntry entry) {
        return entry instanceof MethodHandleEntry handle
               && handle.kind() == MethodHandleInfo.REF_invokeStatic
               && isOld(handle.reference().owner().asInternalName(), handle.reference().name().stringValue(), handle.reference().type().stringValue());
    }

    private static boolean isOld(String owner, String name, String descriptor) {
        return OLD_ARITY.stream().anyMatch(target -> target.matches(owner, name, descriptor));
    }
}
