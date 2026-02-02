package org.pragmatica.jbct.parser;

import org.pragmatica.jbct.parser.Java25Parser.CstNode;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the generated Java25Parser.
 */
class Java25ParserTest {
    private final Java25Parser parser = new Java25Parser();

    @Test
    void parseEmptyClass() {
        var result = parser.parse("class Foo { }");
        assertTrue(result.isSuccess(), () -> "Failed: " + result);
        result.onSuccess(cst -> assertEquals("CompilationUnit",
                                             cst.rule()
                                                .name()));
    }

    @Test
    void parseClassWithField() {
        var result = parser.parse("class C { int x; }");
        assertTrue(result.isSuccess(), () -> "Failed: " + result);
    }

    @Test
    void parseClassWithMethod() {
        var result = parser.parse("""
            class C {
                void foo() { }
            }
            """);
        assertTrue(result.isSuccess(), () -> "Failed: " + result);
    }

    @Test
    void parseRecord() {
        var result = parser.parse("record Point(int x, int y) { }");
        assertTrue(result.isSuccess(), () -> "Failed: " + result);
    }

    @Test
    void parseMethodWithBody() {
        var result = parser.parse("""
            class C {
                int add(int a, int b) {
                    return a + b;
                }
            }
            """);
        assertTrue(result.isSuccess(), () -> "Failed: " + result);
    }

    @Test
    void parsePackageAndImports() {
        var result = parser.parse("""
            package com.example;

            import java.util.List;
            import java.util.*;

            public class Foo { }
            """);
        assertTrue(result.isSuccess(), () -> "Failed: " + result);
    }

    @Test
    void parseLambda() {
        var result = parser.parse("""
            class C {
                void test() {
                    Runnable r = () -> { };
                }
            }
            """);
        assertTrue(result.isSuccess(), () -> "Failed: " + result);
    }

    @Test
    void parseMethodChain() {
        var result = parser.parse("""
            class C {
                void test() {
                    list.stream()
                        .map(x -> x)
                        .filter(x -> true)
                        .toList();
                }
            }
            """);
        assertTrue(result.isSuccess(), () -> "Failed: " + result);
    }

    @Test
    void cstPreservesSourceLocation() {
        var result = parser.parse("class Foo { }");
        assertTrue(result.isSuccess());
        result.onSuccess(cst -> {
                             var span = cst.span();
                             assertEquals(1,
                                          span.start()
                                              .line());
                             assertEquals(1,
                                          span.start()
                                              .column());
                         });
    }

    @Test
    void cstHasChildren() {
        var result = parser.parse("class Foo { int x; }");
        assertTrue(result.isSuccess());
        result.onSuccess(cst -> {
                             assertTrue(cst instanceof CstNode.NonTerminal);
                             var root = (CstNode.NonTerminal) cst;
                             assertFalse(root.children()
                                             .isEmpty());
                         });
    }

    @Test
    void parseSwitchExpressionWithThrow() {
        // Test switch expression with throw in default case
        var result = parser.parse("""
            class C {
                String test(Object o) {
                    return switch (o) {
                        default -> throw new IllegalStateException();
                    };
                }
            }
            """);
        assertTrue(result.isSuccess(), () -> "Failed: " + result);
    }

    @Test
    void parseSwitchExpressionSimple() {
        // Test simple switch expression without throw
        var result = parser.parse("""
            class C {
                String test(Object o) {
                    return switch (o) {
                        default -> "foo";
                    };
                }
            }
            """);
        assertTrue(result.isSuccess(), () -> "Failed: " + result);
    }

    @Test
    void parseGuardWithWhenKeyword() {
        // Test 'when' guard in switch - ensure word boundary works
        var result = parser.parse("""
            class C {
                String test(Object o) {
                    return switch (o) {
                        case String s when s.isEmpty() -> "empty";
                        default -> "other";
                    };
                }
            }
            """);
        assertTrue(result.isSuccess(), () -> "Failed: " + result);
    }

    @Test
    void parseIdentifierStartingWithKeyword() {
        // Test that identifiers starting with keywords are parsed correctly
        var result = parser.parse("""
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
            """);
        assertTrue(result.isSuccess(), () -> "Failed: " + result);
    }

    @Test
    void parsePrimitiveArrayClassLiteral() {
        // Test byte[].class - primitive array class literal
        var result = parser.parse("""
            class C {
                void test() {
                    var c = byte[].class;
                }
            }
            """);
        assertTrue(result.isSuccess(), () -> "Failed: " + result);
    }

    @Test
    void parsePrimitiveClassLiteral() {
        // Test int.class - primitive class literal
        var result = parser.parse("""
            class C {
                void test() {
                    var c = int.class;
                }
            }
            """);
        assertTrue(result.isSuccess(), () -> "Failed: " + result);
    }

    @Test
    void parseReferenceArrayClassLiteral() {
        // Test String[].class - reference array class literal
        var result = parser.parse("""
            class C {
                void test() {
                    var c = String[].class;
                }
            }
            """);
        assertTrue(result.isSuccess(), () -> "Failed: " + result);
    }

    @Test
    void parseReferenceClassLiteral() {
        // Test String.class - reference class literal
        var result = parser.parse("""
            class C {
                void test() {
                    var c = String.class;
                }
            }
            """);
        assertTrue(result.isSuccess(), () -> "Failed: " + result);
    }

    @Test
    void parseChainedGetClass() {
        // Test List.of().getClass() - chained method with getClass
        var result = parser.parse("""
            class C {
                void test() {
                    var c = List.of().getClass();
                }
            }
            """);
        assertTrue(result.isSuccess(), () -> "Failed: " + result);
    }

    @Test
    void parseVoidClassLiteral() {
        // Test void.class
        var result = parser.parse("""
            class C {
                void test() {
                    var c = void.class;
                }
            }
            """);
        assertTrue(result.isSuccess(), () -> "Failed: " + result);
    }

    @Test
    void parseMultiDimensionalArrayClassLiteral() {
        // Test int[][].class - multi-dimensional array class literal
        var result = parser.parse("""
            class C {
                void test() {
                    var c = int[][].class;
                }
            }
            """);
        assertTrue(result.isSuccess(), () -> "Failed: " + result);
    }

    @Test
    void parseArrayTypeMethodReference() {
        // Test String[]::new - array type constructor reference
        var result = parser.parse("""
            class C {
                void test() {
                    var arr = list.stream().toArray(String[]::new);
                }
            }
            """);
        assertTrue(result.isSuccess(), () -> "Failed: " + result);
    }

    @Test
    void parsePrimitiveArrayTypeMethodReference() {
        // Test int[]::new - primitive array type constructor reference
        var result = parser.parse("""
            class C {
                void test() {
                    var arr = stream.toArray(int[]::new);
                }
            }
            """);
        assertTrue(result.isSuccess(), () -> "Failed: " + result);
    }

    @Test
    void parseMultiDimArrayTypeMethodReference() {
        // Test String[][]::new - multi-dimensional array type constructor reference
        var result = parser.parse("""
            class C {
                void test() {
                    var arr = stream.toArray(String[][]::new);
                }
            }
            """);
        assertTrue(result.isSuccess(), () -> "Failed: " + result);
    }

    @Test
    void parseArrayTypeMethodReferenceWithMethod() {
        // Test int[]::clone - array type method reference (not constructor)
        var result = parser.parse("""
            class C {
                void test() {
                    var clone = int[]::clone;
                    var objClone = String[]::clone;
                }
            }
            """);
        assertTrue(result.isSuccess(), () -> "Failed: " + result);
    }

    @Test
    void parseRecordAsMethodName() {
        // 'record' is a contextual keyword - can be used as method/variable name
        var result = parser.parse("""
            class C {
                void test() {
                    record("hello", 42);
                }
                void record(String s, int i) {}
            }
            """);
        assertTrue(result.isSuccess(), () -> "Failed: " + result);
    }

    @Test
    void parseRecordAsTypeName() {
        // 'record' as a class name (valid before Java 14)
        var result = parser.parse("""
            class record {}
            class C {
                record field;
                void test(record r) {
                    record local = new record();
                }
            }
            """);
        assertTrue(result.isSuccess(), () -> "Failed: " + result);
    }
}
