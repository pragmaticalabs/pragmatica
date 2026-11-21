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
/// SCOPE [guess: the module is the unit a lane edits]: this module's `target/classes` and `target/test-classes`. Callers in
/// other modules' tests (aether-slice, ember) are not compiled here and are not seen; at the tip they are tests only.
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
