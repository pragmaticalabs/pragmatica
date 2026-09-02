package org.pragmatica.jbct.parser;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/// Tests for Java25Parser — the v6-backed Cursor-returning entry point.
class Java25ParserTest {
    private final Java25Parser parser = new Java25Parser();

    @Test
    void parseEmptyClass() {
        var result = parser.parse("class Foo { }");
        assertTrue(result.isSuccess(), () -> "Failed: " + result);
        result.onSuccess(root -> assertEquals(RuleKind.ROOT, root.kind()));
    }

    @Test
    void parseClassWithField() {
        assertTrue(parser.parse("class C { int x; }").isSuccess());
    }

    @Test
    void parseClassWithMethod() {
        assertTrue(parser.parse("""
            class C {
                void foo() { }
            }
            """).isSuccess());
    }

    @Test
    void parseRecord() {
        assertTrue(parser.parse("record Point(int x, int y) { }").isSuccess());
    }

    @Test
    void parseMethodWithBody() {
        assertTrue(parser.parse("""
            class C {
                int add(int a, int b) {
                    return a + b;
                }
            }
            """).isSuccess());
    }

    @Test
    void parsePackageAndImports() {
        assertTrue(parser.parse("""
            package com.example;

            import java.util.List;
            import java.util.*;

            public class Foo { }
            """).isSuccess());
    }

    @Test
    void parseLambda() {
        assertTrue(parser.parse("""
            class C {
                void test() {
                    Runnable r = () -> { };
                }
            }
            """).isSuccess());
    }

    @Test
    void parseMethodChain() {
        assertTrue(parser.parse("""
            class C {
                void test() {
                    list.stream()
                        .map(x -> x)
                        .filter(x -> true)
                        .toList();
                }
            }
            """).isSuccess());
    }

    @Test
    void cursorPreservesSourceLocation() {
        var result = parser.parse("class Foo { }");
        assertTrue(result.isSuccess());
        result.onSuccess(root -> {
            assertEquals(1, root.startLine());
            assertEquals(1, root.startColumn());
        });
    }

    @Test
    void cursorRootIsBranchWithChildren() {
        var result = parser.parse("class Foo { int x; }");
        assertTrue(result.isSuccess());
        result.onSuccess(root -> {
            assertInstanceOf(Cursor.Branch.class, root);
            var branch = (Cursor.Branch) root;
            assertTrue(branch.childCount() > 0);
        });
    }

    @Test
    void parseSwitchExpressionWithThrow() {
        assertTrue(parser.parse("""
            class C {
                String test(Object o) {
                    return switch (o) {
                        default -> throw new IllegalStateException();
                    };
                }
            }
            """).isSuccess());
    }

    @Test
    void parseSwitchExpressionSimple() {
        assertTrue(parser.parse("""
            class C {
                String test(Object o) {
                    return switch (o) {
                        default -> "foo";
                    };
                }
            }
            """).isSuccess());
    }

    @Test
    void parseGuardWithWhenKeyword() {
        assertTrue(parser.parse("""
            class C {
                String test(Object o) {
                    return switch (o) {
                        case String s when s.isEmpty() -> "empty";
                        default -> "other";
                    };
                }
            }
            """).isSuccess());
    }

    @Test
    void parseIdentifierStartingWithKeyword() {
        assertTrue(parser.parse("""
            class C {
                void switchCase() {}
                void ifCondition() {}
                void whileLoop() {}
                void forLoop() {}
                void doWork() {}
                void tryAgain() {}
                void catchError() {}
                void finallyDone() {}
            }
            """).isSuccess());
    }

    @Test
    void parsePrimitiveArrayClassLiteral() {
        assertTrue(parser.parse("""
            class C {
                void test() {
                    var c = byte[].class;
                }
            }
            """).isSuccess());
    }

    @Test
    void parsePrimitiveClassLiteral() {
        assertTrue(parser.parse("""
            class C {
                void test() {
                    var c = int.class;
                }
            }
            """).isSuccess());
    }

    @Test
    void parseReferenceArrayClassLiteral() {
        assertTrue(parser.parse("""
            class C {
                void test() {
                    var c = String[].class;
                }
            }
            """).isSuccess());
    }

    @Test
    void parseReferenceClassLiteral() {
        assertTrue(parser.parse("""
            class C {
                void test() {
                    var c = String.class;
                }
            }
            """).isSuccess());
    }

    @Test
    void parseChainedGetClass() {
        assertTrue(parser.parse("""
            class C {
                void test() {
                    var c = List.of().getClass();
                }
            }
            """).isSuccess());
    }

    @Test
    void parseVoidClassLiteral() {
        assertTrue(parser.parse("""
            class C {
                void test() {
                    var c = void.class;
                }
            }
            """).isSuccess());
    }

    @Test
    void parseMultiDimensionalArrayClassLiteral() {
        assertTrue(parser.parse("""
            class C {
                void test() {
                    var c = int[][].class;
                }
            }
            """).isSuccess());
    }

    @Test
    void parseArrayTypeMethodReference() {
        assertTrue(parser.parse("""
            class C {
                void test() {
                    var arr = list.stream().toArray(String[]::new);
                }
            }
            """).isSuccess());
    }

    @Test
    void parsePrimitiveArrayTypeMethodReference() {
        assertTrue(parser.parse("""
            class C {
                void test() {
                    var arr = stream.toArray(int[]::new);
                }
            }
            """).isSuccess());
    }

    @Test
    void parseMultiDimArrayTypeMethodReference() {
        assertTrue(parser.parse("""
            class C {
                void test() {
                    var arr = stream.toArray(String[][]::new);
                }
            }
            """).isSuccess());
    }

    @Test
    void parseArrayTypeMethodReferenceWithMethod() {
        assertTrue(parser.parse("""
            class C {
                void test() {
                    var clone = int[]::clone;
                    var objClone = String[]::clone;
                }
            }
            """).isSuccess());
    }

    @Test
    void parseRecordAsMethodName() {
        assertTrue(parser.parse("""
            class C {
                void test() {
                    record("hello", 42);
                }
                void record(String s, int i) {}
            }
            """).isSuccess());
    }

    /// Pins a KNOWN DIVERGENCE from javac, so it turns red if peglib later tightens the grammar.
    ///
    /// javac rejects `class record {}` — "as of release 14, 'record' is a restricted type name and
    /// cannot be used for type declarations" — but the grammar's restricted set is only
    /// `RestrictedTypeName <- < ('var' / 'yield') ![a-zA-Z0-9_$] >`, so `record` passes as a type
    /// name. This sits in peglib's documented "wrongly accepted" bucket, not in jbct's code.
    ///
    /// Before 0.7.1 the surrounding cases (`record field;`, `void test(record r)`,
    /// `new record()`) were also accepted; they are now rejected, which is why the previous
    /// version of this test — which asserted the whole snippet parsed — stopped passing.
    @Test
    void parse_recordAsTypeName_stillAcceptedDespiteJavacRejectingIt() {
        assertTrue(parser.parse("class record {}").isSuccess());
    }

    @Test
    void parse_acceptsRecordAsVariableName() {
        assertTrue(parser.parse("""
            class C {
                void test() {
                    int record = 3;
                    record(record);
                }
                void record(int value) {}
            }
            """).isSuccess());
    }
}
